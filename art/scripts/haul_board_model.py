"""The Haul Board's body: the dark oak and iron frame of the capital's second board, its models, textures and numbers.

Imported by haul_board_art.py (which writes the files). The board is the market board's sister, made the same way (see
market_board_art.py, whose frame, materials and painting helpers this borrows): standing free on two posts in stone collars, a
carved header plank under a little cap of slate tiles, a front painting of 96x64 texels over its whole 3x2 blocks and six cell
models the blockstate picks by `part` (the cells are authored facing north, the front is the north side). What is its own:

  * the left half is a slate ruled in eight rows, each for a capital within reach, nearest first: a title band over them and a
    faint rule under each row for the merchant's chalk to run along (the renderer writes the rows; haul_board_art.py's table
    says where everything goes in a row);
  * the right half is a plank panel under two rails of iron pegs, four to a rail: a ticket hangs from each peg by its eyelet;
  * a shelf runs the panel's foot with an inkwell, a quill and a brass desk bell standing on it (and room for the merchant's
    satchel), under it two iron brackets;
  * the header carries a wheel at one end and a sheaf at the other, and over its middle, through the cap's ridge, stands the
    crest: a gilt wheel and a straw sheaf on an indigo field in an oak frame, under a little cap of its own. The crest is an
    animated texture: a glint crosses the gilt every few seconds (textures/block/haul_board/crest.png, its .mcmeta says how).

Coordinates: the board's own, in model pixels (16 to a block), as seen from the front: X 0..48 from the left edge, Y 0..32 up
from the ground, Z 0..16 from the front of the cells back. The face layout is in blocks, u to the right and v down from the
board's top left corner, as MarketBoardArt's is.
"""
import base64
import copy
import hashlib
import io
import json
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import market_board_art as MB
from market_board_art import (POST_L, POST_R, POST_Y, POST_Z, COLLAR_L, COLLAR_R, COLLAR_Y, COLLAR_Z, RAIL_X, RAIL_Y, RAIL_Z,
                              PANEL_X, PANEL_Y, PANEL_Z, HEADER_X, HEADER_Y, HEADER_Z, BATTEN_X, BATTENS_Y, BATTEN_Z, PITCH,
                              RIDGE_Z, CAP_HALF, CAP_T, CAP_UNDER, RIDGE_BEAM_Y, RIDGE_BEAM_Z, RIDGE_CAP_Y, RIDGE_CAP_Z, STRAPS,
                              PARTS, PW, PH, tx, ty, R, RB, blocks, Relief, tube, oval, sheaf, TRIM_QUADS, OAK, STRAW)
from hdkit import (PAL, Canvas, grid, fbm, rand, bands, poly, disc, ellipse, rect, shift, grow, outline, path_dist, dist_in)

# ================================================================ geometry (model px)

SLATE_X = (3.5, 22.75)
MULLION_X = (22.75, 24.25)
HOOKS_X = (24.25, 44.5)
MULLION_Z = (12.0, 14.5)
LEDGE_X = (4.5, 22.0)
LEDGE_Y = (6.0, 7.0)
LEDGE_Z = (10.75, 12.0)
# Two stubs of chalk on the chalk ledge
STUBS = [(8.75, 10.5, 7.0, 7.5, 11.0, 11.5), (12.75, 13.75, 7.0, 7.5, 11.25, 11.75)]

# The hooks: two rails of four iron pegs. A ticket hangs from a peg by its eyelet, in the plane TICKET_PLANE.
TIERS = 2
HOOKS_PER_TIER = 4
HOOK_PITCH = (HOOKS_X[1] - HOOKS_X[0]) / HOOKS_PER_TIER
HOOK_X = [HOOKS_X[0] + HOOK_PITCH * (k + 0.5) for k in range(HOOKS_PER_TIER)]
PEG_Y = (25.2, 19.6)                          # the pegs' height on each rail, the upper rail first
RAIL_H = 1.2
HOOK_RAIL_X = (HOOKS_X[0] + 0.5, HOOKS_X[1] - 0.5)
HOOK_RAIL_Z = (11.75, PANEL_Z[0])
PEG_W = 0.6
PEG_Z = (10.6, HOOK_RAIL_Z[0])
TICKET_PLANE = 11.15
TICKET_W_BLK = 0.25                            # a ticket's width in blocks (its height goes by its texels)

