"""
The homestead builders' clips. Same skeleton and kit as the townsfolk's (townsfolk_anims.py: the builders are
townsfolk in citizen looks, played by the game's CombatAnimator), but what a builder works with is a real item in the
hand, drawn by vanilla's ItemInHandLayer: the hammer, saw and mallet (models/item/builders_*.json, the handheld
model), the iron shovel, a sheet of paper (the generated model) and whatever block they carry (the block model's
third-person transform; in the left hand the game mirrors it). The clips aim the item itself, through the same hand
chain and display transforms the game uses: the hammer's face onto the nail, the saw's teeth through the board, the
shovel's edge into the ground, the mallet's face onto the stake, the block into its place. Run from the repo root:

    python3 -B art/scripts/homestead_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]

Writes assets/guildmark/animations/townsfolk/<clip>.json for these fourteen clips only (importing townsfolk_anims
builds its own first, about a minute), a contact sheet per clip (or only the clips named) and, with none named,
builders.gif with every clip playing at once. The previews draw the vanilla shovel, paper and blocks when
GUILDMARK_VANILLA_ASSETS names an unpacked client jar's assets/minecraft (flat colours stand in without it).

    clip         ticks  plays                  the work lands (BuilderCrew.Builder; the server acts on these ticks)
    haul           40   loop, walking overlay  timber lugged at the right side, the body tipped against it
    carry_block    40   loop, walking overlay  a block carried before the chest in both hands
    take           24   once                   bent to the supply crate, the block taken up off it (grab 12)
    lay            20   once                   set into its place before the chest (11)
    lay_low        24   once                   bent low, set into the footing in front (14)
    lay_high       22   once                   reached up and pushed into place overhead (12)
    nail           16   loop                   the hammer on a nail in the board in front (5)
    saw            20   loop                   the board held in the left hand, sawn through on the push (6)
    dig            24   loop                   the shovel's edge driven into the ground (10), levered, thrown
    stake          20   loop                   the mallet down on the stake's head (9)
    clamber        16   loop                   hand over hand up the scaffold
    plans          80   loop                   the plans held open and read, a look up and a point at the work
    mop_brow       40   once                   the brow wiped, the sweat flicked off, a stretch of the back
    hurrah         40   once                   the crew's cheer: a dip, fists up with a hop (9), a second pump

Every run prints each clip's contacts and how deep the held item ever goes into the body or the ground.
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import townsfolk_anims as ta  # noqa: E402
from townsfolk_anims import (clip, key_frame, stand_legs, ticks, seal, check, F_, bend, free, reach, relaxed,  # noqa: E402
                             solve_arm, body_solve, arm_world, level_arms, sink_for, mirror_dir, gaze_head, look_q,
                             GRIP, PIVOTS, EYES, INFO)
from animkit import display, hand_chain, S, apply, qeuler, unit, v, F, B, D, R, L, raster, ortho  # noqa: E402

FIRST = len(ta.clips)
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')
VANILLA = os.environ.get('GUILDMARK_VANILLA_ASSETS', '')
GROUND = 24.0
LOOKS = (0, 1, 2, 4, 6, 7)          # BuilderCrew.LOOKS: the citizen looks the crew wear
GOLD, SKY = (255, 220, 120), (170, 220, 255)

# ================================================================== what's in the hand, as the game draws it

HANDHELD = ('hammer', 'mallet', 'saw', 'shovel')
DISPLAY = {   # thirdperson_righthand of the item's model: (rotation, translation /16, scale)
    'handheld': ((0, -90, 55), (0, 4, 0.5), (0.85, 0.85, 0.85)),
    'generated': ((0, 0, 0), (0, 3, 1), (0.55, 0.55, 0.55)),
    'block': ((75, 45, 0), (0, 2.5, 0), (0.375, 0.375, 0.375)),
}


def model_of(kind):
    return 'handheld' if kind in HANDHELD else 'block' if kind == 'block' else 'generated'


def item_matrix(kind, left=False):
    """Arm space (px, from the joint) from item space (0..1), as ItemInHandLayer and the item's model place it. In the
    left hand the game mirrors the entry it uses: handheld's own left entry is the right's mirrored, so it comes back
    to the right's turn; the block and generated models have no left entry, so the right's is mirrored."""
    rot, trans, sc = DISPLAY[model_of(kind)]
    if left:
        if model_of(kind) != 'handheld':
            rot = (rot[0], -rot[1], -rot[2])
        trans = (-trans[0], trans[1], trans[2])
    return S(16) @ hand_chain(left) @ display(rot, trans, sc)


# Points on each item (item space, 0..1, y up; a block's in its own cube), read off the sprites
PTS = {
    'hammer': {'grip': (0.19, 0.21), 'face': (0.43, 0.76), 'head': (0.55, 0.57), 'claw': (0.64, 0.33)},
    'mallet': {'grip': (0.19, 0.21), 'face': (0.46, 0.74), 'head': (0.62, 0.59), 'heel': (0.77, 0.44)},
    'saw': {'grip': (0.19, 0.21), 'heel': (0.40, 0.53), 'toe': (0.80, 0.88), 'teeth': (0.53, 0.65), 'back': (0.66, 0.50)},
    'shovel': {'grip': (0.19, 0.21), 'edge': (0.84, 0.81), 'blade': (0.75, 0.69), 'shaft': (0.44, 0.40), 'butt': (0.17, 0.12)},
    'paper': {'mid': (0.5, 0.5), 'near': (0.5, 0.1), 'far': (0.5, 0.9), 'lside': (0.1, 0.62)},
    'block': {'mid': (0.5, 0.5, 0.5)},
}


def ipt(kind, name, left=False):
    """A named point of an item in its arm's own space (px)."""
    p = PTS[kind][name]
    return apply(item_matrix(kind, left), (p[0], p[1], 0.5) if len(p) == 2 else p)


def item_world(side, q, fr, kind, name):
    Ra, j = arm_world(side, q, **fr)
    return j + Ra @ ipt(kind, name, side == 'left_arm')

# ================================================================== solving poses: contacts as px residuals for body_solve


def _arm(side, qa, qb):
    return qa if side == 'right_arm' else qb


def at_item(kind, name, target, w=4.0, side='right_arm'):
    """That point of what [side] holds at world [target]."""
    p, tgt = ipt(kind, name, side == 'left_arm'), np.asarray(target, float)

    def res(fr, qa, qb):
        Ra, j = arm_world(side, _arm(side, qa, qb), **fr)
        return list((j + Ra @ p - tgt) * w)
    return res


def along(kind, a, b, toward, w=1.5, side='right_arm'):
    """The item's direction from point [a] to point [b] turned toward world [toward]."""
    d, want = unit(ipt(kind, b, side == 'left_arm') - ipt(kind, a, side == 'left_arm')), unit(toward)

    def res(fr, qa, qb):
        return list((arm_world(side, _arm(side, qa, qb), **fr)[0] @ d - want) * w)
    return res


def fist(side, target, w=4.0):
    tgt = np.asarray(target, float)

    def res(fr, qa, qb):
        Ra, j = arm_world(side, _arm(side, qa, qb), **fr)
        return list((j + Ra @ GRIP[side] - tgt) * w)
    return res


def palm(side, d, w=0.6):
    """The hand's inside (the arm's -z, where a held item sits) toward world [d]."""
    want = unit(d)

    def res(fr, qa, qb):
        return list((arm_world(side, _arm(side, qa, qb), **fr)[0] @ np.array([0.0, 0.0, -1.0]) - want) * w)
    return res


