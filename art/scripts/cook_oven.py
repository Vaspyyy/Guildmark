"""The Bake Oven's models (cook_art.py): a clay-brick oven, its firebox mouth at the front and below, an iron damper register over the
mouth, an arched portal with the baking chamber behind it, a stepped dome and a chimney stub; each unlit and lit, a burnt overlay,
and the pieces the renderer moves: the door (shut, and shut and hot), a tray.

Authored facing north: its front is at z = 1, toward -z, and the block's `facing` turns it.
"""
import math

from cook_geo import cube, ring, disc
import cook_hob as H

# ---------------------------------------------------------------- the numbers (px)
FRONT_Z = 1.2
BODY = (1.0, 1.0, 1.2, 15.0, 8.0, 15.4)                  # the brick body over the footing: x0, y0, z0, x1, y1, z1
MOUTH = (5.0, 1.2, 11.0, 3.6)                            # the firebox mouth: x0, y0, x1, y1 (in the front face), going in MOUTH_DEPTH
MOUTH_DEPTH = 4.0
REGISTER = (3.8, 4.2, 12.2, 7.8)                         # the damper register's casing on the front: x0, y0, x1, y1
SLOT = (8.6, 5.6, 11.0, 6.8)                             # its air slot (the damper plate, 3.4 wide, slides 2.2 px a notch toward -x)
DAMPER_W = 3.4
DAMPER_SLIDE = -2.2
REGISTER_PROUD = 0.25                                    # the register's casing stands this far out of the front face
PORTAL = (3.6, 8.0, 12.4, 14.6)                          # the arched portal standing out of the dome's front: x0, y0, x1, y1
PORTAL_Z = (1.2, 3.4)                                    # its front and back
# The door opening, bottom to top in three steps (an arch): (y0, y1, x0, x1)
OPENING = [(8.8, 12.4, 4.8, 11.2), (12.4, 13.0, 5.5, 10.5), (13.0, 13.6, 6.6, 9.4)]
CAVITY = (4.8, 8.6, 11.2, 12.8)                          # the baking chamber behind it: x0, y0, x1, y1 (it goes in to CAVITY_Z)
CAVITY_Z = (1.8, 8.8)
HINGE = (4.8, 1.0)                                       # the door's hinge: x and z of its vertical axis (the door's -x edge, its front plane)
DOOR_MAX = 100.0                                         # the door opens by yaw 0 to 100 degrees about HINGE (Axis.YP.rotationDegrees, positive = outward)
DOME_C = (8.0, 8.5)                                      # the dome's middle (x, z)
DOME = [(8.0, 9.9, 6.85), (9.9, 10.85, 6.5), (10.85, 11.8, 6.1), (11.8, 12.75, 5.5), (12.75, 13.7, 4.8), (13.7, 14.6, 3.6), (14.6, 15.3, 1.8)]    # tiers: y0, y1, apothem
CHIMNEY = (11.7, 11.8)                                   # the chimney stub's middle
CHIMNEY_R = 1.35
CHIMNEY_TOP = 16.0                                       # smoke leaves from here
TRAY_Y = (8.6, 10.8)                                     # the two tray levels: the top of each rail
TRAY_BOX = (5.4, 10.6, 3.0, 8.4)                         # a tray: x0, x1, z0, z1
TRAY_SLOTS = [(6.5, 6.9), (8.0, 6.9), (9.5, 6.9), (7.25, 4.5), (8.75, 4.5)]   # where a tray lays its five items (x, z of each middle); two trays make the ten


