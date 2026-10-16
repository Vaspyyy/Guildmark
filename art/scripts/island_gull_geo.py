"""The gull's geometry: the parts and cubes of the model, in Minecraft model space (pixels, y down, the bird faces -z, its right is -x, the
ground under its feet is y = 24). Part of `island_gull.py` (which paints it, writes the Kotlin and the previews); nothing here paints.

The bird is a tree of parts hung from `body` (the middle of the body, where the renderer pitches and rolls it):

    body            seven stepped, octagonal slices of torso
      neck1 -> neck2 -> head      the neck in two links; the head carries the bill (upper mandible), the jaw (lower), two eyes and a fish
      tail          a centre card and a card either side that fan
      arm_X         humerus and scapulars: a card, a layer of coverts, a thick leading edge and a hinged flap of secondaries (`arm_X_s`)
        fore_X      forearm: a card, a layer of coverts and its flap of secondaries (`fore_X_s`)
          hand_X    the carpal block and four primaries (`pri_X1`..`pri_X4`, inner to outer), each its own part so they can fan and flex
      leg_X         the feathered thigh down to the ankle -> shin_X (the tarsus) -> foot_X (a webbed card)

with X = l (the bird's left, +x) or r (its right, -x). Every dimension that the animation also needs (the leg's links, where the wings start) is
defined in `island_gull_anim.py` and used here, so the model and its setupAnim cannot disagree.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import sea_kit as K
from sea_kit import Cu, part, Model
import island_gull_anim as A

TEX_W, TEX_H = 64, 64
NAME = 'gull'
DOUBLE = {'wing', 'cov', 'flap', 'prim', 'tail', 'foot', 'hand'}          # thin cards (seen from both sides)


def oct_z(prefix, z0, z1, w, h, yc, mat, **kw):
    """A slice of torso, an octagon in section (a half px cut off each corner): two boxes along z, one wide and low, one narrow and tall."""
    return [Cu((-w / 2, yc - (h - 1.0) / 2, z0), (w, h - 1.0, z1 - z0), mat, prefix + 'a', slice=prefix, **kw),
            Cu((-(w - 1.0) / 2, yc - h / 2, z0), (w - 1.0, h, z1 - z0), mat, prefix + 'b', slice=prefix, **kw)]


def oct_y(prefix, y0, y1, w, d, mat, cx=0.0, cz=0.0, **kw):
    """A link of the neck from [y0] (its top) to [y1], an octagon in section [w] wide (x) and [d] deep (z): two boxes, one wider, one deeper."""
    return [Cu((cx - w / 2, y0, cz - (d - 1.0) / 2), (w, y1 - y0, d - 1.0), mat, prefix + 'a', slice=prefix, **kw),
            Cu((cx - (w - 1.0) / 2, y0, cz - d / 2), (w - 1.0, y1 - y0, d), mat, prefix + 'b', slice=prefix, **kw)]


def xspan(sg, a, b):
    """The x extent (origin, width) of a box from [a] to [b] outward on the side [sg] (+1 the left wing, -1 the right)."""
    return (a, b - a) if sg > 0 else (-b, b - a)


def build_wing(side, sg):
    """One wing: arm, fore, hand and the four primaries, with the two flaps of secondaries and the coverts."""
    W = A.WING
    x0, w = xspan(sg, 0.0, W['arm_len'])
    arm_c = Cu((x0, -0.25, W['arm_lead']), (w, 0.5, W['arm_chord']), 'wing', f'arm_{side}c', faces={'top', 'bottom'}, seg='arm')
    arm_v = Cu((x0, -0.75, W['arm_lead']), (w, 0.5, W['arm_cov']), 'cov', f'arm_{side}v', faces={'top'}, seg='arm_v')
    arm_b = Cu((x0, -0.5, W['arm_lead'] - 0.5), (w, 1.0, 0.5), 'bar', f'arm_{side}b', seg='arm')
    arm_s = part(f'arm_{side}_s', (0, 0, W['arm_lead'] + W['arm_chord'] - 0.1), (0, 0, 0),
                 [Cu((x0, -0.25, 0.0), (w, 0.5, W['arm_flap']), 'flap', f'arm_{side}_sc', faces={'top', 'bottom'}, seg='arm_s')])
    # the forearm hangs from the arm's tip
    fx0, fw0 = xspan(sg, 0.0, W['fore_len'])
    fore_c = Cu((fx0, -0.25, W['fore_lead']), (fw0, 0.5, W['fore_chord']), 'wing', f'fore_{side}c', faces={'top', 'bottom'}, seg='fore')
    fore_v = Cu((fx0, -0.75, W['fore_lead']), (fw0, 0.5, W['fore_cov']), 'cov', f'fore_{side}v', faces={'top'}, seg='fore_v')
    fore_s = part(f'fore_{side}_s', (0, 0, W['fore_lead'] + W['fore_chord'] - 0.1), (0, 0, 0),
                  [Cu((fx0, -0.25, 0.0), (fw0, 0.5, W['fore_flap']), 'flap', f'fore_{side}_sc', faces={'top', 'bottom'}, seg='fore_s')])
    # the hand: the carpal block, and four primaries, inner to outer, each a long narrow card pivoting at its root
    hx0, hw0 = xspan(sg, -0.2, W['hand_len'])
    hand_c = Cu((hx0, -0.25, W['hand_lead']), (hw0, 0.5, W['hand_chord']), 'hand', f'hand_{side}c', faces={'top', 'bottom'}, seg='hand')
    pris = []
    for k in range(4):
        px, pz, L = W['pri_root'][k][0], W['pri_root'][k][1], W['pri_len'][k]
        cx0, cw = xspan(sg, 0.0, L)
        pris.append(part(f'pri_{side}{k + 1}', (sg * px, W['pri_y'][k], pz), (0, 0, 0),
                         [Cu((cx0, -0.25, -0.5), (cw, 0.5, 1.0), 'prim', f'pri_{side}{k + 1}c', faces={'top', 'bottom'}, seg='prim', k=k)]))
    hand = part(f'hand_{side}', (sg * W['fore_len'], 0, 0), (0, 0, 0), [hand_c], pris)
    fore = part(f'fore_{side}', (sg * W['arm_len'], 0, 0), (0, 0, 0), [fore_c, fore_v], [fore_s, hand])
    arm = part(f'arm_{side}', (sg * W['shoulder'][0], W['shoulder'][1], W['shoulder'][2]), (0, 0, 0), [arm_c, arm_v, arm_b], [arm_s, fore])
    return arm


def build_leg(side, sg):
    L = A.LEG
    thigh = Cu((-0.75, -0.25, -0.75), (1.5, L['l1'] + 0.25, 1.5), 'trouser', f'leg_{side}c', side=side)
    shin = Cu((-0.25, -0.25, -0.25), (0.5, L['l2'] + 0.25, 0.5), 'leg', f'shin_{side}c', side=side)
    foot = Cu((-1.0, -0.25, -1.75), (2.0, 0.5, 2.0), 'foot', f'foot_{side}c', faces={'top', 'bottom'}, side=side)
    foot_p = part(f'foot_{side}', (0, L['l2'], 0), (0, 0, 0), [foot])
    shin_p = part(f'shin_{side}', (0, L['l1'], 0), (0, 0, 0), [shin], [foot_p])
    return part(f'leg_{side}', (sg * L['hip'][0], L['hip'][1], L['hip'][2]), (0, 0, 0), [thigh], [shin_p])


def build_head():
    """The head, its pivot at the back of the skull (where the neck joins): the skull, the bill (upper mandible), the jaw (lower), two eyes, a fish."""
    skull = [Cu((-1.0, -0.75, -1.75), (2.0, 1.5, 2.5), 'head', 'skull_a', slice='skull'),
             Cu((-0.75, -1.0, -1.75), (1.5, 2.0, 2.5), 'head', 'skull_b', slice='skull')]
    eyes = []
    for side, sg in (('l', 1.0), ('r', -1.0)):
        ex = 0.85 if sg > 0 else -1.35
        eyes.append(part(f'eye_{side}', (0.0, -0.2, -1.0), (0, 0, 0), [Cu((ex, -0.5, -0.25), (0.5, 1.0, 0.5), 'eye', f'eye_{side}c', side=side)]))
    bill = part('bill', (0.0, 0.15, -1.75), (0, 0, 0), [
        Cu((-0.5, -0.5, -1.0), (1.0, 1.0, 1.0), 'bill', 'bill_a', seg='base'),
        Cu((-0.5, -0.5, -1.5), (1.0, 0.5, 0.5), 'bill', 'bill_b', seg='mid'),
        Cu((-0.25, -0.5, -2.0), (0.5, 1.0, 0.5), 'bill', 'bill_c', seg='hook'),
    ])
    jaw = part('jaw', (0.0, 0.5, -1.0), (0, 0, 0), [Cu((-0.5, -0.25, -0.75), (1.0, 0.5, 0.75), 'jaw', 'jaw_a', seg='base'),
                                                    Cu((-0.5, -0.25, -1.25), (1.0, 0.5, 0.5), 'jaw', 'jaw_b', seg='mid'),
                                                    Cu((-0.25, -0.25, -1.75), (0.5, 0.75, 0.5), 'jaw', 'jaw_c', seg='gonys')])
    fish_tail = part('fish_tail', (2.2, 0.0, 0.0), (0, 0, 0), [Cu((0.0, -0.5, -0.25), (1.0, 1.0, 0.5), 'fish', 'fish_tc', faces={'top', 'bottom', 'front', 'back'}, seg='tail')])
    fish = part('fish', (0.0, 0.5, -2.75), (0, 0, 0), [Cu((-2.2, -0.5, -0.5), (4.5, 1.0, 1.0), 'fish', 'fish_c', seg='body')], [fish_tail])
    return part('head', (0.0, 0.0, 0.0), (0, 0, 0), skull, [bill, jaw, fish] + eyes)


def build():
    """The model: every part with its rest pose (the bird standing with its wings spread out and its feet on the ground: setupAnim poses it
    from there)."""
    K.REG.clear()
    cubes = []
    for k, (z0, z1, w, h, yc) in enumerate(A.TORSO):
        cubes += oct_z(f'bd{k}', z0, z1, w, h, yc, 'body')
    N = A.NECK
    head = build_head()
    head.pivot = (0.0, -N['n2_len'] + 0.2, 0.0)
    neck2 = part('neck2', (0.0, -N['n1_len'] + 0.2, 0.0), (0, 0, 0),
                 oct_y('n2', -N['n2_len'], 0.4, N['w2'], N['d2'], 'neck'), [head])
    neck1 = part('neck1', N['base'], (0, 0, 0), oct_y('n1', -N['n1_len'], 0.5, N['w1'], N['d1'], 'neck'), [neck2])
    T = A.TAIL
    tail_l = part('tail_l', (T['half'], 0, 0), (0, 0, 0), [Cu((0.0, -0.25, 0.0), (T['side_w'], 0.5, T['side_len']), 'tail', 'tail_lc', faces={'top', 'bottom'}, seg='side')])
    tail_r = part('tail_r', (-T['half'], 0, 0), (0, 0, 0), [Cu((-T['side_w'], -0.25, 0.0), (T['side_w'], 0.5, T['side_len']), 'tail', 'tail_rc', faces={'top', 'bottom'}, seg='side')])
    tail = part('tail', T['root'], (0, 0, 0), [Cu((-T['half'], -0.25, 0.0), (2 * T['half'], 0.5, T['len']), 'tail', 'tail_c', faces={'top', 'bottom'}, seg='mid')], [tail_l, tail_r])
    kids = [neck1, tail]
    for side, sg in (('l', 1.0), ('r', -1.0)):
        kids.append(build_wing(side, sg))
        kids.append(build_leg(side, sg))
    torso = part('torso', (0.0, 0.0, 0.0), (0, 0, 0), cubes)
    body = part('body', (0.0, 24.0 - A.BODY_Y, 0.0), (0, 0, 0), [], [torso] + kids)
    return Model(NAME, TEX_W, TEX_H, [body])
