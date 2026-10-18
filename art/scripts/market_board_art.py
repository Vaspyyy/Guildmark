"""The capitals' market board: a dark slate in a sturdy dark wooden frame, standing free on two posts sunk in stone
collars, with a carved header plank under a little pitched cap of slate tiles. The slate on the left two thirds takes
the crier's chalk (the prices, drawn live by client/MarketBoardRenderer.kt); a narrow panel of planks on the right,
behind a mullion, takes the news slips. Iron straps hold the frame's corners, a ledge under the slate keeps two stubs
of chalk, and the back is finished: planks, two battens bolted across the posts, and the guild's crest burnt into the
back of the header. Over the ridge, between two posts on a plinth, the medallion: a five-sided drum of carved oak on an
iron axle, a gilt roundel on each face with the town's times painted and carved in it (a bare branch in snow for hard
times, a thin sheaf for lean, level scales for steady, a full sheaf for good, a sheaf with ribbons under a sun for
thriving), turned by the renderer to the times the board shows.

    python3 -B art/scripts/market_board_art.py src/main/resources/assets/guildmark [preview-dir [minecraft client jar]]

With a preview dir it writes the medallion's previews (market_board_preview.medallion_previews); with the jar as well,
the face filled in and the crier at work (the goods' icons come from the jar).

Writes, all at 32x with the HD art kit (hdkit.py):
  textures/block/market_board_{front,back,wood,post,cap,trim}.png  the board's painting and materials
  textures/block/market_board/letters.png                           the chalk and ink lettering atlas (see
                                                                     market_board_letters.py), the chalk stick, dust
  textures/block/market_board/slips.png                             the papers a slip can be on (in Slip.Kind's order:
                                                                     trouble, good, price, word, year, talk, fortune,
                                                                     works), a tack, shadows, an order's ticket (front
                                                                     and back) and its stamp
  textures/block/market_board/medallion.png                         the medallion's five faces and its end (80x320)
  textures/item/market_board.png                                    the item icon
  models/block/market_board_<part>.json                             the six cells (the blockstate already picks them;
                                                                     the top middle carries the medallion's frame)
  models/item/market_board.json                                     points at the icon
and the Kotlin tables the renderer draws from, src/main/kotlin/.../client/MarketBoardArt.kt (GENERATED: every
atlas cell, every glyph's pieces, the face's layout numbers and the medallion's faces and turn, so the game and the
previews lay the board out the same way).

Coordinates: the board's own, in model pixels (16 to a block), as seen from the front: X 0..48 from the left edge,
Y 0..32 up from the ground, Z 0..16 from the front of the cells back (the cells are authored facing north, so the
front is the north side). The face layout is in blocks, u to the right and v down from the board's top left corner.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import (PAL, Canvas, grid, vnoise, fbm, rand, tone, mix, ramp, bands, poly, disc, ellipse, rect, blur, edge,
                   grow, dist_in, light, outline, glint, bevel, path_dist, seg_dist, cavity, shift)
import market_board_letters as ML
import talk_slip_art as TALK

# ================================================================ geometry (model px)

POST_L = (0.5, 3.5)
POST_R = (44.5, 47.5)
POST_Y = (0.0, 27.0)
POST_Z = (11.5, 14.75)
COLLAR_L = (0.0, 4.0)
COLLAR_R = (44.0, 48.0)
COLLAR_Y = (0.0, 1.0)
COLLAR_Z = (11.0, 15.25)
RAIL_X = (3.5, 44.5)
RAIL_Y = (4.0, 6.5)
RAIL_Z = (12.0, 14.75)
LEDGE_X = (4.5, 32.0)
LEDGE_Y = (6.0, 7.0)
LEDGE_Z = (10.75, 12.0)
PANEL_X = (3.5, 44.5)
PANEL_Y = (6.5, 27.0)
PANEL_Z = (12.75, 14.75)
SLATE_X = (3.5, 33.0)
MULLION_X = (33.0, 34.5)
MULLION_Z = (12.0, 14.5)
NEWS_X = (34.5, 44.5)
HEADER_X = (0.0, 48.0)
HEADER_Y = (27.0, 30.0)
HEADER_Z = (11.25, 14.75)
BATTEN_X = (0.5, 47.5)
BATTENS_Y = [(9.0, 10.5), (22.5, 24.0)]
BATTEN_Z = (14.75, 15.75)
# The cap: two boards pitched at 22.5 degrees from a ridge over the header's middle, resting on its top edges
PITCH = 22.5
RIDGE_Z = 13.0
CAP_HALF = 2.75
CAP_T = 0.75
CAP_UNDER = HEADER_Y[1] + (RIDGE_Z - HEADER_Z[0]) * math.tan(math.radians(PITCH))   # the boards' underside at the ridge
RIDGE_BEAM_Y = (HEADER_Y[1], CAP_UNDER)
RIDGE_BEAM_Z = (12.5, 13.5)
CAP_TOP = CAP_UNDER + CAP_T / math.cos(math.radians(PITCH))
RIDGE_CAP_Y = (CAP_TOP - 0.25, min(32.0, CAP_TOP + 0.4))
RIDGE_CAP_Z = (12.45, 13.55)
# Iron straps at the frame's four corners: (x0, x1, y0, y1, z0, z1), the left ones (the right ones are mirrored)
STRAPS = [
    (1.0, 3.0, 4.0, 10.0, 11.25, 11.5),      # down the post at the rail
    (3.0, 3.5, 4.5, 6.0, 11.25, 11.5),       # across the post's edge
    (3.5, 8.5, 4.5, 6.0, 11.75, 12.0),       # along the rail
    (1.0, 3.0, 21.5, 27.0, 11.25, 11.5),     # up the post under the header
    (1.0, 8.5, 27.0, 28.5, 11.0, 11.25),     # along the header
]
# Two stubs of chalk on the ledge
STUBS = [(9.0, 10.75, 7.0, 7.5, 11.0, 11.5), (13.5, 14.5, 7.0, 7.5, 11.25, 11.75)]

# The medallion at the board's head: a five-sided drum of carved oak on an iron axle, held between two posts on a plinth
# over the ridge, a face carved and painted for each of the town's times (economy/fortune/Fortunes.kt's Times, in its
# order), the one showing turned to the front. The renderer draws the drum and turns it; the plinth, the posts and the
# axle's ends are the top middle cell's (they stand over the board, above its 32 px).
MED_TIMES = ['hard', 'lean', 'steady', 'good', 'thriving']
MED_SIDES = 5
MED_TPX = 8                                                # texels to a model px on its faces and ends
MED_FACE = (64, 42)                                        # a face's texels: 8 px along the drum, 5.25 px round it
MED_LEN = MED_FACE[0] / MED_TPX
MED_FACE_H = MED_FACE[1] / MED_TPX
MED_R = MED_FACE_H / (2 * math.sin(math.pi / MED_SIDES))   # its edges' distance from the axle
MED_APOTHEM = MED_R * math.cos(math.pi / MED_SIDES)        # its faces'
MED_X = (24.0 - MED_LEN / 2, 24.0 + MED_LEN / 2)          # its ends
PLINTH_X, PLINTH_Y, PLINTH_Z = (18.4, 29.6), (30.9, 32.5), (11.7, 14.3)
MED_Y = PLINTH_Y[1] + MED_R + 0.35                         # the axle: this high, over the ridge
MED_Z = RIDGE_Z
MED_POSTS = [(18.6, 19.9), (28.1, 29.4)]
MED_POST_Y = (PLINTH_Y[1], MED_Y + 1.5)
MED_POST_Z = (12.3, 13.7)
MED_CAP = 72                                               # an end's texels: a pentagon in a square 9 px across
MED_SPRITE = (80, 320)

# The orders' tickets hang along the rail, between the iron straps at its ends: how many, and the tag's texels (its
# eyelet's middle among them); the stamp put on one taken
TICKETS_X = (8.5, 39.5)
TICKET_SLOTS = 6
TICKET_PX = (32, 36)
EYELET = (16.0, 4.0)
STAMP_PX = (32, 14)

PARTS = {'bottom_left': (-1, 0), 'bottom_middle': (0, 0), 'bottom_right': (1, 0),
         'top_left': (-1, 1), 'top_middle': (0, 1), 'top_right': (1, 1)}

# The front and back paintings: 2 texels to a model pixel over the whole 48x32
PW, PH = 96, 64


def tx(X):
    return int(round(X * 2))


def ty(Y):
    return int(round((32 - Y) * 2))


def R(X0, X1, Y0, Y1):
    """The painting's mask of a board rectangle (front view)."""
    return rect(tx(X0), ty(Y1), tx(X1), ty(Y0), PW, PH)


def RB(X0, X1, Y0, Y1):
    """The back painting's mask of a board rectangle given in front coordinates (seen from behind, mirrored)."""
    return rect(tx(48 - X1), ty(Y1), tx(48 - X0), ty(Y0), PW, PH)


# ================================================================ the face layout (blocks; u right, v down)

def blocks(px):
    return px / 16.0


SLATE_U = (blocks(SLATE_X[0]), blocks(SLATE_X[1]))
NEWS_U = (blocks(NEWS_X[0]), blocks(NEWS_X[1]))
PANEL_V = (blocks(32 - PANEL_Y[1]), blocks(32 - PANEL_Y[0]))
HEADER_V = (blocks(32 - HEADER_Y[1]), blocks(32 - HEADER_Y[0]))

LAYOUT = {
    # Where the faces are, in blocks from the board's top left (u to the right, v down), and how far back they sit
    # (blocks behind the cells' front)
    'SLATE_U0': SLATE_U[0], 'SLATE_U1': SLATE_U[1], 'NEWS_U0': NEWS_U[0], 'NEWS_U1': NEWS_U[1],
    'PANEL_V0': PANEL_V[0], 'PANEL_V1': PANEL_V[1], 'HEADER_V0': HEADER_V[0], 'HEADER_V1': HEADER_V[1],
    'SLATE_DEPTH': blocks(PANEL_Z[0]), 'HEADER_DEPTH': blocks(HEADER_Z[0]),
    'LEDGE_U0': blocks(LEDGE_X[0]), 'LEDGE_U1': blocks(LEDGE_X[1]), 'LEDGE_V': blocks(32 - LEDGE_Y[1]),
    'LEDGE_DEPTH': blocks(LEDGE_Z[0]),
    # How big the writing is, in blocks to a font pixel: the prices, the small "for", the sections' headings, the
    # title ("Prices at noon"), the key to the coin, the town's name, a slip's headline and its words, the chalk
    'S_PRICE': 0.0090, 'S_FOR': 0.0066, 'S_HEAD': 0.0078, 'S_TITLE': 0.0088, 'S_LEGEND': 0.0064, 'S_TOWN': 0.0128,
    'S_INK': 0.0050, 'S_INK_TEXT': 0.0035, 'S_STICK': 0.0085,
    # The slate: its margins, the title's baseline, where the rows start, a heading's height and its baseline, the
    # gap between two sections in a column (an item's row and a column's width are worked out below)
    'PAD_L': 0.045, 'PAD_R': 0.025, 'PAD_B': 0.045, 'TITLE_BASE': 0.097, 'ROWS_TOP': 0.135, 'HEAD_PITCH': 0.074,
    'HEAD_BASE': 0.058, 'SECTION_GAP': 0.03,
    # One good's row, in font px at S_PRICE from its column's left and its middle: the icon's middle and size, the
    # count's right edge, the small "for", the gap after it before the Marks, the gap before the coin and before the
    # arrow after it, where "none" starts, and how far below the middle the baseline is
    'ICON_X': 5.4, 'ICON_SIZE': 10.4, 'COUNT_RIGHT': 23.0, 'FOR_X': 25.4, 'FOR_GAP': 1.8, 'COIN_GAP': 0.9,
    'ARROW_GAP': 1.6, 'NONE_X': 13.0, 'TEXT_DROP': 3.5,
    # The town's name, gilt on the header plank, centred and no wider than this
    'TOWN_U': 1.5, 'TOWN_MAX_W': 2.2,
    # The slips, in blocks from the news panel's top left: size, the four slots, the margin of the writing, where the
    # headline's capitals start, where the tack goes in and its size; line pitches in font px of their own sizes
    'SLIP_W': 0.565, 'SLIP_H': 0.28, 'SLOT_TOP': 0.028, 'SLOT_PITCH': 0.312, 'SLIP_MARGIN': 0.03,
    'SLIP_HEAD_TOP': 0.056, 'PIN_DOWN': 0.024, 'TACK_SIZE': 0.042, 'HEAD_LINE': 10.5, 'TEXT_LINE': 9.8,
    # Animation, in ticks: an old line rubbed out, a new one's chalking (no faster or slower than these, at about
    # these speeds in font px a tick, drawing and between strokes), fresh chalk's brightness, the rag's smear,
    # a slip flying up (landing at LAND), a slip taken down
    'RUB': 4.0, 'DRAW_MIN': 6.0, 'DRAW_MAX': 12.0, 'DRAW_SPEED': 9.0, 'TRAVEL_SPEED': 24.0, 'FRESH': 70.0,
    'SMEAR': 160.0, 'FLUTTER': 36.0, 'LAND': 13.0, 'FALL': 22.0,
    # Past this many blocks the writing fades, and is gone by the next
    'FADE_NEAR': 20.0, 'FADE_FAR': 24.0,
    # The orders' tickets, hung on the rail's face below the ledge: a ticket's width (its height goes by its texels),
    # the tags' top and how far back the rail's face is; on a ticket, in blocks from its top left: the head's
    # baseline and size, the good's icon's middle and size, the count's left, baseline and size, the business's last
    # baseline and size, the stamp's middle, width and turn (radians) and its word's size (the stamp goes over the
    # count); how far the air swings a ticket on its tack (radians), at most
    'TICKET_W': 0.27, 'TICKET_TOP': 1.645, 'TICKET_DEPTH': blocks(RAIL_Z[0]),
    'TICKET_HEAD_BASE': 0.094, 'S_TICKET_HEAD': 0.0042, 'TICKET_ICON_U': 0.072, 'TICKET_ICON_V': 0.166,
    'TICKET_ICON': 0.075, 'TICKET_COUNT_U': 0.124, 'TICKET_COUNT_BASE': 0.191, 'S_TICKET_COUNT': 0.0068,
    'TICKET_SHOP_BASE': 0.262, 'S_TICKET_SHOP': 0.0031, 'STAMP_U': 0.14, 'STAMP_V': 0.166, 'STAMP_W': 0.232,
    'STAMP_TURN': -0.21, 'S_STAMP': 0.0056, 'SWAY': 0.05,
}
LAYOUT['TICKET_H'] = LAYOUT['TICKET_W'] * TICKET_PX[1] / TICKET_PX[0]
LAYOUT['TICKET_PIN'] = LAYOUT['TICKET_H'] * EYELET[1] / TICKET_PX[1]
LAYOUT['TICKET_PITCH'] = blocks(TICKETS_X[1] - TICKETS_X[0]) / TICKET_SLOTS
LAYOUT['TICKET_U0'] = blocks(TICKETS_X[0]) + (LAYOUT['TICKET_PITCH'] - LAYOUT['TICKET_W']) / 2
# A ticket's margins: where the red rule round it is, inside its edges
LAYOUT['TICKET_MARGIN'] = LAYOUT['TICKET_W'] * 4.0 / TICKET_PX[0]
LAYOUT['ITEM_PITCH'] = ((PANEL_V[1] - PANEL_V[0]) - LAYOUT['ROWS_TOP'] - LAYOUT['PAD_B'] - LAYOUT['HEAD_PITCH']) / 10.0
LAYOUT['COL_W'] = ((SLATE_U[1] - SLATE_U[0]) - LAYOUT['PAD_L'] - LAYOUT['PAD_R']) / 3.0
# The key to the coin goes at the foot of the first column, on the last row's line (from the panel's top)
LAYOUT['LEGEND_BASE'] = ((PANEL_V[1] - PANEL_V[0]) - LAYOUT['PAD_B'] - 0.5 * LAYOUT['ITEM_PITCH']
                         + LAYOUT['TEXT_DROP'] * LAYOUT['S_PRICE'])
