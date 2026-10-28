"""
Hearthcraft's kitchen moves (M3, cook: living-world/cook/design.md, sections 7.5 and 11.3), on the Living World's kit.
The innkeeper's pot and spit: stirring the house pot with the ladle (stir), tasting from a spoon (taste), turning the
spit's crank (turn_spit) and carving the roast onto a bowl (carve). The cookshop's hob: turning what is in the pan with
the turner (flip). The baker's bench: a loaf cut with the knife (slice) and the tray drawn from the oven on the peel
(peel). The turner and the knife are new props (living_props.py); the ladle, the spoon, the bowl and the peel are the
ones the streets and the bakery already use.

living_anims.py imports this at its end and calls build() with itself, so these go out with the Living World's other
clips and LivingSchedule.kt stays one generated file. The blocks they work at (the Stewpot's crane and pot, the Hob,
the Spit, the Prep Board, the Bake oven) are Hearthcraft M1's (cook_stewpot.py, cook_hob.py, cook_spit.py, cook_oven.py,
cook_helpers.py); the stations below are drawn from those models' numbers, and every number a clip leans on (where the
pot stands, how high its rim is, where the crane's post is, where the crank turns) is a named constant here, written up
in the test guide, so that a block made otherwise is one number to change. Each clip's header says what it needs of its
block.

Clip space as every townsfolk clip's: pixels, y down, facing -z, the right hand at -x, the feet at y 24; the block the
townsperson works at is the one ahead, brought NEAR px nearer than the cell's middle would put it, because that is where
Part I2's kitchen scenes stand the keeper (Stands in cooking/town/KitchenScene.kt: 0.12 block toward the work), so at
z -24 + NEAR .. -8 + NEAR. Each clip's stance (her feet and hips) is set NEAR px back with it, so her body stands to the
work as it was designed to at the cell's middle while the game's position is where I2 put it. The cookshop's hob (flip) is
the one station a keeper does not walk up to: it is a block ahead.
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
                             key_frame, check, seal, smooth, bump, apply, GRIP, PIVOTS, HIPS, EYES, LIPS, free, rel,
                             head_at, arm_world, hand_point, body_solve, sink_for, torso_to_world, tool_point, tool_dir,
                             clear_of_body, qinv, gaze_head, q_rvec)
from animkit import qaxis
import living_props as lp
import works_clips as wc
from works_clips import (K, KV, rest_arm, point_arm, mix, lifted, set_arm, reach_arm, stand, stepping, wave, pose_now,
                         part_M, joins_idle, maybe_join, tools_when, feet_down, solve_standing, loop_solve, head_pt,
                         REST, FEET, bx, frame_of, rot_about)

la = None           # living_anims, handed to build()

# ================================================================== what the clips share

IRON = (66, 64, 70)
IRON_D = (46, 44, 50)
HEARTH = (122, 116, 108)
BRICK = (132, 86, 68)
BRICK_D = (104, 66, 52)
EMBER = (214, 104, 40)
STEW = (150, 92, 46)
WOODB = (150, 110, 68)
WOODB_D = (116, 82, 50)
CRUST = (176, 128, 70)
MEAT = (150, 80, 52)
MEAT_D = (112, 58, 40)
TRAY = (96, 98, 106)
PANC = (58, 56, 62)
STONEC = (128, 126, 120)
CAKE = (214, 166, 80)
CAKE_D = (172, 112, 52)

RIGHT_FIST = np.asarray(GRIP['right_arm'], float)
LEFT_FIST = np.asarray(GRIP['left_arm'], float)

# an arm's box in its own space (the right arm: x -3..1, y -2..10, z -2..2; the left: x -1..3), as corners: what a
# station must keep a hand out of
ARM_CORNERS = {'right_arm': [(x, y, z) for x in (-3.0, 1.0) for y in (-2.0, 4.0, 10.0) for z in (-2.0, 2.0)],
               'left_arm': [(x, y, z) for x in (-1.0, 3.0) for y in (-2.0, 4.0, 10.0) for z in (-2.0, 2.0)]}
BODY_CORNERS = [(x, y, z) for x in (-4.0, 4.0) for y in (0.0, 6.0, 12.0) for z in (-2.0, 2.0)]
HEAD_CORNERS = [(x, y, z) for x in (-4.0, 4.0) for y in (-8.0, 0.0) for z in (-4.0, 4.0)]
LEG_CORNERS = [(x, y, z) for x in (-2.0, 2.0) for y in (0.0, 6.0, 12.0) for z in (-2.0, 2.0)]


def depth_in(p, box, margin=0.0):
    """How far a world point sits inside a box (lo, hi): the smallest way out of it, or 0 if it is outside."""
    lo, hi = np.asarray(box[0], float) + margin, np.asarray(box[1], float) - margin
    p = np.asarray(p, float)
    if np.any(p <= lo) or np.any(p >= hi):
        return 0.0
    return float(min(np.min(p - lo), np.min(hi - p)))


def keepout(c, label, boxes, parts, times=None, tol=0.15, margin=0.0):
    """Checks that nothing of [parts] ([(part, corner points in its own space)]) passes into any of the solid [boxes]
    ((lo, hi) pairs, the station's; or a function of the tick giving them, for a station that moves) at any of [times]
    (every tick by default): records the deepest it goes, which should be none, as one printed check. Returns that depth."""
    worst, where, what = 0.0, 0.0, ''
    times = list(times if times is not None else np.arange(0.0, c.length + 0.01, 1.0))
    for t in times:
        bs = boxes(t) if callable(boxes) else boxes
        for part, pts in parts:
            for i, p in enumerate(pts):
                w = ta.fk(c, t, part, p)
                for j, b in enumerate(bs):
                    d = depth_in(w, b, margin)
                    if d > worst:
                        worst, where, what = d, t, f'{part} point {i} in box {j}'
    check(c, f'{label}: deepest into the station{" (" + what + ", t=" + format(where, "g") + ")" if worst > 0 else ""}',
          (worst,), (0.0,), tol=tol)
    return worst


HEAD_BOX = ((-4.0, -8.0, -4.0), (4.0, 0.0, 4.0))
BODY_BOX = ((-4.0, 0.0, -2.0), (4.0, 12.0, 2.0))
ARM_GRID = {'right_arm': [(x, y, z) for x in (-3.0, -1.0, 1.0) for y in (4.0, 6.0, 8.0, 10.0) for z in (-2.0, 0.0, 2.0)],
            'left_arm': [(x, y, z) for x in (-1.0, 1.0, 3.0) for y in (4.0, 6.0, 8.0, 10.0) for z in (-2.0, 0.0, 2.0)]}   # (the arm below the shoulder's joint)


def self_clear(c, label, times=None, tol=0.15, sides=('right_arm', 'left_arm'), against=(('head', HEAD_BOX), ('body', BODY_BOX))):
    """Checks that her arms ([sides]) stay out of her own head and body (their boxes, in their own spaces): the deepest an
    arm's points go into either at any of [times] (every tick by default), printed as one check. Returns that depth."""
    worst, where, what = 0.0, 0.0, ''
    for t in (times if times is not None else np.arange(0.0, c.length + 0.01, 1.0)):
        for side in sides:
            Ma = wc.part_M(c, t, side)
            pts = np.array([Ma @ np.append(p, 1.0) for p in ARM_GRID[side]])[:, :3]
            for part, box in against:
                Mi = np.linalg.inv(wc.part_M(c, t, part))
                for i, p in enumerate(pts):
                    d = depth_in((Mi @ np.append(p, 1.0))[:3], box)
                    if d > worst:
                        worst, where, what = d, t, f'{side} point {i} in her {part}'
    check(c, f'{label}: deepest into her own head or body{" (" + what + ", t=" + format(where, "g") + ")" if worst > 0 else ""}',
          (worst,), (0.0,), tol=tol)
    return worst


def pole_clear(c, label, fist_of, pole_of, times=None, tol=0.25):
    """Checks that the peel's pole, all along it but for a hand's width at each of her fists, stays out of her own head and body:
    the deepest of its points ([pole_of](fist, t) gives the pole's direction, how far up it the right hand has it and the blade's
    middle) at any of [times] (every half tick by default), printed as one check. Returns that depth."""
    worst, where = 0.0, 0.0
    for t in (times if times is not None else np.arange(0.0, c.length + 0.01, 0.5)):
        fist = fist_of(t)
        d, g, B = pole_of(fist, t)
        left = ta.fk(c, t, 'left_arm', LEFT_FIST)
        y_left = g + float(np.clip(np.dot(left - fist, d), 0.0, 14.0))
        for y in np.arange(-3.0, 15.01, 1.0):
            if abs(y - g) < 2.0 or abs(y - y_left) < 2.0:
                continue
            w = ta.fk(c, t, 'peel', (0.0, y, 0.0))
            for part, box in (('head', HEAD_BOX), ('body', BODY_BOX)):
                dd = depth_in((np.linalg.inv(wc.part_M(c, t, part)) @ np.append(w, 1.0))[:3], box)
                if dd > worst:
                    worst, where = dd, t
    check(c, f'{label}: deepest into her own head or body{" (t=" + format(where, "g") + ")" if worst > 0 else ""}', (worst,), (0.0,), tol=tol)
    return worst


def solids(station, skip=(), moving=False):
    """The (lo, hi) pairs of a stand-in station's boxes that can be bumped into: the lit ones (the fire, a stew's
    surface, a pan's food are drawn unlit), less the [skip]ped indices (what a hand is holding). [moving]: a function of
    the tick instead (the station's parts that move, a door, are where they are then)."""
    def at_t(t=None):
        return [(lo, hi) for i, (lo, hi, col, lit) in enumerate(STATION_BOXES[station](t)) if lit and i not in skip]
    return at_t if moving else at_t(None)


def arm_parts(*sides):
    return [(s, ARM_CORNERS[s]) for s in sides]


def circle_phase(t, turn, t0, wobble=0.0, phase=0.0):
    """An angle (radians) that turns once every [turn] ticks, zero at [t0], its speed varying by [wobble] (0.1: a tenth
    faster and slower), so a stroke has a heavy side and a light one; periodic, so a loop has no seam."""
    u = (t - t0) / turn
    return 2.0 * math.pi * (u + wobble / (2.0 * math.pi) * math.sin(2.0 * math.pi * u + phase))


# ================================================================== the stations the previews draw
# Hearthcraft M1's blocks, at the sizes their models give them (the numbers are the ones in cook_art.py's header and its
# station modules, in block px: x east, y up, z south, 16 to a block, every model authored facing north, its front toward
# -z), as boxes; every number a clip leans on is a named constant below and is written up in the test guide, so a block
# that is made otherwise is one number to change (and the generator run again). A station's boxes are in clip space for the
# way the townsperson faces it: 'S' she stands in front of the block and looks at its front (the Prep Board, the Bake oven),
# 'E' she looks east at a block whose crane is at its north-east corner (the Stewpot in the tavern's hearth), 'W' she stands
# at the east end of the Spit, at its crank, and 'H' is 'S' for the cookshop's Hob, whose keeper does not step up to it.

# Part I2's scenes stand the keeper at a station a hand's breadth toward it, not in the middle of the cell before it
# (cooking/town/KitchenScene.kt, Stands.TOWARD: 0.12 block): the block is that much nearer her (px).
NEAR = 0.12 * 16.0


def at(frame, p):
    """A block-space point in clip space for a townsperson facing [frame] ('S', 'E', 'W': standing where Part I2's scenes
    stand her, NEAR px in from the cell's middle; 'H': as 'S' from the middle of the cell, the cookshop keeper at the hob)."""
    x, y, z = (float(v) for v in p)
    if frame == 'H':
        return (x - 8.0, 24.0 - y, -8.0 - z)
    if frame == 'S':
        return (x - 8.0, 24.0 - y, -8.0 - z + NEAR)
    if frame == 'E':
        return (8.0 - z, 24.0 - y, -8.0 - x + NEAR)
    if frame == 'W':
        return (z - 8.0, 24.0 - y, x - 24.0 + NEAR)
    raise ValueError(frame)


def blk(frame, lo, hi, col, lit=True):
    """A box (lo, hi in block space) as the previews' (lo, hi, colour, lit) in clip space. Unlit boxes are for what is not
    solid (a fire, a stew's surface, food in a pan): they are drawn flat and nothing is checked against them."""
    a, b = at(frame, lo), at(frame, hi)
    return (tuple(min(u, w) for u, w in zip(a, b)), tuple(max(u, w) for u, w in zip(a, b)), col, lit)


def _now(name):
    """The tick a preview is drawing [name] at (None: not drawing it): for the parts a station moves (a crank, a door)."""
    return la._CUR[1] if la is not None and la._CUR[0] == name else None


# ---- the Stewpot, hearth style (stir): a paved hearth with a sunk fire pit, the crane's post at its north-east corner, its
# arm out over the middle of the cell, the pot on its bail a hand's breadth under the arm's lug. The clips need the pot's
# middle and rim, the stew's surface and the mouth's radius, and (for the keepouts) the crane, the bail and the pot's walls.
SP_FRAME = 'E'
POT_B = (8.0, 8.0)                              # the pot hangs over the middle of the cell (x, z), notch 0
POT_TOP_B, POT_BOTTOM_B = 10.0, 5.6
POT_MOUTH = 3.0                                 # the mouth's inside radius at the rim
POT_BELLY = 3.65                                # its widest apothem
SURFACE_B = 8.5                                 # the stew's surface (the renderer: 6.7 a ladleful up to 9.4 full)
CRANE_B = (13.2, 2.8)                           # the crane's post (x, z)
HEARTH_B = 1.8                                  # the hearth slab's height
POT_AT = np.array([at(SP_FRAME, (POT_B[0], 0, POT_B[1]))[0], 0.0, at(SP_FRAME, (POT_B[0], 0, POT_B[1]))[2]])   # (0, ., -16 + NEAR)
POT_RIM = 24.0 - POT_TOP_B                      # clip y of the rim: 14.0
POT_BOTTOM = 24.0 - POT_BOTTOM_B
POT_SURFACE = 24.0 - SURFACE_B                  # 15.5


def _stewpot(t=None, turn=0):
    """The Stewpot's boxes; [turn]: the block turned that many quarter turns about its middle. The Stewpot has no facing, so its
    crane stands at the world's north-east corner however the tavern stands, and she may find it at her far left (0, the
    previews' and the plans' way: she faces east), her near left (1), her near right (2) or her far right (3)."""
    f = SP_FRAME

    def blk_(lo, hi, col, lit=True):
        def turned(p):
            x, y, z = (float(v) for v in p)
            dx, dz = x - 8.0, z - 8.0
            for _ in range(turn % 4):
                dx, dz = dz, -dx
            return (8.0 + dx, y, 8.0 + dz)
        return blk(f, turned(lo), turned(hi), col, lit)
    cx, cz = POT_B
    b = [blk_(lo, hi, HEARTH) for lo, hi in (((0.5, 0, 0.5), (15.5, HEARTH_B, 3.2)), ((0.5, 0, 12.8), (15.5, HEARTH_B, 15.5)),
                                              ((0.5, 0, 3.2), (3.2, HEARTH_B, 12.8)), ((12.8, 0, 3.2), (15.5, HEARTH_B, 12.8)))]
    b.append(blk_((3.2, 0.3, 3.2), (12.8, 0.7, 12.8), EMBER, False))                          # the coals
    px, pz = CRANE_B
    b += [blk_((px - 1.6, HEARTH_B, pz - 1.6), (px + 1.6, HEARTH_B + 0.4, pz + 1.6), IRON_D),      # its footplate
          blk_((px - 0.6, HEARTH_B + 0.4, pz - 0.6), (px + 0.6, 15.4, pz + 0.6), IRON),          # the post
          blk_((px - 1.0, HEARTH_B + 0.4, pz - 1.0), (px + 1.0, HEARTH_B + 1.4, pz + 1.0), IRON_D),
          blk_((px - 0.9, 15.0, pz - 0.9), (px + 0.9, 15.8, pz + 0.9), IRON_D)]                 # its cap
    d = np.array([cx - px, cz - pz])
    length = float(np.linalg.norm(d))
    d /= length
    for s_ in (0.9, 2.3, 3.7, 5.1, 6.5, length - 0.2):                                              # the arm over the pot
        mx, mz = px + d[0] * s_, pz + d[1] * s_
        b.append(blk_((mx - 0.8, 14.4, mz - 0.8), (mx + 0.8, 15.4, mz + 0.8), IRON))
    b.append(blk_((cx - 1.2, 12.9, cz - 1.2), (cx + 1.2, 14.4, cz + 1.2), IRON_D))                # the lug at its end
    for k in range(7):                                                                             # the brace under it
        u = k / 6.0
        s_ = 0.6 + u * (0.62 * length - 0.6)
        mx, mz, my = px + d[0] * s_, pz + d[1] * s_, 8.2 + u * 6.1
        b.append(blk_((mx - 0.45, my - 0.45, mz - 0.45), (mx + 0.45, my + 0.45, mz + 0.45), IRON))
    b.append(blk_((cx - 0.5, 12.4, cz - 0.5), (cx + 0.5, 13.0, cz + 0.5), IRON))                  # the chain
    top = POT_TOP_B
    wire = [(cx - 3.7, top - 0.2), (cx - 3.0, top + 1.05), (cx, top + 1.75), (cx + 3.0, top + 1.05), (cx + 3.7, top - 0.2)]
    for (xa, ya), (xb_, yb) in zip(wire, wire[1:]):                                                 # the bail's arch across the mouth
        b.append(blk_((min(xa, xb_), min(ya, yb) - 0.3, cz - 0.3), (max(xa, xb_), max(ya, yb) + 0.3, cz + 0.3), IRON))
    b.append(blk_((cx - 0.7, top + 1.5, cz - 0.3), (cx + 0.7, top + 2.5, cz + 0.3), IRON))        # its eye
    r, w = 3.6, 0.6                                                                                # the pot: floor and four walls
    b += [blk_((cx - 2.5, POT_BOTTOM_B, cz - 2.5), (cx + 2.5, POT_BOTTOM_B + 0.6, cz + 2.5), IRON_D),
          blk_((cx - r, POT_BOTTOM_B + 0.6, cz - r), (cx + r, top, cz - r + w), IRON),
          blk_((cx - r, POT_BOTTOM_B + 0.6, cz + r - w), (cx + r, top, cz + r), IRON),
          blk_((cx - r, POT_BOTTOM_B + 0.6, cz - r + w), (cx - r + w, top, cz + r - w), IRON),
          blk_((cx + r - w, POT_BOTTOM_B + 0.6, cz - r + w), (cx + r, top, cz + r - w), IRON),
          blk_((cx - 2.9, SURFACE_B - 0.1, cz - 2.9), (cx + 2.9, SURFACE_B + 0.1, cz + 2.9), STEW, False)]
    return b


# ---- the Hob (flip): a low brick plinth with an iron top and a raised rim, the flat pan on it (a shallow dish 10.8 across
# with a handle reaching out to the east), six places in it for a piece of food. Needs: the top of the iron (where the pan
# stands), the pan's floor and its places, the plinth's front face.
HB_FRAME = 'H'
HOB_BODY = (1.0, 0.0, 1.0, 15.0, 9.0, 15.0)
HOB_TOP_B, HOB_RIM_B = 10.0, 11.2
PAN_R, PAN_FLOOR_B = 5.4, 10.5
PAN_SLOTS_B = [(8.0 + dx, 8.0 + dz) for dz in (-1.9, 1.9) for dx in (-3.0, 0.0, 3.0)]
HOB_FRONT = at(HB_FRAME, (8.0, 0.0, 1.0))[2]    # the plinth's front face: -9
PAN_AT = np.array([0.0, 24.0 - PAN_FLOOR_B, -16.0])     # the pan's middle, on its floor: y 13.5
PAN_SLOTS = [np.array([at(HB_FRAME, (x, 0, z))[0], 24.0 - PAN_FLOOR_B, at(HB_FRAME, (x, 0, z))[2]]) for x, z in PAN_SLOTS_B]


def _hob(t=None):
    f = HB_FRAME
    x0, y0, z0, x1, y1, z1 = HOB_BODY
    b = [blk(f, (x0, y0, z0), (x1, y1, z1), BRICK),
         blk(f, (x0 - 0.5, y1, z0 - 0.5), (x1 + 0.5, HOB_TOP_B, z1 + 0.5), IRON)]
    xa, xb_, za, zb = x0 - 0.5, x1 + 0.5, z0 - 0.5, z1 + 0.5
    for lo, hi in (((xa, HOB_TOP_B, za), (xb_, HOB_RIM_B, za + 0.8)), ((xa, HOB_TOP_B, zb - 0.8), (xb_, HOB_RIM_B, zb)),
                   ((xa, HOB_TOP_B, za + 0.8), (xa + 0.8, HOB_RIM_B, zb - 0.8)), ((xb_ - 0.8, HOB_TOP_B, za + 0.8), (xb_, HOB_RIM_B, zb - 0.8))):
        b.append(blk(f, lo, hi, IRON_D))
    b.append(blk(f, (12.4, HOB_RIM_B, 12.4), (14.4, 15.4, 14.4), IRON_D))                                   # the flue
    r = PAN_R
    b += [blk(f, (8 - r + 0.2, HOB_TOP_B, 8 - r + 0.2), (8 + r - 0.2, PAN_FLOOR_B, 8 + r - 0.2), PANC),        # the pan: its floor
          blk(f, (8 - r, PAN_FLOOR_B, 8 - r), (8 + r, HOB_TOP_B + 1.5, 8 - r + 0.6), PANC),                  # its rim, four walls
          blk(f, (8 - r, PAN_FLOOR_B, 8 + r - 0.6), (8 + r, HOB_TOP_B + 1.5, 8 + r), PANC),
          blk(f, (8 - r, PAN_FLOOR_B, 8 - r + 0.6), (8 - r + 0.6, HOB_TOP_B + 1.5, 8 + r - 0.6), PANC),
          blk(f, (8 + r - 0.6, PAN_FLOOR_B, 8 - r + 0.6), (8 + r, HOB_TOP_B + 1.5, 8 + r - 0.6), PANC),
          blk(f, (11.78, HOB_TOP_B + 0.7, 7.3), (15.18, HOB_TOP_B + 1.2, 8.7), PANC),                      # its handle, out to the east
          blk(f, (14.78, HOB_TOP_B + 0.7, 7.0), (15.98, HOB_TOP_B + 1.2, 9.0), PANC)]
    tt = t if t is not None else _now('flip')
    for i, (x, z) in enumerate(PAN_SLOTS_B):                                                                 # what is in it (flat, not solid)
        if i == FLIP_SLOT:
            mid, half, done = hob_piece(tt)
            lo = (mid[0] - half[0], mid[1] - half[1], mid[2] - half[2])
            hi = (mid[0] + half[0], mid[1] + half[1], mid[2] + half[2])
            b.append(blk(f, lo, hi, CAKE_D if done else CAKE, False))
        else:
            b.append(blk(f, (x - 1.6, PAN_FLOOR_B, z - 1.6), (x + 1.6, PAN_FLOOR_B + 0.9, z + 1.6), CAKE, False))
    return b


# ---- the Spit (turn_spit, carve): two iron uprights each with three rests, the rod along x resting in the middle one at
# y 9.1, six joints on it, a stone plinth with the pit and the drip tray, the crank at the east end (a thin iron arm 3.3
# long that turns with the rod, a wooden knob at its end). The clips are for a townsperson who stands in the cell east of
# the crank and looks west along the rod: the crank is then in front of her, its axis pointing at her.
SPIT_FRAME = 'W'
SPIT_AXIS_B = (9.1, 8.0)                        # the rod's axis: y, z (block px; notch 1: the middle rest)
SPIT_POSTS_X = ((0.8, 2.2), (13.0, 14.4))
SPIT_POST_Z = (6.8, 9.2)
SPIT_POST_TOP = 13.2
CRANK_X_B = (14.8, 15.4)                        # the crank arm's thickness along the rod
CRANK_KNOB_X_B = (15.4, 16.3)                   # the knob, outside the arm
CRANK_ARM = 3.3                                 # the arm's length from the axis
JOINT_X_B = [3.0 + 1.82 * i for i in range(6)]
# the crank's middle (the axis where the knob's plane is) and the knob's circle, in clip space (frame 'W')
CRANK_C = np.array([at(SPIT_FRAME, (15.85, SPIT_AXIS_B[0], SPIT_AXIS_B[1]))[0], 24.0 - SPIT_AXIS_B[0],
                    at(SPIT_FRAME, (15.85, SPIT_AXIS_B[0], SPIT_AXIS_B[1]))[2]])
CRANK_TURN = 40.0                               # ticks to a revolution in turn_spit
CRANK_DIR = -1.0                                # the angle (0 = the arm up, positive toward her left) grows by this each turn: she turns it clockwise
CRANK_A0 = 200.0                                # the angle at tick 0 of turn_spit (degrees)


def crank_angle(t):
    """The crank's angle at tick [t] of turn_spit (degrees; 0: the arm up; 90: toward the townsperson's left, as she looks along the rod)."""
    return CRANK_A0 + CRANK_DIR * 360.0 * t / CRANK_TURN


def crank_knob(t):
    """The knob's middle (clip space) at tick [t] of turn_spit."""
    a = math.radians(crank_angle(t))
    return CRANK_C + np.array([CRANK_ARM * math.sin(a), -CRANK_ARM * math.cos(a), 0.0])


def _spit(t=None, frame=None, carved=None):
    """The Spit's boxes in clip space for a townsperson in [frame] (the crank end by default). [carved]: the index of a joint
    to leave open to the knife (the others of the roast are solid then: the clip carving at its long side keeps out of them)."""
    f = frame or SPIT_FRAME
    ay, az = SPIT_AXIS_B
    b = [blk(f, lo, hi, STONEC) for lo, hi in (((0.6, 0, 3.2), (15.4, 2.0, 4.2)), ((0.6, 0, 11.8), (15.4, 2.0, 12.8)),
                                              ((0.6, 0, 4.2), (2.4, 2.0, 11.8)), ((12.6, 0, 4.2), (15.4, 2.0, 11.8)))]
    b.append(blk(f, (3.2, 1.0, 6.4), (12.0, 1.9, 9.6), TRAY))                                       # the drip tray
    for z0, z1 in ((4.6, 6.0), (10.0, 11.4)):
        b.append(blk(f, (3.0, 1.0, z0), (12.0, 1.7, z1), EMBER, False))                             # the beds of coals
    for x0, x1 in SPIT_POSTS_X:
        b.append(blk(f, (x0, 2.0, SPIT_POST_Z[0]), (x1, SPIT_POST_TOP, SPIT_POST_Z[1]), IRON))
    b.append(blk(f, (0.4, ay - 0.5, az - 0.5), (15.0, ay + 0.5, az + 0.5), IRON_D))                 # the rod
    for i, x in enumerate(JOINT_X_B):                                                                # the roast on it (not solid unless the knife is to keep out of it)
        b.append(blk(f, (x - 0.9, ay - 1.1, az - 1.1), (x + 0.9, ay + 1.1, az + 1.1), MEAT, carved is not None and i != carved))
    tt = t if t is not None else _now('turn_spit')
    ang = crank_angle(tt) if tt is not None else crank_angle(0.0)
    a = math.radians(ang)
    sa, ca = math.sin(a), math.cos(a)
    for k in range(4):                                                                               # the crank arm (turning: not solid)
        s_ = CRANK_ARM * (k + 0.5) / 4.0
        b.append(blk(f, (CRANK_X_B[0], ay + s_ * ca - 0.35, az + s_ * sa - 0.35), (CRANK_X_B[1], ay + s_ * ca + 0.35, az + s_ * sa + 0.35), IRON, False))
    b.append(blk(f, (CRANK_KNOB_X_B[0], ay + CRANK_ARM * ca - 0.7, az + CRANK_ARM * sa - 0.7),
                 (CRANK_KNOB_X_B[1], ay + CRANK_ARM * ca + 0.7, az + CRANK_ARM * sa + 0.7), WOODB_D, False))
    return b


# ---- the Prep Board (slice): a short counter, four legs, a slab top (y 8 to 9.6), a chopping board on it (its top at
# 10.2). Needs: the board's top and where it ends.
PB_FRAME = 'S'
BOARD_TOP_B = 10.2
BOARD_B = (3.2, 4.2, 12.8, 12.4)                # x0, z0, x1, z1


def _prep_board(t=None):
    f = PB_FRAME
    b = []
    for lx in (1.4, 13.0):
        for lz in (3.0, 11.6):
            b.append(blk(f, (lx, 0, lz), (lx + 1.6, 8.0, lz + 1.4), WOODB_D))
    b += [blk(f, (1.4, 6.8, 3.0), (14.6, 8.0, 3.7), WOODB_D), blk(f, (1.4, 6.8, 12.3), (14.6, 8.0, 13.0), WOODB_D),
          blk(f, (0.6, 8.0, 2.0), (15.4, 9.6, 14.0), WOODB),
          blk(f, (BOARD_B[0], 9.6, BOARD_B[1]), (BOARD_B[2], BOARD_TOP_B, BOARD_B[3]), (176, 142, 94))]
    return b


# ---- the Bake oven (peel): a clay-brick body, the portal standing out of the dome's front with its arched door opening
# (4.8 to 11.2 across, 8.8 to 12.4 up), the baking chamber behind it with the trays on rails (8.6 and 10.8), the door
# hung on the opening's right edge (the hinge at x 4.8, z 1.0) and swinging out toward the townsperson. Needs: the door
# opening, the tray's height, where the front stands (z 1.2), and how far the door swings.
OV_FRAME = 'S'
OV_FRONT_B = 1.2
OV_OPEN_B = (4.8, 8.8, 11.2, 12.4)              # the door opening: x0, y0, x1, y1
OV_TRAYS_B = (8.6, 10.8)
OV_HINGE_B = (4.8, 1.0)
OV_DOOR_MAX = 100.0
OV_DOOR_W = 6.4
OV_DOOR_OPENS = (0.0, 5.0)                      # the door swings open over these ticks of peel and closes again at the end
OV_DOOR_SHUTS = (46.0, 50.0)
OV_CAVITY_Z_B = (1.8, 8.8)


def door_angle(t):
    """The oven door's yaw (degrees, outward positive) at tick [t] of peel as the previews draw it (the game's client drives it)."""
    up = smooth((t - OV_DOOR_OPENS[0]) / (OV_DOOR_OPENS[1] - OV_DOOR_OPENS[0]))
    down = smooth((t - OV_DOOR_SHUTS[0]) / (OV_DOOR_SHUTS[1] - OV_DOOR_SHUTS[0]))
    return OV_DOOR_MAX * up * (1.0 - down)


def _bake_oven(t=None):
    f = OV_FRAME
    x0, y0, x1, y1 = OV_OPEN_B
    b = [blk(f, (0.6, 0, 1.0), (15.4, 1.0, 15.8), STONEC),                                   # footing
         blk(f, (1.0, 1.0, OV_FRONT_B), (15.0, 8.0, 15.4), BRICK),                         # the body
         blk(f, (1.0, 8.0, OV_FRONT_B + 2.2), (3.6, 9.9, 15.0), BRICK_D),                     # the dome's first tier beside the portal ...
         blk(f, (12.4, 8.0, OV_FRONT_B + 2.2), (15.0, 9.9, 15.0), BRICK_D),
         blk(f, (3.6, 8.0, OV_FRONT_B), (x0, 14.6, OV_FRONT_B + 2.2), BRICK_D),               # ... the portal's two sides
         blk(f, (x1, 8.0, OV_FRONT_B), (12.4, 14.6, OV_FRONT_B + 2.2), BRICK_D),
         blk(f, (x0, 8.0, OV_FRONT_B), (x1, y0, OV_FRONT_B + 2.2), BRICK_D),                  # the sill under the opening
         blk(f, (3.6, y1, OV_FRONT_B), (12.4, 14.6, OV_FRONT_B + 2.2), BRICK_D),              # the lintel over it
         blk(f, (x0, 8.0, OV_CAVITY_Z_B[1]), (x1, 12.8, 15.0), BRICK_D),                      # the chamber's back wall
         blk(f, (x0 - 0.2, 8.0, OV_FRONT_B + 2.2), (x0, 12.8, OV_CAVITY_Z_B[1]), BRICK_D),    # its sides and roof
         blk(f, (x1, 8.0, OV_FRONT_B + 2.2), (x1 + 0.2, 12.8, OV_CAVITY_Z_B[1]), BRICK_D),
         blk(f, (x0, 12.8, OV_FRONT_B + 2.2), (x1, 14.0, OV_CAVITY_Z_B[1]), BRICK_D),
         blk(f, (3.6, 14.6, OV_FRONT_B), (12.4, 15.3, 14.0), BRICK_D),
         blk(f, (4.5, 1.2, OV_FRONT_B - 0.2), (11.5, 3.6, OV_FRONT_B), (40, 26, 20), False)]  # the firebox mouth (dark)
    tray = OV_TRAYS_B[0]
    b.append(blk(f, (5.4, tray - 0.4, 3.0), (10.6, tray, 8.4), TRAY, False))                   # a tray on the lower rails (flat; not solid)
    tt = t if t is not None else _now('peel')
    ang = door_angle(tt) if tt is not None else 0.0
    hx, hz = OV_HINGE_B
    a = math.radians(ang)
    for k in range(6):                                                                         # the door leaf, turned outward about its hinge
        s_ = OV_DOOR_W * (k + 0.5) / 6.0
        mx, mz = hx + s_ * math.cos(a), hz - s_ * math.sin(a)
        b.append(blk(f, (mx - 0.75, 8.8, mz - 0.75), (mx + 0.75, 13.6, mz + 0.75), IRON_D))
    return b


def _spit_front(t=None):
    """The Spit as the townsperson carving it sees it: from the front of its long side."""
    return _spit(t, 'S', carved=2)


STATION_BOXES = {'stewpot': _stewpot, 'hob': _hob, 'spit': _spit, 'spit_front': _spit_front, 'prep_board': _prep_board,
                 'bake_oven': _bake_oven}
STATION_GROUND = {}
STATION_COLOUR = {'stewpot': (96, 92, 86), 'hob': (104, 98, 90), 'spit': (96, 92, 86), 'spit_front': (96, 92, 86), 'prep_board': (118, 98, 72), 'bake_oven': (104, 98, 90)}

# ================================================================== stir

# ---- stir (80, loop): the house pot stirred with the ladle. She stands in the cell in front of the hearth, the left foot
# forward, and leans over the pot, the ladle in the right fist along the line of the arm, the left arm easy at her side
# with its hand against her thigh; the ladle's bowl goes round the pot's mouth twice, clockwise from above, a hair
# inside its wall, pushing through the heavy side (the far one) and drawing easily on the near, the body rocking with it
# from the hips, the head following the swirl a beat behind. Needs of the block: the pot's middle (POT_AT), the stew's
# surface (POT_SURFACE), the mouth's radius (POT_MOUTH) and the rim (POT_RIM); the clip stands her at its near edge.
ST_LEN = 80
ST_TURN = 40.0                                  # ticks to a circle
ST_STIRS = (10.0, 30.0, 50.0, 70.0)             # the ladle at the far and near walls in turn: the scrape (stir)
ST_FEET = {'right_leg': (-3.0, -2.2 + NEAR), 'left_leg': (2.8, -5.2 + NEAR)}     # (she stands as far from the pot as at the cell's middle)
ST_BACK = -3.4 + NEAR
ST_DIP = 2.4                                    # the knees give
ST_RX, ST_RZ = 1.15, 0.85                       # the bowl's circle, in the pot's mouth (radius 3.0, the bowl 2.6 across)
ST_C = np.array([POT_AT[0] - 0.15, POT_SURFACE - 0.05, POT_AT[2] - 0.2])   # the ladle bowl's middle at the middle of its circle
ST_BOWL = np.asarray(lp.LADLE_BOWL, float)
ST_WOBBLE = 0.22
LADLE_PTS = [(0.0, y, 0.0) for y in (-1.2, 1.0, 3.0, 5.0)] + [(x, y, z) for x in (-1.3, 1.3) for y in (5.4, 7.4) for z in (-0.4, 2.2)]


def st_theta(t):
    return circle_phase(t, ST_TURN, ST_STIRS[0], ST_WOBBLE, 0.4)


def st_bowl(t):
    th = st_theta(t)
    heave = 0.25 * math.cos(th - 2.2)           # a little deeper in the push, up on the draw (the stew is 2.3 px deep)
    return ST_C + np.array([-ST_RX * math.sin(th), heave, -ST_RZ * math.cos(th)])


def st_look(t):
    """Where the eyes are: the stew's surface behind the ladle, about 40 degrees of the circle back."""
    th = st_theta(t) - 0.7
    return np.array([-2.2 * math.sin(th), POT_SURFACE, POT_AT[2] - 2.0 * math.cos(th)])


ST_KEEP = []


def st_keep():
    """(LO, HI): the Stewpot's solid boxes in all four of its turns, which the ladle's arm keeps out of."""
    if not ST_KEEP:
        boxes = [(lo, hi) for k in range(4) for lo, hi, col, lit in _stewpot(None, k) if lit]
        ST_KEEP.append((np.array([lo for lo, hi in boxes], float), np.array([hi for lo, hi in boxes], float)))
    return ST_KEEP[0]


def st_res(t):
    bowl = st_bowl(t)
    pb = tool_point('ladle', ST_BOWL)
    LP = np.array([tool_point('ladle', p) for p in LADLE_PTS])
    LO, HI = st_keep()

    def res(fr, qa, qb):
        Ra, ja = arm_world('right_arm', qa, **fr)
        out = list((ja + Ra @ pb - bowl) * 5.0)
        out += [clear_of_body(fr, ja + Ra @ RIGHT_FIST), ta.arm_dir('right_arm', qa, fr, (1, 0, 0))[1] * 0.6]   # no rolled forearm
        P = ja + np.array(ARM_CORNERS['right_arm'] + ARM_GRID['right_arm']) @ Ra.T
        out += list(penetration(P, LO, HI, 0.15) * 4.0)            # over the crane's post wherever it stands
        out += list(torso_depth(fr, P, 0.2) * 5.0)
        out += list(penetration(ja + LP @ Ra.T, LO, HI, 0.15) * 4.0)   # the ladle under the bail's arch and off the pot's floor
        out += list(np.asarray(qb, float) * 0.0)                     # (the left arm is not solved: it hangs)
        return out
    return res


def stir():
    c = la.clip('stir', ST_LEN, True, look=('innkeeper', 0), tools_=('ladle',), station_='stewpot',
                events=[('stir', k) for k in ST_STIRS], traces=[('ladle', tuple(lp.LADLE_BOWL), (240, 220, 160))],
                views=((40, -14, True), (90, -4), (-50, -10)))
    times = la.every(ST_LEN)

    def step(t, guess):
        th = st_theta(t)
        pitch = 22.0 + 2.5 * math.cos(th)
        yaw = 3.5 * math.sin(th)
        rz = ST_BACK - 0.7 * math.cos(th)
        fr, qa, qb, x = solve_standing(st_res(t), ST_FEET, pitch, yaw, (0.5 * math.sin(th), rz), dip=ST_DIP, guess=guess,
                                       pitch_w=0.12, yaw_w=0.15, sink_w=1.5, root_w=1.0, arms_w=0.01)
        return (fr, qa, qb), x
    sols = loop_solve(times, step)
    for t in times:
        fr, qa, qb = sols[t]
        key_frame(c, t, fr, ease='linear')
        c.key('right_arm', t, qa, ease='linear')
        set_arm(c, 'left_arm', t, rest_arm('left_arm', fr, fwd=3.0 + 3.0 * math.sin(st_theta(t))))
        stand(c, t, fr, feet=ST_FEET)
        c.key('head', t, qslerp(I, gaze_head(fr, st_look(t - 2.0)), 0.6), ease='linear')
    c.mode('head', 'set')
    seal(c)
    for k in (0.0, 10.0, 20.0, 30.0, 50.0, 70.0):
        check(c, f'ladle bowl on its circle (t={k:g})', ta.fk(c, k, 'ladle', ST_BOWL), st_bowl(k), tol=0.6)
    feet_down(c, (0.0, 20.0, 45.0), ST_FEET)
    # nothing of her goes through the hearth, the pot, the crane or the bail
    keepout(c, 'ladle clear of pot and crane', solids('stewpot'), [('ladle', LADLE_PTS)], tol=0.1)
    keepout(c, 'arms clear of pot and crane', solids('stewpot'), arm_parts('right_arm', 'left_arm'), tol=0.1)
    keepout(c, 'body and head clear of the hearth', solids('stewpot'), [('body', BODY_CORNERS), ('head', HEAD_CORNERS)], tol=0.1)
    # the Stewpot has no facing, so the crane may stand at any corner of it as she sees it: nothing goes through it at any
    for k in (1, 2, 3):
        keepout(c, f'clear of the crane at her {("near left", "near right", "far right")[k - 1]}',
                [(lo, hi) for lo, hi, col, lit in _stewpot(None, k) if lit],
                [('ladle', LADLE_PTS), ('body', BODY_CORNERS), ('head', HEAD_CORNERS)] + arm_parts('right_arm', 'left_arm'), tol=0.1)
    self_clear(c, 'arms', tol=0.4)
    return c


# ================================================================== taste

# ---- taste (40, once): a spoonful tried: the spoon brought up from the hip to the lips, the left hand coming up
# beneath it to catch a drip, a blow on it (two small puffs, the head dipping to each), the sip (the head tipped back,
# the spoon tilted: the slurp, tick 24: the clip's one `taste` moment, since cook.taste is a 0.9 s file and a second ask at
# the swallow, tick 30, would play over it) and the swallow (a gulp of the throat and the head, silent), a look aside to
# weigh it, a nod, the spoon lowered and the hands let go. Needs of the world: nothing but the spoonful in her hand, so
# it works at a pot, a pass or a judges' table.
TA_LEN = 40
TA_SIP = 24.0
SPOON_BOWL = np.array([0.0, 4.7, -0.2])         # the spoon's part space: the middle of its bowl
TA_UP = [(0, 0.0), (1.0, 0.0), (9.0, 1.0), (26.0, 1.0), (37.0, 0.0), (40, 0.0)]          # the spoon from the hip to the lips
TA_LEFT = [(0, 0.0), (3.0, 0.0), (9.5, 1.0), (25.0, 1.0), (33.0, 0.0), (40, 0.0)]        # the left hand cupped under it, let go from the sip on
TA_P = [(0, 0.0), (8.0, 5.0), (14.0, 7.0), (22.0, 5.5), (26.0, 3.5), (31.0, 3.0), (38.0, 0.5), (40, 0.0)]
TA_Y = [(0, 0.0), (28.0, 0.0), (34.0, -7.0), (38.0, -2.0), (40, 0.0)]                    # the body turns a little with the look aside
TA_HP = [(0, 0.0), (4.0, 12.0), (9.0, 9.0), (12.0, 7.0), (17.0, 8.0), (21.0, 5.0), (24.0, -6.0), (27.0, -1.0), (30.0, 5.0),
         (33.0, 2.0), (35.5, -3.0), (38.0, 7.0), (40, 0.0)]                              # head down (+): to the spoon, back for the sip, the nod at the end
TA_HY = [(0, 0.0), (29.0, 0.0), (32.0, 6.0), (34.5, 24.0), (36.5, 20.0), (38.5, 4.0), (40, 0.0)]  # the look aside (to her left)
TA_HR = [(0, 0.0), (22.0, 0.0), (25.0, -4.0), (28.0, 0.0), (34.0, 5.0), (37.0, 2.0), (40, 0.0)]
TA_TIP = [(0, 0.0), (17.0, 0.0), (21.0, 0.6), (24.0, 1.0), (27.0, 0.3), (30.0, 0.0), (40, 0.0)]  # the spoon tilted up to be emptied
TA_AXIS = v(f=-0.6, u=0.2, r=-0.8)
TA_AXIS_SIP = v(f=-0.62, u=0.42, r=-0.66)
TA_LIPS_Z = -1.0                                  # the spoon's bowl is held this far behind the face's front plane (px; negative: in front of it)
TA_LIMIT, TA_POS_W, TA_DIR_W = 2.4, 3.0, 0.5      # the shoulder may roll this far (px) to bring the spoon up; how much the bowl at the lips and the spoon's slant weigh


def taste():
    c = la.clip('taste', TA_LEN, False, look=('innkeeper', 0), tools_=('spoon',),
                events=[('taste', TA_SIP)], traces=[('spoon', tuple(SPOON_BOWL), (240, 220, 160))],
                views=((40, -14), (90, -2), (-40, -8)))
    arms = la.Holds()
    for t in la.every(TA_LEN):
        pitch = K(TA_P, t)
        wup = smooth(K(TA_UP, t))
        wl = smooth(K(TA_LEFT, t))
        fr = F_(qt=bend(pitch, K(TA_Y, t), 0.0), vt=(0.0, sink_for(pitch), 0.0), pr=la.root_for(FEET, dip=0.3 * bump(t, 6.0, 33.0)))
        puff = 3.0 * bump(t, 10.5, 17.5) * math.sin(2 * math.pi * (t - 10.5) / 3.5)          # two small blows
        gulp = 1.5 * bump(t, 28.0, 32.0) * math.sin(2 * math.pi * (t - 28.0) / 4.0)
        q_head = qeuler(x=K(TA_HP, t) + puff + gulp, y=K(TA_HY, t), z=K(TA_HR, t))
        lips = ta.head_point(fr, q_head, LIPS) + np.array([0.0, 0.0, TA_LIPS_Z])
        tip = K(TA_TIP, t)
        axis = unit(TA_AXIS * (1.0 - tip) + TA_AXIS_SIP * tip)
        rpose = arms.slack('right_arm', fr, [(tool_point('spoon', SPOON_BOWL), lips, TA_POS_W)],
                           [(tool_dir('spoon', (0, 1, 0)), axis, TA_DIR_W)], limit=TA_LIMIT)
        under = lips + np.array([2.2, 5.6, -3.4])
        lpose = reach_arm(arms, 'left_arm', fr, under, palm=U, palm_w=0.5, slack=0.4, limit=2.0)
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        set_arm(c, 'right_arm', t, mix(rest_arm('right_arm', fr), rpose, wup))
        set_arm(c, 'left_arm', t, mix(rest_arm('left_arm', fr), lpose, wl))
        c.key('head', t, q_head, ease='linear')
    c.mode('head', 'set')
    for k in (14.0, 18.0, TA_SIP):           # held to the lips for the blows and the sip; lowered from the swallow on
        fr = ta.frame_at(c, k)
        check(c, f'spoon at the lips (t={k:g})', ta.fk(c, k, 'spoon', SPOON_BOWL),
              ta.head_point(fr, c.sample('head', k)[0], LIPS) + np.array([0.0, 0.0, TA_LIPS_Z]), tol=0.9)
    self_clear(c, 'arms', tol=0.4)
    joins_idle(c)
    return c


# ================================================================== flip

# ---- flip (30, once): a piece in the pan turned with the turner. She leans over the hob with the turner held out, the blade
# brought in over the pan's near wall and lowered, slid in under the piece on the pan's floor (the scrape), lifted a
# little, and flicked: the wrist snaps the blade up about her fist and the piece goes up and over to land cooked side up
# (flip at the flick, tick 15; land, tick 20: the sizzle), her head following it through the air; the blade is held up out
# of its way till it lands, laid on it for a pat, and drawn back out over the wall; she straightens and the arm hangs.
# The piece's flight is the renderer's (the previews show one); the clip is the turner and the body. The turner's turn is
# not keyed: its blade points from her fist to the edge wanted (the wrist is free about the grip), so only where the edge is
# is designed and the arm and the body find the rest. Needs of the block: the iron top and the rim (the blade and the arm
# stay out of them), the pan's floor (PAN_AT) and its near wall, and the place of the piece (PAN_SLOTS[FLIP_SLOT]).
FL_LEN = 30
FLIP_SLOT = 0                                   # the piece she turns: the front row's right-hand place
FL_UNDER, FL_LAUNCH, FL_LAND = 10.5, 15.0, 20.0
FL_PX, FL_PZ = float(PAN_SLOTS[FLIP_SLOT][0]), float(PAN_SLOTS[FLIP_SLOT][2])
FL_FLOOR = float(PAN_AT[1])                     # the pan's floor
BLADE_Y = FL_FLOOR - 0.3                        # the blade's middle just over it
TURNER_EDGE = np.asarray(lp.TURNER_EDGE, float)
TURNER_REACH = float(np.linalg.norm(TURNER_EDGE))                       # the fist to the edge's middle
TURNER_KINK = math.degrees(math.atan2(TURNER_EDGE[2], TURNER_EDGE[1]))  # the blade's offset from the handle's line
FL_REST_TIP = -30.0                             # the turner hanging in her hand: its tip down ...
FL_REST_HEAD = -25.0                            # ... and a little out to her right (degrees toward her left, as FL_HEAD)
FL_HEAD = 6.0                                   # the heading she holds it at over the pan (degrees toward her left)
FL_READY_FIST = np.array([-3.97, 11.6, -3.0])   # at the ready her fist hangs a little ahead of her hip, the blade out over the wall
FL_READY_TIP = 18.0                             # its tip a little up
FL_OUT_FIST = np.array([-6.2, 11.9, -1.3])      # on the way to it the blade comes up outside the hob's right side (it is not
FL_OUT_TIP, FL_OUT_HEAD = 8.0, -22.0            # in the way there: the wall in front of her is) and swings in over the rim
FL_HEADS = [(0, FL_REST_HEAD), (0.9, FL_REST_HEAD), (2.6, FL_OUT_HEAD), (4.5, FL_HEAD), (25.0, FL_HEAD), (27.4, FL_OUT_HEAD),
            (29.5, FL_REST_HEAD), (30, FL_REST_HEAD)]
FL_STANCE = 2.4                                 # she steps back from the hob a little to work (the reach wants the hips behind the feet)
FL_STEPS = [(0.6, 3.4, 'right_leg', (-1.9, FL_STANCE)), (1.8, 4.6, 'left_leg', (1.9, FL_STANCE)),
            (26.0, 28.6, 'left_leg', FEET['left_leg']), (27.0, 29.4, 'right_leg', FEET['right_leg'])]
# the turner's leading edge's middle (clip space) from the ready on, between the ready and the ready again; the rest of her follows
FL_EDGE = [
    (6.6, (FL_PX, 12.3, -13.4)),                # the tip dipping into the pan
    (7.8, (FL_PX, BLADE_Y, -14.0)),             # on its floor, at the piece's near edge
    (FL_UNDER, (FL_PX, BLADE_Y, -16.6)),        # slid in under it
    (11.8, (FL_PX, 13.1, -16.7)),               # a beat to be sure of it
    (13.5, (FL_PX, 11.9, -16.4)),               # lifted
    (14.3, (FL_PX, 8.4, -15.2)),
    (FL_LAUNCH, (FL_PX, 4.2, -13.9)),           # the flick
    (16.4, (FL_PX, 1.8, -13.0)),                # followed through
    (17.8, (FL_PX, 2.4, -13.2)),                # held up out of its way
    (FL_LAND, (FL_PX + 0.1, 6.8, -14.3)),       # over the piece as it lands
    (21.4, (FL_PX + 0.2, 11.5, -15.3)),
    (22.5, (FL_PX + 0.2, 12.45, -15.6)),        # laid on it for a pat
    (23.3, (FL_PX + 0.2, 12.45, -15.6)),
    (24.4, (FL_PX + 0.1, 11.3, -14.6)),         # lifted off it
]
# the tip she would like the turner at (a faint wish: the reach decides), and how hard that is held (the ends: hard)
FL_TIP = [(0, FL_REST_TIP), (0.9, FL_REST_TIP), (1.8, -8.0), (2.6, FL_OUT_TIP), (3.5, 14.0), (4.5, FL_READY_TIP), (6.6, 2.0), (7.0, -12.0),
          (7.5, -20.0), (7.8, -22.0), (8.5, -17.0), (9.0, -13.0), (9.5, -9.0), (10.0, -5.0), (FL_UNDER, -3.0), (11.8, 0.0), (13.5, 20.0),
          (FL_LAUNCH, 52.0), (16.4, 60.0), (17.8, 55.0), (FL_LAND, 35.0), (22.5, 0.0), (24.4, 5.0), (26.0, 12.0),
          (27.4, FL_OUT_TIP), (28.5, -8.0), (29.5, FL_REST_TIP), (30, FL_REST_TIP)]
FL_PIN = [(0, 1.0), (4.0, 1.0), (6.6, 0.0), (24.4, 0.0), (27.0, 1.0), (30, 1.0)]
FL_P = [(0, 0.0), (4.5, 4.0), (7.8, 3.0), (FL_UNDER, 12.0), (12.5, 14.0), (14.0, 11.0), (15.0, 3.0), (16.5, -3.0), (18.0, 4.0),
        (20.0, 11.0), (22.0, 14.0), (23.5, 12.0), (24.5, 7.0), (26.5, 3.0), (28.5, 0.0), (30, 0.0)]
FL_Y = [(0, 0.0), (6.0, -3.0), (14.0, -2.0), (22.0, -3.0), (28.0, -1.0), (30, 0.0)]
FL_DIP = [(0, 0.0), (4.0, 0.0), (9.0, 0.0), (11.0, 1.2), (14.0, 1.0), (15.5, 0.2), (18.0, 0.8), (22.0, 1.4), (26.0, 0.6), (30, 0.0)]
FL_FRONT = unit(L + D * 0.35)                   # at work the front of her arm (the palm's side) faces in and a little down
FL_TWIST = [(0, 0.0), (0.9, 0.0), (6.6, 1.0), (24.4, 1.0), (29.4, 0.0), (30, 0.0)]       # how far from the rest's turn about the arm
FL_SOL = {}                                     # tick -> (edge wanted, fist, turner's turn), once the clip is solved
FL_X = {}                                       # tick -> the body's solution (for the working-out)
SOLUTIONS = {'flip': FL_X}                      # each solved clip's: the body's lean, turn, tip, sink and step per tick


def _pts(xs, ys, zs):
    return np.array([(x, y, z) for x in xs for y in ys for z in zs], float)


# what of the turner and the arm must stay out of the hob: the blade's underside, its top corners, the handle; the arm's box
TURNER_PTS = np.vstack([_pts((-1.8, 0.0, 1.8), np.linspace(5.1, 9.3, 9), (1.7,)), _pts((-1.8, 1.8), (5.1, 9.3), (1.3,)),
                        _pts((0.0,), np.linspace(-1.8, 5.0, 8), (0.0,))])
ARM_PTS = {s: _pts((-3.0, -1.0, 1.0) if s == 'right_arm' else (-1.0, 1.0, 3.0), (-2.0, 4.0, 10.0), (-2.0, 0.0, 2.0))
           for s in ('right_arm', 'left_arm')}


def twisted(Ra, front, fist=None):
    """[Ra] (an arm's turn, world; the right arm's unless [fist] is the left's) turned about the line from its shoulder to its
    fist till the arm's front is as near the world direction [front] as the arm allows: the fist stays where it was, so the
    turn about the arm is designed and the solver is left the rest."""
    d = unit(Ra @ (RIGHT_FIST if fist is None else fist))
    f = Ra @ np.array([0.0, 0.0, -1.0])
    fp, rp = f - d * float(f @ d), front - d * float(front @ d)
    if np.linalg.norm(fp) < 1e-6 or np.linalg.norm(rp) < 1e-6:
        return Ra
    fp, rp = unit(fp), unit(rp)
    return rot_about(d, math.degrees(math.atan2(float(np.cross(fp, rp) @ d), float(fp @ rp)))) @ Ra


def penetration(P, LO, HI, grow=0.0):
    """How deep each point of [P] (M x 3) sits inside the deepest of the boxes [LO], [HI] (N x 3 each) grown by [grow]: 0
    for a point outside them all."""
    d = np.minimum(P[:, None, :] - (LO - grow)[None], (HI + grow)[None] - P[:, None, :]).min(axis=2)
    return np.maximum(d.max(axis=1), 0.0)


def turner_R(phi, psi):
    """The turner's turn (part space to clip space): its handle (part +y) pointing ahead [phi] degrees up and [psi] degrees
    toward her left, the flat of its blade (part +z) under it, level when [phi] is 0."""
    a, b = math.radians(phi), math.radians(psi)
    h = np.array([math.sin(b) * math.cos(a), -math.sin(a), -math.cos(b) * math.cos(a)])
    x = np.array([math.cos(b), 0.0, math.sin(b)])
    z = np.cross(x, h)
    return np.column_stack([x, h, z])


def turner_angles(d):
    """The turner's tip (degrees up) and heading (degrees to her left) when its edge is along [d] from the fist."""
    d = np.asarray(d, float)
    return math.degrees(math.atan2(-d[1], math.hypot(d[0], d[2]))) + TURNER_KINK, math.degrees(math.atan2(d[0], -d[2]))


def from_clip(frame, p):
    """A clip-space point as block space (the inverse of at())."""
    x, y, z = (float(v) for v in p)
    if frame == 'H':
        return (x + 8.0, 24.0 - y, -8.0 - z)
    if frame == 'S':
        return (x + 8.0, 24.0 - y, -8.0 - z + NEAR)
    raise ValueError(frame)


def fl_turner(t):
    """(the edge, the turner's turn) at tick [t] as solved (the nearest half tick)."""
    e, fist, R = FL_SOL[min(FL_SOL, key=lambda k: abs(k - t))]
    return e, R


def hob_piece(t):
    """The piece she turns as the previews draw it, in block space: (its middle, its half sizes, cooked side up). On the pan's
    floor until the blade is under it, riding the blade as it lifts, in the air from the flick, then back in its place."""
    x_b, z_b = PAN_SLOTS_B[FLIP_SLOT]
    rest = np.array([x_b, PAN_FLOOR_B + 0.45, z_b])
    half = (1.6, 0.45, 1.6)
    if t is None or t < FL_UNDER or not FL_SOL:
        return rest, half, False

    def on_blade(tt):
        e, R = fl_turner(tt)
        return np.array(from_clip(HB_FRAME, e - R @ np.array([0.0, 2.6, 0.0]) - R[:, 2] * 0.65))
    if t < FL_LAUNCH:
        return on_blade(t), half, False
    land = rest + np.array([0.2, 0.0, 0.1])
    if t < FL_LAND:
        u = (t - FL_LAUNCH) / (FL_LAND - FL_LAUNCH)
        p0 = on_blade(FL_LAUNCH)
        mid = p0 + (land - p0) * u + np.array([0.0, 4.2 * 4.0 * u * (1.0 - u), 0.0])
        s_, c_ = abs(math.sin(math.pi * u)), abs(math.cos(math.pi * u))
        return mid, (1.6, 0.45 * c_ + 1.6 * s_, 1.6 * c_ + 0.45 * s_), u > 0.5
    return land, half, True


TORSO_LO = np.array([-4.0, 0.0, -2.0])
TORSO_HI = np.array([4.0, 12.0, 2.0])


def torso_depth(fr, P, grow=0.1):
    """How deep each world point of [P] (M x 3) is inside her torso's box, grown by [grow] (0 for a point outside it)."""
    Rr, Rt = qmat(fr['qr']), qmat(fr['qt'])
    local = ((((np.asarray(P, float) - HIPS - fr['pr']) @ Rr) - fr['vt']) @ Rt) + HIPS
    d = np.minimum(local - (TORSO_LO - grow), (TORSO_HI + grow) - local).min(axis=1)
    return np.maximum(d, 0.0)


def head_depth(fr, qh, P, grow=0.1):
    """How deep each world point of [P] (M x 3) is inside her head's box, grown by [grow], for the head keyed [qh] (a 'set' key)
    under [fr] (0 for a point outside it)."""
    qr, qt = fr['qr'], fr['qt']
    pivot = HIPS + qmat(qr) @ (HIPS + qmat(qt) @ (np.array(ta.PIVOTS['head'], float) - HIPS) + fr['vt'] - HIPS) + fr['pr']
    local = (np.asarray(P, float) - pivot) @ qmat(qmul(qmul(qr, qt), qh))
    lo, hi = np.array(HEAD_BOX[0]) - grow, np.array(HEAD_BOX[1]) + grow
    return np.maximum(np.minimum(local - lo, hi - local).min(axis=1), 0.0)


def solve_body(res, feet, xh, dip=0.0, guess=None, **w):
    """solve_standing's solve, held near the answer [xh] (pitch, turn, tip, sink, step x, step z: a smoothed earlier one)
    with weights [w] (pitch_w, yaw_w, roll_w, sink_w, root_w, arms_w)."""
    drop = la.root_for(feet, xh[4], xh[5], dip=dip)[1]
    for _ in range(3):
        fr, qa, qb, x = body_solve(res, pitch0=xh[0], yaw0=xh[1], roll0=xh[2], root0=(xh[4], 0.0, xh[5]), guess=guess, sink0=xh[3],
                                   drop=drop, **w)
        want = la.root_for(feet, x[4], x[5], dip=dip)[1]
        if abs(want - drop) < 0.02:
            break
        drop, guess = want, x
    return fr, qa, qb, x


def smoothed(series, sigma=2.0, keep=1):
    """A time series (n x k) filtered with a Gaussian of [sigma] samples, its first and last [keep] rows kept as they are."""
    n = len(series)
    r = int(math.ceil(3 * sigma))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    pad = np.vstack([np.repeat(series[:1], r, axis=0), series, np.repeat(series[-1:], r, axis=0)])
    out = np.stack([np.convolve(pad[:, j], k, mode='valid') for j in range(series.shape[1])], axis=1)
    out[:keep], out[n - keep:] = series[:keep], series[n - keep:]
    return out


def smooth_terms(fr, qa, hist, w_q=20.0, w_p=1.5, w_a=8.0):
    """Residuals that keep a solved pose on the course of the two before it (no jumps between near answers): the body's
    turn, sink and step and the arm's key against what a steady carry-on from [hist] ([(frame, arm key), ...], oldest first)
    would give."""
    if not hist:
        return []
    last = hist[-1]
    prev = hist[-2] if len(hist) > 1 else last
    out = []
    for key, w in (('qt', w_q), ('vt', w_p), ('pr', w_p)):
        out += list((np.asarray(fr[key], float) - (2.0 * last[0][key] - prev[0][key])) * w)
    qp = 2.0 * last[1] - prev[1] if float(np.dot(last[1], prev[1])) > 0 else last[1]
    qn = qa if float(np.dot(qa, last[1])) > 0 else -qa
    out += list((qn - qp) * w_a)
    return out


def fl_front(t, rest_front):
    """The world direction the front of her right arm is turned to at [t]: the rest's, then the work's, and back."""
    w = smooth(K(FL_TWIST, t))
    return unit(rest_front * (1.0 - w) + FL_FRONT * w)


def fl_res(t, E, boxes, hist, rest_front):
    """The residuals of the reach at [t]: the turner's edge at [E], its blade the line from her fist to it, the blade and the
    arm out of the hob's boxes, the fist out of her body, the arm turned about itself as designed (fl_front), the pose on its
    course; the turner's tip and heading near the ones wished (hard at the ends, where they must be the rest's)."""
    LO = np.array([lo for lo, hi in boxes], float)
    HI = np.array([hi for lo, hi in boxes], float)
    tip, pin, head = K(FL_TIP, t), K(FL_PIN, t), K(FL_HEADS, t)
    front = fl_front(t, rest_front)

    def res(fr, qa, qb):
        Ra, ja = arm_world('right_arm', qa, **fr)
        Ra = twisted(Ra, front)
        fist = ja + Ra @ RIGHT_FIST
        d = E - fist
        a, b = turner_angles(d)
        out = [(float(np.linalg.norm(d)) - TURNER_REACH) * 6.0, (a - tip) * (0.15 + 2.0 * pin), (b - head) * (0.12 + 2.0 * pin)]
        out += list(np.asarray(qb, float) * 0.0)                     # (the left arm is not solved: it hangs)
        R = turner_R(a, b)
        out += list(penetration(fist + TURNER_PTS @ R.T, LO, HI, 0.15) * 4.0)
        arm_pts = ja + ARM_PTS['right_arm'] @ Ra.T
        out += list(penetration(arm_pts, LO, HI, 0.08) * 4.0)
        out += list(torso_depth(fr, np.vstack([arm_pts, fist + TURNER_PTS @ R.T]), 0.1) * 4.0)      # (not into her own body)
        out += smooth_terms(fr, qa, hist)
        return out
    return res


def flip():
    c = la.clip('flip', FL_LEN, False, look=('merchant', 0), tools_=('turner',), station_='hob',
                events=[('flip', FL_LAUNCH), ('land', FL_LAND)],
                traces=[('turner', tuple(lp.TURNER_EDGE), (240, 220, 160))], views=((40, -14, True), (90, -4), (-50, -10)))
    boxes = solids('hob')
    g_rest = wc.grip_of('right_arm', rest_arm('right_arm', REST), REST)
    rest_front = arm_world('right_arm', rest_arm('right_arm', REST)[0], **REST)[0] @ np.array([0.0, 0.0, -1.0])

    def edge_of(fist, tip, head):
        return np.asarray(fist, float) + turner_R(tip, head) @ TURNER_EDGE
    e_rest = edge_of(g_rest, FL_REST_TIP, FL_REST_HEAD)
    e_out = edge_of(FL_OUT_FIST, FL_OUT_TIP, FL_OUT_HEAD)
    e_ready = edge_of(FL_READY_FIST, FL_READY_TIP, FL_HEAD)
    keys = ([(0.0, e_rest), (0.9, e_rest), (2.6, e_out), (4.5, e_ready)] +
            [(k, np.asarray(e, float)) for k, e in FL_EDGE] +
            [(27.4, e_out), (29.5, e_rest), (30.0, e_rest)])
    times = la.every(FL_LEN)
    feet_at = {t: stepping(t, FEET, FL_STEPS, lift=1.2) for t in times}
    E_at = {t: la.vec_keys(keys, t, 'spline') for t in times}

    def zmid(t):
        f = feet_at[t][0]
        return 0.5 * (f['right_leg'][1] + f['left_leg'][1])
    # first, each tick on its own (the answers may wander a little between ticks) ...
    guess, rough = None, []
    for t in times:
        fr, qa, qb, guess = solve_standing(fl_res(t, E_at[t], boxes, [], rest_front), feet_at[t][0], K(FL_P, t), K(FL_Y, t), (0.0, zmid(t)),
                                           dip=K(FL_DIP, t), guess=guess, pitch_w=0.25, yaw_w=0.15, sink_w=1.5, root_w=0.15,
                                           roll_w=0.5, arms_w=0.01)
        rough.append(np.asarray(guess, float))
    # ... then the body's course smoothed (the ends the rest's exactly), and each tick solved again close to it, for the contacts
    course = smoothed(np.array([x[:6] if 0.9 < t < 29.1 else np.zeros(6) for t, x in zip(times, rough)]), sigma=2.0)
    first, prev_q = None, None
    for i, t in enumerate(times):
        feet, lift = feet_at[t]
        fr, qa, qb, guess = solve_body(fl_res(t, E_at[t], boxes, [], rest_front), feet, course[i], dip=K(FL_DIP, t),
                                       guess=first if t > 29.4 else rough[i], pitch_w=0.8, yaw_w=0.8, roll_w=0.8, sink_w=3.0,
                                       root_w=2.0, arms_w=0.01)
        first = guess if i == 0 else first
        Ra, ja = arm_world('right_arm', qa, **fr)
        Rp = ta.chain_frame('right_arm', **fr)[0]
        qa = qfrommat(Rp.T @ twisted(Ra, fl_front(t, rest_front)))
        if prev_q is not None and float(np.dot(qa, prev_q)) < 0:
            qa = -qa
        prev_q = qa
        Ra, ja = arm_world('right_arm', qa, **fr)
        fist = ja + Ra @ RIGHT_FIST
        a, b = turner_angles(E_at[t] - fist)
        FL_SOL[t] = (E_at[t], fist, turner_R(a, b))
        FL_X[t] = guess
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        set_arm(c, 'right_arm', t, (qa, np.zeros(3)))
        set_arm(c, 'left_arm', t, rest_arm('left_arm', fr, fwd=3.0 + 6.0 * bump(t, 11.0, 24.0)))
        mid, _, _ = hob_piece(t)
        look = np.array(at(HB_FRAME, mid)) + np.array([0.0, -0.4, 0.0])
        hw = 0.85 * smooth(K([(0, 0.0), (1.2, 0.0), (5.5, 1.0), (25.0, 1.0), (29.5, 0.0), (30, 0.0)], t))
        c.key('head', t, qslerp(I, gaze_head(fr, look), hw), ease='linear')
    c.mode('head', 'set')
    times = sorted(FL_SOL)

    def turner_track(t, pivot):
        i = min(max(int(math.floor(t * 2.0)), 0), len(times) - 2)
        u = min(max(t * 2.0 - i, 0.0), 1.0)
        qa_, qb_ = qfrommat(FL_SOL[times[i]][2]), qfrommat(FL_SOL[times[i + 1]][2])
        return qmat(qslerp(qa_, qb_, u)), None, None
    la.TRACKS['flip'] = {'turner': turner_track}
    for k in (7.8, FL_UNDER, 11.8, 22.5):
        e, _ = fl_turner(k)
        check(c, f'turner\'s edge where it should be (t={k:g})', ta.fk(c, k, 'turner', lp.TURNER_EDGE), e, tol=0.3)
    for k in (4.5, 11.8, 16.4, 22.5):
        check(c, f'turner in the fist (t={k:g})', ta.fk(c, k, 'turner', (0.0, 0.0, 0.0)), ta.fk(c, k, 'right_arm', RIGHT_FIST), tol=0.05)
    check(c, f'edge under the piece, on the pan\'s floor (t={FL_UNDER:g}: its height over the floor)',
          (ta.fk(c, FL_UNDER, 'turner', lp.TURNER_EDGE)[1] - FL_FLOOR,), (-0.3,), tol=0.3)
    for k in (6.0, 10.0, 15.0, 20.0, 22.0):
        feet_down(c, (k,), feet_at[k][0])
    keepout(c, 'turner clear of pan and hob', solids('hob'), [('turner', [tuple(p) for p in TURNER_PTS])], tol=0.1)
    keepout(c, 'arms clear of pan and hob', solids('hob'), arm_parts('right_arm', 'left_arm'), tol=0.1)
    keepout(c, 'body, head and legs clear of the hob', solids('hob'),
            [('body', BODY_CORNERS), ('head', HEAD_CORNERS), ('right_leg', LEG_CORNERS), ('left_leg', LEG_CORNERS)], tol=0.1)
    self_clear(c, 'arms', tol=0.4)
    joins_idle(c)
    return c


# ================================================================== turn_spit

# ---- turn_spit (40, loop): the roast turned by hand. The Spit's crank is a thin iron arm turning with the rod at the east
# end, a knob on its end; she stands in the cell east of it looking west along the rod (the crank is in front of her, its
# axis pointing at her, the knob going round a circle 3.3 across the middle: CRANK_C, CRANK_ARM) and turns it with the
# right fist once round every 40 ticks, clockwise as she sees it, her whole body going with it (the arm is a stiff rod: as
# the knob goes round, her shoulder rocks in and out and up and down to keep it); the left arm is held up in front of her
# face against the heat, the palm to the fire, her head turned a little from it and following the roast. Needs of the
# block: the crank's middle (CRANK_C), the knob's circle (CRANK_ARM), the rod's height (the crank is low: she stoops over
# it, the knees giving), the east post and the plinth in front of her (nothing of her goes through them), and the turn
# kept in step by the game: the knob's angle is crank_angle(tick of the clip). One moment, `crank`, as the knob goes over
# the top (TS_OVER), for the crank's click once a turn.
TS_LEN = 40
TS_FEET = {'right_leg': (-4.0, 1.0 + NEAR), 'left_leg': (3.6, -2.6 + NEAR)}     # (as far from the crank as at the cell's middle)
TS_DIP = 4.5                                    # the knees give
TS_PITCH = 30.0
TS_YAW = -10.0                                  # she stands a little across the crank: the right shoulder leads
TS_ROOT = (1.0, -1.6 + NEAR)
TS_FRONT = unit(L + D * 0.3 + F * 0.2)         # the front of her right arm turns in and a little down as she grips the knob
TS_GRIP_Z = 1.3                                 # the fist sits this far to the wrist's side of the knob's middle
TS_ROAST = np.array([0.0, CRANK_C[1] - 0.6, CRANK_C[2] - 4.8])           # where she looks: the nearest joint of the roast
TS_OVER = round(CRANK_A0 * CRANK_TURN / 360.0, 1)                        # the knob goes over the top (22.2): the crank's click (crank)
TS_X = SOLUTIONS.setdefault('turn_spit', {})


def smoothed_loop(series, sigma=2.0):
    """A periodic time series (n x k, one lap) filtered with a Gaussian of [sigma] samples, round the seam."""
    n = len(series)
    r = int(math.ceil(3 * sigma))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    pad = np.vstack([series[n - r:], series, series[:r]]) if r <= n else np.vstack([series] * 3)
    return np.stack([np.convolve(pad[:, j], k, mode='valid') for j in range(series.shape[1])], axis=1)


def ts_res(t, designed, qh=None):
    """The turn of the crank at [t]: her right fist on the knob and out of her chest; [designed]: the turn of her arm about
    itself as designed (TS_FRONT), the arm kept out of her chest too (else the first, loose pass that finds her body's lean)."""
    knob = crank_knob(t) + np.array([0.0, 0.0, TS_GRIP_Z])

    def res(fr, qa, qb):
        Ra, ja = arm_world('right_arm', qa, **fr)
        if designed:
            Ra = twisted(Ra, TS_FRONT)
        fist = ja + Ra @ RIGHT_FIST
        out = list((fist - knob) * 5.0)
        out += [clear_of_body(fr, fist)]
        if designed:
            P = ja + np.array(ARM_GRID['right_arm']) @ Ra.T
            out += list(torso_depth(fr, P, 0.3) * 5.0)                                              # (not through her own chest ...)
            out += list(head_depth(fr, qh, P, 0.3) * 5.0)                                           # (... or head)
        else:
            out += [ta.arm_dir('right_arm', qa, fr, (1, 0, 0))[1] * 0.6]                           # no rolled forearm
        out += list(np.asarray(qb, float) * 0.0)                     # (the left arm is not solved: it is held up)
        return out
    return res


def turn_spit():
    c = la.clip('turn_spit', TS_LEN, True, look=('innkeeper', 0), tools_=(), station_='spit', events=[('crank', TS_OVER)],
                traces=[('right_arm', tuple(RIGHT_FIST), (240, 220, 160))], views=((40, -14, True), (90, -6), (-45, -10)))
    times = la.every(TS_LEN)

    rough = {}

    def step(t, guess):
        a = math.radians(crank_angle(t))
        pitch = TS_PITCH - 3.0 * math.cos(a)                    # more bent with the knob at the bottom
        yaw = TS_YAW + 3.0 * math.sin(a)                        # the right shoulder leads
        qh = qeuler(x=max(-10.0, 38.0 - pitch), y=-6.0 + 3.0 * math.sin(a), z=-5.0)
        fr, qa, qb, x = solve_standing(ts_res(t, True, qh), TS_FEET, pitch, yaw, TS_ROOT, dip=TS_DIP, guess=guess,
                                       pitch_w=0.1, yaw_w=0.1, sink_w=1.0, root_w=0.6, roll_w=0.6, arms_w=0.01)
        rough[t] = x
        return (fr, qa, qb), x
    loop_solve(times, step)
    # the lean the fist needs, as one smooth course round the lap; then each tick again close to it, with the turn of the
    # arm designed and the arm kept out of her chest
    lap = times[:-1]
    course = smoothed_loop(np.array([rough[t][:6] for t in lap]), sigma=2.0)
    sols, prev_q, guess = {}, None, None
    for i, t in enumerate(lap):
        a = math.radians(crank_angle(t))
        qh = qeuler(x=max(-10.0, 38.0 - course[i][0]), y=-6.0 + 3.0 * math.sin(a), z=-5.0)
        fr, qa, qb, guess = solve_body(ts_res(t, True, qh), TS_FEET, course[i], dip=TS_DIP, guess=rough[t], pitch_w=0.8, yaw_w=0.8,
                                       roll_w=0.8, sink_w=3.0, root_w=2.0, arms_w=0.01)
        sols[t] = (fr, qa, qb)
        TS_X[t] = guess
    sols[times[-1]], TS_X[times[-1]] = sols[times[0]], TS_X[times[0]]
    for t in times:
        fr, qa, qb = sols[t]
        key_frame(c, t, fr, ease='linear')
        Ra, ja = arm_world('right_arm', qa, **fr)
        qa = qfrommat(ta.chain_frame('right_arm', **fr)[0].T @ twisted(Ra, TS_FRONT))
        if prev_q is not None and float(np.dot(qa, prev_q)) < 0:
            qa = -qa
        prev_q = qa
        c.key('right_arm', t, qa, ease='linear')
        a = math.radians(crank_angle(t))
        shield = v(f=0.8, u=0.4 + 0.05 * math.sin(2.0 * a), r=-0.32 + 0.04 * math.cos(a))      # (the hand shifting a little as she works)
        set_arm(c, 'left_arm', t, point_arm(fr, shield, palm=U))
        stand(c, t, fr, feet=TS_FEET)
        c.key('head', t, qeuler(x=max(-10.0, 38.0 - TS_X[t][0]), y=-6.0 + 3.0 * math.sin(a), z=-5.0), ease='linear')
    c.mode('head', 'set')
    seal(c)
    for k in (0.0, 8.0, 14.0, 20.0, 27.0, 33.0):
        check(c, f'right fist on the crank\'s knob (t={k:g})', ta.fk(c, k, 'right_arm', RIGHT_FIST),
              crank_knob(k) + np.array([0.0, 0.0, TS_GRIP_Z]), tol=0.4)
    feet_down(c, (0.0, 13.0, 27.0), TS_FEET)
    self_clear(c, 'arms')
    keepout(c, 'arms clear of the spit', solids('spit'), arm_parts('right_arm', 'left_arm'), tol=0.1)
    keepout(c, 'body, head and legs clear of the spit', solids('spit'),
            [('body', BODY_CORNERS), ('head', HEAD_CORNERS), ('right_leg', LEG_CORNERS), ('left_leg', LEG_CORNERS)], tol=0.1)
    return c


# ================================================================== what the knife clips and the peel share

KNIFE = 'cooks_knife'                           # the prop's part (the name knife is Kestrel's belt knife)
KNIFE_EDGE = np.asarray(lp.KNIFE_EDGE, float)
KNIFE_PTS = [tuple(p) for p in ((0.0, 2.7, 0.9), (0.0, 6.05, 0.9), (0.0, 9.4, 0.9), (0.0, 6.05, -0.5), (0.0, 2.2, 0.0), (0.0, -1.8, 0.0))]
LEFT_HELD = unit(U + F * 0.4)                  # the front of her left arm when it is put to work: up and a little ahead (the near
                                               # side of the arm's box clear of her chest; its far side is where the hand is)


def knife_R(d, down=D):
    """The knife's turn (part space to clip space): its blade (part +y) along [d], its edge (part +z) as far down as that allows."""
    y = unit(d)
    z = unit(np.asarray(down, float) - y * float(np.dot(down, y)))
    return np.column_stack([np.cross(y, z), y, z])


def blend_R(Ra, Rb, w):
    """Two turns (3 x 3) mixed: [w] 0 is [Ra], 1 is [Rb]."""
    if w <= 0.0:
        return Ra
    if w >= 1.0:
        return Rb
    qa, qb = qfrommat(Ra), qfrommat(Rb)
    if float(np.dot(qa, qb)) < 0.0:
        qb = -qb
    return qmat(qslerp(qa, qb, w))


def wish_frame(feet, pitch, yaw, root, dip):
    """The body a clip wishes for at a tick (the solve starts from it and is held near it): the torso bent [pitch] and turned
    [yaw], the hips over [root] (x, z), the knees dipped [dip]."""
    return F_(qt=bend(pitch, yaw, 0.0), vt=(0.0, sink_for(pitch), 0.0), pr=la.root_for(feet, root[0], root[1], dip=dip))


def fist_want(fr_w, side, target, w, lag=1.0):
    """Where a fist is wanted at a tick: where an arm hanging at rest would have it under the wished body [fr_w], eased by
    [w] (0 to 1) to [target]; [lag] above 1 holds it out at its side longer (its sideways way is eased by w to that power),
    so the arm comes forward and then in, and does not cut across her chest."""
    rest = wc.grip_of(side, rest_arm(side, fr_w), fr_w)
    ease = np.array([w ** lag, w, w])
    return rest + (np.asarray(target, float) - rest) * ease


def front_want(design, w):
    """The world direction the front of an arm is turned to: the rest's (ahead), eased by [w] to [design]."""
    return unit(F * (1.0 - w) + np.asarray(design, float) * w)


def hands_res(wants, fronts, boxes, qh, w_fist=5.0, w_head=5.0, w_body=5.0):
    """The residuals of a pose with her fists on the world points [wants] ({side: point}), each arm turned about itself as
    [fronts] ({side: world direction}) has it, and kept out of her own chest and head ([qh]: the head's key) and out of the
    station's [boxes] ((LO, HI) arrays)."""
    LO, HI = boxes

    def res(fr, qa, qb):
        out = []
        for side, q, fist_v in (('right_arm', qa, RIGHT_FIST), ('left_arm', qb, LEFT_FIST)):
            want = wants.get(side)
            if want is None:
                continue
            Ra, ja = arm_world(side, q, **fr)
            if fronts.get(side) is not None:
                Ra = twisted(Ra, fronts[side], fist_v)
            fist = ja + Ra @ fist_v
            out += list((fist - want) * w_fist)
            out += [clear_of_body(fr, fist)]
            P = ja + np.array(ARM_GRID[side]) @ Ra.T
            out += list(torso_depth(fr, P, 0.3) * w_body)
            out += list(head_depth(fr, qh, P, 0.3) * w_head)
            if len(LO):
                out += list(penetration(P, LO, HI, 0.1) * 4.0)
        return out
    return res


def solve_pose(times, feet_at, wish_at, dip_at, res_at, live=0.9, loose=None, tight=None, passes=2, sigma=2.5, calm=None):
    """Solves a clip's body and arms tick by tick for the contacts [res_at](t, wished frame) gives. First each tick on its own
    from the wish [wish_at](t) (pitch, yaw, (x, z) of the hips), the answers free to wander; then the body's course smoothed
    (the ticks within [live] of the ends the rest's exactly) and each tick again close to it, for the contacts, [passes] times
    over, each pass smoothing the last one's answers by [sigma] samples. Returns
    {t: (frame, right key, left key, solution)}. [loose] and [tight]: the first and the second pass's weights, where the clip
    wants them other than they are. [calm]: dict(w=, sigma=): a last pass, where every tick's whole answer (the body and both
    arms) is held near the answers of its neighbours (the series smoothed by [sigma] samples, so a step between two ticks, where
    the solve slipped into another family of answers, is eased into a glide) with weight [w] per radian, the contacts as strong
    as ever; 'also': [(from, to, w, sigma)], a stretch of the clip calmed harder."""
    t_lo, t_hi = times[0] + live, times[-1] - live
    w_a = dict(pitch_w=0.25, yaw_w=0.15, sink_w=1.5, root_w=0.15, roll_w=0.5, arms_w=0.01)
    w_a.update(loose or {})
    w_b = dict(pitch_w=0.8, yaw_w=0.8, roll_w=0.8, sink_w=3.0, root_w=2.0, arms_w=0.01)
    w_b.update(tight or {})
    rough, guess = {}, None
    for t in times:
        pitch, yaw, root = wish_at(t)
        fr_w = wish_frame(feet_at[t][0], pitch, yaw, root, dip_at(t))
        fr, qa, qb, guess = solve_standing(res_at(t, fr_w), feet_at[t][0], pitch, yaw, root, dip=dip_at(t), guess=guess, **w_a)
        rough[t] = np.asarray(guess, float)
    course = smoothed(np.array([rough[t][:6] if t_lo < t < t_hi else np.zeros(6) for t in times]), sigma=sigma)
    guesses = rough
    for k in range(passes):
        sols, first = {}, None
        for i, t in enumerate(times):
            pitch, yaw, root = wish_at(t)
            fr_w = wish_frame(feet_at[t][0], pitch, yaw, root, dip_at(t))
            fr, qa, qb, x = solve_body(res_at(t, fr_w), feet_at[t][0], course[i], dip=dip_at(t), guess=first if t >= t_hi else guesses[t],
                                       **w_b)
            first = x if i == 0 else first
            sols[t] = (fr, qa, qb, x)
        if k < passes - 1:
            course = smoothed(np.array([sols[t][3][:6] if t_lo < t < t_hi else np.zeros(6) for t in times]), sigma=sigma)
            guesses = {t: np.asarray(sols[t][3], float) for t in times}
    if calm:
        X = np.array([np.asarray(sols[t][3], float) for t in times])
        Xs = smoothed(X, sigma=calm.get('sigma', 1.5))
        wts = np.full(len(times), float(calm.get('w', 4.0)))
        for t0, t1, w2, s2 in calm.get('also', ()):                 # (from, to, weight, sigma): a stretch calmed harder
            X2 = smoothed(X, sigma=s2)
            for i, t in enumerate(times):
                a = smooth(t - t0 + 1.0) * smooth(t1 + 1.0 - t)
                Xs[i] = Xs[i] + (X2[i] - Xs[i]) * a
                wts[i] += (w2 - wts[i]) * a
        course = smoothed(np.array([Xs[i][:6] if t_lo < t < t_hi else np.zeros(6) for i, t in enumerate(times)]), sigma=1.0)
        out = {}
        for i, t in enumerate(times):
            pitch, yaw, root = wish_at(t)
            fr_w = wish_frame(feet_at[t][0], pitch, yaw, root, dip_at(t))
            base, target, w_c = res_at(t, fr_w), Xs[i], float(wts[i])

            def res_c(fr, qa, qb, base=base, target=target, w_c=w_c):
                return list(base(fr, qa, qb)) + list((q_rvec(qa) - target[6:9]) * w_c) + list((q_rvec(qb) - target[9:12]) * w_c)
            fr, qa, qb, x = solve_body(res_c, feet_at[t][0], course[i], dip=dip_at(t), guess=Xs[i], **w_b)
            out[t] = (fr, qa, qb, x)
        sols = out
    return sols


def key_arms(c, sols, fronts, sides=('right_arm', 'left_arm')):
    """Keys [sides] from [sols] with each arm turned about itself as [fronts] ({side: fn(t) -> world direction}) has it (the
    fist stays where the solve put it), the sign of the key kept from tick to tick."""
    prev = {}
    for t in sorted(sols):
        fr, qa, qb, _ = sols[t]
        for side, q, fist_v in (('right_arm', qa, RIGHT_FIST), ('left_arm', qb, LEFT_FIST)):
            if side not in sides:
                continue
            Ra, ja = arm_world(side, q, **fr)
            qk = qfrommat(ta.chain_frame(side, **fr)[0].T @ twisted(Ra, fronts[side](t), fist_v))
            if side in prev and float(np.dot(qk, prev[side])) < 0:
                qk = -qk
            prev[side] = qk
            set_arm(c, side, t, (qk, np.zeros(3)))


def gaze_key(fr, look, w):
    """A 'set' head key looking at the world point [look], eased by [w] (0 to 1) from the head straight."""
    return qslerp(I, gaze_head(fr, look), w)


# ================================================================== slice

# ---- slice (40, once): at the Prep Board, the baker's loaf on the board under her left hand, the knife out of her belt and
# over the loaf's right end, two cuts through it with a short saw on each, the cut slices slid apart with the knife's flat, the
# knife put back. She steps up to the counter's edge and bends to it from the hips, the knees giving; the blade lies along her
# line of sight (pointing away, its edge down), the loaf along the board from her right to her left. Needs of the block: the
# board's top (BOARD_TOP_B) and where it lies (BOARD_B), the counter's edge in front of her (clip z -10 + NEAR).
SL_LEN = 40
SL_FEET = {'right_leg': (-2.8, -1.4 + NEAR), 'left_leg': (2.8, -2.6 + NEAR)}    # the left foot a little forward: the body turned a little
SL_STEPS = [(0.4, 3.2, 'left_leg', SL_FEET['left_leg']), (1.4, 4.2, 'right_leg', SL_FEET['right_leg']),
            (33.0, 36.2, 'right_leg', FEET['right_leg']), (34.0, 37.2, 'left_leg', FEET['left_leg'])]
SL_BOARD = 24.0 - BOARD_TOP_B                                   # the chopping board's top
SL_SIZE = 6.0                                                   # the loaf (bread, drawn as the item, a card standing on the board): this big
SL_Z = float(at(PB_FRAME, (0, 0, BOARD_B[1]))[2]) - 3.1         # in from the board's near edge (-12.2 + NEAR): where the cuts and her left hand are
SL_LOAF = np.array([0.0, SL_BOARD - 1.8, SL_Z])                 # the loaf's middle (the card's empty foot sunk a little into the board)
SL_LOAF_TOP = SL_BOARD - 3.3                                    # the bread's top
SL_CUTS = (-2.1, -1.1)                                          # where the two cuts are (x): a slice thick from the loaf's right end
SL_HAND = np.array([2.6, SL_LOAF_TOP - 0.3, SL_Z + 1.0])              # the left fist on the loaf's left part
KNIFE_CUT = np.array([0.0, 5.0, 0.9])                              # where on the edge she cuts: the blade's rear half (the hand well forward of her belly)
SL_SAW = 1.2                                                    # the saw: this far each way along the blade
SL_STRIKES = (12.5, 22.5)                                       # the cuts reach the board: the knife on wood
SL_DIR = unit(F + D * 0.06)                                     # the blade: pointing away, its tip a little down
SL_FRONT_R = unit(U * 0.6 + L * 0.5 + F * 0.2)
c1_, c2_ = SL_CUTS
SL_EDGE = [(4.2, (c1_, SL_LOAF_TOP - 0.9, SL_Z - 0.2)), (5.6, (c1_, SL_LOAF_TOP - 0.5, SL_Z - 0.9)), (8.5, (c1_, 10.8, SL_Z - 0.5)),
           (10.5, (c1_, 12.4, SL_Z - 0.2)), (12.5, (c1_, 13.5, SL_Z)), (14.2, (c1_ + 0.5, 10.0, SL_Z - 0.6)),
           (15.6, (c2_, SL_LOAF_TOP - 0.5, SL_Z - 0.9)), (18.5, (c2_, 10.9, SL_Z - 0.5)), (20.5, (c2_, 12.6, SL_Z - 0.2)),
           (22.5, (c2_, 13.5, SL_Z)), (26.0, (c2_ - 1.2, 13.0, SL_Z)), (28.5, (c2_ - 1.8, 10.2, SL_Z + 0.2))]
SL_P = [(0, 0.0), (2.0, 6.0), (4.5, 14.0), (8.0, 17.0), (12.5, 20.0), (14.2, 17.0), (16.0, 14.0), (20.0, 18.0), (22.5, 20.0),
        (27.0, 17.0), (31.0, 10.0), (35.0, 4.0), (38.0, 1.0), (40, 0.0)]
SL_Y = [(0, 0.0), (3.0, 8.0), (5.5, 18.0), (28.0, 18.0), (33.0, 12.0), (37.0, 3.0), (40, 0.0)]    # the left shoulder leads a little
SL_DIP = [(0, 0.0), (4.0, 1.5), (8.0, 2.2), (12.5, 2.0), (14.2, 1.8), (16.0, 1.5), (20.0, 2.2), (22.5, 2.0), (27.0, 1.6), (31.0, 1.2),
          (38.0, 0.3), (40, 0.0)]
SL_X_SOL = SOLUTIONS.setdefault('slice', {})


def sl_work(t):
    """How far her hands are into the work: the right (the knife's), the left (on the loaf), the knife's own turn."""
    wr = smooth((t - 0.5) / 4.6) * (1.0 - smooth((t - 28.5) / 5.5))
    wl = smooth((t - 0.4) / 3.2) * (1.0 - smooth((t - 30.0) / 5.0))
    wk = smooth((t - 1.0) / 3.4) * (1.0 - smooth((t - 28.0) / 4.0))
    return wr, wl, wk


def sl_edge(t):
    """The knife's cutting edge's middle at [t]: through SL_EDGE, with the saw along the blade."""
    e = la.vec_keys(SL_EDGE, min(max(t, SL_EDGE[0][0]), SL_EDGE[-1][0]), 'spline')
    env = sum(smooth((t - a) / 0.8) * (1.0 - smooth((t - b + 0.8) / 0.8)) for a, b in ((6.2, 12.4), (16.2, 22.4)))
    return e + np.array([0.0, 0.0, SL_SAW * env * math.sin(2.0 * math.pi * (t - 6.0) / 3.2)])


def sl_fist(t):
    """The right fist's place with the knife at work: the cutting point less the knife's own offset."""
    return sl_edge(t) - knife_R(SL_DIR) @ KNIFE_CUT


def sl_left(t):
    """The left fist's place on the loaf: pressing down a little as each cut reaches the board."""
    press = sum(0.22 * bump(t, k - 1.6, k + 1.4) for k in SL_STRIKES)
    return SL_HAND + np.array([0.0, press, 0.0])


def sl_look(t):
    """Where she looks: the loaf, her eyes following the knife part of the way."""
    mid = np.array([-1.0, SL_LOAF_TOP + 0.8, SL_Z])
    return mid + (sl_edge(t) - mid) * 0.55


def slice_():
    c = la.clip('slice', SL_LEN, False, look=('baker', 0), tools_=(KNIFE,), station_='prep_board',
                events=[('slice', k) for k in SL_STRIKES], traces=[(KNIFE, tuple(KNIFE_CUT), (240, 220, 160))],
                views=((40, -14, True), (90, -4), (-50, -10)))
    boxes = solids('prep_board')
    LO = np.array([lo for lo, hi in boxes], float)
    HI = np.array([hi for lo, hi in boxes], float)
    times = la.every(SL_LEN)
    feet_at = {t: stepping(t, FEET, SL_STEPS, lift=1.2) for t in times}

    def wish_at(t):
        f = feet_at[t][0]
        p = K(SL_P, t)
        return p, K(SL_Y, t), (0.5 * (f['right_leg'][0] + f['left_leg'][0]), 0.5 * (f['right_leg'][1] + f['left_leg'][1]))

    def dip_at(t):
        return K(SL_DIP, t)

    def fronts_at(t):
        wr, wl, wk = sl_work(t)
        return {'right_arm': front_want(SL_FRONT_R, wr), 'left_arm': front_want(LEFT_HELD, wl)}

    def head_at_(t, fr):
        return gaze_key(fr, sl_look(t), 0.7 * smooth(t / 4.0) * (1.0 - smooth((t - 29.0) / 5.0)))

    def res_at(t, fr_w):
        wr, wl, wk = sl_work(t)
        wants = {'right_arm': fist_want(fr_w, 'right_arm', sl_fist(t), wr), 'left_arm': fist_want(fr_w, 'left_arm', sl_left(t), wl, lag=3.2)}
        return hands_res(wants, fronts_at(t), (LO, HI), head_at_(t, fr_w), w_head=9.0)
    sols = solve_pose(times, feet_at, wish_at, dip_at, res_at, loose=dict(pitch_w=0.5, yaw_w=0.6, root_w=1.0, roll_w=1.5),
                      tight=dict(pitch_w=0.5, yaw_w=0.6, roll_w=1.2, root_w=1.2))
    for t in times:
        fr, qa, qb, x = sols[t]
        SL_X_SOL[t] = x
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet_at[t][0], lift=feet_at[t][1])
        c.key('head', t, head_at_(t, fr), ease='linear')
    key_arms(c, sols, {side: (lambda t, side=side: fronts_at(t)[side]) for side in ('right_arm', 'left_arm')})
    c.mode('head', 'set')

    def knife_track(t, pivot):
        R_nat = part_M(c, t, 'right_arm')[:3, :3] @ ta.tool_local(KNIFE)[:3, :3]
        return blend_R(R_nat, knife_R(SL_DIR), sl_work(t)[2]), None, None
    la.TRACKS['slice'] = {KNIFE: knife_track}
    ta.INFO['slice']['tools_at'] = tools_when([(KNIFE, 1.2, 33.5)])
    la.set_goods('slice', [(0.0, None, ('ground', tuple(SL_LOAF)), SL_SIZE)])
    la.GOODS_DEFAULT['slice'] = 'bread'
    for k in SL_STRIKES:
        check(c, f'knife\'s edge down to the board, not through it (t={k:g}: its height over the board top)',
              (ta.fk(c, k, KNIFE, KNIFE_CUT)[1] - SL_BOARD,), (-0.2,), tol=0.3)
    for k in (6.0, 10.0, 16.0, 20.0, 26.0):
        check(c, f'knife in the fist (t={k:g})', ta.fk(c, k, KNIFE, (0.0, 0.0, 0.0)), ta.fk(c, k, 'right_arm', RIGHT_FIST), tol=0.05)
        check(c, f'knife\'s edge on its path (t={k:g})', ta.fk(c, k, KNIFE, KNIFE_CUT), sl_edge(k), tol=0.45)
    for k in (6.0, 14.0, 24.0):
        check(c, f'left fist on the loaf (t={k:g})', ta.fk(c, k, 'left_arm', LEFT_FIST), sl_left(k), tol=0.4)
    for k in (6.0, 12.0, 20.0, 26.0):
        feet_down(c, (k,), feet_at[k][0])
    self_clear(c, 'arms', tol=0.2)
    keepout(c, 'knife clear of the counter', solids('prep_board'), [(KNIFE, KNIFE_PTS)], tol=0.25)
    keepout(c, 'arms clear of the counter', solids('prep_board'), arm_parts('right_arm', 'left_arm'), tol=0.1)
    keepout(c, 'body, head and legs clear of the counter', solids('prep_board'),
            [('body', BODY_CORNERS), ('head', HEAD_CORNERS), ('right_leg', LEG_CORNERS), ('left_leg', LEG_CORNERS)], tol=0.1)
    joins_idle(c)
    return c