def on_held(kind, name, w=3.0):
    """The left fist on a point of what the right hand holds (a shaft, the paper's edge)."""
    p = ipt(kind, name)

    def res(fr, qa, qb):
        Ra, j = arm_world('right_arm', qa, **fr)
        Rb, jb = arm_world('left_arm', qb, **fr)
        return list((jb + Rb @ GRIP['left_arm'] - (j + Ra @ p)) * w)
    return res


def level(w=0.5):
    return lambda fr, qa, qb: level_arms(fr, qa, qb, w)


def solve(*res, pitch=0.0, yaw=0.0, roll=0.0, root=(0.0, 0.0, 0.0), drop=0.0, guess=None, **kw):
    """The whole body (lean, turn, tip, sink, step and both arms) solved for the contacts [res], staying near the
    lean, turn and step asked for, the hips [drop]ped that far: (frame, right key, left key, solution)."""
    f = lambda fr, qa, qb: [x for r in res for x in r(fr, qa, qb)]
    return body_solve(f, pitch0=pitch, yaw0=yaw, roll0=roll, root0=root, drop=drop, sink0=sink_for(pitch), guess=guess, **kw)


def feet(fr, right=(0.5, 0.8), left=(0.5, -0.8)):
    """Foot places for stand_legs a leg's length from the hips wherever the root has taken them: the further the
    hips drop, the further out (in the given directions) the feet go; the root's step carries them along."""
    drop, dz = float(fr['pr'][1]), float(fr['pr'][2])
    need = math.sqrt(max(0.0, 144.0 - (12.0 - drop) ** 2))
    out = []
    for ox, oz in (right, left):
        n = math.hypot(ox, oz)
        k = need / n if n > 1e-6 and need > n else 1.0
        out.append((ox * k, oz * k + dz))
    return out


def put(c, t, fr, qa=None, qb=None, ease='inout', head=None, root=True, legs=None):
    key_frame(c, t, fr, ease=ease, root=root)
    if qa is not None:
        c.key('right_arm', t, qa, ease=ease)
    if qb is not None:
        c.key('left_arm', t, qb, ease=ease)
    if head is not None:
        c.key('head', t, head, ease=ease)
    if legs is not None:
        stand_legs(c, t, root=fr['pr'], right=legs[0], left=legs[1], ease=ease)


def close_loop(c, legs):
    """A loop's last keys: its first pose again (seal then makes them exact)."""
    f0 = ta.frame_at(c, 0.0)
    put(c, float(c.length), f0, c.sample('right_arm', 0.0)[0], c.sample('left_arm', 0.0)[0],
        head=c.sample('head', 0.0)[0] if 'head' in c.keys else None, legs=feet(f0, *legs))
    seal(c)

# ================================================================== the clips

HELD = {}       # clip -> fn(t) -> (right hand's kind or None, left hand's)
STATION = {}    # clip -> the scene drawn round it in the previews
FOCUS = {}      # clip -> where the game has them look (the work), for the previews' vanilla head; None: ahead


BOTH_HANDS = set()  # clips whose left hand is on what the right holds (not a clash)


def builder(name, length, loop, right=None, left=None, n=0, overlay=False, events=(), traces=(), station=None, focus=None,
            both=False):
    c = clip(name, length, loop, look=('citizen', LOOKS[n % len(LOOKS)]), overlay=overlay, events=events, traces=traces)
    if both:
        BOTH_HANDS.add(name)
    r_ = right if callable(right) else (lambda t, k=right: k)
    l_ = left if callable(left) else (lambda t, k=left: k)
    HELD[name] = lambda t: (r_(t), l_(t))
    STATION[name] = station
    FOCUS[name] = None if focus is None else np.asarray(focus, float)
    return c


def fk_item(c, t, kind, name, side='right_arm'):
    return ta.fk(c, t, side, ipt(kind, name, side == 'left_arm'))


CHEST = np.array([-0.4, 6.2, -8.8])          # a block's middle, carried before the chest
LEFT_ON = np.array([3.6, 0.6, 0.2])          # the left fist on the far side of a block held in both hands


def carry_arms(fr, at, guess=None):
    """Arms only: both hands on a block whose middle is at [at], as near as they reach: (right, left, its middle)."""
    qa, _ = solve_arm('right_arm', fr, [(ipt('block', 'mid'), np.asarray(at, float), 1.0)],
                      [((0.0, 0.0, -1.0), L, 0.3)], guess=guess)
    mid = item_world('right_arm', qa, fr, 'block', 'mid')
    qb = reach('left_arm', fr, mid + LEFT_ON, palm=R, palm_w=0.3)
    return qa, qb, mid


def holding(at, pitch=0.0, yaw=0.0, roll=0.0, root=(0.0, 0.0, 0.0), drop=0.0, guess=None):
    """The whole body with a block's middle at [at] between both fists: (frame, right, left, solution)."""
    at = np.asarray(at, float)
    return solve(at_item('block', 'mid', at), palm('right_arm', L, 1.0), fist('left_arm', at + LEFT_ON, 2.5),
                 palm('left_arm', R, 0.8), level(0.4), pitch=pitch, yaw=yaw, roll=roll, root=root, drop=drop,
                 guess=guess)


CARRY = holding(CHEST, pitch=-3.0)           # the pose a block is carried up in, between the work's moves
CARRY_LEGS = feet(CARRY[0], (0.6, 1.4), (0.6, -1.8))

# ---- haul (overlay): timber lugged at the right side, the arm straight under it, the body tipped away against the
# weight and the left arm out for balance; the legs keep walking
c = builder('haul', 40, True, right='block', n=0, overlay=True, station='walk')
for t in ticks(0, 40, 5):
    ph = 2 * math.pi * t / 40.0
    fr = F_(qt=bend(3.0 + 0.8 * math.sin(2 * ph), -4.0, 6.5 + 1.2 * math.sin(ph)))
    key_frame(c, t, fr, root=False)
    c.key('right_arm', t, free(fr, v(f=0.04 + 0.05 * math.sin(ph), u=-1.0, r=0.2), v(f=0.5, r=1.0)), ease='inout')
    c.key('left_arm', t, qeuler(x=-3.0 * math.sin(ph), z=-16.0 - 3.0 * math.sin(ph)), ease='inout')
    c.key('head', t, qeuler(x=2.0 + 1.0 * math.sin(2 * ph), z=-2.5), ease='inout')
c.mode('left_arm', 'add')
c.mode('head', 'add')
seal(c)

# ---- carry_block (overlay): a block before the chest in both hands, leaning back against it
c = builder('carry_block', 40, True, right='block', n=1, overlay=True, station='walk')
g = None
for t in ticks(0, 40, 5):
    ph = 2 * math.pi * t / 40.0
    fr = F_(qt=bend(-4.5 + 0.8 * math.sin(2 * ph), 0.0, 1.2 * math.sin(ph)))
    key_frame(c, t, fr, root=False)
    qa, qb, _ = carry_arms(fr, CHEST + np.array([0.0, -0.4 * math.sin(2 * ph), 0.0]), guess=g)
    g = qa
    c.key('right_arm', t, qa, ease='inout')
    c.key('left_arm', t, qb, ease='inout')
    c.key('head', t, qeuler(x=-3.0, z=-1.2 * math.sin(ph)), ease='inout')
c.mode('head', 'add')
seal(c)

