"""The Hob's models (cook_art.py): a low brick plinth with an iron top and a raised rim, a firebox mouth in its front with the coals
(and a small flame when lit) behind it, an iron slide-damper above the mouth (its position by the block's `notch`), and the flat pan
that sits on top (the renderer's piece, which lays the pieces of food in it).

Authored facing north: its front (the mouth, the damper) is at z = 1, toward -z. The pan is centred on (8, 8).
"""
import math

import numpy as np

from cook_geo import cube, along, ring, lathe, disc, crossed, quad
import cook_tex

# ---------------------------------------------------------------- the numbers (px)
BODY = (1.0, 0.0, 1.0, 15.0, 9.0, 15.0)              # the brick plinth: x0, y0, z0, x1, y1, z1
TOP_Y = 10.0                                         # the top of the iron plate the pan stands on
RIM_Y = 11.2                                         # the top of the raised rim round it
FRONT_Z = 1.0                                        # the plinth's front face
MOUTH = (4.5, 1.2, 11.5, 6.4)                        # the firebox mouth in the front face: x0, y0, x1, y1
MOUTH_DEPTH = 4.0                                    # how far it goes in
SLOT = (6.2, 7.2, 9.8, 8.4)                          # the damper's air slot in the lintel's face: x0, y0, x1, y1
DAMPER_W = 4.4                                       # the damper plate's width: it covers the slot (and 0.4 over each side) at notch 0
DAMPER_SLIDE = -2.4                                  # px the plate slides along (x) for each notch: to the left, off the slot, 0 (shut) to 2 (open)
FLUE = (13.4, 13.4)                                  # the flue pipe's middle (it stands on the iron top at the back right corner)
FLUE_R = 1.0                                         # its apothem
FLUE_TOP = 15.4                                      # its top: smoke leaves from here
PAN_Y = TOP_Y                                        # the pan's bottom
PAN_R = 5.4                                          # its apothem, outside
PAN_FLOOR_Y = PAN_Y + 0.5                            # its inside floor, where the pieces of food lie
PAN_SLOTS = [(8.0 + dx, 8.0 + dz) for dz in (-1.9, 1.9) for dx in (-3.0, 0.0, 3.0)]   # six places for a piece, x and z of each middle


def plinth():
    x0, y0, z0, x1, y1, z1 = BODY
    mx0, my0, mx1, my1 = MOUTH
    d = MOUTH_DEPTH
    els = []
    # The front wall round the mouth: two piers, the lintel (with the damper's slot cut out of its front), the sill
    f0 = FRONT_Z
    els.append(cube((x0, y0, f0), (mx0, y1, f0 + d), 'brick'))
    els.append(cube((mx1, y0, f0), (x1, y1, f0 + d), 'brick'))
    els.append(cube((mx0, y0, f0), (mx1, my0, f0 + d), 'brick'))
    els.append(cube((mx0, my1, f0), (mx1, y1, f0 + d), 'brick'))
    # Behind it, the rest of the plinth: its back wall is the mouth's back
    els.append(cube((x0, y0, f0 + d), (x1, y1, z1), {'*': 'brick', 'north': 'clay_sooty'}))
    # The mouth's inside, blackened: the back wall's face, the piers' inner faces, the lintel's underside, the sill's top
    # (those are the faces of the boxes above; the textures are set on them here by laying thin sooty skins on them)
    sk = 0.02
    els.append(cube((mx0, my0, f0 + sk), (mx0 + sk, my1, f0 + d), 'clay_sooty', ('east',)))
    els.append(cube((mx1 - sk, my0, f0 + sk), (mx1, my1, f0 + d), 'clay_sooty', ('west',)))
    els.append(cube((mx0, my1 - sk, f0 + sk), (mx1, my1, f0 + d), 'clay_sooty', ('down',)))
    els.append(cube((mx0, my0, f0 + sk), (mx1, my0 + sk, f0 + d), 'ash', ('up',)))
    # A lintel of iron over the mouth, a fillet of iron round the opening's edge
    els.append(cube((mx0 - 0.4, my1, f0 - 0.2), (mx1 + 0.4, my1 + 0.7, f0 + 0.2), 'iron'))
    els.append(cube((mx0 - 0.4, my0 - 0.5, f0 - 0.2), (mx1 + 0.4, my0, f0 + 0.2), 'iron'))
    return els