def body():
    x0, y0, z0, x1, y1, z1 = BODY
    mx0, my0, mx1, my1 = MOUTH
    f, d = FRONT_Z, MOUTH_DEPTH
    els = [cube((x0, y0, f), (mx0, y1, f + d), 'clay'),
           cube((mx1, y0, f), (x1, y1, f + d), 'clay'),
           cube((mx0, y0, f), (mx1, my0, f + d), 'clay'),
           cube((mx0, my1, f), (mx1, y1, f + d), {'north': 'clay', '*': 'clay_sooty'}),
           cube((x0, y0, f + d), (x1, y1, z1), {'*': 'clay', 'north': 'clay_sooty'})]
    sk = 0.02
    els.append(cube((mx0, my0, f + sk), (mx0 + sk, my1, f + d), 'clay_sooty', ('east',)))
    els.append(cube((mx1 - sk, my0, f + sk), (mx1, my1, f + d), 'clay_sooty', ('west',)))
    els.append(cube((mx0, my0, f + sk), (mx1, my0 + sk, f + d), 'ash', ('up',)))
    els.append(cube((mx0, my1 - sk, f + sk), (mx1, my1, f + d), 'soot_wall', ('down',)))
    # An iron frame round the mouth
    els.append(cube((mx0 - 0.5, my1, f - 0.25), (mx1 + 0.5, my1 + 0.45, f + 0.1), 'iron_band', ('north', 'up', 'down', 'west', 'east')))
    els.append(cube((mx0 - 0.5, my0 - 0.4, f - 0.25), (mx0, my1, f + 0.1), 'iron', ('north', 'west', 'up')))
    els.append(cube((mx1, my0 - 0.4, f - 0.25), (mx1 + 0.5, my1, f + 0.1), 'iron', ('north', 'east', 'up')))
    # The footing: a course of dressed stone, a hair proud
    els.append(cube((x0 - 0.4, 0, z0 - 0.4), (x1 + 0.4, 1.0, z1 + 0.4), 'flagstone'))
    # Iron straps up the four corners
    for cx_, cz_, sx, sz in ((x0, z0, -1, -1), (x1, z0, 1, -1), (x0, z1, -1, 1), (x1, z1, 1, 1)):
        els.append(cube((cx_ - 0.5 + 0.5 * sx, y0, cz_ - 0.5 + 0.5 * sz), (cx_ + 0.5 + 0.5 * sx, y1, cz_ + 0.5 + 0.5 * sz), 'iron_band'))
    # A shelf of iron where the body meets the dome
    els.append(cube((x0 - 0.3, y1, z0 - 0.3), (x1 + 0.3, y1 + 0.5, z1 + 0.3), 'iron'))
    return els


def register():
    """The damper register: a cast iron box on the front over the mouth, 3.6 px tall, whose air slot the damper plate slides over, with three
    brass studs beneath it where the plate's knob stops at each notch."""
    x0, y0, x1, y1 = REGISTER
    f = FRONT_Z
    els = [cube((x0, y0, f - REGISTER_PROUD), (x1, y1, f), 'iron_band', ('north', 'up', 'down', 'west', 'east'))]
    for n in range(3):
        kx = SLOT[0] - 0.4 + DAMPER_SLIDE * n + 0.6
        els.append(cube((kx - 0.4, y0 + 0.25, f - REGISTER_PROUD - 0.3), (kx + 0.4, y0 + 0.95, f - REGISTER_PROUD), 'brass', ('north', 'up', 'west', 'east'), uv=(0, 0, 2, 2)))
    return els