# ---- take (once): bent to the supply crate a block ahead, the hands come down either side of the top, the block
# is taken up off it (tick 12: it's in the hand from here) and carried up to the chest
CRATE_TOP = 8.0
GRAB = np.array([-0.4, CRATE_TOP - 3.3, -14.4])
c = builder('take', 24, False, right=lambda t: 'block' if t >= 12 else None, n=2, events=[('grab', 12)],
            traces=[('right_arm', GRIP['right_arm'], GOLD), ('left_arm', GRIP['left_arm'], SKY)], station='crate',
            focus=(0.0, CRATE_TOP, -15.0))
f12, a12, b12, x12 = holding(GRAB, pitch=32.0, drop=1.2, root=(0.0, 0.0, 1.2))
rf, lf = ta.hand_point('right_arm', a12, f12), ta.hand_point('left_arm', b12, f12)
f9, a9, b9, _ = solve(fist('right_arm', rf + np.array([-0.7, -1.6, 0.6])), fist('left_arm', lf + np.array([0.7, -1.6, 0.6])),
                      palm('right_arm', L, 0.8), palm('left_arm', R, 0.8), level(0.4), pitch=30.0, drop=1.0,
                      root=(0.0, 0.0, 1.2), guess=x12)
f16, a16, b16, x16 = holding(GRAB + np.array([0.0, -2.4, 0.2]), pitch=20.0, drop=0.6, root=(0.0, 0.0, 0.8), guess=x12)
f20, a20, b20, _ = holding(CHEST + np.array([0.0, 0.4, -2.6]), pitch=8.0, drop=0.2, guess=x16)
f4 = F_(qt=bend(14.0), vt=(0, sink_for(14.0), 0), pr=(0.0, 0.3, 0.3))
for t, fr, qa, qb, ease in ((0.0, F_(), relaxed(F_(), 'right_arm'), relaxed(F_(), 'left_arm'), 'inout'),
                            (4.0, f4, free(f4, v(f=0.45, u=-1.0, r=0.1), L), free(f4, v(f=0.45, u=-1.0, r=-0.1), R), 'inout'),
                            (9.0, f9, a9, b9, 'out'), (12.0, f12, a12, b12, 'inout'), (16.0, f16, a16, b16, 'inout'),
                            (20.0, f20, a20, b20, 'inout'), (24.0, CARRY[0], CARRY[1], CARRY[2], 'inout')):
    put(c, t, fr, qa, qb, ease=ease, head=gaze_head(fr, (0.0, CRATE_TOP, -22.0) if t <= 12 else (0.0, 0.0, -40.0)),
        legs=feet(fr, (0.6, 1.4), (0.6, -1.8)))
c.mode('head', 'set')
check(c, 'block on the crate as it is taken (t=12)', fk_item(c, 12, 'block', 'mid'), GRAB, tol=0.6)


def lay_clip(name, length, contact, at, n, pitch, drop, station, focus, arc=1.5, root=(0.0, 0.0, 0.0), back=(0.0, 0.3, 2.8)):
    """A block carried at the chest, set into its place ([at]: where its middle goes, on tick [contact]: the world's
    block appears there), the hands let go at once ([back]: how far they come away) and back to the chest, where the
    next one comes from (the clip starts again for each block)."""
    c = builder(name, length, False, right='block', n=n, events=[('lay', contact)],
                traces=[('right_arm', ipt('block', 'mid'), GOLD)], station=station, focus=focus)
    at = np.asarray(at, float)
    way, back = at - CHEST, np.asarray(back, float)
    keys = [  # (t, block middle, pitch, drop, ease)
        (contact * 0.4, CHEST + way * 0.15 + np.array([0.0, -arc * 0.6, 0.0]), pitch * 0.2, drop * 0.25, 'inout'),
        (contact * 0.78, CHEST + way * 0.72 + np.array([0.0, -arc, 0.0]), pitch * 0.75, drop * 0.8, 'inout'),
        (contact - 1.0, at + np.array([0.0, -0.5, 1.3]), pitch * 0.95, drop, 'in'),
        (float(contact), at, pitch, drop, 'out'),
        (contact + 1.5, at + back, pitch * 0.9, drop, 'out'),
        (contact + (length - contact) * 0.6, CHEST + way * 0.25, pitch * 0.35, drop * 0.3, 'inout'),
    ]
    put(c, 0.0, CARRY[0], CARRY[1], CARRY[2], legs=CARRY_LEGS)
    x = CARRY[3]
    for t, mid, p, d, ease in keys:
        fr, qa, qb, x = holding(mid, pitch=p, drop=d, root=(root[0], 0.0, root[2]), guess=x)
        put(c, t, fr, qa, qb, ease=ease, legs=feet(fr, (0.6, 1.4), (0.6, -1.8)))
    put(c, float(length), CARRY[0], CARRY[1], CARRY[2], legs=CARRY_LEGS)
    check(c, f'block in its place (t={contact})', fk_item(c, contact, 'block', 'mid'), at, tol=0.6)
    return c


# ---- lay (once): set into the course in front, about the waist (tick 11); the block's own place is a block ahead,
# from the feet's level to the head's: the held one goes into it at the middle of that
lay_clip('lay', 20, 11, (0.0, 6.0, -12.6), 3, 20.0, 0.6, 'course', (0.0, 0.0, -16.0))
# ---- lay_low (once): bent right down, set into the footing in front, below the feet (tick 14)
lay_clip('lay_low', 24, 14, (0.0, 21.6, -13.0), 4, 56.0, 1.8, 'footing', (0.0, 32.0, -16.0), arc=-1.0,
         back=(0.0, -2.6, 2.0))
# ---- lay_high (once): reached up and pushed into place overhead (tick 12)
lay_clip('lay_high', 22, 12, (-1.5, -7.6, -6.6), 5, -10.0, -0.8, 'overhead', (0.0, -16.0, -16.0), arc=1.0,
         back=(0.0, 2.6, 1.4))

# ---- nail (loop): the hammer swung down onto a nail in the face of the board in front (tick 5), the left hand
# holding the board beside it, a bounce off the head, and up again; stood back a little from the work
STRIKE = np.array([-2.2, 4.0, -9.6])
NAIL_LEGS = ((0.7, 1.8), (0.6, -2.4))
c = builder('nail', 16, True, right='hammer', n=0, events=[('hammer', 5)],
            traces=[('right_arm', ipt('hammer', 'face'), GOLD)], station='board', focus=(0.0, 0.0, -16.0))
HOLD = STRIKE + np.array([4.8, 0.6, 0.3])
f5, a5, b5, x5 = solve(at_item('hammer', 'face', STRIKE), along('hammer', 'head', 'face', v(f=1.0, u=-0.35), w=1.0),
                       fist('left_arm', HOLD, 2.0), palm('left_arm', F, 0.6), pitch=9.0, yaw=4.0, root=(0.0, 0.0, 2.5))
