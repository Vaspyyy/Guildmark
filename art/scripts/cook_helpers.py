"""The helpers' models (cook_art.py): the Prep Board (a short counter with a chopping board), the Plate (a board with a stoneware plate on it,
very low), the Larder (a slatted cupboard on feet, shut and open, and the door that swings) and the Menu Board (a chalk slate on a post, and
a wall variant), and the herb crop in four ages.

Authored facing north (front toward -z); the block's `facing` turns the ones that have it.
"""
import math

import numpy as np

from cook_geo import cube, along, ring, disc, crossed, quad
import cook_tex

# ---------------------------------------------------------------- the numbers (px)
BOARD_TOP = 10.2                                         # the Prep Board: the top of the chopping board (where the heap lies)
HEAP_BOX = (4.2, 10.2, 4.6, 11.8, 14.4, 12.2)            # x0, y0, z0, x1, y1, z1: where up to 16 items lie, heaped
PLATE_TOP = 1.55                                         # the Plate: the top of the plate's floor (a dish laid flat sits here)
PLATE_AT = (8.0, PLATE_TOP, 8.0)                         # the middle of the dish
PLATE_R = 4.4                                            # the plate's apothem
# The Larder
LARDER_BODY = (2.0, 1.5, 3.4, 14.0, 15.2, 12.6)          # x0, y0, z0, x1, y1, z1 of the cupboard (the door is on the front, z0)
LARDER_SHELVES = (2.7, 7.0, 11.3)                        # the shelf tops (y)
LARDER_DISHES = ((5.6, 2.7, 8.0), (8.0, 7.0, 8.0), (10.4, 11.3, 8.0))   # where the three dishes seen through the slats stand (x, y, z of each middle)
LARDER_DOOR_HINGE = (2.0, 3.0)                           # the door's hinge: x, z of its vertical axis (the door's -x edge, its front plane)
LARDER_DOOR_MAX = 100.0                                  # the door opens by yaw 0 to 100 degrees about it (positive swings outward)
# The Menu Board
SLATE_FACE = (1.9, 6.7, 14.1, 14.3)                      # the slate's chalking area on the standing board, x0, y0, x1, y1, on the plane z = SLATE_Z
SLATE_Z = 7.05
WALL_FACE = (1.9, 3.3, 14.1, 12.7)                       # the same on the wall variant (on the plane z = WALL_Z)
WALL_Z = 14.75


# ================================================================ the Prep Board

def prep_board():
    els = []
    # Four legs, a lower shelf, an apron under the top
    for lx in (1.4, 13.0):
        for lz in (3.0, 11.6):
            els.append(cube((lx, 0, lz), (lx + 1.6, 8.0, lz + 1.4), 'darkwood'))
    els.append(cube((1.4, 2.0, 3.0), (14.6, 2.7, 13.0), 'planks', ('up', 'down', 'north', 'south', 'west', 'east')))
    els.append(cube((1.4, 6.8, 3.0), (14.6, 8.0, 3.7), 'darkwood', ('north', 'south', 'west', 'east')))
    els.append(cube((1.4, 6.8, 12.3), (14.6, 8.0, 13.0), 'darkwood', ('north', 'south', 'west', 'east')))
    els.append(cube((1.4, 6.8, 3.7), (2.1, 8.0, 12.3), 'darkwood', ('west', 'east', 'north', 'south')))
    els.append(cube((13.9, 6.8, 3.7), (14.6, 8.0, 12.3), 'darkwood', ('west', 'east', 'north', 'south')))
    # The counter top, a slab overhanging the legs
    els.append(cube((0.6, 8.0, 2.0), (15.4, 9.6, 14.0), {'up': 'planks', '*': 'planks'}))
    # The chopping board on it, corners cut, a little proud, edges worn
    els.append(cube((3.2, 9.6, 4.2), (12.8, BOARD_TOP, 12.4), {'up': 'board', '*': 'board'}, uv={'up': (3.2, 4.2, 12.8, 12.4)}))
    # A knife lying at the front edge of the counter: a steel blade, a wooden handle
    els.append(cube((7.0, 9.6, 2.7), (11.6, 9.95, 3.5), {'up': 'pot_iron', '*': 'pot_iron'}, uv={'up': (0, 0, 8, 2)}))
    els.append(cube((11.6, 9.6, 2.65), (14.2, 10.2, 3.55), 'darkwood', ('up', 'north', 'south', 'east', 'west')))
    els.append(cube((12.2, 9.6, 2.7), (12.5, 10.25, 3.5), 'brass', ('up', 'north', 'south')))
    els.append(cube((12.9, 9.6, 2.7), (13.2, 10.25, 3.5), 'brass', ('up', 'north', 'south')))
    return els