def portal(lit):
    """The arched portal: a clay case 2.2 deep standing out of the dome's front, the opening cut through it in three steps, an iron arch strap
    round the opening, a keystone. Behind: the baking chamber, lined black (and lit glowing when the oven is lit), the floor a stone slab, a
    rail each side at both tray levels."""
    px0, py0, px1, py1 = PORTAL
    z0, z1 = PORTAL_Z
    els = []
    # The front wall round the opening: the piers and the lintel in rows between the steps
    ox0, ox1 = OPENING[0][2], OPENING[0][3]
    els.append(cube((px0, py0, z0), (ox0, OPENING[1][0], z1), 'clay'))
    els.append(cube((ox1, py0, z0), (px1, OPENING[1][0], z1), 'clay'))
    els.append(cube((px0, OPENING[0][0] - 0.8, z0), (px1, OPENING[0][0], z1), 'clay'))              # the sill under the opening
    for (ya, yb, xa, xb) in OPENING[1:]:
        els.append(cube((px0, ya, z0), (xa, yb, z1), 'clay'))
        els.append(cube((xb, ya, z0), (px1, yb, z1), 'clay'))
    els.append(cube((px0, OPENING[-1][1], z0), (px1, py1, z1), 'clay'))
    # The iron arch strap round the opening (a stepped band a pixel wide, proud of the front)
    zf = z0 - 0.25
    t = 0.9
    els.append(cube((ox0 - t, OPENING[0][0] - 0.2, zf), (ox0, OPENING[0][1], z0 + 0.1), 'iron_band', ('north', 'west', 'up')))
    els.append(cube((ox1, OPENING[0][0] - 0.2, zf), (ox1 + t, OPENING[0][1], z0 + 0.1), 'iron_band', ('north', 'east', 'up')))
    for (ya, yb, xa, xb) in OPENING[1:]:
        els.append(cube((xa - t, ya, zf), (xa, yb, z0 + 0.1), 'iron_band', ('north', 'west', 'up')))
        els.append(cube((xb, ya, zf), (xb + t, yb, z0 + 0.1), 'iron_band', ('north', 'east', 'up')))
    top = OPENING[-1]
    els.append(cube((top[2] - t, top[1], zf), (top[3] + t, top[1] + 0.7, z0 + 0.1), 'iron_band', ('north', 'up', 'west', 'east')))
    els.append(cube((7.2, top[1] + 0.1, zf - 0.15), (8.8, top[1] + 0.9, z0 + 0.1), 'brass', ('north', 'up', 'west', 'east'), uv=(0, 0, 3, 2)))     # the keystone
    # The chamber behind: its walls (their faces turned in), glowing when lit
    cx0, cy0, cx1, cy1 = CAVITY
    cz0, cz1 = CAVITY_Z
    wall = 'oven_glow' if lit else 'oven_dark'
    em = dict(light_emission=12) if lit else {}
    els.append(cube((cx0, cy0 - 0.4, cz0), (cx1, cy0, cz1), 'flagstone', ('up',)))
    els.append(cube((cx0, cy1, cz0), (cx1, cy1 + 0.02, cz1), 'clay_sooty', ('down',)))
    els.append(cube((cx0 - 0.02, cy0, cz0), (cx0, cy1, cz1), 'clay_sooty', ('east',)))
    els.append(cube((cx1, cy0, cz0), (cx1 + 0.02, cy1, cz1), 'clay_sooty', ('west',)))
    els.append(dict(cube((cx0, cy0, cz1 - 0.02), (cx1, cy1, cz1), wall, ('north',), uv=(0, 0, 16, 16)), **em))
    # The rails the trays rest on, two levels, both sides
    for yy in TRAY_Y:
        els.append(cube((cx0, yy - 0.5, cz0), (cx0 + 0.7, yy, cz1 - 0.2), 'iron', ('up', 'west', 'east', 'north')))
        els.append(cube((cx1 - 0.7, yy - 0.5, cz0), (cx1, yy, cz1 - 0.2), 'iron', ('up', 'west', 'east', 'north')))
    return els


def dome():
    """The stepped dome over the body: five tiers of clay brick narrowing to a cap, each a regular octagon, the first flush with the body."""
    cx, cz = DOME_C
    els = []
    for k, (y0, y1, r) in enumerate(DOME):
        faces = ('down', 'up', 'north', 'south', 'west', 'east') if k == 0 else ('up', 'north', 'south', 'west', 'east')
        els += disc(cx, cz, y0, y1, r, 'clay', faces=faces)
        if k < len(DOME) - 1:
            # a thin iron band round the seam of two tiers would be too busy: a mortar ledge instead (the next tier's overhang does it)
            pass
    return els


def chimney():
    """The chimney stub at the back right: a clay pipe through the dome with a soot-black mouth, an iron collar at its top."""
    fx, fz = CHIMNEY
    r = CHIMNEY_R
    els = ring(fx, fz, 10.4, CHIMNEY_TOP - 0.8, r, 0.7, 'clay', inner='soot_wall', top=False, bottom=False)
    els += ring(fx, fz, CHIMNEY_TOP - 0.8, CHIMNEY_TOP, r + 0.4, 0.8, 'iron_band', inner='soot_wall', top=True, bottom=False)
    return els