NAIL = [  # (t, right arm direction, palm (where the handle points), pitch, yaw, ease)
    (0.0, v(f=0.6, u=0.74, r=0.3), v(f=-0.62, u=0.42, r=0.66), 3.0, 11.0, 'inout'),
    (3.0, v(f=1.0, u=0.12, r=0.1), v(f=-0.12, u=1.0, r=0.2), 6.5, 7.0, 'in'),
    (5.0, None, None, None, None, 'in'),
    (6.5, 'rebound', None, 8.0, 5.0, 'out'),
    (10.0, v(f=0.75, u=0.5, r=0.45), v(f=-0.45, u=0.3, r=0.85), 4.5, 9.0, 'inout'),
]
for t, d, p, pitch, yaw, ease in NAIL:
    if d is None:
        fr, qa = f5, a5
    elif isinstance(d, str):
        fr, qa, _, _ = solve(at_item('hammer', 'face', STRIKE + np.array([0.0, -1.6, 1.4])),
                             along('hammer', 'head', 'face', v(f=1.0, u=-0.1), w=0.8), fist('left_arm', HOLD, 2.0),
                             palm('right_arm', v(u=0.55, r=0.85), 0.5), pitch=pitch, yaw=yaw, root=(0.0, 0.0, 2.5),
                             guess=x5)
    else:
        fr = F_(qt=bend(pitch, yaw), vt=(0, sink_for(pitch), 0), pr=f5['pr'])
        qa = free(fr, d, p)
    qb = reach('left_arm', fr, HOLD + (np.array([0.0, -0.2, -0.15]) if t == 5.0 else 0.0), palm=F, palm_w=0.4)
    put(c, t, fr, qa, qb, ease=ease, legs=feet(fr, *NAIL_LEGS))
close_loop(c, NAIL_LEGS)
check(c, 'hammer face on the nail (t=5)', fk_item(c, 5, 'hammer', 'face'), STRIKE, tol=0.6)

# ---- saw (loop): the board held out in the left hand and the saw run through its far end, the body rocking into
# the push (the stroke, tick 6) and back on the pull; the teeth stay in the kerf and the blade level all the way
BOARD = np.array([1.4, 9.4, -9.6])
KERF = BOARD + np.array([-3.0, -1.6, 0.0])
c = builder('saw', 20, True, right='saw', left='block', n=1, events=[('saw', 6)],
            traces=[('right_arm', GRIP['right_arm'], GOLD)], station='walk', focus=KERF)
heel, toe = ipt('saw', 'heel'), ipt('saw', 'toe')
x = None
for t in ticks(0, 20, 2.5):
    k = 0.5 + 0.5 * math.cos(2 * math.pi * t / 20.0)          # 1: pulled back (the toe in the kerf), 0: pushed through
    s = 0.1 + 0.82 * k
    p_local = heel + (toe - heel) * s

    def in_kerf(fr, qa, qb, p_local=p_local):
        Ra, j = arm_world('right_arm', qa, **fr)
        return list((j + Ra @ p_local - KERF) * 4.0)
    fr, qa, qb, x = solve(in_kerf, along('saw', 'heel', 'toe', v(f=1.0, u=-0.12), w=1.6),
                          along('saw', 'back', 'teeth', D, w=0.6), at_item('block', 'mid', BOARD, 4.0, 'left_arm'),
                          palm('left_arm', v(f=0.2, r=1.0), 0.6), pitch=6.0 + 18.0 * (1 - k), yaw=13.0 * k,
                          root=(0.0, 0.0, 1.0 * k), guess=x)
    put(c, t, fr, qa, qb, ease='linear', head=gaze_head(fr, KERF), legs=feet(fr, (0.8, 2.6), (0.6, -2.6)))
c.mode('head', 'set')
seal(c)
for _t in (0.0, 5.0, 10.0, 15.0):
    check(c, f'board held still (t={_t:g})', fk_item(c, _t, 'block', 'mid', 'left_arm'), BOARD, tol=0.6)

# ---- dig (loop): the shovel's edge driven into the ground in front (tick 10), levered back, the earth lifted and
# thrown off to the left, and back for the next; the left hand down the shaft
DIRT = np.array([-1.5, GROUND + 1.0, -14.0])
DIG_LEGS = ((0.8, 2.2), (0.8, -2.4))
c = builder('dig', 24, True, right='shovel', n=2, events=[('dig', 10)], both=True,
            traces=[('right_arm', ipt('shovel', 'edge'), GOLD)], station='dirt', focus=(0.0, 32.0, -16.0))
DIG = [  # (t, which end of the shovel is put where (the edge in the earth, or the butt: the right fist), shaft toward
         # (butt to edge), pitch, yaw, drop, ease); the throw is keyed by the fist, kept before the right hip
    (0.0, 'edge', (-1.5, 19.5, -14.5), v(f=0.42, u=-1.0), 24.0, 4.0, 0.6, 'inout'),
    (6.0, 'edge', (-1.5, 17.0, -13.0), v(f=0.32, u=-1.0), 18.0, 4.0, 0.2, 'in'),
    (10.0, 'edge', DIRT, v(f=0.4, u=-1.0), 34.0, 3.0, 1.4, 'out'),
    (13.0, 'edge', DIRT + np.array([0.0, -0.8, 1.5]), v(f=1.0, u=-0.75), 30.0, 1.0, 1.2, 'inout'),
    (17.0, 'butt', (-3.5, 12.5, -5.5), v(f=0.85, u=-0.1, r=-0.5), 14.0, -14.0, 0.4, 'in'),
    (19.0, 'butt', (-2.5, 11.0, -5.5), v(f=0.45, u=0.25, r=-0.9), 8.0, -26.0, 0.0, 'out'),
    (21.5, 'butt', (-3.0, 12.0, -6.0), v(f=0.9, u=-0.45, r=-0.25), 16.0, -8.0, 0.3, 'inout'),
]
x = None
for t, end, at, toward, pitch, yaw, drop, ease in DIG:
    fr, qa, qb, x = solve(at_item('shovel', end, at), along('shovel', 'butt', 'edge', toward, w=1.4),
                          on_held('shovel', 'shaft'), palm('left_arm', v(f=0.0, r=1.0), 0.2), pitch=pitch, yaw=yaw,
                          drop=drop, root=(0.0, 0.0, 0.8), guess=x)
    put(c, t, fr, qa, qb, ease=ease, legs=feet(fr, *DIG_LEGS))
close_loop(c, DIG_LEGS)
check(c, 'edge in the ground (t=10)', fk_item(c, 10, 'shovel', 'edge'), DIRT, tol=0.6)

# ---- stake (loop): the mallet swung up and brought down on the head of the stake a block ahead (tick 9), the
# left hand on the knee
STAKE_TOP = np.array([-1.0, 8.4, -13.6])
KNEE = np.array([4.4, 13.6, -4.0])
STAKE_LEGS = ((1.2, 2.4), (1.2, -2.0))
c = builder('stake', 20, True, right='mallet', n=3, events=[('knock', 9)],
            traces=[('right_arm', ipt('mallet', 'face'), GOLD)], station='stake', focus=(0.0, 8.0, -16.0))
f9, a9, b9, x9 = solve(at_item('mallet', 'face', STAKE_TOP), along('mallet', 'head', 'face', v(f=0.55, u=-1.0), w=0.9),
                       fist('left_arm', KNEE, 1.5), pitch=26.0, yaw=3.0, drop=1.2, root=(0.0, 0.0, 0.4))