# The shelf under the panel and what stands on it
SHELF_X = (25.0, 43.5)
SHELF_Y = (6.0, 7.0)
SHELF_Z = (8.25, PANEL_Z[0])
LIP_Y = (7.0, 7.7)
LIP_Z = (8.25, 8.9)
BRACKETS = [(26.2, 27.4), (41.1, 42.3)]
BRACKET_Y = (4.6, 6.0)
BRACKET_Z = (8.6, 12.0)
INK_X, INK_Y, INK_Z = (33.6, 36.6), (7.0, 9.2), (9.6, 12.2)
INK_NECK_X, INK_NECK_Y, INK_NECK_Z = (34.3, 35.9), (9.2, 9.9), (10.3, 11.5)
INK_RIM_X, INK_RIM_Y, INK_RIM_Z = (34.1, 36.1), (9.9, 10.25), (10.1, 11.7)
QUILL_FOOT = (35.1, 9.7, 10.9)                # where the quill stands in the ink: X, Y, Z
QUILL_LEN, QUILL_T, QUILL_TURN = 5.5, 0.35, -22.5      # the turn is about Z: a minus turn leans it to the viewer's left
# The feather's vane stepped up its shaft: (from, to along the shaft, half width)
QUILL_VANE = [(1.7, 2.7, 0.55), (2.7, 3.9, 0.85), (3.9, 4.9, 0.7), (4.9, 5.5, 0.4)]
BELL_MID = (41.3, 10.45)
BELL_HW = 1.5                                  # the plate's half width: the bell is round, so the same across and back
BELL_X = (BELL_MID[0] - BELL_HW, BELL_MID[0] + BELL_HW)
BELL_Z = (BELL_MID[1] - BELL_HW, BELL_MID[1] + BELL_HW)
# The bell stepped up from a plate round a dome (in ten steps) to its knob: (half width, bottom, top) of each layer
BELL_DOME = (1.3, 7.3, 9.0)                    # the dome's half width, bottom and top
BELL_STEPS = 10
BELL_LAYERS = [(BELL_HW, 7.0, 7.3)]
for _k in range(BELL_STEPS):                    # straight in the lower sides, curving in over the shoulder: r = R sqrt(1 - t^3)
    _h = (BELL_DOME[2] - BELL_DOME[1]) / BELL_STEPS
    BELL_LAYERS.append((round(BELL_DOME[0] * math.sqrt(1 - ((_k + 0.5) / BELL_STEPS) ** 3), 3), BELL_DOME[1] + _k * _h, BELL_DOME[1] + (_k + 1) * _h))
BELL_LAYERS.append((0.3, 9.0, 9.45))
BELL_V = [(k / len(BELL_LAYERS), (k + 1) / len(BELL_LAYERS)) for k in range(len(BELL_LAYERS))]    # the slice of the brass each takes

# The crest, standing through the cap's ridge, and its little cap
PLAQUE_X = (16.0, 32.0)
PLAQUE_Y = (30.4, 37.4)
PLAQUE_Z = (12.4, 13.6)
HOOD_X = (15.4, 32.6)
HOOD_HALF = 2.1
HOOD_T = 0.6
HOOD_UNDER = PLAQUE_Y[1] + (PLAQUE_Z[1] - RIDGE_Z) * math.tan(math.radians(PITCH))   # the boards' underside at their ridge
HOOD_TOP = HOOD_UNDER + HOOD_T / math.cos(math.radians(PITCH))
HOOD_RIDGE_Y = (HOOD_TOP - 0.2, HOOD_TOP + 0.35)
HOOD_RIDGE_Z = (RIDGE_Z - 0.5, RIDGE_Z + 0.5)

# ================================================================ the face layout (blocks; u right, v down from the board's top left)

SLATE_U = (blocks(SLATE_X[0]), blocks(SLATE_X[1]))
HOOKS_U = (blocks(HOOKS_X[0]), blocks(HOOKS_X[1]))
PANEL_V = (blocks(32 - PANEL_Y[1]), blocks(32 - PANEL_Y[0]))
HEADER_V = (blocks(32 - HEADER_Y[1]), blocks(32 - HEADER_Y[0]))

ROWS = 8
ROWS_TOP = 0.125                               # where the first row starts, down from the slate's top
PAD_L, PAD_R, PAD_B = 0.04, 0.03, 0.06         # the foot's margin clears the chalk ledge, whose top edge covers the slate's lowest half pixel
ROW_PITCH = ((PANEL_V[1] - PANEL_V[0]) - ROWS_TOP - PAD_B) / ROWS

_EAVE_LOW = CAP_UNDER - (CAP_HALF / math.cos(math.radians(PITCH)) + 0.05) * math.sin(math.radians(PITCH))   # the cap's lower front edge