# ================================================================== carve

# ---- carve (50, once): at the Spit's long side, a slice off the roast: the bowl brought up in the left hand and held under the
# joints, the knife out of her belt and over a joint's far top, laid on it and drawn toward her twice with a little saw (carve,
# tick 19.5: the slice parts), the wrist turned flat under it, the slice lifted clear and carried left over the bowl's rim,
# the blade tipped and the slice slid in (plate, tick 30.0), the knife drawn back, the bowl held out to the side to be taken
# and the hands down. She stands in front of the Spit looking at its long side (frame 'S': the roast is a block ahead and a
# little more, its rod running from her right to her left), bent from the hips with the knees giving: the roast is below her
# hips. The blade lies above the rod (the cut is a thin slice off the joint's top). Needs of the block: the rod's height and
# place (SPIT_AXIS_B), the joints' sizes (JOINT_X_B, the 1.1 apothem), the plinth's front edge; the bowl is the diner's, held
# in her hand.
CV_LEN = 50
CV_FRAME = 'S'
CV_JOINT = 2                                    # the joint she carves: the third from her right
JOINT_C = np.array(at(CV_FRAME, (JOINT_X_B[CV_JOINT], SPIT_AXIS_B[0], SPIT_AXIS_B[1])))       # its middle (the axis there)
JOINT_TOP = float(JOINT_C[1]) - 1.1             # clip y of the joint's top (13.8)
JOINT_NEAR = float(JOINT_C[2]) + 1.1            # of its near face (-14.9 + NEAR)
ROD_TOP = float(JOINT_C[1]) - 0.5               # the rod's top (14.4): the blade stays above it
CV_CX = float(JOINT_C[0]) + 0.1                 # where on the joint she cuts: near its middle
CV_CUT, CV_PLATE = 19.5, 30.0                   # the slice parts; it lands in the bowl
CV_LIFT, CV_REL = 22.6, 28.0                    # the slice rides off the joint on the flat blade; it slides off it
CV_FEET = {'right_leg': (-2.2, -1.2 + NEAR), 'left_leg': (3.0, -3.6 + NEAR)}    # the left foot forward
CV_STEPS = [(0.4, 3.2, 'left_leg', CV_FEET['left_leg']), (1.4, 4.2, 'right_leg', CV_FEET['right_leg']),
            (44.0, 47.2, 'right_leg', FEET['right_leg']), (45.0, 48.2, 'left_leg', FEET['left_leg'])]