STK = [  # (t, right arm direction, palm, pitch, yaw, drop, ease)
    (0.0, v(f=0.35, u=0.88, r=0.32), v(f=-0.7, u=0.3, r=0.62), 4.0, 8.0, 0.2, 'inout'),
    (5.0, v(f=0.62, u=0.74, r=0.25), v(f=-0.66, u=0.5, r=0.5), 7.0, 7.0, 0.3, 'in'),
    (7.5, v(f=1.0, u=-0.05, r=0.06), v(f=0.05, u=1.0), 17.0, 5.0, 0.8, 'in'),
    (9.0, None, None, None, None, None, 'out'),
    (11.0, 'rebound', None, 23.0, 4.0, 1.0, 'inout'),
]
for t, d, p, pitch, yaw, drop, ease in STK:
    if d is None:
        fr, qa = f9, a9
    elif isinstance(d, str):
        fr, qa, _, _ = solve(at_item('mallet', 'face', STAKE_TOP + np.array([0.0, -2.2, 0.8])),
                             along('mallet', 'head', 'face', v(f=0.7, u=-1.0), w=0.8), palm('right_arm', v(u=0.4, r=0.9), 0.4),
                             pitch=pitch, yaw=yaw, drop=drop, root=(0.0, 0.0, 0.4), guess=x9)
    else:
        fr = F_(qt=bend(pitch, yaw), vt=(0, sink_for(pitch), 0), pr=(f9['pr'][0], drop, f9['pr'][2]))
        qa = free(fr, d, p)
    put(c, t, fr, qa, reach('left_arm', fr, KNEE, palm=B, palm_w=0.2), ease=ease, legs=feet(fr, *STAKE_LEGS))
close_loop(c, STAKE_LEGS)
check(c, 'mallet on the stake (t=9)', fk_item(c, 9, 'mallet', 'face'), STAKE_TOP, tol=0.6)

# ---- clamber (loop): hand over hand up the scaffold poles in front, a knee coming up with each reach (the game
# lifts them; the clip is the effort)
c = builder('clamber', 16, True, n=4, station='scaffold',
            traces=[('right_arm', GRIP['right_arm'], GOLD), ('left_arm', GRIP['left_arm'], SKY)])
for t in ticks(0, 16, 2):
    ph = 2 * math.pi * t / 16.0
    fr = F_(qt=bend(6.0 + 1.5 * math.cos(2 * ph)), vt=(0, sink_for(6.0), 0))
    key_frame(c, t, fr, ease='linear')
    up_r = 0.5 + 0.5 * math.cos(ph)          # 1: the right hand at the top of its reach
    c.key('right_arm', t, reach('right_arm', fr, (-3.2, -6.0 + 9.0 * (1 - up_r), -6.4), palm=F, palm_w=0.3), ease='linear')
    c.key('left_arm', t, reach('left_arm', fr, (3.2, -6.0 + 9.0 * up_r, -6.4), palm=F, palm_w=0.3), ease='linear')
    ta.legs(c, t, rx=-38.0 * max(0.0, math.sin(ph + 0.6)) - 3.0, lx=-38.0 * max(0.0, -math.sin(ph + 0.6)) - 3.0,
            ease='linear')
    c.key('head', t, qeuler(x=-14.0), ease='linear')
c.mode('head', 'add')
seal(c)

# ---- plans (loop): the plans held open before the chest and read, the head bowed; a look up at the work and a point
# at it with the free hand; back to the page
PLAN_LEGS = ((0.5, 1.0), (0.5, -1.0))
c = builder('plans', 80, True, right='paper', n=5, both=True, traces=[('left_arm', GRIP['left_arm'], SKY)])
PLANS = [  # (t, head pitch, head yaw, left: 'page' / 'point', pitch, ease)
    (0.0, 30.0, 2.0, 'page', 5.0, 'inout'),
    (12.0, 34.0, -3.0, 'page', 6.0, 'inout'),
    (22.0, 28.0, 4.0, 'page', 5.0, 'inout'),
    (30.0, -8.0, 10.0, 'page', 1.0, 'inout'),
    (36.0, -12.0, 12.0, 'point', -1.0, 'out'),
    (46.0, -10.0, -6.0, 'point', -1.0, 'inout'),
    (52.0, -6.0, -8.0, 'point', 0.0, 'inout'),
    (60.0, 28.0, 0.0, 'page', 4.0, 'inout'),
    (70.0, 33.0, 4.0, 'page', 6.0, 'inout'),
]
for t, hx, hy, left, pitch, ease in PLANS:
    fr = F_(qt=bend(pitch), vt=(0, sink_for(pitch), 0))
    lift = 0.0 if left == 'page' else 0.12
    qa = free(fr, v(f=0.8, u=-0.55 + lift, r=-0.22), v(f=0.55, u=0.8))
    if left == 'page':
        qb = reach('left_arm', fr, item_world('right_arm', qa, fr, 'paper', 'lside'), palm=v(f=0.3, r=1.0), palm_w=0.3)
    else:
        qb = free(fr, v(f=1.0, u=0.5, r=-0.25), D)
    put(c, t, fr, qa, qb, ease=ease, head=qeuler(x=hx, y=hy), legs=feet(fr, *PLAN_LEGS))
c.mode('head', 'add')
close_loop(c, PLAN_LEGS)

# ---- mop_brow (once): the back of the wrist wiped across the brow, the sweat flicked off, both hands to the small of
# the back and a long stretch backwards
c = builder('mop_brow', 40, False, n=0, traces=[('right_arm', GRIP['right_arm'], GOLD)])
MOP = [  # (t, pitch, right (target, 'rest' or 'back'), left, head pitch, ease)
    (0.0, 0.0, 'rest', 'rest', 0.0, 'inout'),
    (7.0, 4.0, (2.0, -6.6, -5.0), 'rest', 10.0, 'out'),
    (12.0, 3.0, (-4.2, -6.8, -4.8), 'rest', 6.0, 'inout'),
    (15.0, 1.0, (-9.0, 3.0, -3.0), 'rest', 0.0, 'out'),
    (21.0, -4.0, 'back', 'back', -4.0, 'inout'),
    (28.0, -15.0, 'back', 'back', -16.0, 'inout'),
    (33.0, -14.0, 'back', 'back', -14.0, 'inout'),
    (40.0, 0.0, 'rest', 'rest', 0.0, 'inout'),
]
for t, pitch, rt, lt, hx, ease in MOP:
    fr = F_(qt=bend(pitch), vt=(0, sink_for(pitch), 0))
    keys = []
    for side, tgt in (('right_arm', rt), ('left_arm', lt)):
        s_ = -1 if side == 'right_arm' else 1
        if isinstance(tgt, str) and tgt == 'rest':
            keys.append(relaxed(fr, side, 4.0, 3.0))
        elif isinstance(tgt, str):
            keys.append(reach(side, fr, (s_ * 3.2, 9.6, 3.6), palm=F, palm_w=0.3))
        else:
            keys.append(reach(side, fr, tgt, palm=B, palm_w=0.3))
    put(c, t, fr, keys[0], keys[1], ease=ease, head=qeuler(x=hx), legs=feet(fr, (0.6, 0.6), (0.6, -0.6)))
c.mode('head', 'add')

# ---- hurrah (once): a dip, both fists flung up with a hop (tick 9), a second pump, and the arms down
c = builder('hurrah', 40, False, n=1, events=[('hurrah', 9)],
            traces=[('right_arm', GRIP['right_arm'], GOLD), ('left_arm', GRIP['left_arm'], SKY)])