def hook_layout():
    """The pegs' places in blocks (u from the board's left, v down from its top, depth back from the cells' front)."""
    out = {'HOOK_DEPTH': blocks(PEG_Z[0] + (PEG_Z[1] - PEG_Z[0]) / 2), 'TICKET_DEPTH': blocks(TICKET_PLANE),
           'TICKET_BLOCKS': TICKET_W_BLK, 'HOOK_PITCH': blocks(HOOK_PITCH), 'HOOK_U0': blocks(HOOK_X[0])}
    for t in range(TIERS):
        out['HOOK_V%d' % t] = blocks(32 - PEG_Y[t])
    return out


# The numbers the board's renderer lays its writing and its tickets out by: groups of (what they are, {name: value}), in the order
# the Kotlin table gives them. Lengths are blocks (a block is 16 model pixels), u to the right and v down from the board's top left
# corner, depth back from the front of the cells; ints and floats are written as they are here.
LAYOUT_GROUPS = [
    ('Where the faces are: the slate that takes the rows (its left and right, top and foot), the panel of hooks (its left and right), the '
     'header plank (its top and foot), and how far back each lies. The writing is drawn just in front of them.',
     {'SLATE_U0': SLATE_U[0], 'SLATE_U1': SLATE_U[1], 'HOOKS_U0': HOOKS_U[0], 'HOOKS_U1': HOOKS_U[1],
      'PANEL_V0': PANEL_V[0], 'PANEL_V1': PANEL_V[1], 'HEADER_V0': HEADER_V[0], 'HEADER_V1': HEADER_V[1],
      'SLATE_DEPTH': blocks(PANEL_Z[0]), 'HEADER_DEPTH': blocks(HEADER_Z[0])}),
    ("The chalk ledge under the slate, where the merchant's chalk lies: its left and right, the v of its top and how far back its front is.",
     {'LEDGE_U0': blocks(LEDGE_X[0]), 'LEDGE_U1': blocks(LEDGE_X[1]), 'LEDGE_V': blocks(32 - LEDGE_Y[1]), 'LEDGE_DEPTH': blocks(LEDGE_Z[0])}),
    ("The slate: ROWS rows, the margins left, right and at the foot (clear of the ledge), the title's baseline and the v, down from the "
     "slate's top, where the first row starts, and a row's height. The size of the writing, in blocks to a pixel of the market board's "
     "lettering (market_board_letters): the title, a town's name, the age beside the hourglass and the number of tickets by the seal.",
     {'ROWS': ROWS, 'PAD_L': PAD_L, 'PAD_R': PAD_R, 'PAD_B': PAD_B, 'TITLE_BASE': 0.097, 'ROWS_TOP': ROWS_TOP, 'ROW_PITCH': ROW_PITCH,
      'S_TITLE': 0.0078, 'S_NAME': 0.0075, 'S_AGE': 0.0062, 'S_COUNT': 0.0062}),
    ("In a row (blocks from its top left, after PAD_L): the name's left and baseline; the chips' top, pitch and where the red ones and the "
     "green ones start (a chip is CHIP_BLOCKS square); the hourglass's left and middle and the blocks a texel of the atlas is drawn at "
     "(the pennant's and its pole's too); the age's left and baseline; the pennant's left and top; the seal's left and middle and the "
     "blocks a texel of it is drawn at; the number of tickets, its left and baseline, to the seal's right; and the top of a stale row's "
     "strike-through stroke, laid over the middle of the name.",
     {'NAME_X': 0.0, 'NAME_BASE': 0.060, 'CHIP_TOP': 0.067, 'CHIP_PITCH': 0.073, 'CHIP_RED_X': 0.0, 'CHIP_GREEN_X': 0.30,
      'HOURGLASS_X': 0.585, 'HOURGLASS_MID': 0.0685, 'TEXEL': 0.0035, 'AGE_X': 0.665, 'AGE_BASE': 0.081,
      'PENNANT_X': 0.915, 'PENNANT_TOP': 0.0115, 'SEAL_X': 1.0, 'SEAL_MID': 0.0685, 'SEAL_TEXEL': 0.0029,
      'COUNT_X': 1.078, 'COUNT_BASE': 0.0905, 'STRUCK_Y': 0.032}),
    ("The header plank's lettering (a town's name, or the board's, as the renderer likes): the baseline down from the plank's top and the "
     "size in blocks to a pixel of the lettering, and how far the cap's eave covers the plank's top seen straight on (keep the letters "
     "clear of it).",
     {'HEADER_BASE': 0.153, 'S_HEADER': 0.0105, 'HEADER_EAVE': round((HEADER_Y[1] - _EAVE_LOW) / 16.0, 3)}),
    ("Past FADE_NEAR blocks from the viewer the writing fades, and by FADE_FAR it is gone.",
     {'FADE_NEAR': 20.0, 'FADE_FAR': 24.0}),
    ("The pegs: the depth of a peg, the depth of the plane a ticket hangs in, a ticket's width (its height follows the paper's "
     "TICKET_W x TICKET_H texels), the u of the first peg and the pitch between them, and the v of the pegs on each of the two rails (HOOK_V0 the "
     "upper). A ticket hangs from its eyelet (TICKET_EYELET_X and _Y of the atlas) on the peg.",
     hook_layout()),
    ("The shelf under the panel: its left and right, the v of its top, and how far back its front and its back are; where the merchant's "
     "satchel is set down (the middle of its foot: u, v, depth); the brass bell (the middle of its plate: u, v of its middle, depth, and "
     "the radius of its plate); the inkwell (the middle of its foot).",
     {'SHELF_U0': blocks(SHELF_X[0]), 'SHELF_U1': blocks(SHELF_X[1]), 'SHELF_V': blocks(32 - SHELF_Y[1]),
      'SHELF_DEPTH0': blocks(SHELF_Z[0]), 'SHELF_DEPTH1': blocks(SHELF_Z[1]),
      'SATCHEL_U': blocks((SHELF_X[0] + 32.0) / 2 - 0.3), 'SATCHEL_V': blocks(32 - SHELF_Y[1]), 'SATCHEL_DEPTH': blocks((SHELF_Z[0] + SHELF_Z[1]) / 2),
      'BELL_U': blocks(BELL_MID[0]), 'BELL_V': blocks(32 - (BELL_LAYERS[0][1] + BELL_LAYERS[-1][2]) / 2), 'BELL_DEPTH': blocks(BELL_MID[1]),
      'BELL_R': blocks(BELL_LAYERS[0][0]),
      'INK_U': blocks((INK_X[0] + INK_X[1]) / 2), 'INK_V': blocks(32 - INK_Y[1]), 'INK_DEPTH': blocks((INK_Z[0] + INK_Z[1]) / 2)}),
    ("The crest over the cap's ridge: its left and right, its top and foot (v), and how far back its face is.",
     {'CREST_U0': blocks(PLAQUE_X[0]), 'CREST_U1': blocks(PLAQUE_X[1]), 'CREST_V0': blocks(32 - PLAQUE_Y[1]),
      'CREST_V1': blocks(32 - PLAQUE_Y[0]), 'CREST_DEPTH': blocks(PLAQUE_Z[0])}),
]
LAYOUT = {k: v for _, group in LAYOUT_GROUPS for k, v in group.items()}