def coals_in_mouth(lit):
    mx0, my0, mx1, my1 = MOUTH
    z0 = FRONT_Z + 0.4
    z1 = FRONT_Z + MOUTH_DEPTH - 0.2
    if lit:
        return [dict(cube((mx0 + 0.3, my0 + 0.03, z0), (mx1 - 0.3, my0 + 0.15, z1), 'coals', ('up',), uv=(0, 0, 7, 4)), light_emission=14)]
    return [cube((mx0 + 0.3, my0 + 0.03, z0), (mx1 - 0.3, my0 + 0.15, z1), 'coals_cold', ('up',), uv=(0, 0, 7, 4))]


def mouth_flames():
    from cook_geo import crossed
    import cook_tex
    mx0, my0, mx1, my1 = MOUTH
    return crossed(0, 0, my0 + 0.1, my0 + 0.1 + 3.6, 'flame', cx=(mx0 + mx1) / 2, cz=FRONT_Z + MOUTH_DEPTH / 2 + 0.2, half=2.7,
                   uv=cook_tex.FLAME_UV, light_emission=15)


def shell(lit):
    els = body() + register() + H.slot_dark(SLOT, FRONT_Z - REGISTER_PROUD) + portal(lit) + dome() + chimney() + coals_in_mouth(lit)
    if lit:
        els += mouth_flames()
    return els


def burnt():
    """The burnt look laid over the oven: the dome and the portal's face blackened to a near-black crust, soot streaked up the front."""
    cx, cz = DOME_C
    g = 0.06
    els = []
    for (y0, y1, r) in DOME:
        els += disc(cx, cz, y0, y1, r + g, 'soot_wall', faces=('north', 'south', 'west', 'east'))
    px0, py0, px1, py1 = PORTAL
    els.append(cube((px0, py0, PORTAL_Z[0] - 0.3), (px1, py1, PORTAL_Z[0] - 0.28), 'soot_wall', ('north',)))
    els.append(cube((BODY[0], BODY[1], FRONT_Z - g), (BODY[3], BODY[4], FRONT_Z - g + 0.01), 'soot_wall', ('north',)))
    els.append(cube((BODY[0] - g, BODY[1], BODY[2]), (BODY[0] - g + 0.01, BODY[4], BODY[5]), 'soot_wall', ('west',)))
    els.append(cube((BODY[3] + g - 0.01, BODY[1], BODY[2]), (BODY[3] + g, BODY[4], BODY[5]), 'soot_wall', ('east',)))
    return els


# ---------------------------------------------------------------- the pieces