CV_SIZE = 2.4                                   # the slice: the item drawn this big
CV_CARD = np.array([0.0, -1.45, 0.7])           # the slice's middle from the cutting point, the blade flat (it stands on its top face)
BOWL_BASE = np.array([0.0, -2.0, 1.0])          # the empty bowl's part space (street_clips' too): its bottom's middle
BOWL_HOLD = np.array([0.0, -3.3, 3.0])          # ... its near rim, where the left fist has it
BOWL_IN = np.array([0.0, -2.9, 1.0])            # ... where the slice lies in it
BOWL_ABOVE = np.array([0.0, -6.1, 1.0])         # ... where it is above it, on the blade, to slide in
CV_DIR = unit(F + D * 0.04)                     # the blade at the cut: pointing away, its edge down
CV_DIR_FLAT = unit(L * 0.92 + F * 0.17 + D * 0.02)       # carried: pointing across to her left, the flat up (its edge away from her)
CV_DIR_TILT = unit(L * 0.88 + F * 0.2 + D * 0.25)
CV_FRONT_R = unit(U * 0.6 + L * 0.5 + F * 0.2)
CV_ARC = 1.0                                    # the slice rises this far above the straight way to the bowl
_x, _top = CV_CX, JOINT_TOP
# (the z of the points below are as for the Spit a block ahead, its rod at z -16; nearer() brings them NEAR px in with it)