HUR = [  # (t, root y, pitch, arms (0 down .. 1 flung up), knees, ease)
    (0.0, 0.0, 0.0, 0.0, 0.0, 'inout'),
    (5.0, 1.4, 8.0, -0.2, 1.0, 'in'),
    (9.0, -3.0, -6.0, 1.0, 0.0, 'out'),
    (13.0, -1.0, -5.0, 1.0, 0.0, 'in'),
    (15.0, 0.8, 2.0, 0.85, 0.7, 'out'),
    (20.0, 0.4, 1.0, 0.1, 0.3, 'inout'),
    (24.0, -1.2, -6.0, 1.0, 0.0, 'out'),
    (27.0, 0.3, -1.0, 0.25, 0.2, 'inout'),
    (30.0, -0.6, -5.0, 1.0, 0.0, 'out'),
    (40.0, 0.0, 0.0, 0.0, 0.0, 'inout'),
]
for t, y, pitch, up, knees, ease in HUR:
    fr = F_(qt=bend(pitch), vt=(0, sink_for(pitch), 0), pr=(0, y, 0))
    if up <= 0.0:
        qa, qb = relaxed(fr, 'right_arm', 6.0, 3.0 - 20 * up), relaxed(fr, 'left_arm', 6.0, 3.0 - 20 * up)
    else:
        d = v(f=0.35 * (1 - up) + 0.22, u=0.4 + up, r=0.42)
        qa, qb = free(fr, d, L), free(fr, mirror_dir(d), R)
    put(c, t, fr, qa, qb, ease=ease, head=qeuler(x=-10.0 * max(0.0, up)))
    if y < 0:
        ta.legs(c, t, rx=-6.0, lx=4.0, rz=2.0, lz=-2.0, ease=ease)
    else:
        stand_legs(c, t, root=fr['pr'], right=(0.8, 0.5 + 3.0 * knees), left=(0.8, -0.5 - 3.0 * knees), ease=ease)
c.mode('head', 'add')

MINE = ta.clips[FIRST:]

# ================================================================== the previews: textures and meshes

_TEX = {}


def solid(col):
    a = np.zeros((4, 4, 4), float)
    a[..., :3] = col
    a[..., 3] = 255
    return a


def tex(rel, fallback=(150, 150, 150), tint=None):
    """A texture as a float RGBA array: ours from the assets, vanilla's ('minecraft:...') from GUILDMARK_VANILLA_ASSETS,
    a flat colour when it isn't there."""
    key = (rel, tint)
    if key not in _TEX:
        if rel.startswith('minecraft:'):
            path = os.path.join(VANILLA, 'textures', rel[len('minecraft:'):] + '.png') if VANILLA else ''
        else:
            path = os.path.join(ASSETS, 'textures', rel + '.png')
        if path and os.path.exists(path):
            a = np.array(Image.open(path).convert('RGBA'), float)
            if a.shape[0] > a.shape[1]:
                a = a[:a.shape[1]]          # an animated strip: its first frame
            if tint is not None:
                a[..., :3] *= np.array(tint, float) / 255.0
            _TEX[key] = a
        else:
            _TEX[key] = solid(fallback)
    return _TEX[key]


ITEM_TEX = {'hammer': ('item/builders_hammer', (120, 110, 100)), 'saw': ('item/builders_saw', (150, 150, 160)),
            'mallet': ('item/builders_mallet', (150, 110, 70)), 'shovel': ('minecraft:item/iron_shovel', (200, 200, 205)),
            'paper': ('minecraft:item/paper', (236, 230, 214))}
_MESH = {}


def sprite_mesh(arr):
    """A flat item as the game extrudes it: both faces and the sides of every opaque texel at the edge, in item
    space, as (A, B, D, rect, light)."""
    n, z0, z1 = arr.shape[1], 7.5 / 16, 8.5 / 16
    a = arr[..., 3] > 100
    mesh = [(np.array([0.0, 1.0, z]), np.array([1.0, 1.0, z]), np.array([0.0, 0.0, z]), (0, 0, n, n), 0.95) for z in (z0, z1)]
    P = lambda x, y, z: np.array([x, y, z])
    for j in range(n):
        for i in range(n):
            if not a[j, i]:
                continue
            x0, x1, yt, yb = i / n, (i + 1) / n, 1 - j / n, 1 - (j + 1) / n
            rect = (i, j, 1, 1)
            if i == 0 or not a[j, i - 1]:
                mesh.append((P(x0, yt, z0), P(x0, yt, z1), P(x0, yb, z0), rect, 0.75))
            if i == n - 1 or not a[j, i + 1]:
                mesh.append((P(x1, yt, z0), P(x1, yt, z1), P(x1, yb, z0), rect, 0.8))
            if j == 0 or not a[j - 1, i]:
                mesh.append((P(x0, yt, z0), P(x1, yt, z0), P(x0, yt, z1), rect, 1.0))
            if j == n - 1 or not a[j + 1, i]:
                mesh.append((P(x0, yb, z0), P(x1, yb, z0), P(x0, yb, z1), rect, 0.55))
    return mesh


CUBE_FACES = {  # face: (A, B, D) corners in a unit cube (y up), light
    'south': ((0, 1, 1), (1, 1, 1), (0, 0, 1), 0.86), 'north': ((1, 1, 0), (0, 1, 0), (1, 0, 0), 0.62),
    'west': ((0, 1, 0), (0, 1, 1), (0, 0, 0), 0.74), 'east': ((1, 1, 1), (1, 1, 0), (1, 0, 1), 0.8),
    'up': ((0, 1, 0), (1, 1, 0), (0, 1, 1), 1.0), 'down': ((0, 0, 1), (1, 0, 1), (0, 0, 0), 0.5),
}


def held_quads(kind, M_arm, block_tex, left=False):
    """The quads of what an arm placed by [M_arm] holds."""
    if kind is None:
        return []
    M = M_arm @ item_matrix(kind, left)
    if kind == 'block':
        return [(apply(M, A), apply(M, B_), apply(M, D_), (0, 0, block_tex.shape[1], block_tex.shape[0]), k, block_tex)
                for A, B_, D_, k in CUBE_FACES.values()]
    arr = tex(*ITEM_TEX[kind])
    if kind not in _MESH:
        _MESH[kind] = sprite_mesh(arr)
    return [(apply(M, A), apply(M, B_), apply(M, D_), rect, k, arr) for A, B_, D_, rect, k in _MESH[kind]]


def cube(lo, hi, faces, tile=16.0):
    """A box in model px (y down), each face ([faces]: face -> texture, or one texture for all) tiled a block (16 px)
    at a time, as the world draws a block's texture."""
    lo, hi = np.asarray(lo, float), np.asarray(hi, float)
    size = hi - lo
    out = []
    for name, (A, B_, D_, k) in CUBE_FACES.items():
        t = faces.get(name) if isinstance(faces, dict) else faces
        if t is None:
            continue
        fl = lambda p: lo + np.array([p[0], 1 - p[1], p[2]], float) * size
        Aw, Bw, Dw = fl(A), fl(B_), fl(D_)
        eu, ev = Bw - Aw, Dw - Aw
        nu = max(1, int(math.ceil(np.linalg.norm(eu) / tile - 1e-6)))
        nv = max(1, int(math.ceil(np.linalg.norm(ev) / tile - 1e-6)))
        th, tw = t.shape[0], t.shape[1]
        for i in range(nu):
            for j in range(nv):
                u0, u1, v0, v1 = i / nu, (i + 1) / nu, j / nv, (j + 1) / nv
                lu, lv = np.linalg.norm(eu) * (u1 - u0), np.linalg.norm(ev) * (v1 - v0)
                rect = (0, 0, tw * min(1.0, lu / tile), th * min(1.0, lv / tile))
                out.append((Aw + eu * u0 + ev * v0, Aw + eu * u1 + ev * v0, Aw + eu * u0 + ev * v1, rect, k, t))
    return out