# ================================================================ the cells' models

TEXTURES = {
    'front': 'guildmark:block/haul_board/front',
    'back': 'guildmark:block/haul_board/back',
    'wood': 'guildmark:block/haul_board/wood',
    'post': 'guildmark:block/haul_board/post',
    'cap': 'guildmark:block/haul_board/cap',
    'trim': 'guildmark:block/haul_board/trim',
    'props': 'guildmark:block/haul_board/props',
    'crest': 'guildmark:block/haul_board/crest',
    'particle': 'guildmark:block/haul_board/wood',
}


class Box:
    """A box in board coordinates: X0..X1 (viewer's left to right), Y0..Y1 (up), Z0..Z1 (front to back), with a material
    for its front (north) face, its back (south) face and the rest.

    `rot` = (axis, degrees, (ox, oy, oz)) turns it about a point of the board; a box turned about x is cut along the cells'
    edges like any other, one turned about another axis, or with `uv` (faces with their own uv, {face: (texture, uv)}), must
    lie in the one cell `cell` names. `above` stands over the cell's top (its cells take it whole in height)."""

    def __init__(self, X, Y, Z, front='#front', back='#back', side='#wood', top=None, rot=None, cell=None, uv=None, above=False,
                 top_uv=None, name=None):
        self.X, self.Y, self.Z = X, Y, Z
        self.front, self.back, self.side, self.top = front, back, side, top or side
        self.rot, self.cell, self.uv, self.above, self.top_uv, self.name = rot, cell, uv or {}, above, top_uv, name