def nearer(keys, since=0.0):
    """Keys (t, point) designed for the station a whole cell ahead, from tick [since] on, brought NEAR px nearer with it."""
    return [(k, (p[0], p[1], p[2] + NEAR) if k >= since else p) for k, p in keys]


CV_EDGE_LIFT = np.array([_x - 0.2, _top + 0.25, -15.1 + NEAR])        # the cutting point as the slice is lifted
CV_EDGE = nearer([(4.6, (_x + 0.3, _top - 1.7, -17.4)), (6.6, (_x, _top - 0.15, -17.1)),             # in over the far top, laid on it
           (9.4, (_x, _top + 0.2, -16.0)), (11.2, (_x, _top + 0.35, -15.0)),               # the first draw, toward her
           (12.8, (_x, _top - 0.45, -16.2)), (14.4, (_x, _top - 0.05, -17.0)),             # lifted a hair and back
           (17.4, (_x, _top + 0.35, -15.6)), (CV_CUT - 0.1, (_x, _top + 0.45, -14.8)),     # the second draw: through
           (CV_LIFT, tuple(CV_EDGE_LIFT - np.array([0.0, 0.0, NEAR])))])                   # the wrist turned flat under it
CV_SAW = 0.55
CV_P = [(0, 0.0), (3.0, 20.0), (6.0, 33.0), (10.0, 36.0), (19.0, 36.0), (24.0, 32.0), (30.0, 31.0), (36.0, 29.0), (41.0, 16.0),
        (46.0, 4.0), (50, 0.0)]