def block(bx, by, bz, faces):
    """A whole block at block offsets from the builder (by 0: the block their feet stand in)."""
    lo = np.array([bx * 16 - 8, GROUND - (by + 1) * 16, bz * 16 - 8], float)
    return cube(lo, lo + 16, faces)


def model_block(name, bx, by, bz):
    """A block model of ours (models/block/<name>.json elements), as the world draws it, its front to the builder."""
    import json
    with open(os.path.join(ASSETS, 'models/block', name + '.json')) as f:
        m = json.load(f)
    tx = {k: (val.split(':', 1)[1] if ':' in val else val) for k, val in m.get('textures', {}).items()}
    base = np.array([bx * 16 - 8, GROUND - by * 16, bz * 16 - 8], float)
    out = []
    for e in m['elements']:
        fr_, to = np.array(e['from'], float), np.array(e['to'], float)
        lo = base + np.array([fr_[0], -to[1], fr_[2]])
        size = to - fr_
        for face, spec in e.get('faces', {}).items():
            ref = spec['texture'].lstrip('#')
            while ref in tx and tx[ref].startswith('#'):
                ref = tx[ref].lstrip('#')
            arr = tex(tx.get(ref, ref))
            u0, v0, u1, v1 = spec.get('uv', (0, 0, 16, 16))
            A, B_, D_, k = CUBE_FACES[face]
            fl = lambda p: lo + np.array([p[0], 1 - p[1], p[2]], float) * size
            s = arr.shape[1] / 16.0
            out.append((fl(A), fl(B_), fl(D_), (u0 * s, v0 * s, (u1 - u0) * s, (v1 - v0) * s), k, arr))
    return out


GRASS = (124, 189, 107)
HELD_BLOCK = {'haul': 'minecraft:block/oak_log', 'carry_block': 'minecraft:block/cobblestone',
              'take': 'minecraft:block/stone_bricks', 'lay': 'minecraft:block/stone_bricks',
              'lay_low': 'minecraft:block/cobblestone', 'lay_high': 'minecraft:block/oak_planks',
              'saw': 'minecraft:block/oak_planks'}


def ground(skip=()):
    top = tex('minecraft:block/grass_block_top', (100, 140, 70), tint=GRASS)
    q = []
    for bx in range(-2, 3):
        for bz in range(-3, 2):
            if (bx, bz) not in skip:
                lo = np.array([bx * 16 - 8, GROUND, bz * 16 - 8], float)
                q += cube(lo, lo + np.array([16.0, 0.01, 16.0]), {'up': top})
    return q


def scene(kind, t):
    """What's round the builder in the previews: the ground, and the work."""
    V = lambda n, col: tex('minecraft:block/' + n, col)
    stone, planks = V('stone_bricks', (130, 130, 130)), V('oak_planks', (160, 120, 80))
    cobble, dirt = V('cobblestone', (120, 120, 120)), V('dirt', (120, 90, 60))
    if kind in (None, 'walk'):
        return ground()
    q = []
    if kind == 'crate':
        q += ground() + model_block('supply_crate', 0, 0, -1)
    elif kind == 'course':
        q += ground()
        for bx in (-1, 0, 1):
            q += block(bx, 0, -1, cobble)
        q += block(-1, 1, -1, stone)
        if t >= 11:
            q += block(0, 1, -1, stone)
    elif kind == 'footing':
        q += ground(skip={(-1, -1), (0, -1), (1, -1)})
        for bx in (-1, 0, 1):
            q += block(bx, -2, -1, dirt)
        q += block(-1, -1, -1, cobble)
        if t >= 14:
            q += block(0, -1, -1, cobble)
        q += block(1, -1, -1, {'up': dirt, 'south': dirt})
    elif kind == 'overhead':
        q += ground()
        for bx in (-1, 0, 1):
            for by in (0, 1):
                q += block(bx, by, -1, planks)
        q += block(-1, 2, -1, planks)
        if t >= 12:
            q += block(0, 2, -1, planks)
    elif kind == 'board':
        q += ground()
        for bx in (-1, 0, 1):
            q += block(bx, 0, -1, stone)
            q += block(bx, 1, -1, planks)
    elif kind == 'dirt':
        q += ground(skip={(0, -1)})
        q += block(0, -1, -1, {'up': dirt})
    elif kind == 'stake':
        # a boundary stake a block ahead, knocked on round after round (the game sets the next one at each knock)
        q += ground()
        q += cube((-2.0, 8.0, -18.0), (2.0, 24.0, -14.0), planks)
        q += cube((-0.5, -6.0, -16.5), (0.5, 8.0, -15.5), planks)
        q += cube((0.5, -6.0, -16.2), (8.5, 2.0, -15.8), V('red_wool', (170, 40, 40)))
    elif kind == 'scaffold':
        q += ground()
        side, top = V('scaffolding_side', (200, 170, 90)), V('scaffolding_top', (210, 180, 100))
        for by in range(0, 4):
            q += block(0, by, -1, {'south': side, 'east': side, 'west': side, 'up': top})
    return q


def base_pose(c, t):
    """Vanilla's pose under the clip (the walk for overlays), its head turned to the work the game has them look at."""
    info = INFO[c.name]
    base = ta.walk_base(t, speed=ta.walk_speed(c, info['walking']), age=t)
    focus = FOCUS.get(c.name)
    if focus is not None:
        eyes = np.array(PIVOTS['head'], float) + EYES
        base['head'] = (look_q(focus - eyes), base['head'][1])
    return base


def render(c, t, project, size, bg=(54, 60, 74)):
    info = INFO[c.name]
    look = ta.look_for(info['look'])
    pose = ta.pose_at(c, t, base_pose(c, t))
    mats = ta.part_matrices(look, pose)
    quads = ta.model_quads(look, mats, set())
    right, left = HELD[c.name](t)
    btex = tex(HELD_BLOCK.get(c.name, 'minecraft:block/stone_bricks'), (140, 140, 140))
    quads += held_quads(right, mats['right_arm'], btex)
    quads += held_quads(left, mats['left_arm'], btex, left=True)
    quads += scene(STATION[c.name], t)
    return raster(quads, project, size, bg=bg), pose, mats

# ================================================================== checks: the item against the body and the ground


BODY = {'head': ((-4.0, -8.0, -4.0), (4.0, 0.0, 4.0)), 'body': ((-4.0, 0.0, -2.0), (4.0, 12.0, 2.0)),
        'right_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)), 'left_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)),
        'right_arm': ((-3.0, -2.0, -2.0), (1.0, 10.0, 2.0)), 'left_arm': ((-1.0, -2.0, -2.0), (3.0, 10.0, 2.0))}
_SAMPLES = {}


def item_samples(kind, left=False):
    """Points through the item in its arm's space: every opaque texel's middle, or a grid through a block, less the
    stretch the hand itself closes round."""
    key = (kind, left)
    if key not in _SAMPLES:
        M = item_matrix(kind, left)
        if kind == 'block':
            g = np.linspace(0.08, 0.92, 5)
            pts = np.array([apply(M, (a, b, c_)) for a in g for b in g for c_ in g])
        else:
            arr = tex(*ITEM_TEX[kind])
            n = arr.shape[1]
            js, is_ = np.nonzero(arr[..., 3] > 100)
            pts = np.array([apply(M, ((i + 0.5) / n, 1 - (j + 0.5) / n, 0.5)) for j, i in zip(js, is_)])
            side = 'left_arm' if left else 'right_arm'
            pts = pts[np.linalg.norm(pts - GRIP[side], axis=1) > (4.5 if kind == 'saw' else 3.0)]
        _SAMPLES[key] = pts
    return _SAMPLES[key]