def door(hot):
    """The oven door, a piece hinged on its -x edge at HINGE (yaw 0 to DOOR_MAX about that vertical axis): an iron slab in the three steps of the
    opening, a window of four panes (dark, or the fire's glow when [hot], emissive), two hinge straps, a brass handle on the free edge."""
    z0 = HINGE[1]
    z1 = z0 + 1.0
    els = []
    m = 0.15                                              # the door sits this much inside the opening on every side
    wall = 'oven_glow' if hot else 'oven_dark'
    for (ya, yb, xa, xb) in OPENING:
        els.append(cube((xa + m, ya + (0.0 if ya > OPENING[0][0] else m), z0), (xb - m, yb, z1), 'iron'))
    # The window: a pane over the door's middle, four panes by two iron muntins
    wx0, wx1, wy0, wy1 = 5.6, 10.4, 9.6, 12.4
    wf = z0 - 0.04
    glow = dict(light_emission=15) if hot else {}
    els.append(dict(cube((wx0, wy0, wf), (wx1, wy1, wf + 0.02), wall, ('north',), uv=(0, 0, 16, 16)), **glow))
    mid_x = (wx0 + wx1) / 2
    mid_y = (wy0 + wy1) / 2
    els.append(cube((mid_x - 0.3, wy0 - 0.2, wf - 0.2), (mid_x + 0.3, wy1 + 0.2, wf + 0.05), 'iron_band', ('north', 'west', 'east', 'up')))
    els.append(cube((wx0 - 0.2, mid_y - 0.3, wf - 0.2), (wx1 + 0.2, mid_y + 0.3, wf + 0.05), 'iron_band', ('north', 'up', 'down')))
    # Window frame, a bead round the glass
    els.append(cube((wx0 - 0.5, wy1, wf - 0.2), (wx1 + 0.5, wy1 + 0.5, wf + 0.05), 'iron_band', ('north', 'up', 'west', 'east')))
    els.append(cube((wx0 - 0.5, wy0 - 0.5, wf - 0.2), (wx1 + 0.5, wy0, wf + 0.05), 'iron_band', ('north', 'down', 'west', 'east')))
    els.append(cube((wx0 - 0.5, wy0, wf - 0.2), (wx0, wy1, wf + 0.05), 'iron_band', ('north', 'west', 'up')))
    els.append(cube((wx1, wy0, wf - 0.2), (wx1 + 0.5, wy1, wf + 0.05), 'iron_band', ('north', 'east', 'up')))
    # Hinge straps (on the -x edge, reaching across the door) and the handle
    for hy in (9.4, 11.8):
        els.append(cube((HINGE[0] - 0.4, hy, z0 - 0.3), (HINGE[0] + 2.4, hy + 0.7, z0), 'iron_band', ('north', 'up', 'down', 'east')))
    els.append(cube((10.0, 10.3, z0 - 1.0), (10.6, 11.7, z0 - 0.3), 'brass', ('north', 'up', 'down', 'west', 'east'), uv=(0, 0, 2, 4)))
    return els


def tray():
    """One baking tray, a piece at the lower tray level (the renderer lifts it TRAY_Y[1] - TRAY_Y[0] for the upper): an iron sheet resting on the
    rails with a low lip all round and a little handle at the front. Its top is TRAY_Y[0] + 0.4."""
    x0, x1, z0, z1 = TRAY_BOX
    y = TRAY_Y[0]
    els = [cube((x0, y, z0), (x1, y + 0.3, z1), 'iron_inside', ('up', 'down', 'north', 'south', 'west', 'east'))]
    els.append(cube((x0, y + 0.3, z0), (x1, y + 0.7, z0 + 0.4), 'iron', ('up', 'north', 'south', 'west', 'east')))
    els.append(cube((x0, y + 0.3, z1 - 0.4), (x1, y + 0.7, z1), 'iron', ('up', 'north', 'south', 'west', 'east')))
    els.append(cube((x0, y + 0.3, z0 + 0.4), (x0 + 0.4, y + 0.7, z1 - 0.4), 'iron', ('up', 'west', 'north', 'south')))
    els.append(cube((x1 - 0.4, y + 0.3, z0 + 0.4), (x1, y + 0.7, z1 - 0.4), 'iron', ('up', 'east', 'north', 'south')))
    els.append(cube(((x0 + x1) / 2 - 1.2, y + 0.1, z0 - 0.9), ((x0 + x1) / 2 + 1.2, y + 0.5, z0), 'iron', ('up', 'north', 'west', 'east', 'down')))
    return els


def models():
    out = {
        'bake_oven': (shell(False), 'clay', True),
        'bake_oven_lit': (shell(True), 'clay', False),
        'cook_oven_burnt': (burnt(), 'soot_wall', True),
        'cook_oven_door': (door(False), 'iron', True),
        'cook_oven_door_hot': (door(True), 'iron', False),
        'cook_oven_tray': (tray(), 'iron', True),
    }
    for n in range(3):
        out['bake_oven_damper_%d' % n] = (H.damper(n, SLOT, FRONT_Z - REGISTER_PROUD, w=DAMPER_W, slide=DAMPER_SLIDE), 'iron', True)
    return out