CV_Y = [(0, 0.0), (4.0, 6.0), (30.0, 6.0), (38.0, 3.0), (44.0, 1.0), (50, 0.0)]
CV_DIP = [(0, 0.0), (4.0, 3.5), (8.0, 5.0), (30.0, 5.0), (38.0, 3.5), (44.0, 1.0), (50, 0.0)]
# the bowl's bottom's middle: out of the hip at once, up and in front of the roast and held under the joints, lifted a hair to
# the slice, down a hair as it lands, held out to the left to be taken
CV_BOWL_AT, CV_BOWL_GONE = 1.6, 41.0
CV_BOWL = nearer([(CV_BOWL_AT, (5.0, 17.9, -5.5)), (5.0, (4.3, 16.6, -11.0)), (8.0, (3.4, 16.3, -12.4)), (10.5, (3.2, 16.3, -12.4)), (CV_REL, (3.2, 16.3, -12.4)),
           (CV_PLATE, (3.2, 16.2, -12.4)), (31.0, (3.2, 16.5, -12.4)), (32.6, (3.2, 16.2, -12.2)), (34.5, (3.5, 15.9, -11.6)),
           (37.0, (4.0, 15.7, -10.8)), (39.5, (4.4, 15.5, -9.9)), (CV_BOWL_GONE, (4.5, 15.4, -9.6))])