# ================================================================ the Plate

def plate():
    """A trencher of pale wood with a stoneware plate on it: the board 13 x 9.6 and 1 px thick, the plate (a dark-rimmed round of eight sides)
    a hair over half a pixel on it. A dish laid flat sits at PLATE_AT."""
    els = [cube((1.6, 0, 3.2), (14.4, 1.0, 12.8), {'up': 'board', '*': 'board'}, uv={'up': (1.6, 3.2, 14.4, 12.8)})]
    r = PLATE_R
    y0, y1 = 1.0, 1.0 + 0.55
    # The plate: the round of eight sides, its top the stoneware texture laid so the plate's circle fills it, a low raised rim ring
    s = 2.0 * r * math.tan(math.pi / 8)
    k = 8.0 / (r * 1.06)
    for i, ang in enumerate((0.0, 45.0, 90.0, 135.0)):
        yt = y1 - 0.006 * i
        u0, u1 = 8.0 - r * k, 8.0 + r * k
        v0, v1 = 8.0 - s / 2 * k, 8.0 + s / 2 * k
        els.append(cube((8 - r, y0, 8 - s / 2), (8 + r, yt, 8 + s / 2), {'up': 'stoneware', '*': 'stoneware'}, ('up', 'west', 'east'),
                        uv={'up': (u0, v0, u1, v1), 'west': (0, 5, 2, 6), 'east': (0, 5, 2, 6)}, rot=(0.0, ang, 0.0), origin=(8, y0, 8)))
    return els


# ================================================================ the Larder

def _larder_frame():
    x0, y0, z0, x1, y1, z1 = LARDER_BODY
    els = []
    # Feet: four stubs
    for fx in (x0 + 0.2, x1 - 1.6):
        for fz in (z0 + 0.2, z1 - 1.6):
            els.append(cube((fx, 0, fz), (fx + 1.4, y0, fz + 1.4), 'darkwood'))
    # The plinth, the corner posts, the top with a cornice
    els.append(cube((x0, y0, z0), (x1, 2.7, z1), 'planks'))
    pw = 1.5
    for px, pz in ((x0, z0), (x1 - pw, z0), (x0, z1 - pw), (x1 - pw, z1 - pw)):
        els.append(cube((px, 2.7, pz), (px + pw, y1 - 1.2, pz + pw), 'darkwood'))
    els.append(cube((x0 - 0.4, y1 - 1.2, z0 - 0.4), (x1 + 0.4, y1, z1 + 0.4), 'planks'))
    els.append(cube((x0 - 0.8, y1, z0 - 0.8), (x1 + 0.8, y1 + 0.5, z1 + 0.8), 'darkwood', ('up', 'north', 'south', 'west', 'east')))
    # The back: plain boards
    els.append(cube((x0 + pw, 2.7, z1 - 0.7), (x1 - pw, y1 - 1.2, z1 - 0.02), 'darkwood', ('north', 'south')))
    # The sides: upright slats with gaps between (so the dishes show), a rail top and bottom
    for sx in (x0, x1 - 0.7):
        sf = ('west', 'east', 'up', 'down', 'north', 'south')
        z = z0 + pw
        while z + 1.0 <= z1 - pw + 1e-6:
            els.append(cube((sx, 2.7, z), (sx + 0.7, y1 - 1.2, z + 1.0), 'slats', sf))
            z += 1.9
    # The shelves
    for sy in LARDER_SHELVES[1:]:
        els.append(cube((x0 + pw, sy - 0.5, z0 + 0.4), (x1 - pw, sy, z1 - 0.7), 'planks', ('up', 'down', 'north', 'south', 'west', 'east')))
    return els