# The medallion, laid out as the face is (u from the board's left, v down from its top, so over the top v is less than
# 0; depth back from the cells' front): its ends, its axle, how far its edges and its faces are from the axle, half its
# end's square (blocks). How it turns, as a heavy drum let go onto its catch: a spring's damping, its speed in radians a
# tick for a turn of one face (a turn of n faces is slower by the root of n), and the ticks a turn of one face takes to
# come still; how far it stirs on its axle at rest in a calm (radians; the wind adds to it).
MED_ZETA, MED_OMEGA = 0.62, 0.24
LAYOUT.update({
    'MED_U0': blocks(MED_X[0]), 'MED_U1': blocks(MED_X[1]), 'MED_V': blocks(32 - MED_Y), 'MED_DEPTH': blocks(MED_Z),
    'MED_R': blocks(MED_R), 'MED_APOTHEM': blocks(MED_APOTHEM), 'MED_CAP_HALF': blocks(MED_CAP / 2 / MED_TPX),
    'MED_ZETA': MED_ZETA, 'MED_OMEGA': MED_OMEGA, 'MED_STILL': math.log(500) / (MED_ZETA * MED_OMEGA),
    'MED_ROCK': math.radians(0.8),
})


def med_turn(age, faces):
    """How far through a turn of `faces` faces the medallion is `age` ticks after it began (MarketBoardArt.medallionTurn)."""
    faces = max(1.0, faces)
    if age <= 0:
        return 0.0
    if age >= LAYOUT['MED_STILL'] * math.sqrt(faces):
        return 1.0
    w = MED_OMEGA / math.sqrt(faces)
    d = math.sqrt(1 - MED_ZETA ** 2)
    return 1 - math.exp(-MED_ZETA * w * age) * (math.cos(w * d * age) + MED_ZETA / d * math.sin(w * d * age))

# The goods' sections, in MarketBoards.ORDER's order: how many goods each takes, and the column it's chalked in
SECTIONS = [2, 5, 2, 4, 10]
SECTION_COLUMN = [0, 0, 1, 1, 2]

# Chalk colours (the game tints white chalk with these) and the ink's
COLOURS = {
    'CHALK': 0xECE7DC, 'CHALK_DEAR': 0xF0A89A, 'CHALK_CHEAP': 0xAEDCA0, 'CHALK_HEAD': 0xE8D79E, 'CHALK_FRESH': 0xFFFDF6,
    'INK': 0x2A1E1A, 'INK_TROUBLE': 0x5A1414, 'GILT': 0xE2BC6C, 'GILT_SHADOW': 0x24170F, 'GILT_GLINT': 0xFFF0C0,
    'INK_WANTED': 0x6A1610, 'INK_STAMP': 0x9C2A22,
}

# The papers, in Slip.Kind's order, and for each the corner its decoration takes (texels of the 64x32 paper; text
# keeps clear of it)
SLIP_KINDS = ['trouble', 'good', 'price', 'word', 'year', 'talk', 'fortune', 'works']
SLIP_PX = (64, 32)
SLIP_KEEP = {'trouble': (47, 17, 64, 32), 'good': (0, 0, 9, 32), 'price': (0, 21, 16, 32), 'word': (45, 15, 64, 32),
             'year': (0, 18, 17, 32), 'talk': TALK.KEEP, 'fortune': (46, 13, 64, 32), 'works': (0, 14, 19, 32)}


# ================================================================ painting

def grain_1d(n, seed, cell=2.0):
    """Smooth noise along one axis, for grain that runs the length of a board."""
    rng = np.random.default_rng(seed)
    k = int(n / cell) + 3
    g = rng.random(k)
    f = np.arange(n) / cell
    i = np.floor(f).astype(int)
    t = f - i
    t = t * t * (3 - 2 * t)
    return g[i] * (1 - t) + g[i + 1] * t


def fibres(W, H, seed, vertical):
    """A grain field in about -1..1: one tone per fibre line across the grain wandering slowly along it, fine
    fibres, and a few dark grain lines. Shaped (H, W); runs along y when vertical."""
    rng = np.random.default_rng(seed)
    n_across, n_along = (W, H) if vertical else (H, W)
    t = rng.normal(0, 1, n_across)
    t = np.convolve(np.pad(t, 1, mode='edge'), [0.25, 0.5, 0.25], mode='valid')
    a = np.arange(n_along)[:, None]
    T = t[None, :] + 0.35 * np.sin(a * 0.3 + t[None, :] * 3 + rng.uniform(0, 6))
    T = T * 0.5 + rng.normal(0, 0.1, (n_along, n_across))
    lines = rng.random(n_across) < 0.16
    T = np.where(lines[None, :], T - 0.7, T)
    return T if vertical else T.T


def wood(cv, mask, pal, seed, vertical, base=0.45, edge_lit=None, edge_dark=None, knots=1):
    """Boards of `pal` with their grain along y (vertical) or x, a little light on one edge and shadow on the other."""
    H, W = cv.h, cv.w
    t = base + 0.13 * fibres(W, H, seed, vertical) + 0.05 * (fbm(W, H, 6, seed + 3) - 0.5)
    if edge_lit is not None:
        t = np.where(edge_lit, t + 0.14, t)
    if edge_dark is not None:
        t = np.where(edge_dark, t - 0.18, t)
    rng = np.random.default_rng(seed + 11)
    ys_m, xs_m = np.nonzero(mask)
    for _ in range(knots if len(xs_m) else 0):
        k = int(rng.integers(0, len(xs_m)))
        m, h = ellipse(xs_m[k] + 0.5, ys_m[k] + 0.5, 1.0 if vertical else 1.6, 1.6 if vertical else 1.0, W, H)
        ring, _ = ellipse(xs_m[k] + 0.5, ys_m[k] + 0.5, 1.8 if vertical else 2.6, 2.6 if vertical else 1.8, W, H)
        t = np.where(ring & ~m, t + 0.07, t)
        t = np.where(m, t - 0.26 + 0.1 * h, t)
    cv.put(mask, bands(pal, t, 0.15))


def lit_edges(mask, vertical):
    """The light (left or top) and dark (right or bottom) edge texels of a member."""
    m = mask.astype(bool)
    left = m & ~np.roll(m, 1, axis=1)
    right = m & ~np.roll(m, -1, axis=1)
    top = m & ~np.roll(m, 1, axis=0)
    bottom = m & ~np.roll(m, -1, axis=0)
    if vertical:
        return left, right | (m & ~np.roll(m, -2, axis=1)) & ~left
    return top, bottom


def iron(cv, mask, seed, rivets=()):
    xs, ys = grid(cv.w, cv.h)
    n = fbm(cv.w, cv.h, 3, seed)
    t = 0.42 + 0.18 * (n - 0.5)
    lit, dark = lit_edges(mask, False)
    t = np.where(lit, t + 0.22, t)
    t = np.where(dark, t - 0.2, t)
    cv.put(mask, bands(PAL['iron'], t, 0.2))
    # A little rust where water stands
    r = (fbm(cv.w, cv.h, 4, seed + 1) > 0.66) & mask
    cv.over(r, (96, 52, 32), 0.35)
    for (x, y) in rivets:
        cv.px(x, y, PAL['iron'][5])
        cv.px(x + 1, y + 1, PAL['iron'][0])
        cv.px(x + 1, y, PAL['iron'][3])


def strap_masks(mirror):
    out = []
    for (x0, x1, y0, y1, z0, z1) in STRAPS:
        if mirror:
            x0, x1 = 48 - x1, 48 - x0
        out.append((x0, x1, y0, y1))
    return out