CV_EDGE_AFTER = nearer([(30.4, (_x + 3.0, _top - 1.1, -13.3)), (32.6, (_x + 1.4, _top - 0.9, -14.9)),
                        (34.8, (_x - 0.3, _top - 0.8, -16.2))])
CV_X_SOL = SOLUTIONS.setdefault('carve', {})


def cv_work(t):
    """How far her hands are into the work: the right (the knife's), the left (the bowl's), the knife's own turn."""
    wr = smooth((t - 0.1) / 4.6) * (1.0 - smooth((t - 36.5) / 6.5))
    wl = smooth((t - 0.5) / 3.2) * (1.0 - smooth((t - 38.0) / 7.0))
    wk = smooth((t - 1.0) / 3.4) * (1.0 - smooth((t - 38.0) / 4.5))
    return wr, wl, wk


def path_keys(keys, t):
    """A point along keys (t, point), held at either end."""
    return la.vec_keys(keys, min(max(t, keys[0][0]), keys[-1][0]), 'spline')


def bowl_level(fwd=B):
    """A bowl's turn held level: its -y up, its +z (where its middle sits from the fist) towards [fwd]."""
    return frame_of(D, fwd)


def cv_bowl(t):
    """The bowl's bottom's middle, as designed."""
    return path_keys(CV_BOWL, t)


def cv_bowl_pt(t, p):
    """The bowl-space point [p] in clip space at [t], the bowl as designed (held level)."""
    return cv_bowl(t) + bowl_level() @ (np.asarray(p, float) - BOWL_BASE)


def cv_left(t):
    """The left fist's place with the bowl: its near rim, under the bowl's middle as designed."""
    return cv_bowl(t) - bowl_level() @ (BOWL_BASE - BOWL_HOLD)


def cv_start():
    """Where the slice lies when it parts: on the joint's top, over the blade as it is turned flat under it."""
    return CV_EDGE_LIFT + CV_CARD


def cv_carry(t):
    """The slice's middle as it is lifted (CV_LIFT) and carried over the bowl (CV_REL): the way the renderer takes a tossed
    thing, straight from where it lay to the point above the bowl, rising a little on the way."""
    u = min(max((t - CV_LIFT) / (CV_REL - CV_LIFT), 0.0), 1.0)
    s = cv_start()
    e = cv_bowl_pt(t, BOWL_ABOVE)
    return s + (e - s) * u + np.array([0.0, -4.0 * CV_ARC * u * (1.0 - u), 0.0])