def _larder_door(angle_free=True):
    """The door in its own frame: hinged on its -x edge at LARDER_DOOR_HINGE, it fills the front of the body: a frame of two stiles and two rails
    with six upright slats between (gaps so the dishes show), an iron latch on the free edge, and two strap hinges."""
    hx, hz = LARDER_DOOR_HINGE
    x0, y0, z0, x1, y1, z1 = LARDER_BODY
    dx1 = x1 - 0.0
    ya, yb = 2.7, y1 - 1.2
    els = []
    f = ('up', 'down', 'north', 'south', 'west', 'east')
    t = 0.7
    z_a, z_b = hz - 0.0, hz + 0.9                          # the door's thickness
    els.append(cube((hx, ya, z_a), (hx + 1.3, yb, z_b), 'darkwood', f))
    els.append(cube((dx1 - 1.3, ya, z_a), (dx1, yb, z_b), 'darkwood', f))
    els.append(cube((hx + 1.3, ya, z_a), (dx1 - 1.3, ya + 1.2, z_b), 'darkwood', f))
    els.append(cube((hx + 1.3, yb - 1.2, z_a), (dx1 - 1.3, yb, z_b), 'darkwood', f))
    sx = hx + 1.3 + 0.55
    while sx + 1.0 <= dx1 - 1.3 - 0.4:
        els.append(cube((sx, ya + 1.2, z_a + 0.15), (sx + 1.0, yb - 1.2, z_b - 0.15), 'slats', f))
        sx += 1.85
    # Straps
    for ys in (ya + 2.0, yb - 3.0):
        els.append(cube((hx - 0.2, ys, z_a - 0.3), (hx + 3.6, ys + 0.7, z_a), 'iron_band', ('north', 'up', 'down', 'east')))
    # The latch: a plate and a bar that drops over a staple, a ring pull
    ly = (ya + yb) / 2 + 1.0
    els.append(cube((dx1 - 2.6, ly, z_a - 0.35), (dx1 - 0.4, ly + 0.8, z_a), 'iron', ('north', 'up', 'down', 'west')))
    els.append(cube((dx1 - 1.3, ly - 0.9, z_a - 0.55), (dx1 - 0.7, ly + 0.9, z_a - 0.3), 'iron_band', ('north', 'up', 'down', 'west', 'east')))
    els.append(cube((dx1 - 1.2, ly - 1.8, z_a - 0.7), (dx1 - 0.8, ly - 0.9, z_a - 0.3), 'brass', ('north', 'up', 'down', 'west', 'east'), uv=(0, 0, 2, 2)))
    return els


def larder(open_):
    els = _larder_frame()
    if not open_:
        els += _larder_door()
    return els


def larder_door():
    return _larder_door()


# ================================================================ the Menu Board