def paint_front():
    cv = Canvas(PW, PH)
    xs, ys = grid(PW, PH)
    # Unused corners first (nothing samples them), so the sheet isn't patchy if anything ever does
    wood(cv, np.ones((PH, PW), bool), PAL['darkwood'], 1, False, base=0.4)

    # The posts and their stone collars
    for k, (x0, x1) in enumerate((POST_L, POST_R)):
        m = R(x0, x1, POST_Y[0], POST_Y[1])
        lit, dark = lit_edges(m, True)
        wood(cv, m, PAL['darkwood'], 20 + k, True, base=0.47, edge_lit=lit, edge_dark=dark, knots=2)
        # Weathered dark toward the ground, a green bloom where the rain splashes
        foot = m & (ys > ty(5.0))
        cv.over(foot, PAL['darkwood'][0], np.clip((ys - ty(5.0)) / 10.0, 0, 0.5))
        cv.over(foot & (fbm(PW, PH, 3, 30 + k) > 0.55), PAL['moss'][2], 0.35)
    for k, (x0, x1) in enumerate((COLLAR_L, COLLAR_R)):
        m = R(x0, x1, COLLAR_Y[0], COLLAR_Y[1])
        t = 0.45 + 0.25 * (fbm(PW, PH, 2, 40 + k) - 0.5) + 0.15 * (ys < ty(COLLAR_Y[1]) + 0.9)
        cv.put(m, bands(PAL['stone'], t, 0.3))

    # The rail, its top edge worn pale where hands rest, and the ledge in front of it
    m = R(RAIL_X[0], RAIL_X[1], RAIL_Y[0], RAIL_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['darkwood'], 50, False, base=0.46, edge_lit=lit, edge_dark=dark, knots=2)
    m = R(LEDGE_X[0], LEDGE_X[1], LEDGE_Y[0], LEDGE_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['wood'], 52, False, base=0.42, edge_lit=lit, edge_dark=dark, knots=0)
    # Chalk dust caught along the ledge
    cv.over(m & (rand(PW, PH, 53) > 0.8), (170, 168, 160), 0.3)

    # The slate: dark and cold, rubbed smooth in rows, scratched, ghosts of old chalk where lines were wiped out
    m = R(SLATE_X[0], SLATE_X[1], PANEL_Y[0], PANEL_Y[1])
    big = fbm(PW, PH, 12, 61)
    t = 0.2 + 0.07 * (big - 0.5) + 0.035 * (fbm(PW, PH, 3, 62) - 0.5)
    # Worn lighter toward the middle, where the crier stands and rubs
    cx, cy = (tx(SLATE_X[0]) + tx(SLATE_X[1])) / 2, (ty(PANEL_Y[0]) + ty(PANEL_Y[1])) / 2
    t += 0.03 * np.exp(-(((xs - cx) / 22) ** 2 + ((ys - cy) / 16) ** 2))
    cv.put(m, bands(PAL['slate'], t, 0.32))
    rng = np.random.default_rng(64)
    x0, x1, y0, y1 = tx(SLATE_X[0]), tx(SLATE_X[1]), ty(PANEL_Y[1]), ty(PANEL_Y[0])
    # Ghost rows: smeared, faint, in the rows where the prices go
    for row in range(9):
        yy = y0 + 4.5 + row * 4.05 + rng.normal(0, 0.3)
        for col in range(3):
            if rng.random() < 0.45:
                continue
            gx0 = x0 + 2 + col * 19.3 + rng.uniform(0, 3)
            gx1 = gx0 + rng.uniform(8, 15)
            g = (np.abs(ys - yy) < 1.0) & (xs > gx0) & (xs < gx1)
            cv.over(g & m & (rand(PW, PH, 70 + row * 3 + col) > 0.6), (96, 104, 114), 0.07 + 0.06 * rng.random())
            cv.over((np.abs(ys - yy) < 1.6) & (xs > gx0 - 2) & (xs < gx1 + 3) & m, (70, 78, 90), 0.08)
    # A ghost of figures not quite rubbed out, and a wide swipe of the rag
    for (gx, gy) in ((x0 + 9, y0 + 9), (x0 + 30, y0 + 22), (x0 + 47, y0 + 31)):
        for i in range(3):
            g = rect(int(gx + i * 2), int(gy), int(gx + i * 2) + 1, int(gy) + 2, PW, PH)
            cv.over(g & m & (rand(PW, PH, int(gx) + i) > 0.4), (96, 102, 112), 0.1)
    swipe = (np.abs((ys - y0) - 0.32 * (xs - x0) - 6) < 3.2) & m
    cv.over(swipe, (86, 94, 106), 0.12)
    # Scratches: fine pale lines
    for _ in range(5):
        sx, sy = rng.uniform(x0 + 2, x1 - 6), rng.uniform(y0 + 2, y1 - 2)
        ln = rng.uniform(3, 7)
        ang = rng.uniform(-0.5, 0.5)
        for s in range(int(ln * 2)):
            px, py = int(sx + s * 0.5 * math.cos(ang)), int(sy + s * 0.5 * math.sin(ang))
            if m[min(max(py, 0), PH - 1), min(max(px, 0), PW - 1)]:
                cv.over(rect(px, py, px + 1, py + 1, PW, PH), (104, 112, 120), 0.2)
    # The frame's shadow on the sunken slate: along the top (the header) and the left (the post)
    cv.over(m & (ys < y0 + 1), PAL['slate'][0], 0.75)
    cv.over(m & (ys < y0 + 2) & (ys >= y0 + 1), PAL['slate'][0], 0.35)
    cv.over(m & (xs < x0 + 1), PAL['slate'][0], 0.6)
    cv.over(m & (xs >= x1 - 1), PAL['slate'][4], 0.12)

    # The mullion
    m = R(MULLION_X[0], MULLION_X[1], PANEL_Y[0], PANEL_Y[1])
    lit, dark = lit_edges(m, True)
    wood(cv, m, PAL['darkwood'], 80, True, base=0.48, edge_lit=lit, edge_dark=dark, knots=1)

    # The news panel: upright planks, pricked all over with old pin holes, scraps of old slips under rusted tacks
    m = R(NEWS_X[0], NEWS_X[1], PANEL_Y[0], PANEL_Y[1])
    nx0 = tx(NEWS_X[0])
    plank = np.floor((xs - nx0) / 5.0)
    within = (xs - nx0) - plank * 5
    t = 0.46 + 0.07 * np.sin(plank * 2.3 + 1) + 0.16 * (grain_1d(PW, 90, 1.3)[np.clip(xs.astype(int), 0, PW - 1)] - 0.5)
    t += 0.05 * (fbm(PW, PH, 4, 91) - 0.5)
    t = np.where(within < 1.0, t - 0.3, t)
    t = np.where((within >= 1.0) & (within < 2.0), t + 0.08, t)
    cv.put(m, bands(PAL['wood'], t, 0.22))
    holes = m & (rand(PW, PH, 92) > 0.975) & (within >= 1.0)
    cv.put(holes, PAL['wood'][1])
    for (sx, sy, w, h) in ((nx0 + 3, ty(PANEL_Y[1]) + 4, 3, 2), (nx0 + 12, ty(PANEL_Y[1]) + 19, 2, 3),
                           (nx0 + 6, ty(PANEL_Y[1]) + 33, 3, 2)):
        sm = rect(sx, sy, sx + w, sy + h, PW, PH)
        cv.put(sm, bands(PAL['parchment'], 0.45 + 0.2 * (rand(PW, PH, sx) > 0.5), 0))
        cv.px(sx + w // 2, sy, (110, 62, 40))
    cv.over(m & (ys < ty(PANEL_Y[1]) + 1), PAL['wood'][0], 0.7)
    cv.over(m & (xs < nx0 + 1), PAL['wood'][0], 0.45)

    # The header plank: a carved groove inside its edges, a rosette carved at each end
    m = R(HEADER_X[0], HEADER_X[1], HEADER_Y[0], HEADER_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['darkwood'], 100, False, base=0.5, edge_lit=lit, edge_dark=dark, knots=1)
    hy0, hy1 = ty(HEADER_Y[1]), ty(HEADER_Y[0])
    groove_top = m & (ys >= hy0 + 1) & (ys < hy0 + 2) & (xs > 9) & (xs < PW - 9)
    groove_bot = m & (ys >= hy1 - 2) & (ys < hy1 - 1) & (xs > 9) & (xs < PW - 9)
    cv.over(groove_top, PAL['darkwood'][0], 0.8)
    cv.over(groove_bot, PAL['darkwood'][5], 0.25)
    for cx in (4.5, PW - 4.5):
        dm, h = disc(cx, (hy0 + hy1) / 2, 2.6, PW, PH)
        cv.over(dm, PAL['darkwood'][1], 0.75)
        for a in range(4):
            px = int(cx + 1.6 * math.cos(a * math.pi / 2 + 0.6))
            py = int((hy0 + hy1) / 2 + 1.6 * math.sin(a * math.pi / 2 + 0.6))
            cv.px(px, py, PAL['darkwood'][4])
        cv.px(int(cx), int((hy0 + hy1) / 2), PAL['darkwood'][5])
        for side in (-1, 1):
            vx = int(cx + side * 3.8)
            cv.over(rect(vx, hy0 + 2, vx + 1, hy1 - 2, PW, PH), PAL['darkwood'][1], 0.6)

    # The iron straps at the corners, riveted
    for mirror in (False, True):
        for k, (x0, x1, y0, y1) in enumerate(strap_masks(mirror)):
            sm = R(x0, x1, y0, y1)
            ys_m, xs_m = np.nonzero(sm)
            rivets = []
            if len(xs_m):
                if (x1 - x0) > (y1 - y0):
                    for f in (0.25, 0.8):
                        rivets.append((int(xs_m.min() + f * (xs_m.max() - xs_m.min())) - 0, int((ys_m.min() + ys_m.max()) / 2)))
                elif (y1 - y0) > 3:
                    for f in (0.2, 0.75):
                        rivets.append((int((xs_m.min() + xs_m.max()) / 2), int(ys_m.min() + f * (ys_m.max() - ys_m.min()))))
            iron(cv, sm, 110 + k + 7 * mirror, rivets)
    return cv


def paint_back():
    cv = Canvas(PW, PH)
    xs, ys = grid(PW, PH)
    wood(cv, np.ones((PH, PW), bool), PAL['darkwood'], 2, False, base=0.4)
    for k, (x0, x1) in enumerate((POST_L, POST_R)):
        m = RB(x0, x1, POST_Y[0], POST_Y[1])
        lit, dark = lit_edges(m, True)
        wood(cv, m, PAL['darkwood'], 120 + k, True, base=0.44, edge_lit=lit, edge_dark=dark, knots=2)
        foot = m & (ys > ty(5.0))
        cv.over(foot, PAL['darkwood'][0], np.clip((ys - ty(5.0)) / 10.0, 0, 0.5))
    for k, (x0, x1) in enumerate((COLLAR_L, COLLAR_R)):
        m = RB(x0, x1, COLLAR_Y[0], COLLAR_Y[1])
        t = 0.42 + 0.25 * (fbm(PW, PH, 2, 140 + k) - 0.5) + 0.15 * (ys < ty(COLLAR_Y[1]) + 0.9)
        cv.put(m, bands(PAL['stone'], t, 0.3))
    m = RB(RAIL_X[0], RAIL_X[1], RAIL_Y[0], RAIL_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['darkwood'], 150, False, base=0.42, edge_lit=lit, edge_dark=dark)

    # The cladding: upright planks, each its own tone, dark seams, the odd knot
    m = RB(PANEL_X[0], PANEL_X[1], PANEL_Y[0], PANEL_Y[1])
    px0 = tx(48 - PANEL_X[1])
    plank = np.floor((xs - px0) / 8.0)
    within = (xs - px0) - plank * 8
    t = 0.42 + 0.06 * np.sin(plank * 2.9 + 0.4) + 0.15 * (grain_1d(PW, 160, 1.4)[np.clip(xs.astype(int), 0, PW - 1)] - 0.5)
    t += 0.06 * (fbm(PW, PH, 5, 161) - 0.5)
    t = np.where(within < 1.0, t - 0.3, t)
    t = np.where((within >= 1.0) & (within < 2.0), t + 0.09, t)
    cv.put(m, bands(PAL['darkwood'], t, 0.22))
    rng = np.random.default_rng(162)
    ys_m, xs_m = np.nonzero(m)
    for _ in range(4):
        k = int(rng.integers(0, len(xs_m)))
        km, kh = ellipse(xs_m[k] + 0.5, ys_m[k] + 0.5, 1.0, 1.6, PW, PH)
        cv.over(km & m, PAL['darkwood'][0], 0.6)
    cv.over(m & (ys < ty(PANEL_Y[1]) + 1), PAL['darkwood'][0], 0.6)

    # The battens, bolted through the posts, nailed to every plank
    for k, (y0, y1) in enumerate(BATTENS_Y):
        bm = RB(BATTEN_X[0], BATTEN_X[1], y0, y1)
        lit, dark = lit_edges(bm, False)
        wood(cv, bm, PAL['wood'], 170 + k, False, base=0.38, edge_lit=lit, edge_dark=dark, knots=1)
        yb = ty((y0 + y1) / 2)
        for p in range(int(PANEL_X[0] * 2) + 4, int(PANEL_X[1] * 2), 8):
            cv.px(PW - p, yb, PAL['iron'][2])
        for (x0, x1) in (POST_L, POST_R):
            bx = tx(48 - (x0 + x1) / 2)
            cv.put(rect(bx - 1, yb - 1, bx + 1, yb + 1, PW, PH), PAL['iron'][3])
            cv.px(bx - 1, yb - 1, PAL['iron'][5])
            cv.px(bx, yb, PAL['iron'][1])
        # Their shadow on the planks below
        sh = RB(PANEL_X[0], PANEL_X[1], y0 - 0.5, y0) & m
        cv.over(sh, PAL['darkwood'][0], 0.55)

    # The header's back, the guild's crest burnt into it
    m = RB(HEADER_X[0], HEADER_X[1], HEADER_Y[0], HEADER_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['darkwood'], 180, False, base=0.46, edge_lit=lit, edge_dark=dark, knots=1)
    hy = (ty(HEADER_Y[1]) + ty(HEADER_Y[0])) / 2
    crest = poly([(46, hy - 2.6), (50, hy - 2.6), (50, hy + 0.4), (48, hy + 2.6), (46, hy + 0.4)], PW, PH)
    cv.over(crest, (20, 12, 10), 0.7)
    cv.over(grow(crest) & ~crest & m, (40, 24, 16), 0.35)
    cv.over(rect(47, int(hy - 2), 49, int(hy - 1), PW, PH), (60, 36, 22), 0.5)
    return cv


def tile_wood(vertical, seed, pal=None, base=0.46):
    cv = Canvas(32)
    xs, ys = grid(32)
    m = np.ones((32, 32), bool)
    wood(cv, m, pal or PAL['darkwood'], seed, vertical, base=base, knots=1)
    return cv


def paint_cap():
    """Slate tiles in staggered rows, each row's lower edge in shadow (the rows run along u; down the slope is +v)."""
    cv = Canvas(32)
    xs, ys = grid(32)
    row = np.floor(ys / 4.0)
    within = ys - row * 4
    col = np.floor((xs + row * 4) / 8.0)
    jx = (xs + row * 4) - col * 8
    tone_k = np.sin(col * 1.9 + row * 2.7) * 0.06
    t = 0.38 + tone_k + 0.08 * (fbm(32, 32, 4, 190) - 0.5) + 0.1 * (within < 1.0) - 0.22 * (within >= 3.0)
    t = np.where(jx < 1.0, t - 0.16, t)
    cv.put(np.ones((32, 32), bool), bands(PAL['slate'], t, 0.25))
    cv.over((fbm(32, 32, 5, 191) > 0.68), PAL['moss'][1], 0.3)
    return cv


def paint_trim():
    """Four small materials in quadrants: iron (top left), chalk (top right), stone (bottom left), paper (bottom right)."""
    cv = Canvas(32)
    xs, ys = grid(32)
    q = lambda x0, y0: rect(x0, y0, x0 + 16, y0 + 16, 32)
    t = 0.4 + 0.2 * (fbm(32, 32, 3, 200) - 0.5)
    cv.put(q(0, 0), bands(PAL['iron'], t, 0.3))
    cv.over(q(0, 0) & (fbm(32, 32, 4, 201) > 0.64), (96, 52, 32), 0.35)
    chalk = 0.74 + 0.1 * (rand(32, 32, 202) - 0.5)
    cv.put(q(16, 0), np.stack([chalk * 236, chalk * 232, chalk * 222], -1))
    cv.over(q(16, 0) & (fbm(32, 32, 3, 205) > 0.55), (128, 130, 134), 0.3)
    cv.over(q(16, 0) & (xs >= 28), (120, 122, 128), 0.4)
    t = 0.45 + 0.3 * (fbm(32, 32, 3, 203) - 0.5)
    cv.put(q(0, 16), bands(PAL['stone'], t, 0.3))
    t = 0.66 + 0.12 * (fbm(32, 32, 4, 204) - 0.5)
    cv.put(q(16, 16), bands(PAL['parchment'], t, 0.25))
    return cv


def paint_item():
    """The item: the board seen from the front, under its cap, its slate chalked up and a slip on the panel."""
    N = 32
    xs, ys = grid(N)
    cv = Canvas(N)
    # Posts and collars
    for px in (2, 27):
        m = rect(px, 9, px + 3, 30, N)
        t = 0.44 + 0.2 * (xs - px < 1) - 0.18 * (xs - px >= 2) + 0.08 * (vnoise(N, N, 2, px) - 0.5)
        cv.put(m, bands(PAL['darkwood'], t))
        cv.put(rect(px - 1, 29, px + 4, 31, N), bands(PAL['stone'], 0.5 + 0.2 * (ys < 29.9)))
    # The rail and the panel
    cv.put(rect(5, 24, 27, 26, N), bands(PAL['darkwood'], 0.45 + 0.2 * (ys < 24.9) - 0.15 * (ys > 25.1)))
    slate = rect(5, 11, 20, 24, N)
    cv.put(slate, bands(PAL['slate'], 0.2 + 0.05 * (vnoise(N, N, 4, 7) - 0.5) - 0.08 * ((ys < 12) | (xs < 6)), 0.3))
    cv.put(rect(20, 11, 21, 24, N), bands(PAL['darkwood'], 0.5 + 0.0 * xs))
    news = rect(21, 11, 27, 24, N)
    k3 = np.floor(xs - 21) % 3
    cv.put(news, bands(PAL['wood'], 0.45 + 0.1 * (k3 == 0) - 0.2 * (k3 == 2) + 0.0 * ys))
    # Chalk: a pale head line, rows of prices with a coloured mark here and there
    rng = np.random.default_rng(5)
    cv.put(rect(6, 12, 13, 13, N), (226, 212, 160))
    for r, y in enumerate(range(14, 24, 2)):
        for c, x in enumerate((6, 13)):
            if x + 6 > 20:
                continue
            cv.px(x, y, (190, 150, 90) if (r + c) % 3 else (150, 170, 120))
            for i in range(2, 6):
                if rng.random() < 0.8:
                    col = (236, 231, 220)
                    if (r * 2 + c) % 5 == 1:
                        col = (240, 170, 156)
                    if (r * 2 + c) % 5 == 3:
                        col = (176, 220, 160)
                    cv.px(x + i, y, col)
    # A slip with a red seal, pinned up
    sl = rect(22, 13, 27, 18, N)
    cv.put(sl, bands(PAL['parchment'], 0.72 - 0.18 * (ys > 17) - 0.12 * (xs > 26) + 0.0 * xs))
    for y in (15, 16):
        cv.put(rect(23, y, 26, y + 1, N), PAL['ink'][1])
    cv.px(24, 13, PAL['iron'][4])
    cv.px(26, 17, PAL['wax'][3])
    sl2 = rect(22, 19, 27, 23, N)
    cv.put(sl2, bands(PAL['parchment'], 0.6 - 0.15 * (ys > 22) + 0.0 * xs))
    cv.put(rect(23, 20, 26, 21, N), PAL['ink'][1])
    cv.px(24, 19, PAL['iron'][4])
    cv.px(22, 22, (70, 130, 70))
    # The header and the cap
    cv.put(rect(1, 8, 31, 11, N), bands(PAL['darkwood'], 0.5 + 0.18 * (ys < 8.9) - 0.2 * (ys > 10.1) + 0.0 * xs))
    for x in range(10, 22, 2):
        cv.px(x, 9, (210, 172, 96))
    roof = poly([(0, 8.5), (16, 3.0), (32, 8.5), (32, 9.2), (0, 9.2)], N)
    row = np.floor((ys - 3) / 2.0)
    shingle = ((xs + row * 1.5) % 3.0) < 0.7
    cv.put(roof, bands(PAL['slate'], 0.5 - 0.04 * row - 0.22 * shingle + 0.1 * (xs < 16)))
    # The medallion over the ridge: its drum between two posts, a gilt roundel on the face it shows
    for px in (12, 19):
        cv.put(rect(px, 1, px + 1, 4, N), PAL['darkwood'][3])
    cv.put(rect(13, 0, 19, 3, N), bands(OAK, 0.5 + 0.2 * (ys < 0.9) - 0.15 * (ys > 2.1) + 0.0 * xs))
    cv.put(rect(15, 1, 17, 2, N), PAL['gold'][4])
    # Iron straps at the corners
    for (bx, by) in ((2, 23), (28, 23), (2, 11), (28, 11)):
        cv.put(rect(bx, by, bx + 2, by + 2, N), PAL['iron'][3])
        cv.px(bx, by, PAL['iron'][5])
    outline(cv, 0.8)
    return cv


# ================================================================ the medallion

# Where its five faces (in MED_TIMES order) and its end are in the sprite: x, y, w, h in texels. The faces' edges are
# run out into the gutters round them, so a mip level doesn't fetch the face beside.
MED_WHERE = {'faces': [(8, 2 + 46 * k, MED_FACE[0], MED_FACE[1]) for k in range(MED_SIDES)],
             'cap': (4, 236, MED_CAP, MED_CAP)}

OAK = [(24, 17, 15), (46, 32, 25), (70, 50, 35), (96, 70, 47), (124, 94, 63), (152, 120, 82)]
# Each face's painted ground, darkest first: cold for hard times, dun for lean, sage for steady, green for good, sky
GROUNDS = {
    'hard': [(14, 18, 28), (26, 34, 50), (42, 54, 74), (62, 78, 100), (88, 106, 126), (120, 138, 154)],
    'lean': [(30, 22, 14), (56, 40, 22), (86, 64, 32), (116, 88, 44), (146, 114, 60), (174, 142, 84)],
    'steady': [(18, 24, 20), (34, 44, 36), (54, 66, 52), (76, 90, 70), (100, 114, 90), (128, 140, 112)],
    'good': [(14, 24, 12), (28, 46, 22), (44, 70, 30), (64, 96, 40), (90, 122, 54), (122, 150, 74)],
    'thriving': [(16, 26, 44), (30, 50, 82), (48, 80, 122), (72, 114, 160), (108, 152, 194), (154, 188, 218)],
}
# How light each ground is laid (0..1 along its tones): a pale winter sky behind the bare branch, the rest mid
GROUND_TONE = {'hard': 0.7, 'lean': 0.33, 'steady': 0.4, 'good': 0.4, 'thriving': 0.46}
STRAW = [(42, 28, 14), (86, 60, 26), (134, 98, 40), (180, 138, 58), (216, 178, 88), (242, 216, 142)]
STRAW_THIN = [(38, 30, 20), (72, 58, 36), (108, 90, 58), (142, 122, 82), (172, 154, 108), (200, 184, 138)]
BARK = [(16, 12, 12), (30, 23, 21), (48, 37, 32), (68, 54, 45), (92, 74, 60), (118, 98, 80)]
SNOW = [(70, 84, 104), (128, 144, 164), (180, 194, 210), (214, 224, 234), (238, 244, 250)]
SUN = [(66, 36, 18), (130, 76, 26), (190, 126, 40), (230, 172, 62), (248, 214, 112), (255, 242, 186)]
RED_RIBBON = [(36, 8, 12), (84, 16, 22), (140, 30, 34), (190, 56, 50), (226, 100, 80)]
BLUE_RIBBON = [(10, 16, 38), (20, 38, 84), (36, 66, 136), (64, 104, 184), (112, 154, 218)]


class Relief:
    """A carved and painted panel: a height field, and for each texel the material it's painted in and its tone before
    the light. Painted at the end, lit from the upper left (the kit's light()), its hollows a little darker."""

    def __init__(self, W, H, pals):
        self.W, self.H = W, H
        self.names = list(pals)
        self.pals = pals
        self.hgt = np.zeros((H, W))
        self.mat = np.zeros((H, W), int)
        self.base = np.full((H, W), 0.5)

    def lay(self, mask, mat, base, height):
        mask = np.asarray(mask, bool)
        if not mask.any():
            return
        self.mat[mask] = self.names.index(mat)
        self.base[mask] = np.broadcast_to(np.asarray(base, float), mask.shape)[mask]
        self.hgt[mask] = np.broadcast_to(np.asarray(height, float), mask.shape)[mask]

    def of(self, *names):
        return np.isin(self.mat, [self.names.index(n) for n in names])

    def paint(self, strength=1.4, k=0.62, dither=0.3):
        t = self.base + k * light(self.hgt, strength) - 0.3 * np.clip(cavity(self.hgt, 1), 0, 1)
        cv = Canvas(self.W, self.H)
        for i, n in enumerate(self.names):
            m = self.mat == i
            if m.any():
                cv.put(m, bands(self.pals[n], t, dither))
        return cv


def tube(pts, r0, r1, W, H):
    """A round stem along a polyline, r0 thick at its start and r1 at its end: its mask and its height (1 on its ridge)."""
    d, along = path_dist(pts, W, H)
    L = sum(math.hypot(x1 - x0, y1 - y0) for (x0, y0), (x1, y1) in zip(pts, pts[1:]))
    r = r0 + (r1 - r0) * np.clip(along / max(L, 1e-6), 0, 1)
    return d < r, np.sqrt(np.clip(1 - (d / r) ** 2, 0, 1))


def oval(cx, cy, rx, ry, ang, W, H):
    """An ellipse turned by ang (its rx axis along (cos, sin) of it, y down): mask, dome, and the texels' place in it
    (u along rx, v along ry, each -1..1 over it)."""
    xs, ys = grid(W, H)
    c, s = math.cos(ang), math.sin(ang)
    u = ((xs - cx) * c + (ys - cy) * s) / rx
    v = (-(xs - cx) * s + (ys - cy) * c) / ry
    d2 = u * u + v * v
    return d2 <= 1, np.sqrt(np.clip(1 - d2, 0, 1)), u, v


def ear(rel, fld, pts, length, width, mat, base, raise_, awns=3, seed=0):
    """An ear of grain at the top of the stalk pts: its grains in a chevron and its awns fanned on past its tip."""
    W, H = rel.W, rel.H
    (x0, y0), (x1, y1) = pts[-2], pts[-1]
    a = math.atan2(y1 - y0, x1 - x0)
    cx, cy = x1 + math.cos(a) * (length * 0.32), y1 + math.sin(a) * (length * 0.32)
    m, h, u, v = oval(cx, cy, length / 2, width / 2, a, W, H)
    # Each grain a little dome, set alternately either side of the ear's spine, so its outline is beaded
    phase = u * length / 2.8 + 0.5 * (v > 0)
    grains = 0.5 + 0.5 * np.cos(2 * np.pi * phase)
    edge = 1 - 0.24 * (1 - grains)
    m = (np.abs(u) ** 3 + (v / edge) ** 2 <= 1) & (np.abs(u) <= 1)
    h = np.sqrt(np.clip(1 - np.abs(u) ** 3 - v * v, 0, 1))
    seam = (np.abs(v) < 0.18) & (np.abs(u) < 0.8)
    rel.lay(m & fld, mat, base + 0.12 * (v < 0) - 0.1 * (v > 0.5) + 0.08 * (grains - 0.5) - 0.08 * seam,
            raise_ + 0.7 * h * (0.7 + 0.3 * grains) - 0.15 * seam)
    tip = (cx + math.cos(a) * length / 2, cy + math.sin(a) * length / 2)
    for i in range(awns):
        da = (i - (awns - 1) / 2) * 0.34
        n = length * 0.55
        am, ah = tube([tip, (tip[0] + math.cos(a + da) * n, tip[1] + math.sin(a + da) * n)], 0.5, 0.36, W, H)
        rel.lay(am & fld & ~m, mat, base + 0.1, raise_ + 0.25 * ah)


def sheaf(rel, fld, cx, band_y, top_y, foot_y, half_top, half_band, half_foot, n, ear_len, mat, base, seed):
    """A sheaf as it's carved: n stalks fanned up from a twisted band at the waist into a crown of ears (the ears
    shoulder to shoulder, each its grains set alternately either side, a few awns standing out of the crown), and
    splayed below the band to their cut ends. half_top, half_band and half_foot are its half widths there."""
    W, H = rel.W, rel.H
    xs, ys = grid(W, H)
    rng = np.random.default_rng(seed)
    # Above the band: every stalk on a line from a point below the waist, as a bound sheaf's are
    slope = (half_top - half_band) / (band_y - top_y)
    fy = band_y + half_band / slope
    spread = math.atan(slope)
    th = np.arctan2(xs - cx, fy - ys)
    rr = np.hypot(xs - cx, fy - ys)
    k = (th / spread + 1) * n / 2
    idx = np.clip(np.floor(k), 0, n - 1).astype(int)
    f = k - np.floor(k)
    crown = (fy - top_y) * (1 - 0.16 * (th / spread) ** 2)
    jitter = rng.normal(0, 0.55, n)
    tip = crown + jitter[idx] - 1.6 * (2 * f - 1) ** 2
    upper = (np.abs(th) <= spread) & (ys <= band_y) & (rr <= tip) & fld
    across = 1 - (2 * f - 1) ** 2                          # 1 down a stalk's middle, 0 between two
    ears = upper & (rr > tip - ear_len)
    grains = 0.5 + 0.5 * np.cos(2 * np.pi * (rr / 2.1 + 0.5 * (idx % 2) + 0.5 * (f > 0.5)))
    stalks = upper & ~ears
    rel.lay(stalks, mat, base - 0.18 + 0.14 * across, 0.3 + 0.35 * across)
    rel.lay(ears, mat, base + 0.06 + 0.12 * across + 0.08 * grains - 0.1 * (f > 0.82),
            0.6 + 0.45 * across + 0.2 * grains)
    # Awns standing out of the crown, from every other ear
    for i in range(0, n, 2):
        a = ((i + 0.5) / n * 2 - 1) * spread
        r0 = (fy - top_y) * (1 - 0.16 * (a / spread) ** 2) + jitter[i] - 0.3
        x0, y0 = cx + math.sin(a) * r0, fy - math.cos(a) * r0
        for da in (-0.22, 0.22):
            L = 2.4 + rng.random() * 1.2
            m, h = tube([(x0, y0), (x0 + math.sin(a + da) * L, y0 - math.cos(a + da) * L)], 0.5, 0.36, W, H)
            rel.lay(m & fld & ~upper, mat, base + 0.12, 0.35)
    # Below it: the stalks splayed from a point above the waist down to their cut ends, pale
    slope2 = (half_foot - half_band) / (foot_y - band_y)
    fy2 = band_y - half_band / slope2
    spread2 = math.atan(slope2)
    th2 = np.arctan2(xs - cx, ys - fy2)
    k2 = (th2 / spread2 + 1) * (n - 2) / 2
    f2 = k2 - np.floor(k2)
    ends = foot_y + 0.5 * np.sin(np.floor(k2) * 2.3)
    lower = (np.abs(th2) <= spread2) & (ys >= band_y) & (ys <= ends) & fld
    across2 = 1 - (2 * f2 - 1) ** 2
    cut = lower & (ys > ends - 1.0)
    rel.lay(lower & ~cut, mat, base - 0.14 + 0.16 * across2, 0.3 + 0.35 * across2)
    rel.lay(cut, mat, base + 0.16 + 0.1 * across2 - 0.25 * (f2 < 0.2), 0.7)
    # The band, twisted straw round the waist
    half = half_band + 1.0
    bm = (np.abs(xs - cx) < half) & (np.abs(ys - band_y) < 1.35)
    bh = np.sqrt(np.clip(1 - ((ys - band_y) / 1.35) ** 2, 0, 1))
    twist = np.sin((xs - cx) * 1.9 - (ys - band_y) * 2.6) > 0.55
    rel.lay(bm & fld, mat, base - 0.2 + 0.1 * bh - 0.12 * twist, 0.9 + 0.6 * bh - 0.3 * twist)


def emblem_hard(rel, fld, seed):
    """A bare branch, gnarled, its twigs empty, snow along it and falling, and lying in the foot of the field."""
    W, H = rel.W, rel.H
    xs, ys = grid(W, H)
    rng = np.random.default_rng(seed)
    limbs = [
        ([(18.6, 33.6), (22.6, 29.6), (27.0, 27.4), (31.0, 24.0), (35.0, 19.5), (40.0, 15.5), (45.6, 11.6)], 2.4, 0.7),
        ([(27.0, 27.4), (25.5, 22.0), (26.5, 17.0), (24.8, 12.0)], 1.3, 0.5),
        ([(31.0, 24.0), (36.0, 25.2), (41.0, 23.6), (44.8, 25.2)], 1.15, 0.5),
        ([(35.0, 19.5), (34.4, 14.0), (36.6, 9.2)], 1.05, 0.48),
        ([(40.0, 15.5), (43.8, 18.2)], 0.8, 0.48),
        ([(25.6, 21.5), (22.0, 19.0)], 0.75, 0.48),
        ([(34.5, 14.5), (31.5, 12.0)], 0.65, 0.48),
    ]
    branch = np.zeros((H, W), bool)
    stout = np.zeros((H, W), bool)          # the limbs thick enough to hold snow along them
    hb = np.zeros((H, W))
    for pts, r0, r1 in limbs:
        m, h = tube(pts, r0, r1, W, H)
        h = h * min(1.0, 0.4 + r0 / 2.4)
        hb = np.where(m, np.maximum(hb, h), hb)
        branch |= m
        if r0 >= 1.2:
            stout |= m
    for (x, y, r) in ((27.0, 27.4, 1.7), (31.0, 24.0, 1.5), (35.0, 19.5, 1.25), (22.6, 29.6, 2.1)):
        m, h = disc(x, y, r, W, H)
        hb = np.where(m, np.maximum(hb, h * 0.95), hb)
        branch |= m
    branch &= fld
    tb = 0.44 + 0.14 * (rand(W, H, seed + 1) - 0.5) + 0.1 * (fbm(W, H, 2, seed + 2) - 0.5)
    rel.lay(branch, 'bark', tb, 0.35 + 0.85 * hb)
    # Its foot broken off: the pale wood inside the bark
    end, eh = disc(18.9, 33.3, 1.55, W, H)
    rel.lay(end & branch, 'oak', 0.68 + 0.06 * np.sin(np.hypot(xs - 18.9, ys - 33.3) * 3.2), 1.0)
    # Snow lying along the limbs' tops, a texel deep and broken where a twig's too steep to hold it
    top = branch & ~shift(branch, 0, 1) & ~end
    steep = ~(shift(top, 1, 0) | shift(top, -1, 0))
    lay = top & ~steep & (rand(W, H, seed + 3) > np.where(stout, 0.12, 0.7))
    rel.lay(lay, 'snow', 0.72 + 0.1 * (rand(W, H, seed + 4) - 0.5), 1.25)
    # A drift in the foot of the field, and flakes falling
    drift = fld & (ys > 34.2 + 0.9 * np.sin(xs * 0.42 + 1.3)) & ~branch
    rel.lay(drift, 'snow', 0.58 + 0.12 * (ys < 35.5), -0.1)
    placed = 0
    while placed < 13:
        x, y = int(rng.integers(17, 47)), int(rng.integers(6, 33))
        if not fld[y, x] or grow(branch, 1)[y, x]:
            continue
        one = np.zeros((H, W), bool)
        one[y, x] = True
        rel.lay(one, 'snow', 0.8 + 0.15 * rng.random(), -0.1)
        placed += 1


def emblem_lean(rel, fld, seed):
    """A thin sheaf: five poor stalks in a band, three with a meagre ear, one empty, one bent over and broken."""
    W, H = rel.W, rel.H
    xs, ys = grid(W, H)
    stalks = [
        ([(30.3, 26.4), (27.6, 17.0), (24.8, 9.8)], True),
        ([(31.3, 26.4), (29.9, 15.5), (28.8, 7.6)], False),
        ([(32.3, 26.4), (32.5, 15.0), (33.0, 6.8)], True),
        ([(33.3, 26.4), (35.6, 16.0), (37.8, 8.8)], True),
        ([(34.2, 26.4), (37.3, 17.4), (40.3, 13.4), (42.6, 15.4)], False),
    ]
    for pts, has_ear in stalks:
        m, h = tube(pts, 0.6, 0.5, W, H)
        rel.lay(m & fld, 'thin', 0.5 + 0.1 * h, 0.25 + 0.45 * h)
        if has_ear:
            ear(rel, fld, pts, 5.2, 1.8, 'thin', 0.64, 0.4, awns=2)
    # The empty one's frayed tip, and the broken one hanging its head
    m, h = disc(28.7, 7.0, 0.7, W, H)
    rel.lay(m & fld, 'thin', 0.4, 0.5)
    m, h = tube([(42.6, 15.4), (43.5, 18.4)], 0.75, 0.45, W, H)
    rel.lay(m & fld, 'thin', 0.42, 0.45 + 0.3 * h)
    for i in range(5):
        f = (i - 2) / 2
        m, h = tube([(30.3 + i, 27.6), (32.3 + f * 4.6, 35.2 - 0.5 * f * f)], 0.58, 0.58, W, H)
        rel.lay(m & fld, 'thin', 0.48, 0.3 + 0.4 * h)
        e, _ = disc(32.3 + f * 4.6, 35.2 - 0.5 * f * f, 0.7, W, H)
        rel.lay(e & fld, 'thin', 0.66, 0.7)
    bm = (np.abs(xs - 32.3) < 3.3) & (np.abs(ys - 27.4) < 1.1)
    bh = np.sqrt(np.clip(1 - ((ys - 27.4) / 1.1) ** 2, 0, 1))
    rel.lay(bm & fld, 'thin', 0.36 + 0.1 * bh, 0.8 + 0.5 * bh)


def emblem_steady(rel, fld, seed):
    """A pair of brass scales on a post, the beam level and the pans even."""
    W, H = rel.W, rel.H
    xs, ys = grid(W, H)
    m, h = tube([(32.0, 10.8), (32.0, 32.6)], 1.05, 1.05, W, H)
    rel.lay(m & fld, 'brass', 0.54 + 0.1 * (xs < 31.6), 0.3 + 0.7 * h)
    foot = poly([(26.4, 35.4), (37.6, 35.4), (34.8, 32.3), (29.2, 32.3)], W, H)
    rel.lay(foot & fld, 'brass', 0.5 + 0.14 * (ys < 33.3) - 0.1 * (xs > 35), 0.9)
    m, h = disc(32.0, 9.2, 1.75, W, H)
    rel.lay(m & fld, 'brass', 0.62, 0.6 + 0.8 * h)
    m, h = tube([(20.6, 13.4), (43.4, 13.4)], 0.85, 0.85, W, H)
    rel.lay(m & fld, 'brass', 0.56, 0.45 + 0.7 * h)
    for (x, r) in ((32.0, 1.65), (20.6, 1.05), (43.4, 1.05)):
        m, h = disc(x, 13.4, r, W, H)
        rel.lay(m & fld, 'brass', 0.62, 0.65 + 0.8 * h)
    for px in (20.6, 43.4):
        for dx in (-3.5, 3.5):
            m, h = tube([(px, 14.2), (px + dx, 24.6)], 0.42, 0.42, W, H)
            links = (np.floor(np.hypot(xs - px, ys - 14.2) / 1.3) % 2) == 0
            rel.lay(m & fld, 'brass', 0.46 + 0.14 * links, 0.25 + 0.35 * h)
        pan, ph, u, v = oval(px, 24.6, 4.3, 2.7, 0.0, W, H)
        bowl = pan & (ys >= 24.6)
        rel.lay(bowl & fld, 'brass', 0.5 - 0.1 * u - 0.08 * (ys > 26.2), 0.5 + 0.8 * ph)
        rim = (np.abs(ys - 24.6) < 0.6) & (np.abs(xs - px) < 4.5)
        rel.lay(rim & fld, 'brass', 0.74, 1.15)
    glint_at = (31, 8)
    one = np.zeros((H, W), bool)
    one[glint_at[1], glint_at[0]] = True
    rel.lay(one & fld, 'brass', 0.95, 1.3)


def emblem_good(rel, fld, seed):
    """A full sheaf, golden and heavy in the ear, tied fat round the middle."""
    sheaf(rel, fld, 32.0, 24.4, 7.0, 35.4, 12.6, 3.4, 6.2, 11, 7.0, 'straw', 0.56, seed)


def emblem_thriving(rel, fld, seed):
    """A sun in its rays over a sheaf, red and blue ribbons tied in a bow at its band and streaming off it."""
    W, H = rel.W, rel.H
    xs, ys = grid(W, H)
    sx, sy = 32.0, 10.8
    d = np.hypot(xs - sx, ys - sy)
    # The sky lighter round the sun
    rel.base = np.where(rel.of('ground') & fld, rel.base + 0.2 * np.exp(-np.clip(d - 4, 0, None) / 5.0), rel.base)
    for j in range(12):
        a = math.radians(15 + 30 * j)
        long_ = j % 2 == 0
        L = 7.4 if long_ else 6.3
        if math.sin(a) > 0.9:
            continue                # none straight down: the sun stands clear over the sheaf
        w = 1.25 if long_ else 0.95
        ca, sa = math.cos(a), math.sin(a)
        pts = [(sx + ca * 3.6 - sa * w, sy + sa * 3.6 + ca * w), (sx + ca * L, sy + sa * L), (sx + ca * 3.6 + sa * w, sy + sa * 3.6 - ca * w)]
        m = poly(pts, W, H) & fld
        rel.lay(m, 'sun', 0.62 + 0.12 * long_ - 0.04 * (d / L), 0.5 + 0.3 * (1 - d / L))
    m, h = disc(sx, sy, 3.9, W, H)
    rel.lay(m & fld, 'sun', 0.64 + 0.1 * h, 0.7 + 1.0 * h)
    ring = (np.abs(d - 2.9) < 0.45)
    rel.lay(ring & fld, 'sun', 0.5, 0.95)
    sheaf(rel, fld, 32.0, 30.2, 18.0, 35.8, 8.8, 2.8, 4.6, 7, 5.4, 'straw', 0.58, seed + 5)
    # The bow: a red loop to the left, a blue to the right, the knot, and their ends streaming out and down
    for (cx, cy, a, mat) in ((28.2, 29.6, -0.28, 'red'), (35.8, 29.6, 0.28, 'blue')):
        m, h, u, v = oval(cx, cy, 3.1, 1.7, a, W, H)
        hole = (u * u + v * v) < 0.2
        rel.lay(m & ~hole & fld, mat, 0.5 + 0.18 * (v < 0) - 0.1 * (u * np.sign(cx - 32) > 0.5), 1.25 + 0.4 * h)
    for (pts, mat) in (([(31.0, 31.0), (28.4, 32.4), (25.6, 34.4), (23.6, 33.6)], 'red'),
                       ([(33.0, 31.0), (35.6, 32.4), (38.4, 34.4), (40.4, 33.6)], 'blue')):
        m, h = tube(pts, 0.95, 0.75, W, H)
        fold = (np.hypot(xs - pts[2][0], ys - pts[2][1]) < 0.9)
        rel.lay(m & fld, mat, 0.52 + 0.12 * h - 0.16 * fold, 1.1 + 0.4 * h)
        # A swallowtail cut in the end
        ex, ey = pts[-1]
        notch, _ = disc(ex + (0.7 if ex < 32 else -0.7) * -1, ey + 0.1, 0.55, W, H)
        rel.lay(notch & m & fld, 'ground', GROUND_TONE['thriving'] - 0.06, -0.3)
    m, h = disc(32.0, 30.5, 1.25, W, H)
    rel.lay(m & fld, 'red', 0.6 + 0.12 * h, 1.5 + 0.4 * h)


EMBLEMS = {'hard': emblem_hard, 'lean': emblem_lean, 'steady': emblem_steady, 'good': emblem_good,
           'thriving': emblem_thriving}


def med_face(kind, seed):
    """One of the drum's faces: carved oak with a bead at each end and a leaf and acorn beside the roundel, the
    roundel's rim gilt and twisted like rope, its field painted the times' colour, the emblem carved up out of it."""
    W, H = MED_FACE
    xs, ys = grid(W, H)
    cx, cy = W / 2, H / 2
    r = np.hypot(xs - cx, ys - cy)
    ang = np.arctan2(ys - cy, xs - cx)
    pals = {'oak': OAK, 'gilt': PAL['gold'], 'ground': GROUNDS[kind], 'straw': STRAW, 'thin': STRAW_THIN, 'bark': BARK,
            'snow': SNOW, 'brass': PAL['brass'], 'sun': SUN, 'red': RED_RIBBON, 'blue': BLUE_RIBBON}
    rel = Relief(W, H, pals)
    # The oak, its grain along the drum; the arrises where it meets the faces above and below lit and shaded
    t = 0.46 + 0.12 * fibres(W, H, seed, False) + 0.05 * (fbm(W, H, 6, seed + 3) - 0.5)
    t = t + 0.16 * (ys < 1) - 0.22 * (ys > H - 1)
    rel.lay(np.ones((H, W), bool), 'oak', t, 0.0)
    # A bead round each end, a groove inside it
    for x0 in (1.0, W - 4.0):
        bead = (xs >= x0) & (xs < x0 + 3)
        hb = np.sqrt(np.clip(1 - ((xs - x0 - 1.5) / 1.5) ** 2, 0, 1))
        rel.hgt[bead] = 0.9 * hb[bead]
    for xg in (5.0, W - 5.0):
        rel.hgt[np.abs(xs - xg) < 0.6] = -0.6
    # Either side of the roundel, a quatrefoil carved proud in a sunk round, and a sunk drop above and below it
    for lx in (8.6, W - 8.6):
        sunk, _ = disc(lx, cy, 3.3, W, H)
        rel.lay(sunk, 'oak', 0.26, -0.55)
        for (dx, dy) in ((1.4, 0), (-1.4, 0), (0, 1.4), (0, -1.4)):
            m, h = disc(lx + dx, cy + dy, 1.3, W, H)
            rel.lay(m, 'oak', 0.6 + 0.08 * h, 0.1 + 0.7 * h)
        m, h = disc(lx, cy, 0.8, W, H)
        rel.lay(m, 'oak', 0.7, 0.9)
        for (dy, sgn) in ((-10.6, 1), (10.6, -1)):
            m, h, u, v = oval(lx, cy + dy, 2.8, 1.25, math.pi / 2, W, H)
            drop = m & (u * sgn < 0.75 - 0.25 * np.abs(v))
            rel.lay(drop, 'oak', 0.3, -0.5)
            rib = drop & (np.abs(v) < 0.3) & (np.abs(u) < 0.7)
            rel.lay(rib, 'oak', 0.62, 0.2)
    # The roundel: the gilt rim twisted like rope, a dark groove inside it, then the painted field
    rim = (r >= 16.5) & (r < 19.5)
    hr = np.sqrt(np.clip(1 - ((r - 18.0) / 1.5) ** 2, 0, 1))
    twist = np.sin(ang * 20 - r * 1.3) > 0.62
    rel.lay(rim, 'gilt', 0.56 + 0.08 * (fbm(W, H, 4, seed + 4) - 0.5) - 0.12 * twist, 0.9 + 0.7 * hr - 0.4 * twist)
    groove = (r >= 15.7) & (r < 16.5)
    rel.lay(groove, 'ground', 0.12, -0.7)
    fld = r < 15.7
    gt = (GROUND_TONE[kind] + 0.05 * (-(xs - cx) - (ys - cy)) / 15.7 + 0.06 * (fbm(W, H, 5, seed + 7) - 0.5)
          - 0.06 * (r / 15.7) ** 2)
    rel.lay(fld, 'ground', gt, -0.3)
    EMBLEMS[kind](rel, fld, seed + 10)
    # The emblem's shadow on the field, down and to the right
    emb = fld & ~rel.of('ground')
    for (dx, dy, k) in ((1, 1, 0.13), (2, 2, 0.06)):
        sh = shift(emb, dx, dy) & fld & rel.of('ground')
        rel.base = np.where(sh, rel.base - k, rel.base)
    cv = rel.paint()
    # The gilt rubbed through to its red bole here and there, the paint worn at the field's edge
    cv.over(rim & (fbm(W, H, 3, seed + 8) > 0.7) & (rand(W, H, seed + 9) > 0.4), (118, 54, 34), 0.35)
    cv.over(fld & rel.of('ground') & (r > 13.6) & (rand(W, H, seed + 11) > 0.9), OAK[2], 0.5)
    return cv


def med_cap(seed):
    """The drum's end: a pentagon (its corners at 72 degree steps from the right, as the faces' edges are) of end grain
    round the iron boss of its axle, an incised ring and a gilt rose of five petals, its arris chamfered."""
    N = MED_CAP
    xs, ys = grid(N)
    c = N / 2
    R = MED_R * MED_TPX
    pts = [(c + R * math.cos(math.radians(72 * k)), c - R * math.sin(math.radians(72 * k))) for k in range(MED_SIDES)]
    pent = poly(pts, N)
    r = np.hypot(xs - c, ys - c)
    ang = np.arctan2(ys - c, xs - c)
    rel = Relief(N, N, {'oak': OAK, 'gilt': PAL['gold'], 'iron': PAL['iron']})
    wob = fbm(N, N, 9, seed) - 0.5
    rings = np.sin((r + wob * 4.0) * 1.35)
    t = 0.44 + 0.07 * rings + 0.05 * (rand(N, N, seed + 1) - 0.5) + 0.04 * (fbm(N, N, 4, seed + 2) - 0.5)
    rel.lay(pent, 'oak', t, 0.0)
    rng = np.random.default_rng(seed)
    for k in range(3):
        a = rng.uniform(0, 2 * math.pi)
        r0, r1 = rng.uniform(25, 28), rng.uniform(31, 34)
        m = tube([(c + math.cos(a) * r0, c + math.sin(a) * r0), (c + math.cos(a + 0.03) * r1, c + math.sin(a + 0.03) * r1)], 0.55, 0.4, N, N)[0]
        rel.lay(m & pent, 'oak', 0.2, -0.5)
    di = dist_in(pent, 3)
    rel.hgt = np.where(pent & (di <= 2), rel.hgt - 0.45 * (3 - di), rel.hgt)
    ring = np.abs(r - 24.2) < 0.75
    rel.lay(ring & pent, 'oak', 0.24, -0.7)
    bead = np.abs(r - 26.0) < 0.9
    rel.lay(bead & pent, 'oak', 0.5, 0.4 + 0.4 * np.sqrt(np.clip(1 - ((r - 26.0) / 0.9) ** 2, 0, 1)))
    for k in range(MED_SIDES):
        a = math.radians(72 * k)
        m, h, u, v = oval(c + math.cos(a) * 11.0, c - math.sin(a) * 11.0, 7.6, 4.0, -a, N, N)
        vein = np.abs(v) < 0.14
        rel.lay(m, 'gilt', 0.56 + 0.12 * (v < 0) - 0.12 * vein, 0.6 + 0.7 * h - 0.3 * vein)
        a2 = a + math.radians(36)
        m, h, u, v = oval(c + math.cos(a2) * 8.0, c - math.sin(a2) * 8.0, 4.6, 2.5, -a2, N, N)
        rel.lay(m, 'gilt', 0.48 + 0.1 * (v < 0), 0.4 + 0.5 * h)
    m, h = disc(c, c, 5.2, N, N)
    rel.lay(m, 'iron', 0.5 + 0.1 * h, 1.2 + 1.2 * h)
    nut = (np.abs(xs - c) < 2.0) & (np.abs(ys - c) < 2.0)
    rel.lay(nut, 'iron', 0.38 + 0.18 * ((xs < c) & (ys < c)), 2.2)
    cv = rel.paint(strength=1.2)
    cv.over(pent & rel.of('gilt') & (fbm(N, N, 3, seed + 5) > 0.7), (118, 54, 34), 0.3)
    cv.a = np.where(pent, 1.0, 0.0)
    return cv


def paint_medallion():
    """The medallion's sprite: its five faces, one under another in MED_TIMES order, and its end below them."""
    W, H = MED_SPRITE
    out = Canvas(W, H)
    for k, kind in enumerate(MED_TIMES):
        x, y, w, h = MED_WHERE['faces'][k]
        cv = med_face(kind, 600 + 17 * k)
        out.rgb[y - 2:y + h + 2, x - 8:x + w + 8] = np.pad(cv.rgb, ((2, 2), (8, 8), (0, 0)), mode='edge')
        out.a[y - 2:y + h + 2, x - 8:x + w + 8] = 1.0
    x, y, w, h = MED_WHERE['cap']
    cap = med_cap(640)
    out.rgb[y:y + h, x:x + w] = cap.rgb
    out.a[y:y + h, x:x + w] = cap.a
    return out


# ================================================================ the slips' papers

PAPERS = {
    'trouble': [(70, 62, 58), (122, 112, 102), (172, 162, 148), (206, 198, 184), (228, 222, 210), (242, 238, 228)],
    'good': [(76, 62, 42), (130, 112, 78), (184, 164, 120), (216, 198, 152), (234, 220, 176), (246, 236, 202)],
    'price': [(66, 64, 62), (114, 112, 106), (162, 158, 150), (198, 194, 184), (222, 218, 208), (238, 234, 224)],
    'word': PAL['parchment'],
    'year': [(68, 70, 60), (118, 122, 106), (170, 174, 154), (206, 210, 190), (228, 232, 214), (242, 244, 232)],
    'talk': TALK.PALETTE,
    # A printed broadsheet's cheap grey-white, and a builder's buff ruled sheet
    'fortune': [(62, 60, 56), (108, 104, 96), (156, 150, 138), (196, 190, 176), (222, 216, 202), (238, 234, 222)],
    'works': [(70, 58, 40), (122, 106, 76), (172, 154, 116), (208, 192, 152), (230, 216, 180), (244, 234, 204)],
}
PRINT_INK = (30, 26, 26)
WORKS_INK = (46, 52, 104)
RIBBON = [(14, 36, 22), (26, 64, 36), (44, 100, 54), (76, 142, 80), (124, 190, 116)]
LEAF = [(18, 40, 24), (34, 70, 38), (60, 104, 54), (98, 142, 78)]


def paper(kind, seed):
    W, H = SLIP_PX
    xs, ys = grid(W, H)
    cv = Canvas(W, H)
    rng = np.random.default_rng(seed)
    # Torn along the bottom (0-2 texels), cut a little crooked along the top and sides, the corners knocked round
    tear = np.clip(np.round(0.9 + 0.7 * np.sin(np.arange(W) * 0.8 + seed) * rng.random(W) + rng.normal(0, 0.4, W)), 0, 2)
    top = (grain_1d(W, seed + 1, 9.0) > 0.72).astype(float)
    left = (grain_1d(H, seed + 2, 6.0) > 0.74).astype(float)
    right = (grain_1d(H, seed + 3, 6.0) > 0.7).astype(float)
    xi, yi = xs.astype(int), ys.astype(int)
    m = (ys >= top[xi]) & (ys < H - tear[xi]) & (xs >= left[yi]) & (xs < W - right[yi])
    for (cx, cy) in ((0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)):
        m[cy, cx] = False
    if kind == 'word':
        # An old notice: a dog-eared corner
        m &= ~poly([(W - 4.5, 0), (W, 0), (W, 4.5)], W, H)
    pal = PAPERS[kind]
    t = 0.68 + 0.05 * (fbm(W, H, 10, seed + 1) - 0.5) + 0.06 * (ys < 2) - 0.1 * (ys > H - 3)
    cv.put(m, ramp(pal, t))
    cv.put(m, bands(pal, t, 0.1))
    # Soft light over the paper, and the fold where it was carried folded
    cv.over(m, pal[4], 0.25 * np.clip(fbm(W, H, 12, seed + 2) - 0.4, 0, 1))
    cv.over(m & (np.abs(xs - W * 0.52) < 0.6), pal[2], 0.22)
    cv.over(m & (np.abs(xs - W * 0.52 - 1) < 0.6), pal[5], 0.2)
    if kind in ('word', 'year'):
        # Foxing: a few small brown spots of age
        for k in range(5 if kind == 'word' else 2):
            fx, fy = rng.uniform(4, W - 4), rng.uniform(3, H - 4)
            fm, fh = disc(fx, fy, rng.uniform(0.7, 1.4), W, H)
            cv.over(fm & m, pal[1], 0.3)
    # Pin hole at the top middle
    cv.px(W // 2, 2, pal[1])
    if kind == 'trouble':
        dm, h = disc(54.5, 24.5, 4.6, W, H)
        t = 0.42 + 0.35 * light(h * 3) + 0.1 * (rand(W, H, seed + 3) - 0.5)
        cv.put(dm, bands(PAL['wax'], t, 0.2))
        # A drip run down, and the seal's pressed mark
        cv.put(rect(51, 28, 53, 31, W, H) & ~dm, PAL['wax'][2])
        cv.put(rect(57, 28, 58, 30, W, H) & ~dm, PAL['wax'][2])
        ring = dm & (dist_in(dm, 4) == 2)
        cv.over(ring, PAL['wax'][1], 0.6)
        cv.put(rect(53, 23, 56, 24, W, H), PAL['wax'][1])
        cv.put(rect(54, 22, 55, 27, W, H), PAL['wax'][1])
        glint(cv, 52, 22, 1, (250, 190, 170))
    elif kind == 'good':
        rib = rect(2, 0, 7, H, W, H)
        rib &= ~(poly([(2, H), (4.5, H - 3.5), (7, H)], W, H))
        t = 0.55 + 0.25 * (xs < 3.5) - 0.25 * (xs >= 6) + 0.12 * np.sin(ys * 0.9)
        cv.put(rib, bands(RIBBON, t, 0.2))
        cv.over(rect(7, 0, 8, H - 2, W, H) & m, pal[1], 0.45)
        # The ribbon goes through two slits cut in the paper
        for y in (5, 20):
            cv.put(rect(1, y, 8, y + 1, W, H), pal[1])
    elif kind == 'price':
        dust = (np.hypot(xs - 4, ys - 29) < 10) & m
        f = np.clip(1 - np.hypot(xs - 4, ys - 29) / 10, 0, 1)
        cv.over(dust & (rand(W, H, seed + 4) > 0.35), (236, 234, 228), 0.55 * f)
        # A chalky thumb print
        tm, th = ellipse(8.5, 24.5, 3.2, 4.0, W, H)
        rid = tm & (np.sin(np.hypot(xs - 8.5, (ys - 24.5) * 0.8) * 3.1) > 0.2)
        cv.over(rid, (232, 230, 222), 0.5)
    elif kind == 'word':
        # The guild's stamp, inked a little unevenly and not quite square
        cx, cy = 54.5, 23.5
        d = np.hypot(xs - cx, ys - cy)
        ringm = (d > 5.4) & (d < 6.6) & m
        cv.over(ringm & (rand(W, H, seed + 5) > 0.25), (122, 34, 30), 0.75)
        sh = poly([(cx - 2.5, cy - 3), (cx + 2.5, cy - 3), (cx + 2.5, cy + 0.5), (cx, cy + 3.2), (cx - 2.5, cy + 0.5)], W, H)
        cv.over(sh & (rand(W, H, seed + 6) > 0.3), (122, 34, 30), 0.7)
        cv.over(rect(int(cx) - 1, int(cy) - 2, int(cx) + 1, int(cy) + 1, W, H), pal[4], 0.6)
        # Darker, older edges
        cv.over(m & (dist_in(m, 3) == 2), pal[3], 0.3)
    elif kind == 'year':
        # A sprig: a twig, five leaves and two red berries
        twig = [(2, 30), (6, 26), (10, 23), (14, 20)]
        for (x0, y0), (x1, y1) in zip(twig, twig[1:]):
            for s in range(8):
                f = s / 8
                cv.px(int(x0 + (x1 - x0) * f), int(y0 + (y1 - y0) * f), (70, 48, 32))
        for (lx, ly, ang) in ((5, 25, -0.6), (8, 27, 0.5), (9, 21, -0.9), (12, 24, 0.3), (14, 18, -0.4)):
            lm, lh = ellipse(lx + 0.5, ly + 0.5, 2.2, 1.1, W, H)
            t = 0.5 + 0.3 * (lh - 0.5) + 0.2 * (ys < ly)
            cv.put(lm, bands(LEAF, t, 0.2))
        for (bx, by) in ((11, 27), (13, 26)):
            cv.put(rect(bx, by, bx + 2, by + 2, W, H), PAL['wax'][3])
            cv.px(bx, by, PAL['wax'][5])
    elif kind == 'talk':
        # Two heads leaning together over a whisper (talk_slip_art.py)
        TALK.decorate(cv, m, pal, seed)
    elif kind == 'fortune':
        fortune_print(cv, m, pal, seed)
    elif kind == 'works':
        works_ruling(cv, m, pal, seed)
    # The edges a shade darker, as paper's are
    cv.over(m & (dist_in(m, 2) == 1), pal[2], 0.3)
    return cv


def fortune_print(cv, m, pal, seed):
    """The times' broadsheet: printed, so its rules are straight whatever its edges do, a thick and thin border with a
    printer's flower at each corner, and in the bottom right corner a small woodcut of a pair of scales, cut in its
    block with the ground hatched, the ink a little heavy and broken as cheap print is."""
    W, H = SLIP_PX
    xs, ys = grid(W, H)
    inked = m & (rand(W, H, seed + 1) > 0.12)
    # Thick and thin rules round the print, set a hair crooked on the sheet
    sx = 0.35 * (ys - H / 2) / H
    outer = ((np.abs(xs + sx - 2.5) < 0.5) | (np.abs(xs + sx - (W - 2.5)) < 0.5)) & (ys >= 2) & (ys < H - 2)
    outer |= ((np.abs(ys - 2.5) < 0.5) | (np.abs(ys - (H - 2.5)) < 0.5)) & (xs >= 2) & (xs < W - 2)
    cv.over(outer & inked, PRINT_INK, 0.85)
    # A printer's flower in each corner: a dot inside the rule's turn
    for (fx, fy) in ((4, 4), (W - 5, 4), (4, H - 5), (W - 5, H - 5)):
        cv.over(rect(fx, fy, fx + 1, fy + 1, W, H) & m, PRINT_INK, 0.8)
    # The woodcut: a pair of scales level, standing on hatched ground
    lines = np.zeros((H, W), bool)
    for (ax, ay, bx, by, r) in ((53.5, 15.0, 53.5, 25.0, 0.55), (47.5, 16.5, 59.5, 16.5, 0.55),   # the post, the beam
                                (48.5, 17.0, 46.6, 20.6, 0.45), (48.5, 17.0, 50.4, 20.6, 0.45),   # the left pan's cords
                                (58.5, 17.0, 56.6, 20.6, 0.45), (58.5, 17.0, 60.4, 20.6, 0.45),   # the right's
                                (51.0, 25.0, 56.0, 25.0, 0.6)):                                   # the foot
        d, _ = seg_dist(ax, ay, bx, by, W, H)
        lines |= d < r
    for cx in (48.5, 58.5):
        bowl, _ = ellipse(cx, 20.6, 2.4, 1.6, W, H)
        lines |= bowl & (ys >= 20.6)
    finial, _ = disc(53.5, 14.6, 0.9, W, H)
    hatch = rect(46, 26, 61, 28, W, H) & (((xs.astype(int) + ys.astype(int)) % 2) == 0)
    ink = (lines | finial | hatch) & inked
    cv.over(ink, PRINT_INK, 0.88)
    # Ink spread a little into the paper round the cut
    cv.over(grow(ink, 1) & ~ink & m, pal[2], 0.18)


def works_ruling(cv, m, pal, seed):
    """A builder's sheet: ruled in faint squares for reckoning, a red rule down the margin, and the works' stamp in the
    bottom left corner, a hammer and a trowel crossed in two rings, inked blue-black and pressed unevenly."""
    W, H = SLIP_PX
    xs, ys = grid(W, H)
    rows = (((ys.astype(int) - 2) % 4) == 0) & (ys > 3) & (ys < H - 2)
    cols = (((xs.astype(int) - 1) % 8) == 0) & (ys > 3) & (ys < H - 2)
    cv.over((rows | cols) & m, (120, 138, 156), 0.13)
    cv.over((np.abs(xs - 3.5) < 0.5) & m & (ys > 2), (168, 64, 56), 0.36)
    cx, cy = 9.5, 23.2
    d = np.hypot(xs - cx, ys - cy)
    press = 0.6 + 0.4 * np.clip((fbm(W, H, 5, seed + 3) - 0.3) * 2, 0, 1)
    rings = ((d > 7.1) & (d < 8.1)) | ((d > 5.9) & (d < 6.5))
    tools = np.zeros((H, W), bool)
    # The hammer: its shaft from low left to high right, its head across the top end
    dh, _ = seg_dist(6.6, 27.4, 11.6, 21.0, W, H)
    tools |= dh < 0.55
    dh, _ = seg_dist(10.2, 18.9, 13.6, 22.3, W, H)
    tools |= dh < 0.95
    # The trowel: its blade a lozenge high on the left, its handle down to the right
    tools |= poly([(5.0, 20.4), (7.6, 17.8), (9.6, 20.0), (7.0, 22.6)], W, H)
    dt, _ = seg_dist(8.6, 21.6, 12.8, 26.8, W, H)
    tools |= dt < 0.55
    stamp_ink = (rings | (tools & (d < 5.9))) & m & (rand(W, H, seed + 4) > 0.08)
    cv.over(stamp_ink, WORKS_INK, 0.8 * press)


def tack():
    """An iron tack's head, 6x6, and its soft shadow beside it."""
    cv = Canvas(16, 8)
    dm, h = disc(3, 3, 2.6, 16, 8)
    t = 0.45 + 0.45 * light(h * 2.5)
    cv.put(dm, bands(PAL['iron'], t, 0.2))
    cv.px(2, 2, PAL['iron'][5])
    sm = np.hypot(grid(16, 8)[0] - 11, grid(16, 8)[1] - 4) / 3.2
    a = np.clip(1 - sm, 0, 1) ** 1.5 * 0.7
    shadow = a > 0.02
    cv.rgb[shadow] = (12, 8, 8)
    cv.a[shadow] = a[shadow]
    return cv


def slip_shadow():
    """A slip's soft shadow on the planks: a blurred dark card, 64x32."""
    W, H = SLIP_PX
    a = np.zeros((H, W))
    a[3:H - 3, 3:W - 3] = 1.0
    a = blur(a, 2)
    cv = Canvas(W, H)
    cv.rgb[:] = (10, 6, 6)
    cv.a = np.clip(a * 0.85, 0, 1)
    return cv


# ================================================================ the orders' tickets

MANILA = [(86, 64, 36), (142, 114, 68), (190, 160, 106), (218, 190, 136), (236, 212, 162), (248, 232, 194)]
TICKET_RED = (156, 38, 30)


def ticket(seed, printed=True):
    """An order's ticket: a manila tag with its top corners cut off and an eyelet ringed with card for the tack, printed
    with a red rule round it and one under its head (the renderer inks the head, the good, how many and whose on it).
    Not printed, it's the tag's back."""
    W, H = TICKET_PX
    xs, ys = grid(W, H)
    cv = Canvas(W, H)
    rng = np.random.default_rng(seed)
    cut = 6.5
    shape = (xs + ys >= cut) & ((W - xs) + ys >= cut)
    # Cut a little unevenly: a nick out of an edge here and there
    edge_px = shape & (dist_in(shape, 2) == 1)
    m = shape & ~(edge_px & (rng.random((H, W)) < 0.12) & (ys > cut))
    pal = MANILA
    t = 0.62 + 0.05 * (fbm(W, H, 8, seed + 1) - 0.5) + 0.04 * (rand(W, H, seed + 2) - 0.5) + 0.05 * (ys < 3) - 0.07 * (ys > H - 3)
    cv.put(m, ramp(pal, t))
    cv.put(m, bands(pal, t, 0.12))
    # A faint crease across, where it was carried folded, and a smudge of dirt low down
    cv.over(m & (np.abs(ys - H * 0.58) < 0.6), pal[2], 0.18)
    cv.over(m & (np.abs(ys - H * 0.58 - 1) < 0.6), pal[5], 0.15)
    sm, _ = ellipse(W * 0.78, H * 0.86, 3.0, 2.2, W, H)
    cv.over(sm & m, pal[1], 0.12)
    # The eyelet: a ring of card glued on, the hole through it
    ex, ey = EYELET
    ring, rh = disc(ex, ey, 2.8, W, H)
    cv.put(ring & m, bands(pal, 0.74 + 0.18 * light(rh * 2), 0.1))
    cv.over(ring & m & (dist_in(ring, 2) == 1), pal[2], 0.6)
    hole, _ = disc(ex, ey, 1.25, W, H)
    cv.put(hole, (44, 30, 20))
    if printed:
        # Printed before it was cut, so the rule keeps to the tag's own shape; the eyelet's card over it
        ink = m & ~ring & (rand(W, H, seed + 3) > 0.1)
        cv.over((dist_in(shape, 4) == 3) & ink, TICKET_RED, 0.82)
        cv.over((ys >= 12) & (ys < 13) & (xs >= 3) & (xs < W - 3) & ink, TICKET_RED, 0.85)
    # The edges a shade darker, as card's are
    cv.over(m & (dist_in(m, 2) == 1), pal[2], 0.35)
    return cv


def stamp(seed):
    """The stamp put on a ticket whose order is taken: a red frame, inked unevenly (the renderer inks the word inside
    it and turns it)."""
    W, H = STAMP_PX
    cv = Canvas(W, H)
    box = rect(1, 1, W - 1, H - 1, W, H)
    for (cx, cy) in ((1, 1), (W - 2, 1), (1, H - 2), (W - 2, H - 2)):
        box[cy, cx] = False
    a = np.where(box & (dist_in(box, 3) <= 2), 0.92, 0.0)
    a = a * (rand(W, H, seed) > 0.1) * (0.8 + 0.2 * fbm(W, H, 4, seed + 1))
    cv.rgb[:] = TICKET_RED
    cv.a = np.clip(a, 0, 1)
    return cv


# ================================================================ atlases

class Packer:
    """Shelf packing into a fixed width, with a gutter round every cell so mip levels don't bleed."""

    def __init__(self, width, gutter=2):
        self.w, self.g = width, gutter
        self.x = self.y = gutter
        self.row = 0
        self.cells = []

    def add(self, w, h):
        if self.x + w + self.g > self.w:
            self.x = self.g
            self.y += self.row + self.g
            self.row = 0
        at = (self.x, self.y)
        self.x += w + self.g
        self.row = max(self.row, h)
        return at

    def height(self):
        h = self.y + self.row + self.g
        return int(math.ceil(h / 16.0) * 16)


def letters_atlas():
    """Packs the chalk glyphs (whole and in pieces), the ink glyphs, and the chalk's marks into one RGBA sheet.
    Gives the image and a table of what's where."""
    chalk = ML.build(ML.CHALK, True)
    ink = ML.build(ML.INK, False)
    extras = {
        'underline': ML.chalk_line(40.0, 3, wave=0.18),
        'strike': ML.chalk_line(30.0, 8, wave=0.35, width=0.9),
        'swash': ML.chalk_line(56.0, 11, wave=0.3),
        'smudge': ML.smudge(40.0, 6.0, 21),
        'specks': ML.specks(31),
        'puff': ML.puff(41),
    }
    items = []      # (key, alpha or (rgb, a), box)
    for ch, e in chalk.items():
        if e['full'] is not None:
            items.append((('chalk', ch, -1), e['full'][0], e['full'][1]))
        for k, (a, box, axis, d, pts) in enumerate(e['pieces']):
            if a is not None:
                items.append((('chalk', ch, k), a, box))
    for ch, e in ink.items():
        if e['full'] is not None:
            items.append((('ink', ch, -1), e['full'][0], e['full'][1]))
    for name, (a, box) in extras.items():
        items.append((('extra', name, -1), a, box))
    stick_rgb, stick_a = ML.stick()
    items.append((('extra', 'stick', -1), (stick_rgb, stick_a), (0, 0, 4, 12)))
    # Tallest first packs tightest
    def hw(it):
        a = it[1] if not isinstance(it[1], tuple) else it[1][1]
        return a.shape
    items.sort(key=lambda it: (-hw(it)[0], -hw(it)[1]))
    pk = Packer(512)
    where = {}
    for key, a, box in items:
        h, w = hw((key, a, box))
        where[key] = (pk.add(w, h), (w, h), box)
    W, H = 512, pk.height()
    img = np.zeros((H, W, 4))
    for key, a, box in items:
        (x, y), (w, h), _ = where[key]
        if isinstance(a, tuple):
            img[y:y + h, x:x + w, :3] = a[0]
            img[y:y + h, x:x + w, 3] = a[1] * 255
        else:
            img[y:y + h, x:x + w, :3] = 255
            img[y:y + h, x:x + w, 3] = a * 255
    out = Image.fromarray(np.clip(img, 0, 255).round().astype(np.uint8), 'RGBA')
    return out, where, chalk, ink


def slips_atlas():
    W = 64
    parts = [(k, paper(k, 300 + i)) for i, k in enumerate(SLIP_KINDS)]
    below = SLIP_PX[1] * (len(parts) + 1) + 16
    H = int(math.ceil((below + TICKET_PX[1] + STAMP_PX[1]) / 16.0) * 16)
    img = Canvas(W, H)
    where = {}
    y = 0
    for k, cv in parts:
        img.rgb[y:y + 32, :, :] = cv.rgb
        img.a[y:y + 32, :] = cv.a
        where[k] = (0, y, 64, 32)
        y += 32
    sh = slip_shadow()
    img.rgb[y:y + 32] = sh.rgb
    img.a[y:y + 32] = sh.a
    where['shadow'] = (0, y, 64, 32)
    y += 32
    tk = tack()
    img.rgb[y:y + 8, 0:16] = tk.rgb
    img.a[y:y + 8, 0:16] = tk.a
    where['tack'] = (0, y, 6, 6)
    where['tack_shadow'] = (8, y, 7, 8)
    # An order's ticket; its back beside it, mirrored as it's seen from behind; the stamp under them
    y = below
    tw, th = TICKET_PX
    front = ticket(320)
    back = ticket(320, printed=False)
    img.rgb[y:y + th, 0:tw] = front.rgb
    img.a[y:y + th, 0:tw] = front.a
    where['ticket'] = (0, y, tw, th)
    img.rgb[y:y + th, tw:2 * tw] = back.rgb[:, ::-1]
    img.a[y:y + th, tw:2 * tw] = back.a[:, ::-1]
    where['ticket_back'] = (tw, y, tw, th)
    y += th
    st = stamp(321)
    sw, sh = STAMP_PX
    img.rgb[y:y + sh, 0:sw] = st.rgb
    img.a[y:y + sh, 0:sw] = st.a
    where['stamp'] = (0, y, sw, sh)
    return img.image(), where


# ================================================================ models

def face(uv, tex, rotation=0):
    f = {'uv': [round(v, 4) for v in uv], 'texture': tex}
    if rotation:
        f['rotation'] = rotation
    return f


def local_uv(d, frm, to):
    fx, fy, fz = frm
    tx_, ty_, tz = to
    return {
        'down': (fx, 16 - tz, tx_, 16 - fz), 'up': (fx, fz, tx_, tz),
        'north': (16 - tx_, 16 - ty_, 16 - fx, 16 - fy), 'south': (fx, 16 - ty_, tx_, 16 - fy),
        'west': (fz, 16 - ty_, tz, 16 - fy), 'east': (16 - tz, 16 - ty_, 16 - fz, 16 - fy),
    }[d]


class Box:
    """A box in board coordinates: X0..X1 (viewer's left to right), Y0..Y1 (up), Z0..Z1 (front to back), with a
    material for its front (north) face, its back (south) face and the rest."""

    def __init__(self, X, Y, Z, front='#front', back='#back', side='#wood', top=None, rotation=None, faces=None,
                 top_uv=None, above=False):
        self.X, self.Y, self.Z = X, Y, Z
        self.front, self.back, self.side, self.top = front, back, side, top or side
        self.rotation = rotation
        self.faces = faces
        self.top_uv = top_uv
        self.above = above          # stands over the board's top (the top row's cells take it whole)


def boxes():
    out = []
    for (x0, x1) in (POST_L, POST_R):
        out.append(Box((x0, x1), POST_Y, POST_Z, side='#post'))
    for (x0, x1) in (COLLAR_L, COLLAR_R):
        out.append(Box((x0, x1), COLLAR_Y, COLLAR_Z, side='#trim_stone', back='#back'))
    out.append(Box(RAIL_X, RAIL_Y, RAIL_Z))
    out.append(Box(LEDGE_X, LEDGE_Y, LEDGE_Z))
    out.append(Box(PANEL_X, PANEL_Y, PANEL_Z))
    out.append(Box(MULLION_X, PANEL_Y, MULLION_Z, side='#post'))
    out.append(Box(HEADER_X, HEADER_Y, HEADER_Z))
    for (y0, y1) in BATTENS_Y:
        out.append(Box(BATTEN_X, (y0, y1), BATTEN_Z, front='#wood'))
    out.append(Box(HEADER_X, RIDGE_BEAM_Y, RIDGE_BEAM_Z, front='#wood', back='#wood'))
    for mirror in (False, True):
        for (x0, x1, y0, y1, z0, z1) in STRAPS:
            if mirror:
                x0, x1 = 48 - x1, 48 - x0
            out.append(Box((x0, x1), (y0, y1), (z0, z1), back='#trim_iron', side='#trim_iron'))
    for (x0, x1, y0, y1, z0, z1) in STUBS:
        out.append(Box((x0, x1), (y0, y1), (z0, z1), front='#trim_chalk', back='#trim_chalk', side='#trim_chalk'))
    # The cap's two boards, pitched from the ridge, and the ridge cap over their seam
    L = CAP_HALF / math.cos(math.radians(PITCH)) + 0.05
    out.append(Box(HEADER_X, (CAP_UNDER, CAP_UNDER + CAP_T), (RIDGE_Z - L, RIDGE_Z + 0.02), front='#wood', back='#wood',
                   top='#cap', rotation=('x', -PITCH, RIDGE_Z), top_uv='front'))
    out.append(Box(HEADER_X, (CAP_UNDER, CAP_UNDER + CAP_T), (RIDGE_Z - 0.02, RIDGE_Z + L), front='#wood', back='#wood',
                   top='#cap', rotation=('x', PITCH, RIDGE_Z), top_uv='back'))
    out.append(Box(HEADER_X, RIDGE_CAP_Y, RIDGE_CAP_Z, front='#trim_iron', back='#trim_iron', side='#trim_iron'))
    # The medallion's frame over the ridge: the plinth, the two posts with a knob on each, the axle's iron ends
    out.append(Box(PLINTH_X, PLINTH_Y, PLINTH_Z, front='#wood', back='#wood', above=True))
    for (x0, x1) in MED_POSTS:
        out.append(Box((x0, x1), MED_POST_Y, MED_POST_Z, front='#post', back='#post', side='#post', above=True))
        out.append(Box((x0 + 0.25, x1 - 0.25), (MED_POST_Y[1], MED_POST_Y[1] + 0.9), (MED_POST_Z[0] + 0.25, MED_POST_Z[1] - 0.25),
                       front='#post', back='#post', side='#post', above=True))
    for (x0, x1) in ((MED_POSTS[0][0] - 0.3, MED_POSTS[0][0]), (MED_POSTS[1][1], MED_POSTS[1][1] + 0.3)):
        out.append(Box((x0, x1), (MED_Y - 0.55, MED_Y + 0.55), (MED_Z - 0.55, MED_Z + 0.55), front='#trim_iron', back='#trim_iron',
                       side='#trim_iron', above=True))
    return out


TRIM_QUADS = {'#trim_iron': (0, 0), '#trim_chalk': (8, 0), '#trim_stone': (0, 8), '#trim_paper': (8, 8)}


def in_tile(uv):
    """A face's uv moved so it lies in 0..16: by whole tiles, and if it then runs over a tile's edge, back off that edge
    (a face less than a tile across, on a material that tiles, so where on it a face starts doesn't show)."""
    u0, v0, u1, v1 = uv
    du = 16 * math.floor(min(u0, u1) / 16 + 1e-6)
    dv = 16 * math.floor(min(v0, v1) / 16 + 1e-6)
    du += max(0.0, max(u0, u1) - du - 16)
    dv += max(0.0, max(v0, v1) - dv - 16)
    out = (u0 - du, v0 - dv, u1 - du, v1 - dv)
    assert all(-1e-6 <= x <= 16 + 1e-6 for x in out), f'a face over the board is wider than a tile: {uv}'
    return tuple(round(x, 4) for x in out)


def tex_and_uv(mat, d, frm, to):
    """A material's texture and uv for one face: the trim quadrants squeeze the local uv into their 8x8."""
    if mat in TRIM_QUADS:
        u0, v0 = TRIM_QUADS[mat]
        a, b, c, e = local_uv(d, frm, to)
        return '#trim', (u0 + (a % 16) / 2, v0 + (b % 16) / 2, u0 + ((c - 0.001) % 16 + 0.001) / 2, v0 + ((e - 0.001) % 16 + 0.001) / 2)
    return mat, local_uv(d, frm, to)


def cell_elements(col, row):
    """The elements of one cell, from every box that reaches into it, in the cell's own coordinates."""
    off_x = (col + 1) * 16
    off_y = row * 16
    elements = []
    for b in boxes():
        X0, X1 = max(b.X[0], off_x), min(b.X[1], off_x + 16)
        if b.rotation is None and not b.above:
            Y0, Y1 = max(b.Y[0], off_y), min(b.Y[1], off_y + 16)
        else:
            Y0, Y1 = b.Y
            # A pitched board, and what stands over the board, belong to the top row only
            if row != 1:
                continue
        if X1 - X0 <= 1e-6 or Y1 - Y0 <= 1e-6:
            continue
        frm = [16 - (X1 - off_x), Y0 - off_y, b.Z[0]]
        to = [16 - (X0 - off_x), Y1 - off_y, b.Z[1]]
        faces = {}
        internal_left = X0 > b.X[0] + 1e-6        # the box goes on into the cell to the viewer's left
        internal_right = X1 < b.X[1] - 1e-6
        internal_down = b.rotation is None and not b.above and Y0 > b.Y[0] + 1e-6
        internal_up = b.rotation is None and not b.above and Y1 < b.Y[1] - 1e-6
        for d in ('north', 'south', 'east', 'west', 'up', 'down'):
            # East is the viewer's left as seen from the front (models face north)
            if d == 'east' and internal_left or d == 'west' and internal_right:
                continue
            if d == 'up' and internal_up or d == 'down' and internal_down:
                continue
            if d == 'north':
                if b.front == '#front':
                    faces[d] = face((X0 / 3, (32 - Y1) / 2, X1 / 3, (32 - Y0) / 2), '#front')
                else:
                    t, uv = tex_and_uv(b.front, d, frm, to)
                    faces[d] = face(uv, t)
            elif d == 'south':
                if b.back == '#back':
                    faces[d] = face(((48 - X1) / 3, (32 - Y1) / 2, (48 - X0) / 3, (32 - Y0) / 2), '#back')
                else:
                    t, uv = tex_and_uv(b.back, d, frm, to)
                    faces[d] = face(uv, t)
            elif d == 'up' and b.top == '#cap':
                # Tile rows run along the board; the eave's edge of each row is its lit lower edge
                u0, u1 = frm[0], to[0]
                # The front board's +v must run toward the eave (north, -z), the back board's toward the south
                if b.top_uv == 'front':
                    faces[d] = face((u0, 12, u1, 16), '#cap', rotation=180)
                else:
                    faces[d] = face((u0, 12, u1, 16), '#cap')
            else:
                mat = b.top if d == 'up' else b.side
                t, uv = tex_and_uv(mat, d, frm, to)
                faces[d] = face(uv, t)
        if b.above:
            # Over the cell's top its local uv runs past 16: back onto the tile, as each face is narrower than one
            for d, f in faces.items():
                f['uv'] = list(in_tile(f['uv']))
        e = {'from': [round(v, 4) for v in frm], 'to': [round(v, 4) for v in to], 'faces': faces}
        if b.rotation is not None:
            axis, angle, rz = b.rotation
            e['rotation'] = {'origin': [8, round(CAP_UNDER - off_y, 4), rz], 'axis': axis, 'angle': angle}
        elements.append(e)
    return elements


def model(part):
    col, row = PARTS[part]
    return {
        'textures': {
            'front': 'guildmark:block/market_board_front',
            'back': 'guildmark:block/market_board_back',
            'wood': 'guildmark:block/market_board_wood',
            'post': 'guildmark:block/market_board_post',
            'cap': 'guildmark:block/market_board_cap',
            'trim': 'guildmark:block/market_board_trim',
            'particle': 'guildmark:block/market_board_wood',
        },
        'elements': cell_elements(col, row),
    }


# ================================================================ Kotlin

def cell_ints(where, key):
    (x, y), (w, h), box = where[key]
    return [x, y, w, h, int(round(box[0] * 1000)), int(round(box[1] * 1000))]


def glyph_data(glyphs, where, style):
    ints = []
    for ch, e in glyphs.items():
        ints += [ord(ch), int(round(e['advance'] * 100))]
        if e['full'] is None:
            ints.append(0)
        else:
            ints.append(1)
            ints += cell_ints(where, (style, ch, -1))
        ps = e['pieces']
        ints.append(len(ps))
        for k, (a, box, axis, d, pts) in enumerate(ps):
            if a is None:
                ints.append(0)
            else:
                ints.append(1)
                ints += cell_ints(where, (style, ch, k))
            ints += [axis, d, len(pts)]
            for (x, y) in pts:
                ints += [int(round(x * 100)), int(round(y * 100))]
    return ','.join(str(i) for i in ints)


def chunks(s, n=60000):
    return [s[i:i + n] for i in range(0, len(s), n)]


def kotlin(where, chalk, ink, letters_size, slips_where, slips_size):
    lines = []
    w = lines.append
    w('// GENERATED by art/scripts/market_board_art.py: edit the script, not this file.')
    w('package io.github.vaspyyy.guildmark.client')
    w('')
    for f in ('cos', 'exp', 'max', 'sin', 'sqrt'):
        w(f'import kotlin.math.{f}')
    w('')
    w('/**')
    w(' * The market board\'s art as the renderer draws it (see [MarketBoardRenderer]): where every chalk and ink glyph, each')
    w(' * piece of a chalk glyph and every other mark sits in the lettering atlas, the numbers the face is laid out by, and')
    w(' * the medallion\'s faces and how it turns.')
    w(' * The same numbers lay out the script\'s previews, so the game and the pictures agree. Sizes are blocks unless')
    w(' * they say font pixels; atlas cells are texels of their sprite.')
    w(' */')
    w('internal object MarketBoardArt {')
    w('    const val LETTERS = "block/market_board/letters"')
    w('    const val SLIPS = "block/market_board/slips"')
    w(f'    const val LETTERS_W = {letters_size[0]}')
    w(f'    const val LETTERS_H = {letters_size[1]}')
    w(f'    const val SLIPS_W = {slips_size[0]}')
    w(f'    const val SLIPS_H = {slips_size[1]}')
    w('    const val MEDALLION = "block/market_board/medallion"')
    w(f'    const val MEDALLION_W = {MED_SPRITE[0]}')
    w(f'    const val MEDALLION_H = {MED_SPRITE[1]}')
    w('')
    w('    /** Texels of the atlas to a font pixel. */')
    w(f'    const val TEXELS = {ML.T}')
    w('')
    w('    /** The board\'s own characters: the Guild Mark\'s coin and the trend arrows. */')
    w(f'    const val COIN = \'\\u{ord(ML.COIN):04x}\'')
    w(f'    const val UP = \'\\u{ord(ML.UP):04x}\'')
    w(f'    const val DOWN = \'\\u{ord(ML.DOWN):04x}\'')
    w('')
    for k, v in LAYOUT.items():
        w(f'    const val {k} = {v:.6f}f')
    w('')
    for k, v in COLOURS.items():
        w(f'    const val {k} = 0x{v:06X}')
    w('')
    w('    /** How many of MarketBoards.ORDER\'s goods each section takes, and the column of the slate it\'s in. */')
    w('    val SECTION_SIZES = intArrayOf(' + ', '.join(str(n) for n in SECTIONS) + ')')
    w('    val SECTION_COLUMNS = intArrayOf(' + ', '.join(str(n) for n in SECTION_COLUMN) + ')')
    w('')
    w('    /** Cells of the lettering atlas that aren\'t glyphs: x, y, w, h in texels, then the top left in font px x1000. */')
    for name in ('underline', 'strike', 'swash', 'smudge', 'specks', 'puff', 'stick'):
        w(f'    val {name.upper()} = intArrayOf({", ".join(str(i) for i in cell_ints(where, ("extra", name, -1)))})')
    w('')
    w('    /** The slips\' papers in Slip.Kind order, their shadow and the tack: x, y, w, h in texels of the slips sprite. */')
    w('    val PAPERS = arrayOf(' + ', '.join('intArrayOf(%d, %d, %d, %d)' % slips_where[k] for k in SLIP_KINDS) + ')')
    w('    val PAPER_KINDS = arrayOf(' + ', '.join(f'"{k}"' for k in SLIP_KINDS) + ')')
    w('    /** Where on each paper (texels of 64x32) its seal, ribbon, dust, stamp, sprig, heads or woodcut is: the writing keeps off it. */')
    w('    val PAPER_KEEP = arrayOf(' + ', '.join('intArrayOf(%d, %d, %d, %d)' % SLIP_KEEP[k] for k in SLIP_KINDS) + ')')
    w('    val SLIP_SHADOW = intArrayOf(%d, %d, %d, %d)' % slips_where['shadow'])
    w('    val TACK = intArrayOf(%d, %d, %d, %d)' % slips_where['tack'])
    w('    val TACK_SHADOW = intArrayOf(%d, %d, %d, %d)' % slips_where['tack_shadow'])
    w('    /** An order\'s ticket, its back (mirrored, as it\'s seen from behind) and the stamp on one taken: x, y, w, h in texels. */')
    w('    val TICKET = intArrayOf(%d, %d, %d, %d)' % slips_where['ticket'])
    w('    val TICKET_BACK = intArrayOf(%d, %d, %d, %d)' % slips_where['ticket_back'])
    w('    val STAMP = intArrayOf(%d, %d, %d, %d)' % slips_where['stamp'])
    w('')
    w('    /**')
    w('     * The medallion\'s faces, one for each of the town\'s times in Times order (economy/fortune/Fortunes.kt\'s ids), and')
    w('     * its end: x, y, w, h in texels of its sprite. Face k is the one showing when the drum has turned k fifths of a')
    w('     * turn; its end\'s pentagon has a corner at the sprite cell\'s right, the rest every fifth of a turn round.')
    w('     */')
    w('    val MEDALLION_TIMES = arrayOf(' + ', '.join(f'"{k}"' for k in MED_TIMES) + ')')
    w('    val MEDALLION_FACES = arrayOf(' + ', '.join('intArrayOf(%d, %d, %d, %d)' % f for f in MED_WHERE['faces']) + ')')
    w('    val MEDALLION_CAP = intArrayOf(%d, %d, %d, %d)' % MED_WHERE['cap'])
    w('')
    w('    /**')
    w('     * How far through a turn of [faces] faces (1 or more) the medallion is [age] ticks after it began: 0 to 1, a')
    w('     * little past 1 as it swings onto its catch and back, and 1 from [medallionStill] on. A heavy drum let go: a')
    w('     * spring\'s step, damped by MED_ZETA, slower the further it has to go.')
    w('     */')
    w('    fun medallionTurn(age: Float, faces: Float): Float {')
    w('        if (age <= 0f) return 0f')
    w('        if (age >= medallionStill(faces)) return 1f')
    w('        val w = MED_OMEGA / sqrt(max(1f, faces))')
    w('        val d = sqrt(1f - MED_ZETA * MED_ZETA)')
    w('        return 1f - exp(-MED_ZETA * w * age) * (cos(w * d * age) + MED_ZETA / d * sin(w * d * age))')
    w('    }')
    w('')
    w('    /** The ticks a turn of [faces] faces takes to come still. */')
    w('    fun medallionStill(faces: Float): Float = MED_STILL * sqrt(max(1f, faces))')
    w('')
    w('    /**')
    w('     * Every glyph, one after another: its character, its advance (font px x100), whether it has a cell and the cell,')
    w('     * then its pieces, each with whether it has a cell, the cell, the axis it is swept along (0 x, 1 y), which way,')
    w('     * and its path for the chalk\'s tip (font px x100). Ink glyphs have no pieces.')
    w('     */')
    c = chunks(glyph_data(chalk, where, 'chalk'))
    w('    val CHALK_GLYPHS: String get() = ' + ' + '.join('CHALK_GLYPHS_%d' % i for i in range(len(c))))
    for i, s in enumerate(c):
        w(f'    private const val CHALK_GLYPHS_{i} = "{s}"')
    c = chunks(glyph_data(ink, where, 'ink'))
    w('    val INK_GLYPHS: String get() = ' + ' + '.join('INK_GLYPHS_%d' % i for i in range(len(c))))
    for i, s in enumerate(c):
        w(f'    private const val INK_GLYPHS_{i} = "{s}"')
    w('}')
    return '\n'.join(lines) + '\n'


# ================================================================ main

def build(assets):
    out = {}
    out['block/market_board_front'] = paint_front().image()
    out['block/market_board_back'] = paint_back().image()
    out['block/market_board_wood'] = tile_wood(False, 210).image()
    out['block/market_board_post'] = tile_wood(True, 211, base=0.47).image()
    out['block/market_board_cap'] = paint_cap().image()
    out['block/market_board_trim'] = paint_trim().image()
    out['item/market_board'] = paint_item().image()
    letters, where, chalk, ink = letters_atlas()
    out['block/market_board/letters'] = letters
    slips, slips_where = slips_atlas()
    out['block/market_board/slips'] = slips
    out['block/market_board/medallion'] = paint_medallion().image()
    return out, where, chalk, ink, slips_where


def main():
    assets = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    jar = sys.argv[3] if len(sys.argv) > 3 else None
    imgs, where, chalk, ink, slips_where = build(assets)
    for key, img in imgs.items():
        path = os.path.join(assets, 'textures', key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
    os.makedirs(os.path.join(assets, 'models', 'block'), exist_ok=True)
    os.makedirs(os.path.join(assets, 'models', 'item'), exist_ok=True)
    for part in PARTS:
        with open(os.path.join(assets, 'models', 'block', f'market_board_{part}.json'), 'w') as f:
            json.dump(model(part), f, indent=2)
            f.write('\n')
    with open(os.path.join(assets, 'models', 'item', 'market_board.json'), 'w') as f:
        json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/market_board'}}, f, indent=2)
        f.write('\n')
    # assets is .../src/main/resources/assets/guildmark: the Kotlin goes under .../src/main/kotlin
    kt = os.path.join(os.path.abspath(os.path.join(assets, '..', '..', '..', 'kotlin')), 'io', 'github', 'vaspyyy', 'guildmark', 'client', 'MarketBoardArt.kt')
    os.makedirs(os.path.dirname(kt), exist_ok=True)
    with open(kt, 'w') as f:
        f.write(kotlin(where, chalk, ink, imgs['block/market_board/letters'].size, slips_where,
                       imgs['block/market_board/slips'].size))
    if prev:
        import market_board_preview as MP
        MP.medallion_previews(assets, prev, imgs)
        # The face filled in needs the goods' icons, from the game's jar
        if jar:
            MP.previews(assets, prev, jar, imgs, where, chalk, ink, slips_where)


if __name__ == '__main__':
    main()