def top():
    """The iron plate over the plinth, a hair wider, and the raised rim round it (1.2 high, 0.8 wide)."""
    x0, y0, z0, x1, y1, z1 = BODY
    els = [cube((x0 - 0.5, y1, z0 - 0.5), (x1 + 0.5, TOP_Y, z1 + 0.5), 'iron')]
    r, w = RIM_Y, 0.8
    xa, xb, za, zb = x0 - 0.5, x1 + 0.5, z0 - 0.5, z1 + 0.5
    els.append(cube((xa, TOP_Y, za), (xb, r, za + w), 'iron'))
    els.append(cube((xa, TOP_Y, zb - w), (xb, r, zb), 'iron'))
    els.append(cube((xa, TOP_Y, za + w), (xa + w, r, zb - w), 'iron'))
    els.append(cube((xb - w, TOP_Y, za + w), (xb, r, zb - w), 'iron'))
    return els


def slot_dark(slot=SLOT, front=FRONT_Z):
    """The damper's slot: a dark opening in the lintel's face (the plate slides over it)."""
    sx0, sy0, sx1, sy1 = slot
    return [cube((sx0, sy0, front - 0.02), (sx1, sy1, front + 0.1), 'oven_dark', ('north',), uv=(0, 0, 8, 2))]


def coals_in_mouth(lit):
    mx0, my0, mx1, my1 = MOUTH
    z0 = FRONT_Z + 0.4
    z1 = FRONT_Z + MOUTH_DEPTH - 0.2
    if lit:
        return [dict(cube((mx0 + 0.3, my0 + 0.03, z0), (mx1 - 0.3, my0 + 0.15, z1), 'coals', ('up',), uv=(0, 0, 7, 4)), light_emission=14)]
    return [cube((mx0 + 0.3, my0 + 0.03, z0), (mx1 - 0.3, my0 + 0.15, z1), 'coals_cold', ('up',), uv=(0, 0, 7, 4))]


def mouth_flames():
    """A small flame in the firebox: two crossed quads standing in the coals, glowing."""
    mx0, my0, mx1, my1 = MOUTH
    cx, cz = (mx0 + mx1) / 2, FRONT_Z + MOUTH_DEPTH / 2 + 0.2
    uv = cook_tex.FLAME_UV
    els = crossed(0, 0, my0 + 0.1, my0 + 0.1 + 4.2, 'flame', cx=cx, cz=cz, half=2.8, uv=uv, light_emission=15)
    return els


def soot_over_mouth():
    """The brick over the mouth blackened by the smoke: a soot smear on the face above the opening and up the plinth's front, laid a hair proud."""
    mx0, my0, mx1, my1 = MOUTH
    return [cube((mx0 - 0.6, my1 + 0.7, FRONT_Z - 0.04), (mx1 + 0.6, BODY[4], FRONT_Z), 'soot_wall', ('north',))]


def footing():
    """A course of dressed stone under the brick, a hair proud, and four iron straps up the corners."""
    x0, y0, z0, x1, y1, z1 = BODY
    els = [cube((x0 - 0.4, 0, z0 - 0.4), (x1 + 0.4, 1.2, z1 + 0.4), 'flagstone')]
    for cx_, cz_ in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        sx = -1 if cx_ == x0 else 1
        sz = -1 if cz_ == z0 else 1
        els.append(cube((cx_ - 0.5 + 0.5 * sx, 1.2, cz_ - 0.5 + 0.5 * sz), (cx_ + 0.5 + 0.5 * sx, y1, cz_ + 0.5 + 0.5 * sz), 'iron_band'))
    return els


def flue():
    """The flue: a short iron pipe on the iron top at the back right corner, a collar at its foot, a lip and a cap-ring at its top."""
    fx, fz = FLUE
    r = FLUE_R
    els = ring(fx, fz, RIM_Y - 1.0, FLUE_TOP - 0.7, r, 0.5, 'iron_band', inner='iron_inside', top=False, bottom=False)
    els += ring(fx, fz, RIM_Y - 1.0, RIM_Y + 0.4, r + 0.45, 0.6, 'iron', inner='iron_inside', top=True, bottom=False)
    els += ring(fx, fz, FLUE_TOP - 0.7, FLUE_TOP, r + 0.4, 0.6, 'iron', inner='iron_inside', top=True, bottom=False)
    return els