def cv_edge(t):
    """The knife's cutting point's middle at [t]: through CV_EDGE with a little saw along the blade in the draws; under the
    slice as it is carried (CV_LIFT to CV_REL); and drawn up and back after it."""
    if t <= CV_LIFT:
        e = la.vec_keys(CV_EDGE, min(max(t, CV_EDGE[0][0]), CV_EDGE[-1][0]), 'spline')
        env = sum(smooth((t - a) / 0.8) * (1.0 - smooth((t - b + 0.8) / 0.8)) for a, b in ((7.0, 11.2), (14.8, 19.2)))
        return e + np.array([0.0, 0.0, CV_SAW * env * math.sin(2.0 * math.pi * (t - 7.0) / 2.4)])
    if t <= CV_REL:
        return cv_carry(t) - CV_CARD
    keys = [(CV_REL, tuple(cv_carry(CV_REL) - CV_CARD))] + CV_EDGE_AFTER
    return la.vec_keys(keys, min(t, keys[-1][0]), 'spline')


def cv_orient(t):
    """The knife's turn at [t] as designed: edge down at the cut, rolled flat and carried, tipped to slide, back."""
    cut = knife_R(CV_DIR, D)
    flat = knife_R(CV_DIR_FLAT, F)
    tilt = knife_R(CV_DIR_TILT, unit(F + U * 0.9))
    keys = [(4.0, cut), (CV_CUT - 0.3, cut), (CV_LIFT, flat), (CV_REL - 0.6, flat), (CV_PLATE - 0.4, tilt), (31.4, tilt), (34.4, cut)]
    if t <= keys[0][0]:
        return keys[0][1]
    for (ta_, Ra), (tb_, Rb) in zip(keys, keys[1:]):
        if t < tb_:
            return blend_R(Ra, Rb, smooth((t - ta_) / (tb_ - ta_)))
    return keys[-1][1]


def cv_fist(t):
    """The right fist's place with the knife at work: the cutting point less the knife's own offset."""
    return cv_edge(t) - cv_orient(t) @ KNIFE_CUT


def cv_look(t):
    keys = [(0.0, (0.0, 4.0, -40.0))] + nearer([(2.5, (CV_CX, JOINT_TOP - 0.5, -14.0)), (5.0, (CV_CX, JOINT_TOP, -16.0)),
            (CV_LIFT, (CV_CX, JOINT_TOP, -15.6)), (26.0, (CV_CX + 1.8, JOINT_TOP + 1.0, -13.6)), (CV_PLATE, (3.2, 15.0, -12.6)),
            (36.0, (3.4, 15.0, -12.2))]) + [(42.0, (0.0, 6.0, -40.0)), (50, (0.0, 6.0, -40.0))]
    return path_keys(keys, t)


def arms_apart(c, label, times=None, tol=0.15):
    """Checks that her two arms keep out of each other: the deepest a point of the left arm goes into the right arm's box
    (in its own space) at any of [times] (every tick by default), printed as one check. Returns that depth."""
    worst, where = 0.0, 0.0
    box = ((-3.0, -2.0, -2.0), (1.0, 10.0, 2.0))
    for t in (times if times is not None else np.arange(0.0, c.length + 0.01, 1.0)):
        Ml = wc.part_M(c, t, 'left_arm')
        pts = np.array([Ml @ np.append(p, 1.0) for p in ARM_GRID['left_arm']])[:, :3]
        Mi = np.linalg.inv(wc.part_M(c, t, 'right_arm'))
        for p in pts:
            d = depth_in((Mi @ np.append(p, 1.0))[:3], box)
            if d > worst:
                worst, where = d, t
    check(c, f'{label}: deepest one arm goes into the other{" (t=" + format(where, "g") + ")" if worst > 0 else ""}', (worst,), (0.0,), tol=tol)
    return worst


def carve():
    c = la.clip('carve', CV_LEN, False, look=('innkeeper', 0), tools_=(KNIFE, 'bowl_empty'), station_='spit_front',
                events=[('carve', CV_CUT), ('plate', CV_PLATE)], traces=[(KNIFE, tuple(KNIFE_CUT), (240, 220, 160))],
                views=((40, -14, True), (90, -4), (-50, -10)))
    boxes = solids('spit_front')
    LO = np.array([lo for lo, hi in boxes], float)
    HI = np.array([hi for lo, hi in boxes], float)
    times = la.every(CV_LEN)
    feet_at = {t: stepping(t, FEET, CV_STEPS, lift=1.2) for t in times}

    def wish_at(t):
        f = feet_at[t][0]
        return K(CV_P, t), K(CV_Y, t), (0.5 * (f['right_leg'][0] + f['left_leg'][0]), 0.5 * (f['right_leg'][1] + f['left_leg'][1]))

    def dip_at(t):
        return K(CV_DIP, t)

    def fronts_at(t):
        wr, wl, wk = cv_work(t)
        return {'right_arm': front_want(CV_FRONT_R, wr), 'left_arm': front_want(LEFT_HELD, wl)}

    def head_at_(t, fr):
        return gaze_key(fr, cv_look(t), (0.7 - 0.3 * bump(t, 30.0, 40.0)) * smooth(t / 5.0) * (1.0 - smooth((t - 42.0) / 5.0)))   # (her eyes, not her chin, go to the bowl as the knife comes back past it)

    def res_at(t, fr_w):
        wr, wl, wk = cv_work(t)
        wants = {'right_arm': fist_want(fr_w, 'right_arm', cv_fist(t), wr),
                 'left_arm': fist_want(fr_w, 'left_arm', cv_left(t), wl, lag=3.2)}
        return hands_res(wants, fronts_at(t), (LO, HI), head_at_(t, fr_w), w_head=9.0 + 8.0 * bump(t, 28.0, 41.0), w_body=14.0)
    sols = solve_pose(times, feet_at, wish_at, dip_at, res_at, loose=dict(pitch_w=0.5, yaw_w=0.6, root_w=1.0, roll_w=1.5),
                      tight=dict(pitch_w=0.5, yaw_w=0.6, roll_w=1.2, root_w=3.0), passes=3, sigma=3.0)
    for t in times:
        fr, qa, qb, x = sols[t]
        CV_X_SOL[t] = x
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet_at[t][0], lift=feet_at[t][1])
        c.key('head', t, head_at_(t, fr), ease='linear')
    key_arms(c, sols, {side: (lambda t, side=side: fronts_at(t)[side]) for side in ('right_arm', 'left_arm')})
    c.mode('head', 'set')

    def knife_track(t, pivot):
        R_nat = part_M(c, t, 'right_arm')[:3, :3] @ ta.tool_local(KNIFE)[:3, :3]
        return blend_R(R_nat, cv_orient(t), cv_work(t)[2]), None, None

    def bowl_track(t, pivot):
        return bowl_level(), apply(part_M(c, t, 'left_arm'), LEFT_FIST) - bowl_level() @ BOWL_HOLD, None
    la.TRACKS['carve'] = {KNIFE: knife_track, 'bowl_empty': bowl_track}
    ta.INFO['carve']['tools_at'] = tools_when([(KNIFE, 1.2, 44.5), ('bowl_empty', CV_BOWL_AT, CV_BOWL_GONE)])
    rel = tuple(cv_carry(CV_REL))
    la.set_goods('carve', [(CV_CUT, CV_LIFT, ('ground', tuple(cv_start())), CV_SIZE),
                           (CV_LIFT, CV_REL, ('toss', tuple(cv_start()), 'bowl_empty', tuple(BOWL_ABOVE), CV_ARC), CV_SIZE),
                           (CV_REL, CV_PLATE, ('toss', rel, 'bowl_empty', tuple(BOWL_IN), 0.4), CV_SIZE),
                           (CV_PLATE, CV_BOWL_GONE, ('part', 'bowl_empty', tuple(BOWL_IN)), CV_SIZE)])
    la.GOODS_DEFAULT['carve'] = 'cooked_beef'
    for k in (8.0, 14.0, 24.0, 26.5, 29.0, 33.0):
        check(c, f'knife in the fist (t={k:g})', ta.fk(c, k, KNIFE, (0.0, 0.0, 0.0)), ta.fk(c, k, 'right_arm', RIGHT_FIST), tol=0.05)
    for k in (10.0, 16.0, 19.0, 24.0, 26.5, 29.0, 33.0):
        check(c, f'knife\'s edge on its path (t={k:g})', ta.fk(c, k, KNIFE, KNIFE_CUT), cv_edge(k), tol=0.5)
    for k in (5.0, 10.0, 20.0, 28.0, 31.0):
        check(c, f'left fist on the bowl\'s rim (t={k:g})', ta.fk(c, k, 'left_arm', LEFT_FIST), cv_left(k), tol=0.4)
    for k in (6.0, 12.0, 20.0, 28.0, 34.0):
        feet_down(c, (k,), feet_at[k][0])
    self_clear(c, 'arms', tol=0.25)
    arms_apart(c, 'arms')
    keepout(c, 'knife clear of the Spit', solids('spit_front'), [(KNIFE, KNIFE_PTS)], tol=0.25)
    keepout(c, 'arms clear of the Spit', solids('spit_front'), arm_parts('right_arm', 'left_arm'), tol=0.1)
    keepout(c, 'body, head and legs clear of the Spit', solids('spit_front'),
            [('body', BODY_CORNERS), ('head', HEAD_CORNERS), ('right_leg', LEG_CORNERS), ('left_leg', LEG_CORNERS)], tol=0.1)
    joins_idle(c)
    return c


# ================================================================== peel

# ---- peel (50, once): the baker at the Bake oven. The door swings open (door, tick 1: the client drives it; the previews draw
# it opening over ticks 0 to 5 and shutting over 46 to 50, so the leaf is wide before the blade comes near it) and she steps back from
# the heat and takes the long peel (it is in her right fist from tick 1.6), brings its blade up to the mouth and down to the
# sill (peel, tick 8: its tip meets the sill), steps in with the left foot and slides it in along the sill and the tray's top
# to the back, works it a hair under the loaves and lifts, draws it out with them (peel, tick 36: the blade clears the sill),
# swings it out to her left and holds it up in front of her in both hands (41.5), and lets go (down, tick 43: the peel and the
# loaves gone; the counter or the next step takes the loaves) and steps in to where she began. The moments in that order (door,
# peel, peel, down) are what Part I3's TownKitchen plays: the oven's door at the first, the loaves set down at the last. The peel is a pole of 15 beyond
# the grip's origin and a blade of 6 by 5, and the mouth is only 6.4 wide (and its leaf, open, stands out to the right of it),
# so the blade goes in square, flat, where it is designed to go (its middle is the clip's design, tick by tick, and the
# pole's lean to either side is limited to what the mouth allows); her hands take the pole wherever her arms reach it and
# slide along it as it goes in (so the pole is drawn through her fists, the right hand at the butt when it is deepest); she
# stands side-on to the oven (the hips 6 to 8 px to the left of the cell's middle and 2 to 6 px back from it, so 4 to 8 back
# from where Part I2 stands her, bent 21 to 27 degrees forward and turned 50 to 64, which the guide tells the scene) so that
# the arms, which are straight and 10 long, reach the pole.
# Needs of the block: the mouth's width and height (OV_OPEN_B), the sill's top, where the front stands (OV_FRONT_B), the
# chamber's back (OV_CAVITY_Z_B), the door's hinge side and how far it swings (OV_HINGE_B, OV_DOOR_MAX) and when
# (OV_DOOR_OPENS, OV_DOOR_SHUTS).
PL_LEN = 50
PEEL_REACH = 15.0                               # the peel's part space: the pole's origin to the blade's middle (part +y)
PEEL_BLADE_MID = np.array([0.0, 15.0, 0.0])
PEEL_LOAVES = np.array([0.0, 15.0, 0.4])        # where the loaves lie on the blade (on its upper face, part +z)
PEEL_BUTT = -3.0                                # the pole's end behind the origin
# what of the peel must stay out of the oven: the blade's outline, the pole
PEEL_DENSE = np.array([(x, y, z) for x in (-2.5, -1.25, 0.0, 1.25, 2.5) for y in (12.0, 18.0) for z in (-0.25, 0.25)] +
                      [(x, y, z) for x in (-2.5, 2.5) for y in (13.5, 15.0, 16.5) for z in (-0.25, 0.25)] +
                      [(0.0, y, 0.0) for y in (-3.0, 0.0, 3.0, 6.0, 9.0, 12.0)], float)
# the loaves as the game draws them (the item, 5 across, lying flat on the blade's upper face): the outline and the middle
PEEL_LOAF = np.array([(x, y, 0.4) for x in (-2.5, 0.0, 2.5) for y in (12.5, 15.0, 17.5)], float)
PL_FRONT = at(OV_FRAME, (8.0, 0.0, OV_FRONT_B))[2]                 # the oven's front face (-9.2 + NEAR)
PL_SILL = 24.0 - OV_OPEN_B[1]                   # the sill's top (15.2)
PL_BACK = at(OV_FRAME, (8.0, 0.0, OV_CAVITY_Z_B[1]))[2]            # the chamber's back wall (-16.8 + NEAR)
PL_X, PL_XD = 0.33, 0.1                         # the blade's middle goes in at this x (the mouth is -3.2 to 3.2, the open door's inner
                                                # face at -2.54, the blade 5 wide: so 0.16 to 0.5 while its rear is outside), then at the other
PL_Y = PL_SILL - 0.75                           # its height over the sill
PL_DEEP = PL_BACK + 3.0 + 0.4                   # the blade's middle at the deepest: its far end 0.4 short of the back (-13.4 + NEAR)
PL_FIRST, PL_OUT = 8.0, 36.0                    # the blade's tip meets the sill going in; the blade clears the sill coming out
PL_APPEAR, PL_GONE = 1.6, 43.0                  # the peel is in her hand from, and gone at
PL_DOOR = 1.0                                   # the door is swinging open
PL_MOUTH = np.array(at(OV_FRAME, (8.0, 10.6, OV_FRONT_B)))         # the middle of the oven's mouth, in its front face
PL_L = (6.6, 14.6)                              # how far behind the blade's middle her right fist may take the pole: least, most
PL_GAP = (2.6, 7.0, 4.2)                        # how far ahead of the right fist the left takes it: least, most, preferred
PL_START_DIR = unit(np.array([0.0, -0.45, -0.9]))                  # as it appears: her fist at the rest's, the blade ahead and up ...
PL_START_N = 9.0                                # ... this far from the fist
# where the blade's middle goes: raised and brought up to the mouth, lowered to the sill, slid in along it and the tray, worked
# a hair under the loaves and lifted, drawn out, swung out to her left, held up in front of her, lowered a little
PL_B = [(3.6, (0.5, 7.8, -7.4 + NEAR)), (4.4, (0.47, 9.3, PL_FRONT + 3.1)), (5.2, (0.43, 11.3, PL_FRONT + 3.2)), (6.0, (0.4, 13.0, PL_FRONT + 3.2)),
        (PL_FIRST, (PL_X, PL_Y - 0.15, PL_FRONT + 2.9)),
        (9.5, (PL_X, PL_Y - 0.1, PL_FRONT - 0.5)), (12.0, (PL_X, PL_Y, PL_FRONT - 2.4)), (14.5, (PL_X - 0.15, PL_Y + 0.1, PL_FRONT - 4.0)),
        (16.8, (PL_XD, PL_Y + 0.1, PL_DEEP)), (18.6, (PL_XD, PL_Y + 0.1, PL_DEEP + 0.9)), (20.4, (PL_XD, PL_Y + 0.1, PL_DEEP + 0.1)),
        (22.6, (PL_XD, PL_Y - 0.25, PL_DEEP + 0.3)), (26.0, (PL_XD, PL_Y - 0.35, PL_DEEP + 0.5)),
        (29.0, (PL_X, PL_Y - 0.4, PL_FRONT - 2.0)), (31.0, (PL_X, PL_Y - 0.45, PL_FRONT - 0.3)), (PL_OUT, (PL_X, PL_Y - 0.8, PL_FRONT + 3.3)),
        (39.0, (1.3, PL_Y - 2.2, PL_FRONT + 4.6)), (41.5, (1.8, PL_Y - 3.4, PL_FRONT + 6.0))]
# the most the pole may lean to either side (the x of its direction): the blade is 5 wide, the mouth 6.4 and the door's leaf
# leaves 5.74 to its corners, so it goes in nearly square (7 degrees) while its rear is outside the face, a little more (11) in
# the chamber; free of all that while it is away from the oven
PL_LIM = [(0, 0.7), (4.4, 0.7), (6.2, 0.13), (13.0, 0.13), (15.0, 0.2), (27.0, 0.2), (29.0, 0.12), (35.0, 0.12), (37.5, 0.5), (39.0, 0.7), (50, 0.7)]
PL_LEAN_W = 30.0
# the pole's wished slope (degrees down toward the blade; the blade is flat when it is 0) and where on it her right hand likes to be
PL_SLOPE = [(0, -26.0), (3.6, -33.0), (6.0, -10.0), (PL_FIRST, 3.0), (12.0, 4.0), (26.0, 4.0), (PL_OUT, 3.0), (39.0, -2.0), (41.5, -8.0),
            (50, 0.0)]
PL_LPREF = [(0, 9.0), (PL_APPEAR, 9.0), (6.0, 7.6), (PL_FIRST, 7.4), (12.0, 10.5), (16.8, 12.2), (24.0, 12.0), (31.0, 9.5), (PL_OUT, 7.8),
            (41.5, 7.6), (50, 9.0)]
# her stance: the feet, then the lean, turn, knees and hips she wishes (the solve keeps near them)
PL_READY_F = {'right_leg': (5.0, 9.0 + NEAR), 'left_leg': (7.2, 5.2 + NEAR)}                                   # side-on to the oven, 5 to 7 px left of the cell's middle
PL_LUNGE_F = {'right_leg': (5.0, 9.0 + NEAR), 'left_leg': (6.4, 2.4 + NEAR)}                                   # the left foot forward
PL_STEPS = [(0.4, 2.6, 'left_leg', (4.4, 2.6 + NEAR)), (1.2, 3.4, 'right_leg', (2.2, 5.8 + NEAR)),
            (2.8, 5.2, 'left_leg', PL_READY_F['left_leg']), (3.6, 6.0, 'right_leg', PL_READY_F['right_leg']),
            (9.5, 12.5, 'left_leg', PL_LUNGE_F['left_leg']), (24.0, 27.0, 'left_leg', PL_READY_F['left_leg']),
            (41.0, 44.0, 'right_leg', (2.0, 5.0 + NEAR)), (42.0, 45.0, 'left_leg', (4.4, 2.6 + NEAR)),
            (44.5, 47.5, 'right_leg', FEET['right_leg']), (45.5, 48.5, 'left_leg', FEET['left_leg'])]