def cell_of(X, Y):
    """The cell (col, row) a box starting at X[0], Y[0] belongs to: col -1, 0, 1 for X 0..16, 16..32, 32..48, row 0 or 1."""
    return (int(X[0] // 16) - 1, int(Y[0] // 16))


def prop(X, Y, Z, side, top, north=None, **kw):
    """A small thing painted in the props texture, as a list of boxes (one, or two where it stands over a cell's edge: a box with
    its own uv lies in one cell): every side takes the region `side` (u0, v0, u1, v1 in uv units), top and bottom `top`, and
    its front `north` if given."""
    uv = {d: ('#props', side) for d in ('north', 'south', 'east', 'west')}
    uv.update({d: ('#props', top) for d in ('up', 'down')})
    if north is not None:
        uv['north'] = ('#props', north)
    cuts = [X[0]] + [c for c in (16.0, 32.0) if X[0] + 1e-6 < c < X[1] - 1e-6] + [X[1]]
    name = kw.pop('name', None)
    return [Box((a, b), Y, Z, uv=uv, cell=cell_of((a, b), Y), name=name if len(cuts) == 2 else '%s %s' % (name, 'ab'[k]), **kw)
            for k, (a, b) in enumerate(zip(cuts, cuts[1:]))]


# Where in the props texture (64x64 texels, 4 to a uv unit) each thing is painted: x0, y0, x1, y1 in texels
PROPS = {
    'brass': (0, 0, 32, 32), 'glass': (32, 0, 64, 32), 'ink': (0, 32, 16, 48), 'vane': (16, 32, 24, 40), 'edge': (24, 32, 28, 56),
    'pewter': (32, 32, 48, 48), 'knob': (48, 32, 56, 40),
}


def puv(name, x0=0.0, y0=0.0, x1=1.0, y1=1.0):
    """A uv (in 0..16 units) over the fraction x0..x1, y0..y1 of a region of the props texture."""
    a, b, c, d = PROPS[name]
    return tuple(round(v / 4.0, 4) for v in (a + (c - a) * x0, b + (d - b) * y0, a + (c - a) * x1, b + (d - b) * y1))


def boxes():
    out = []
    for name, (x0, x1) in (('post left', POST_L), ('post right', POST_R)):
        out.append(Box((x0, x1), POST_Y, POST_Z, side='#post', name=name))
    for name, (x0, x1) in (('collar left', COLLAR_L), ('collar right', COLLAR_R)):
        out.append(Box((x0, x1), COLLAR_Y, COLLAR_Z, side='#trim_stone', back='#back', name=name))
    out.append(Box(RAIL_X, RAIL_Y, RAIL_Z, name='rail'))
    out.append(Box(LEDGE_X, LEDGE_Y, LEDGE_Z, name='chalk ledge'))
    out.append(Box(PANEL_X, PANEL_Y, PANEL_Z, name='panel'))
    out.append(Box(MULLION_X, PANEL_Y, MULLION_Z, side='#post', name='mullion'))
    out.append(Box(HEADER_X, HEADER_Y, HEADER_Z, name='header plank'))
    for k, (y0, y1) in enumerate(BATTENS_Y):
        out.append(Box(BATTEN_X, (y0, y1), BATTEN_Z, front='#wood', name='batten %d' % (k + 1)))
    out.append(Box(HEADER_X, RIDGE_BEAM_Y, RIDGE_BEAM_Z, front='#wood', back='#wood', name='ridge beam'))
    for mirror in (False, True):
        for k, (x0, x1, y0, y1, z0, z1) in enumerate(STRAPS):
            if mirror:
                x0, x1 = 48 - x1, 48 - x0
            out.append(Box((x0, x1), (y0, y1), (z0, z1), back='#trim_iron', side='#trim_iron',
                           name='strap %s %d' % ('right' if mirror else 'left', k + 1)))
    for k, (x0, x1, y0, y1, z0, z1) in enumerate(STUBS):
        out.append(Box((x0, x1), (y0, y1), (z0, z1), front='#trim_chalk', back='#trim_chalk', side='#trim_chalk', name='chalk %d' % (k + 1)))
    # The cap: two boards pitched from a ridge over the header's middle, resting on its top edges, and the ridge cap over their seam
    L = CAP_HALF / math.cos(math.radians(PITCH)) + 0.05
    out.append(Box(HEADER_X, (CAP_UNDER, CAP_UNDER + CAP_T), (RIDGE_Z - L, RIDGE_Z + 0.02), front='#wood', back='#wood', top='#cap',
                   rot=('x', -PITCH, (8, CAP_UNDER, RIDGE_Z)), top_uv='front', name='cap front'))
    out.append(Box(HEADER_X, (CAP_UNDER, CAP_UNDER + CAP_T), (RIDGE_Z - 0.02, RIDGE_Z + L), front='#wood', back='#wood', top='#cap',
                   rot=('x', PITCH, (8, CAP_UNDER, RIDGE_Z)), top_uv='back', name='cap back'))
    out.append(Box(HEADER_X, RIDGE_CAP_Y, RIDGE_CAP_Z, front='#trim_iron', back='#trim_iron', side='#trim_iron', name='ridge cap'))

    # The hooks: a rail of oak, painted in the front painting, and the iron pegs that stand out of it with a head on each
    for t in range(TIERS):
        c = PEG_Y[t]
        out.append(Box(HOOK_RAIL_X, (c - RAIL_H / 2, c + RAIL_H / 2), HOOK_RAIL_Z, front='#front', back='#wood', side='#wood',
                       name='hook rail %d' % (t + 1)))
        for k, x in enumerate(HOOK_X):
            pew, shade = puv('pewter'), puv('pewter', 0.55, 0.55, 1.0, 1.0)
            out.extend(prop((x - PEG_W / 2, x + PEG_W / 2), (c - PEG_W / 2, c + PEG_W / 2), PEG_Z, shade, shade, name='peg %d.%d' % (t + 1, k + 1)))
            h = PEG_W / 2 + 0.2
            out.extend(prop((x - h, x + h), (c - h, c + h), (PEG_Z[0], PEG_Z[0] + 0.4), shade, shade, north=pew, name='peg head %d.%d' % (t + 1, k + 1)))
    # The shelf: its board, the lip along its front and the two brackets under it
    wood = dict(front='#wood', back='#wood', side='#wood')
    out.append(Box(SHELF_X, SHELF_Y, SHELF_Z, name='shelf', **wood))
    out.append(Box(SHELF_X, LIP_Y, LIP_Z, name='shelf lip', **wood))
    for k, (x0, x1) in enumerate(BRACKETS):
        out.append(Box((x0, x1), BRACKET_Y, BRACKET_Z, front='#trim_iron', back='#trim_iron', side='#trim_iron', name='bracket %d' % (k + 1)))
    # The inkwell: a squat glass bottle, its neck, a pewter collar with the black ink in it, and the quill standing in it
    out.extend(prop(INK_X, INK_Y, INK_Z, puv('glass'), puv('glass', 0.1, 0.1, 0.5, 0.5), name='inkwell'))
    out.extend(prop(INK_NECK_X, INK_NECK_Y, INK_NECK_Z, puv('glass', 0, 0.55, 1, 0.8), puv('glass', 0.1, 0.1, 0.5, 0.5), name='inkwell neck'))
    out.extend(prop(INK_RIM_X, INK_RIM_Y, INK_RIM_Z, puv('pewter'), puv('ink'), name='inkwell collar'))
    qx, qy, qz = QUILL_FOOT
    turn = ('z', QUILL_TURN, (qx, qy, qz))
    for k, (y0, y1, hw, t, region) in enumerate([(0.0, QUILL_LEN, 0.2, QUILL_T, 'edge')] + [(a, b, w, 0.3, 'vane') for (a, b, w) in QUILL_VANE]):
        face = ('#props', puv(region))
        out.append(Box((qx - hw, qx + hw), (qy + y0, qy + y1), (qz - t / 2, qz + t / 2), cell=cell_of((qx, qx), (qy, qy)), rot=turn,
                       uv={d: face for d in ('north', 'south', 'east', 'west', 'up', 'down')}, name='quill shaft' if k == 0 else 'quill vane %d' % k))
    # The desk bell, stepped up from a plate to its knob
    cx, cz = BELL_MID
    for k, (hw, y0, y1) in enumerate(BELL_LAYERS):
        side = puv('brass', 0, BELL_V[k][0], 1, BELL_V[k][1])
        top = puv('knob') if k == len(BELL_LAYERS) - 1 else puv('brass', 0.0, 0.0, 0.5, 0.5)
        out.extend(prop((cx - hw, cx + hw), (y0, y1), (cz - hw, cz + hw), side, top, name='bell %d' % (k + 1)))
    # The crest, standing through the cap's ridge, and its cap over it
    out.append(Box(PLAQUE_X, PLAQUE_Y, PLAQUE_Z, front='#crest', back='#wood', side='#wood', above=True, cell=(0, 1),
                   uv={'north': ('#crest', (0, 0, 16, 16))}, name='crest'))
    Lh = HOOD_HALF / math.cos(math.radians(PITCH)) + 0.05
    out.append(Box(HOOD_X, (HOOD_UNDER, HOOD_UNDER + HOOD_T), (RIDGE_Z - Lh, RIDGE_Z + 0.02), front='#wood', back='#wood', top='#cap',
                   rot=('x', -PITCH, (8, HOOD_UNDER, RIDGE_Z)), top_uv='front', name='crest cap front'))
    out.append(Box(HOOD_X, (HOOD_UNDER, HOOD_UNDER + HOOD_T), (RIDGE_Z - 0.02, RIDGE_Z + Lh), front='#wood', back='#wood', top='#cap',
                   rot=('x', PITCH, (8, HOOD_UNDER, RIDGE_Z)), top_uv='back', name='crest cap back'))
    out.append(Box(HOOD_X, HOOD_RIDGE_Y, HOOD_RIDGE_Z, front='#trim_iron', back='#trim_iron', side='#trim_iron', name='crest ridge cap'))
    return out


def fit_uv(uv):
    """A face's uv of a tiling material brought inside the texture: a hair outside (a pitched board's rounding) is clamped, further
    out (a box high over the cell, whose uv run on with its height) moved by whole tiles, as market_board_art's in_tile does."""
    out, moved = [], None
    for k in (0, 1):
        a, b = uv[k], uv[k + 2]
        if min(a, b) >= -0.06 and max(a, b) <= 16.06:
            out.append((min(16.0, max(0.0, a)), min(16.0, max(0.0, b))))
        else:
            moved = moved or MB.in_tile(uv)
            out.append((moved[k], moved[k + 2]))
    return (round(out[0][0], 4), round(out[1][0], 4), round(out[0][1], 4), round(out[1][1], 4))


def tiled_face(mat, d, frm, to):
    """The face of a box on the material `mat` (a texture, or one of the trim's quadrants), its uv inside the texture."""
    t, uv = MB.tex_and_uv(mat, d, frm, to)
    return MB.face(uv if t == '#trim' else fit_uv(uv), t)


def cell_elements(col, row, names=None):
    """The elements of one cell, from every box that reaches into it, in the cell's own coordinates; the boxes' names go into the
    list `names`, if given, one for each element."""
    off_x = (col + 1) * 16
    off_y = row * 16
    elements = []
    for b in boxes():
        whole = b.cell is not None
        if whole:
            if b.cell != (col, row):
                continue
            X0, X1, Y0, Y1 = b.X[0], b.X[1], b.Y[0], b.Y[1]
            assert off_x - 1e-6 <= X0 and X1 <= off_x + 16 + 1e-6, 'a box with its own uv lies over a cell edge: %s' % (b.X,)
        else:
            X0, X1 = max(b.X[0], off_x), min(b.X[1], off_x + 16)
            if b.rot is None and not b.above:
                Y0, Y1 = max(b.Y[0], off_y), min(b.Y[1], off_y + 16)
            else:
                Y0, Y1 = b.Y
                if row != 1:
                    continue                    # a pitched board, and what stands over the board, belong to the top row
        if X1 - X0 <= 1e-6 or Y1 - Y0 <= 1e-6:
            continue
        frm = [16 - (X1 - off_x), Y0 - off_y, b.Z[0]]
        to = [16 - (X0 - off_x), Y1 - off_y, b.Z[1]]
        faces = {}
        clip = not whole
        internal_left = clip and X0 > b.X[0] + 1e-6        # the box goes on into the cell to the viewer's left
        internal_right = clip and X1 < b.X[1] - 1e-6
        internal_down = clip and b.rot is None and not b.above and Y0 > b.Y[0] + 1e-6
        internal_up = clip and b.rot is None and not b.above and Y1 < b.Y[1] - 1e-6
        for d in ('north', 'south', 'east', 'west', 'up', 'down'):
            # East is the viewer's left as seen from the front (models face north)
            if d == 'east' and internal_left or d == 'west' and internal_right:
                continue
            if d == 'up' and internal_up or d == 'down' and internal_down:
                continue
            if d in b.uv:
                tex, uv = b.uv[d]
                faces[d] = MB.face(uv, tex)
            elif d == 'north':
                if b.front == '#front':
                    faces[d] = MB.face((X0 / 3, (32 - Y1) / 2, X1 / 3, (32 - Y0) / 2), '#front')
                else:
                    faces[d] = tiled_face(b.front, d, frm, to)
            elif d == 'south':
                if b.back == '#back':
                    faces[d] = MB.face(((48 - X1) / 3, (32 - Y1) / 2, (48 - X0) / 3, (32 - Y0) / 2), '#back')
                else:
                    faces[d] = tiled_face(b.back, d, frm, to)
            elif d == 'up' and b.top == '#cap':
                # Tile rows run along the board; the eave's edge of each row is its lit lower edge
                u0, u1 = frm[0], to[0]
                if b.top_uv == 'front':
                    faces[d] = MB.face((u0, 12, u1, 16), '#cap', rotation=180)
                else:
                    faces[d] = MB.face((u0, 12, u1, 16), '#cap')
            else:
                faces[d] = tiled_face(b.top if d == 'up' else b.side, d, frm, to)
        if b.above and not whole:
            for d, f in faces.items():
                f['uv'] = list(MB.in_tile(f['uv']))
        e = {'from': [round(v, 4) for v in frm], 'to': [round(v, 4) for v in to], 'faces': faces}
        if b.rot is not None:
            axis, angle, (ox, oy, oz) = b.rot
            e['rotation'] = {'origin': [round(16 - (ox - off_x), 4), round(oy - off_y, 4), round(oz, 4)], 'axis': axis, 'angle': angle}
        elements.append(e)
        if names is not None:
            names.append(b.name or 'element')
    return elements


def model(part):
    col, row = PARTS[part]
    return {'textures': dict(TEXTURES), 'elements': cell_elements(col, row)}


# ================================================================ the Blockbench file

BB_LIFT = -7.0                                  # the board's feet sit at y = -7 in the file: its crest's cap then stays inside Blockbench's -16..32
BB_TEXTURES = ('front', 'back', 'wood', 'post', 'cap', 'trim', 'props', 'crest')
BB_FACES = ('north', 'east', 'south', 'west', 'up', 'down')


def steady_uuid(*key):
    """A uuid made from its key, so a rerun writes the same file."""
    h = hashlib.sha1('/'.join(str(k) for k in key).encode()).hexdigest()
    return '%s-%s-%s-%s-%s' % (h[:8], h[8:12], h[12:16], h[16:20], h[20:32])


def png_data(im):
    buf = io.BytesIO()
    im.save(buf, 'PNG')
    return 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()


def bbmodel(images, name='haul_board'):
    """The whole board as one Blockbench project (the Java Block format), the six cells' elements put where they stand: the
    board's left at x = 32 and its right at -16 (as the market board's preview model has it), its feet at y = BB_LIFT (-7), each
    cell a group and each box named. [images] is {texture name: image} of BB_TEXTURES (the crest as one frame). Blockbench
    keeps the Java format's cubes inside -16..32, hence the lift."""
    textures = []
    for i, k in enumerate(BB_TEXTURES):
        w, h = images[k].size
        textures.append({'path': '', 'name': k + '.png', 'folder': '', 'namespace': 'guildmark', 'id': str(i), 'width': w, 'height': h,
                         'uv_width': 16, 'uv_height': 16, 'particle': k == 'wood', 'render_mode': 'default', 'render_sides': 'auto',
                         'frame_time': 1, 'frame_order_type': 'loop', 'frame_order': '', 'frame_interpolate': False, 'visible': True,
                         'internal': True, 'saved': False, 'uuid': steady_uuid(name, 'texture', k), 'relative_path': '',
                         'source': png_data(images[k].convert('RGBA'))})
    index = {'#' + k: i for i, k in enumerate(BB_TEXTURES)}
    elements, outliner = [], []
    for part, (col, row) in PARTS.items():
        names = []
        els = cell_elements(col, row, names)
        children = []
        for n, (e, label) in enumerate(zip(els, names)):
            dx, dy = -col * 16, row * 16 + BB_LIFT
            frm = [e['from'][0] + dx, e['from'][1] + dy, e['from'][2]]
            to = [e['to'][0] + dx, e['to'][1] + dy, e['to'][2]]
            rot, origin = [0, 0, 0], [(frm[0] + to[0]) / 2, (frm[1] + to[1]) / 2, (frm[2] + to[2]) / 2]
            if 'rotation' in e:
                r = e['rotation']
                rot = [r['angle'] if r['axis'] == a else 0 for a in 'xyz']
                origin = [r['origin'][0] + dx, r['origin'][1] + dy, r['origin'][2]]
            faces = {}
            for d in BB_FACES:
                f = e['faces'].get(d)
                if f is None:
                    faces[d] = {'uv': [0, 0, 0, 0], 'texture': None}
                else:
                    faces[d] = {'uv': f['uv'], 'texture': index[f['texture']]}
                    if f.get('rotation'):
                        faces[d]['rotation'] = f['rotation']
            uid = steady_uuid(name, part, n, label)
            elements.append({'name': label, 'box_uv': False, 'rescale': False, 'locked': False, 'light_emission': 0,
                             'render_order': 'default', 'allow_mirror_modeling': True,
                             'from': [round(v, 4) for v in frm], 'to': [round(v, 4) for v in to], 'autouv': 0, 'color': 0,
                             'origin': [round(v, 4) for v in origin], 'rotation': rot, 'faces': faces, 'type': 'cube', 'uuid': uid})
            children.append(uid)
        outliner.append({'name': part, 'origin': [0, 0, 0], 'color': 0, 'uuid': steady_uuid(name, 'group', part), 'export': True,
                         'mirror_uv': False, 'isOpen': False, 'locked': False, 'visibility': True, 'autouv': 0, 'children': children})
    return {'meta': {'format_version': '4.10', 'model_format': 'java_block', 'box_uv': False}, 'name': name, 'model_identifier': '',
            'visible_box': [1, 1, 0], 'variable_placeholders': '', 'variable_placeholder_buttons': [], 'unhandled_root_fields': {},
            'resolution': {'width': 16, 'height': 16}, 'elements': elements, 'outliner': outliner, 'textures': textures}