def menu_board():
    """A chalk slate in a worn frame on a post, a little foot of two crossed planks. The slate's chalking area is SLATE_FACE on z = SLATE_Z."""
    x0, y0, x1, y1 = SLATE_FACE
    f = 0.9                                                # frame width
    z0, z1 = 7.2 - 0.0, 8.8
    els = []
    # Foot and post
    els.append(cube((3.0, 0, 6.6), (13.0, 1.2, 9.4), 'darkwood'))
    els.append(cube((6.6, 0, 3.0), (9.4, 1.2, 13.0), 'darkwood'))
    els.append(cube((7.2, 1.2, 7.2), (8.8, 7.4, 8.8), 'darkwood'))
    # The frame and the slate
    els.append(cube((x0 - f, y0 - f, z0), (x1 + f, y1 + f, z1), 'darkwood', ('up', 'down', 'west', 'east', 'south')))
    els.append(cube((x0 - f, y0 - f, z0 - 0.35), (x1 + f, y0, z0 + 0.1), 'darkwood', ('north', 'up', 'down')))
    els.append(cube((x0 - f, y1, z0 - 0.35), (x1 + f, y1 + f, z0 + 0.1), 'darkwood', ('north', 'up', 'down')))
    els.append(cube((x0 - f, y0, z0 - 0.35), (x0, y1, z0 + 0.1), 'darkwood', ('north', 'west', 'up')))
    els.append(cube((x1, y0, z0 - 0.35), (x1 + f, y1, z0 + 0.1), 'darkwood', ('north', 'east', 'up')))
    els.append(cube((x0, y0, SLATE_Z - 0.0), (x1, y1, z0 + 0.12), 'slate', ('north',), uv=(0, 0, 16, 16)))
    # The chalk ledge along the bottom with a stub of chalk on it
    els.append(cube((x0 - f, y0 - f - 0.7, z0 - 1.0), (x1 + f, y0 - f, z0 + 0.4), 'darkwood', ('north', 'up', 'down', 'west', 'east')))
    els.append(cube((9.0, y0 - f, z0 - 0.7), (10.6, y0 - f + 0.5, z0 - 0.2), 'chalk', ('north', 'up', 'west', 'east')))
    return els


def menu_board_wall():
    """The wall variant: the same slate and frame laid flat against the wall at the back of the cell (facing north, the wall is at z = 16)."""
    x0, y0, x1, y1 = WALL_FACE
    f = 0.9
    zf, zb = WALL_Z, 16.0
    els = [cube((x0 - f, y0 - f, zf - 0.1), (x1 + f, y1 + f, zb), 'darkwood', ('up', 'down', 'west', 'east', 'south'))]
    els.append(cube((x0 - f, y0 - f, zf - 0.4), (x1 + f, y0, zf), 'darkwood', ('north', 'up', 'down')))
    els.append(cube((x0 - f, y1, zf - 0.4), (x1 + f, y1 + f, zf), 'darkwood', ('north', 'up', 'down')))
    els.append(cube((x0 - f, y0, zf - 0.4), (x0, y1, zf), 'darkwood', ('north', 'west', 'up')))
    els.append(cube((x1, y0, zf - 0.4), (x1 + f, y1, zf), 'darkwood', ('north', 'east', 'up')))
    els.append(cube((x0, y0, WALL_Z - 0.0), (x1, y1, zf + 0.02), 'slate', ('north',), uv=(0, 0, 16, 16)))
    els.append(cube((x0 - f, y0 - f - 0.7, zf - 1.0), (x1 + f, y0 - f, zb), 'darkwood', ('north', 'up', 'down', 'west', 'east')))
    els.append(cube((9.0, y0 - f, zf - 0.7), (10.6, y0 - f + 0.5, zf - 0.2), 'chalk', ('north', 'up', 'west', 'east')))
    return els


# ================================================================ the herb

HERB_AGES = 4


def herb(age):
    """A crop in the vanilla pattern: four upright quads in a tic-tac-toe at 4 and 12 px, the texture of the age (herb_stage<age>)."""
    tex = 'herb_stage%d' % age
    els = []
    uv = (0, 0, 16, 16)
    for fixed in (4.0, 12.0):
        for face in ('north', 'south'):
            els.append(quad((0, 0, fixed), (16, 16, fixed), face, tex, uv))
        for face in ('west', 'east'):
            els.append(quad((fixed, 0, 0), (fixed, 16, 16), face, tex, uv))
    return els


def models():
    out = {
        'prep_board': (prep_board(), 'planks', True),
        'plate': (plate(), 'board', True),
        'larder': (larder(False), 'darkwood', True),
        'larder_open': (larder(True), 'darkwood', True),
        'cook_larder_door': (larder_door(), 'darkwood', True),
        'menu_board': (menu_board(), 'darkwood', True),
        'menu_board_wall': (menu_board_wall(), 'darkwood', True),
    }
    for a in range(HERB_AGES):
        out['herb_stage%d' % a] = (herb(a), 'herb_stage%d' % a, False)
    return out