def shell(lit):
    els = plinth() + top() + footing() + flue() + slot_dark() + coals_in_mouth(lit) + soot_over_mouth()
    if lit:
        els += mouth_flames()
    return els


def burnt():
    """The burnt look laid over the hob: the brick's front and sides blackened to the top, a scorch on the iron round the pan."""
    x0, y0, z0, x1, y1, z1 = BODY
    g = 0.05
    t = 'soot_wall'
    return [cube((x0 - g, y0, FRONT_Z - g), (x1 + g, y1, FRONT_Z), t, ('north',)),
            cube((x0 - g, y0, z0), (x0, y1, z1), t, ('west',)),
            cube((x1, y0, z0), (x1 + g, y1, z1), t, ('east',)),
            cube((x0, TOP_Y - 0.02, z0), (x1, TOP_Y + 0.02, z1), t, ('up',))]


# ---------------------------------------------------------------- the damper (by notch)

def damper(notch, slot=SLOT, front=FRONT_Z, w=DAMPER_W, slide=DAMPER_SLIDE):
    """The slide-damper at [notch] 0 to 2: an iron plate (a thin sheet, [w] wide) over the air slot in the lintel, slid along the lintel by
    [slide] px per notch (toward -x: to the right as one faces the front, the way the knob on its end pulls), a brass knob on that end and
    two staples that hold it. Notch 0 shuts the slot, notch 2 slides the plate clear of it."""
    sx0, sy0, sx1, sy1 = slot
    x0 = sx0 - 0.4 + slide * notch
    x1 = x0 + w
    els = [cube((x0, sy0 - 0.3, front - 0.47), (x1, sy1 + 0.3, front - 0.02), 'iron'),
           cube((x0 + 0.1, sy0 + 0.1, front - 0.9), (x0 + 1.1, sy1 - 0.1, front - 0.47), 'brass', uv=(0, 0, 2, 2))]
    # Two staples that hold the plate to the casing, at the ends of its run
    for gx in (sx0 - 0.4 + slide * 2 - 0.1, sx1 + 0.3):
        els.append(cube((gx, sy0 - 0.45, front - 0.6), (gx + 0.5, sy1 + 0.45, front - 0.02), 'iron'))
    return els


# ---------------------------------------------------------------- the pan (a piece)

def pan():
    """The flat pan the food lies in, centred on (8, 8) on the iron top: a shallow eight-sided dish 10.8 across with a low rim, and a handle
    reaching out toward the front-right (it lies over the rim of the plate). Its inside floor is PAN_FLOOR_Y."""
    y0 = PAN_Y
    els = disc(8.0, 8.0, y0, y0 + 0.5, PAN_R - 0.2, 'pot_iron', top='iron_inside', faces=('down', 'up', 'north', 'south', 'west', 'east'))
    els += ring(8.0, 8.0, y0 + 0.5, y0 + 1.5, PAN_R, 0.6, 'pot_iron', inner='iron_inside', top=True, bottom=False)
    # The handle: a flat iron bar from the rim out to the front right, with an eye at its end
    hx = 8.0 + PAN_R * 0.7
    els.append(cube((hx, y0 + 0.7, 8.0 - 0.7 - 0.0), (hx + 3.4, y0 + 1.2, 8.0 + 0.7), 'pot_iron'))
    els.append(cube((hx + 3.0, y0 + 0.7, 8.0 - 1.0), (hx + 4.2, y0 + 1.2, 8.0 + 1.0), 'pot_iron'))
    return els


def models():
    out = {
        'hob': (shell(False), 'brick', True),
        'hob_lit': (shell(True), 'brick', False),
        'cook_hob_burnt': (burnt(), 'soot_wall', True),
        'cook_hob_pan': (pan(), 'pot_iron', True),
    }
    for n in range(3):
        out['hob_damper_%d' % n] = (damper(n), 'iron', True)
    return out