PL_P = [(0, 0.0), (3.0, 10.0), (6.0, 19.0), (9.0, 21.0), (18.0, 21.0), (26.0, 20.0), (34.0, 19.0), (40.0, 15.0), (46.0, 6.0), (50, 0.0)]
PL_YAW = [(0, 0.0), (3.0, 22.0), (6.0, 48.0), (9.0, 58.0), (26.0, 57.0), (34.0, 52.0), (40.0, 38.0), (46.0, 10.0), (50, 0.0)]
PL_DIP = [(0, 0.0), (3.0, 1.5), (7.0, 3.0), (12.0, 4.0), (18.0, 4.5), (26.0, 4.0), (34.0, 3.0), (41.0, 2.0), (46.0, 1.0), (50, 0.0)]
PL_ROOT_Z = [(0, 0.0)] + [(k, z + NEAR) for k, z in ((3.0, 3.0), (6.0, 6.5), (9.0, 7.0), (12.0, 6.0), (17.0, 5.8), (24.0, 6.0), (30.0, 6.5),
                                                    (36.0, 6.8), (42.0, 5.5), (46.0, 3.0))] + [(50, 0.0)]
PL_FRONT_R = unit(L + D * 0.1)                          # the fronts of her arms: toward the middle (the pole's side), the arm hanging
PL_FRONT_L = unit(R + D * 0.1)                          # steeply, so the front is never along the arm
PL_X_SOL = SOLUTIONS.setdefault('peel', {})
PL_START_B = []                                         # the blade as it appears (set when the clip is built)
PL_BOXES = [None]


def pl_blade(t):
    """The blade's middle at tick [t]: through PL_B, from where it appears, and held where it ends."""
    keys = [(PL_APPEAR, tuple(PL_START_B[0]))] + PL_B if PL_START_B else PL_B
    tt = min(max(t, keys[0][0]), keys[-1][0])
    return la.vec_keys(keys, tt, 'spline')


def pl_dir0(t):
    """The pole's wished direction (slope from PL_SLOPE, dead ahead)."""
    a = math.radians(K(PL_SLOPE, t))
    return np.array([0.0, math.sin(a), -math.cos(a)])


def pl_lim(t):
    """The most the pole may lean to either side at tick [t]."""
    return K(PL_LIM, t)


def peel_R(d):
    """The peel's turn (part space to clip space): its pole (part +y) along [d], the blade's flat face (part +z) up."""
    return frame_of(d, U)


def pl_work(t):
    """How far her hands are into the work: the right (the pole's), the left."""
    wr = smooth((t - 0.2) / 6.3) * (1.0 - smooth((t - 40.0) / 7.5))
    wl = smooth((t - 1.0) / 6.2) * (1.0 - smooth((t - 39.0) / 7.0))
    return wr, wl


def pl_pole(fist, t):
    """(the pole's direction, how far up it [fist] has it: part y, the blade's middle) with the blade where it is designed to
    be and the pole running from [fist] to it, her hand slid to where that is."""
    B = pl_blade(t)
    u = B - fist
    n = float(np.linalg.norm(u))
    d = u / max(n, 1e-6)
    return d, PEEL_REACH - min(max(n, PL_L[0]), PL_L[1]), B


def pl_look(t):
    w = smooth((t - 1.5) / 2.5) * (1.0 - smooth((t - 41.0) / 5.0))
    ahead = np.array([0.0, 4.0, -40.0])
    return ahead + (pl_blade(t) + np.array([0.0, -1.2, 0.0]) - ahead) * w


def pl_feet(t):
    return stepping(t, FEET, PL_STEPS, lift=1.2)


def pl_fronts(t):
    wr, wl = pl_work(t)
    return {'right_arm': front_want(PL_FRONT_R, wr), 'left_arm': front_want(PL_FRONT_L, wl)}


def pl_head(t, fr):
    return gaze_key(fr, pl_look(t), 0.7 * smooth(t / 5.0) * (1.0 - smooth((t - 42.0) / 5.0)))


def pl_boxes(t):
    if PL_BOXES[0] is None:
        PL_BOXES[0] = solids('bake_oven', moving=True)
    bx_ = PL_BOXES[0](t)
    return np.array([lo for lo, hi in bx_], float), np.array([hi for lo, hi in bx_], float)


def head_world(fr, qh, pts):
    """Points of the head's space (its neck the origin) in the world, for the head keyed [qh] (a 'set' key) under [fr]."""
    qr, qt = fr['qr'], fr['qt']
    pivot = HIPS + qmat(qr) @ (HIPS + qmat(qt) @ (np.array(ta.PIVOTS['head'], float) - HIPS) + fr['vt'] - HIPS) + fr['pr']
    return pivot + np.asarray(pts, float) @ qmat(qmul(qmul(qr, qt), qh)).T


def torso_world(fr, pts):
    """Points of the torso's space in the world under [fr]."""
    Rr, Rt = qmat(fr['qr']), qmat(fr['qt'])
    return ((np.asarray(pts, float) - HIPS) @ Rt.T + fr['vt']) @ Rr.T + HIPS + fr['pr']


BODY_PTS = np.array([(x, y, z) for x in (-4.0, 0.0, 4.0) for y in (0.0, 6.0, 12.0) for z in (-2.0, 2.0)], float)
HEAD_PTS = np.array([(x, y, z) for x in (-4.0, 0.0, 4.0) for y in (-8.0, -4.0, 0.0) for z in (-4.0, 0.0, 4.0)], float)


def peel_res(t, fr_w, boxes, qh, fronts, lean_w=None):
    """The residuals of a pose with the peel's blade where it is designed to be: her right fist on the pole between PL_L behind
    the blade's middle (near the length she likes, the pole near its wished slope, never leaning more than pl_lim, so the blade
    goes square through the mouth), her left fist on the pole a little ahead of it, the tail of the pole behind her right fist
    and her arms out of her own body and head, the peel, her arms, head and body clear of the oven and the open door."""
    LO, HI = boxes
    wr, wl = pl_work(t)
    B = pl_blade(t)
    d0 = pl_dir0(t)
    lim = pl_lim(t)
    lpref = K(PL_LPREF, t)
    lean_w = PL_LEAN_W if lean_w is None else lean_w
    rest = {s: wc.grip_of(s, rest_arm(s, fr_w), fr_w) for s in ('right_arm', 'left_arm')}
    near = min(1.0, wr * 1.5)

    def res(fr, qa, qb):
        fists, P = {}, {}
        for side, q, fist_v in (('right_arm', qa, RIGHT_FIST), ('left_arm', qb, LEFT_FIST)):
            Ra, ja = arm_world(side, q, **fr)
            Ra = twisted(Ra, fronts[side], fist_v)
            fists[side] = ja + Ra @ fist_v
            P[side] = ja + np.array(ARM_GRID[side]) @ Ra.T
            if side == 'right_arm':
                Ra_r, ja_r = Ra, ja
        fr_, fl_ = fists['right_arm'], fists['left_arm']
        out = []
        out += list((fr_ - rest['right_arm']) * 5.0 * (1.0 - wr))
        out += list((fl_ - rest['left_arm']) * 5.0 * (1.0 - wl))
        u = B - fr_
        n = max(float(np.linalg.norm(u)), 1e-3)
        d = u / n
        out.append((max(0.0, n - PL_L[1]) + max(0.0, PL_L[0] - n)) * 4.0 * wr)
        out.append((n - lpref) * 0.15 * wr)
        out += list((d - d0) * np.array([0.8, 2.0, 0.4]) * wr)
        out.append(max(0.0, abs(float(d[0])) - lim) * lean_w * wr)
        s_raw = float(np.dot(fl_ - fr_, d))
        s = min(max(s_raw, PL_GAP[0]), max(PL_GAP[0], min(PL_GAP[1], n - 3.0)))
        out += list((fl_ - (fr_ + s * d)) * 5.0 * wl)
        out.append((s_raw - PL_GAP[2]) * 0.1 * wl)
        for side in ('right_arm', 'left_arm'):
            out += [clear_of_body(fr, fists[side])]
            out += list(torso_depth(fr, P[side], 0.3) * 8.0)
            out += list(head_depth(fr, qh, P[side], 0.3) * 9.0)
            if len(LO):
                out += list(penetration(P[side], LO, HI, 0.25) * 4.0)
        out += list(arm_in_arm(P['left_arm'], Ra_r, ja_r, 0.3) * 6.0)
        # the pole behind her right fist (to its butt) clear of her body and head, and the peel clear of the oven and the door
        g = PEEL_REACH - min(max(n, PL_L[0]), PL_L[1])
        back = (1.5, 3.5, 5.5, 7.5, 9.5, 11.5)
        tail = np.array([fr_ - (s_ * d) for s_ in back])
        ind = np.array([min(1.0, max(0.0, (g - PEEL_BUTT - s_) / 1.5 + 0.5)) for s_ in back])
        out += list(torso_depth(fr, tail, 0.2) * ind * 5.0 * near)
        out += list(head_depth(fr, qh, tail, 0.2) * ind * 9.0 * near)
        if len(LO):
            Rp = peel_R(d)
            pts = B + (PEEL_DENSE - PEEL_BLADE_MID) @ Rp.T
            out += list(penetration(pts, LO, HI, 0.05) * 6.0 * near)
            out += list(penetration(torso_world(fr, BODY_PTS), LO, HI, 0.15) * 4.0)
            out += list(penetration(head_world(fr, qh, HEAD_PTS), LO, HI, 0.15) * 4.0)
        return out
    return res


def arm_in_arm(P, Ra, ja, grow=0.0):
    """How deep each world point of [P] (M x 3) is inside her right arm's box (the arm turned [Ra], its shoulder at [ja]), grown by
    [grow]: 0 for a point outside it."""
    lo, hi = np.array([-3.0, -2.0, -2.0]) - grow, np.array([1.0, 10.0, 2.0]) + grow
    local = (np.asarray(P, float) - ja) @ Ra
    return np.maximum(np.minimum(local - lo, hi - local).min(axis=1), 0.0)


def pl_start_blade():
    """Where the blade is as the peel appears: ahead of and above the fist the arm at rest has."""
    return wc.grip_of('right_arm', rest_arm('right_arm', wc.REST), wc.REST) + PL_START_N * PL_START_DIR


def pl_res_at(t, fr_w):
    return peel_res(t, fr_w, pl_boxes(t), pl_head(t, fr_w), pl_fronts(t))


def peel_():
    c = la.clip('peel', PL_LEN, False, look=('baker', 0), tools_=('peel',), station_='bake_oven',
                events=[('door', PL_DOOR), ('peel', PL_FIRST), ('peel', PL_OUT), ('down', PL_GONE)], traces=[('peel', tuple(PEEL_BLADE_MID), (240, 220, 160))],
                views=((40, -14, True), (90, -4), (-50, -10)))
    PL_START_B[:] = [pl_start_blade()]
    times = la.every(PL_LEN)
    feet_at = {t: pl_feet(t) for t in times}

    def wish_at(t):
        f = feet_at[t][0]
        return K(PL_P, t), K(PL_YAW, t), (0.5 * (f['right_leg'][0] + f['left_leg'][0]), K(PL_ROOT_Z, t))

    def dip_at(t):
        return K(PL_DIP, t)
    sols = solve_pose(times, feet_at, wish_at, dip_at, pl_res_at, loose=dict(pitch_w=0.5, yaw_w=0.6, root_w=1.0, roll_w=1.5),
                      tight=dict(pitch_w=0.5, yaw_w=0.6, roll_w=1.2, root_w=3.0), passes=3, sigma=3.0,
                      calm=dict(w=4.0, sigma=1.5, also=[(2.5, 6.0, 14.0, 3.0), (40.5, 46.0, 14.0, 3.0)]))
    for t in times:
        fr, qa, qb, x = sols[t]
        PL_X_SOL[t] = x
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet_at[t][0], lift=feet_at[t][1])
        c.key('head', t, pl_head(t, fr), ease='linear')
    key_arms(c, sols, {side: (lambda t, side=side: pl_fronts(t)[side]) for side in ('right_arm', 'left_arm')})
    c.mode('head', 'set')

    def fist_of(t):
        return apply(part_M(c, t, 'right_arm'), RIGHT_FIST)

    def peel_track(t, pivot):
        d, g, B = pl_pole(fist_of(t), t)
        Rp = peel_R(d)
        return Rp, fist_of(t) - Rp @ np.array([0.0, g, 0.0]), None
    la.TRACKS['peel'] = {'peel': peel_track}
    ta.INFO['peel']['tools_at'] = tools_when([('peel', PL_APPEAR, PL_GONE)])
    la.set_goods('peel', [(22.0, PL_GONE, ('part', 'peel', tuple(PEEL_LOAVES)), 5.0)])
    la.GOODS_DEFAULT['peel'] = 'bread'
    for k in (PL_FIRST, 11.0, 14.0, 17.0, 26.0, 31.0, PL_OUT, 41.0):
        check(c, f'peel\'s blade where it should be (t={k:g})', ta.fk(c, k, 'peel', PEEL_BLADE_MID), pl_blade(k), tol=0.15)
    for k in (6.0, 12.0, 17.0, 24.0, 30.0, 36.0, 40.0):
        fist = fist_of(k)
        d, g, B = pl_pole(fist, k)
        check(c, f'peel in the right fist (t={k:g}, her hand {g:.1f} px up the pole)', ta.fk(c, k, 'peel', (0.0, g, 0.0)), fist, tol=0.05)
        left = ta.fk(c, k, 'left_arm', LEFT_FIST)
        s = float(np.clip(np.dot(left - fist, d), PL_GAP[0], PL_GAP[1]))
        check(c, f'left fist on the pole (t={k:g})', left, fist + s * d, tol=0.7)
        check(c, f'pole leaning no more than the mouth allows (t={k:g})', (min(abs(float(d[0])), pl_lim(k)),), (abs(float(d[0])),), tol=0.04)
    for k in (6.0, 13.0, 20.0, 30.0, 40.0):
        feet_down(c, (k,), feet_at[k][0])
    self_clear(c, 'arms', tol=0.4)              # (her left forearm brushes her chest by a third of a px as it comes across to the pole)
    pole_clear(c, 'pole', fist_of, pl_pole, times=np.arange(PL_APPEAR, PL_GONE + 0.01, 0.5))
    arms_apart(c, 'arms')
    keepout(c, 'peel clear of the oven and its door', pl_boxes_pairs, [('peel', PEEL_DENSE)], times=np.arange(PL_APPEAR, PL_GONE + 0.01, 0.5), tol=0.15)
    keepout(c, 'loaves clear of the oven and its door', pl_boxes_pairs, [('peel', PEEL_LOAF)], times=np.arange(22.0, PL_GONE + 0.01, 0.5), tol=0.1)
    keepout(c, 'arms clear of the oven and its door', pl_boxes_pairs, arm_parts('right_arm', 'left_arm'), tol=0.1)
    keepout(c, 'body, head and legs clear of the oven and its door', pl_boxes_pairs,
            [('body', BODY_CORNERS), ('head', HEAD_CORNERS), ('right_leg', LEG_CORNERS), ('left_leg', LEG_CORNERS)], tol=0.1)
    joins_idle(c)
    return c


def pl_boxes_pairs(t):
    LO, HI = pl_boxes(t)
    return list(zip(LO, HI))


MAKERS = {'stir': stir, 'taste': taste, 'flip': flip, 'turn_spit': turn_spit, 'slice': slice_, 'carve': carve, 'peel': peel_}
ORDER = ['stir', 'taste', 'flip', 'turn_spit', 'slice', 'carve', 'peel']
COOK_CLIPS = list(ORDER)


def cook_moment_specs():
    """Where the kitchen clips' moments happen (living_anims' moment_specs forms)."""
    return {
        'stir': {'stir': ('part', 'ladle', tuple(lp.LADLE_BOWL))},
        'taste': {'taste': ('part', 'head', (0.0, -1.3, -5.2))},
        'flip': {'flip': ('part', 'turner', tuple(lp.TURNER_EDGE)), 'land': ('point', tuple(PAN_SLOTS[FLIP_SLOT]))},
        'turn_spit': {'crank': ('part', 'right_arm', tuple(RIGHT_FIST))},
        'slice': {'slice': ('part', KNIFE, tuple(KNIFE_CUT))},
        'carve': {'carve': ('part', KNIFE, tuple(KNIFE_CUT)), 'plate': ('part', 'bowl_empty', tuple(BOWL_IN))},
        'peel': {'door': ('point', tuple(PL_MOUTH)), 'peel': ('part', 'peel', tuple(PEEL_BLADE_MID)),
                 'down': ('part', 'peel', tuple(PEEL_LOAVES))},
    }


BUILT = {}


def build(living, only=None):
    """Builds the kitchen clips on living_anims' kit ([living]: the module), and teaches it their stations, moments and
    props' tracks. [only]: just these (the quick previews)."""
    global la
    la = living
    if wc.la is None:           # (living_anims builds the works' clips first; a quick preview of these alone does not)
        wc.la = living
    if BUILT:
        return
    la.STATIONS.update(STATION_BOXES)
    la.GROUND_AT.update(STATION_GROUND)
    la.GROUND_COLOUR.update(STATION_COLOUR)
    for name in ORDER:
        if only and name not in only:
            continue
        MAKERS[name]()
    specs_ = la.moment_specs

    def moment_specs():
        out = specs_()
        out.update(cook_moment_specs())
        return out
    la.moment_specs = moment_specs
    BUILT['done'] = True
    la.WANTED.extend(COOK_CLIPS)