def depth_in(M, box, pts):
    lo, hi = (np.array(b) for b in box)
    inv = np.linalg.inv(M)
    local = (inv[:3, :3] @ pts.T).T + inv[:3, 3]
    return np.maximum(np.minimum(local - lo, hi - local).min(axis=1), 0.0)


def clearance(c, step=0.5):
    """The deepest any held item goes into the body (other than the hand holding it, and a block's other hand) and
    below the ground, over the whole clip: ((depth, what, tick), (depth below the ground, tick))."""
    look = ta.look_for(INFO[c.name]['look'])
    worst, low = (0.0, '', 0.0), (0.0, 0.0)
    t = 0.0
    while t <= c.length + 1e-6:
        pose = ta.pose_at(c, t, base_pose(c, t))
        mats = ta.part_matrices(look, pose)
        for side, kind in zip(('right_arm', 'left_arm'), HELD[c.name](t)):
            if kind is None:
                continue
            M = mats[side]
            pts = (M[:3, :3] @ item_samples(kind, side == 'left_arm').T).T + M[:3, 3]
            for part, box in BODY.items():
                if part == side or (part in ('right_arm', 'left_arm') and (kind == 'block' or c.name in BOTH_HANDS)):
                    continue
                d = float(depth_in(mats[part], box, pts).max())
                if d > worst[0]:
                    worst = (d, f'{kind} in the {part.replace("_", " ")}', t)
            below = float(pts[:, 1].max()) - GROUND
            if below > low[0]:
                low = (below, t)
        t += step
    return worst, low

# ================================================================== contact sheets and the montage


# The way each clip is seen best (yaw, pitch: ortho's, a positive pitch looks down): work done at something a block
# ahead from behind the right shoulder, so it doesn't hide them; the rest from the front
WORK_VIEW, OPEN_VIEW = (118, 16), (34, 12)


def view_of(c):
    return WORK_VIEW if STATION[c.name] not in (None, 'walk') else OPEN_VIEW


def sheet(c, views=None, cell=(200, 250), zoom=4.0, center_y=6.0):
    views = views or (view_of(c), (90, 3))
    info = INFO[c.name]
    times = ta.frame_times(c)
    W, H = cell
    out = Image.new('RGB', (W * len(times), H * len(views) + 20), (30, 32, 40))
    d = ImageDraw.Draw(out)
    ev = ' '.join(f'{name}@{tick}' for name, tick in info['events'])
    look = ta.look_for(info['look'])
    d.text((4, 4), f'{c.name}  {c.length}t {"loop" if info["loop"] else "once"}{" overlay (walking)" if info["walking"] else ""}'
           f'  {ev}  [citizen {look.n}]', fill=(235, 235, 235))
    paths = []
    for part, p, col in info['traces']:
        pts = []
        k = 0
        while k / 2 <= c.length + 1e-6:
            tt_ = k / 2
            pose = ta.pose_at(c, tt_, base_pose(c, tt_))
            pts.append((tt_, ta.trace_point(look, pose, part, p)))
            k += 1
        paths.append((pts, col))
    for i, t in enumerate(times):
        for j, (yaw, pitch) in enumerate(views):
            project = ortho(yaw, pitch, zoom, (W, H), center_y)
            img, pose, mats = render(c, t, project, (W, H))
            dd = ImageDraw.Draw(img)
            for pts, col in paths:
                dd.line([tuple(project(p)[:2]) for tt_, p in pts], fill=tuple(int(x * 0.45) for x in col), width=1)
                now = [tuple(project(p)[:2]) for tt_, p in pts if tt_ <= t + 1e-6]
                if now:
                    x_, y_ = now[-1]
                    dd.ellipse((x_ - 2, y_ - 2, x_ + 2, y_ + 2), outline=col)
            out.paste(img, (i * W, 20 + j * H))
        label = f't={t:g}' + ''.join(f'  {name}!' for name, tick in info['events'] if abs(tick - t) < 1e-6)
        d.text((i * W + 4, 23), label, fill=(255, 255, 160) if '!' in label else (255, 255, 255))
        d.line([(i * W, 20), (i * W, out.height)], fill=(20, 20, 26))
    return out


def montage(path, cols=5, cell=(150, 186), frames=160, step=1, zoom=2.7):
    """Every clip at once in its own cell, a tick a frame at the game's pace: loops go round, one-shots play, rest a
    moment and go again."""
    rows = int(math.ceil(len(MINE) / cols))
    W, H = cell
    projects = {c.name: ortho(*view_of(c), zoom, (W, H), 7.0) for c in MINE}
    imgs = []
    for f in range(0, frames, step):
        out = Image.new('RGB', (W * cols, H * rows), (30, 32, 40))
        d = ImageDraw.Draw(out)
        for k, c in enumerate(MINE):
            info = INFO[c.name]
            period = c.length if info['loop'] else c.length + 14
            t = min(float(f % period), float(c.length))
            img, _, _ = render(c, t, projects[c.name], (W, H))
            x_, y_ = (k % cols) * W, (k // cols) * H
            out.paste(img, (x_, y_))
            hit = any(abs(tick - t) < step * 0.5 + 1e-6 for _, tick in info['events'])
            d.text((x_ + 4, y_ + 3), c.name, fill=(255, 236, 140) if hit else (235, 235, 235))
        if len(MINE) % cols:
            x_, y_ = (len(MINE) % cols) * W, (rows - 1) * H
            for i, line in enumerate(('Homestead builders', f'{len(MINE)} clips, a game tick', 'a frame; the name lights',
                                      'up on the beat the game', 'plays its sound')):
                d.text((x_ + 10, y_ + 12 + 15 * i), line, fill=(235, 235, 235) if i == 0 else (170, 176, 190))
        imgs.append(out)
    pal = imgs[len(imgs) // 2].convert('P', palette=Image.ADAPTIVE, colors=200)
    frames_p = [im.quantize(palette=pal, dither=Image.Dither.NONE) for im in imgs]
    frames_p[0].save(path, save_all=True, append_images=frames_p[1:], duration=50 * step, loop=0, optimize=True)


def main(argv):
    global ASSETS
    ASSETS, previews = argv[1], argv[2]
    only = set(argv[3:])
    unknown = only - {c.name for c in MINE}
    if unknown:
        raise SystemExit(f'no such clip: {sorted(unknown)}')
    os.makedirs(previews, exist_ok=True)
    for c in MINE:
        info = INFO[c.name]
        bad = ta.check_loop(c) if info['loop'] else []
        if info['overlay']:
            bad += ta.check_overlay(c)
        if bad:
            print(f'!! {c.name}: ' + '; '.join(bad))
        c.write(ASSETS, ta.GROUP)
    print(f'wrote {len(MINE)} clips to {os.path.join(ASSETS, "animations", ta.GROUP)}')
    for c in MINE:
        for line in ta.report(c):
            print(line)
        (d, what, at), (below, bt) = clearance(c)
        print(f'  {c.name} clearance: deepest {d:.2f} px ({what or "nothing"} at t={at:g}), below ground {below:.2f} px'
              f' (t={bt:g}){"   !! INTO THE BODY" if d > 1.0 else ""}')
    for c in MINE:
        if only and c.name not in only:
            continue
        sheet(c).save(os.path.join(previews, f'builder_{c.name}.png'))
    if not only:
        montage(os.path.join(previews, 'builders.gif'))


if __name__ == '__main__':
    main(sys.argv)
