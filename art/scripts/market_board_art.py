"""The capitals' market board: a dark slate in a sturdy dark wooden frame, standing free on two posts sunk in stone
collars, with a carved header plank under a little pitched cap of slate tiles. The slate on the left two thirds takes
the crier's chalk (the prices, drawn live by client/MarketBoardRenderer.kt); a narrow panel of planks on the right,
behind a mullion, takes the news slips. Iron straps hold the frame's corners, a ledge under the slate keeps two stubs
of chalk, and the back is finished: planks, two battens bolted across the posts, and the guild's crest burnt into the
back of the header.

    python3 -B art/scripts/market_board_art.py src/main/resources/assets/guildmark [preview-dir [minecraft client jar]]

Writes, all at 32x with the HD art kit (hdkit.py):
  textures/block/market_board_{front,back,wood,post,cap,trim}.png  the board's painting and materials
  textures/block/market_board/letters.png                           the chalk and ink lettering atlas (see
                                                                     market_board_letters.py), the chalk stick, dust
  textures/block/market_board/slips.png                             the five papers a slip can be on, a tack, shadows
  textures/item/market_board.png                                    the item icon
  models/block/market_board_<part>.json                             the six cells (the blockstate already picks them)
  models/item/market_board.json                                     points at the icon
and the Kotlin tables the renderer draws from, src/main/kotlin/.../client/MarketBoardArt.kt (GENERATED: every
atlas cell, every glyph's pieces and the face's layout numbers, so the game and the previews lay the board out the
same way). With a preview dir it also writes the previews (see market_board_preview.py); the minecraft jar is where
the goods' item icons come from for the offline face render.

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
                   grow, dist_in, light, outline, glint, bevel)
import market_board_letters as ML

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
}
LAYOUT['ITEM_PITCH'] = ((PANEL_V[1] - PANEL_V[0]) - LAYOUT['ROWS_TOP'] - LAYOUT['PAD_B'] - LAYOUT['HEAD_PITCH']) / 10.0
LAYOUT['COL_W'] = ((SLATE_U[1] - SLATE_U[0]) - LAYOUT['PAD_L'] - LAYOUT['PAD_R']) / 3.0
# The key to the coin goes at the foot of the first column, on the last row's line (from the panel's top)
LAYOUT['LEGEND_BASE'] = ((PANEL_V[1] - PANEL_V[0]) - LAYOUT['PAD_B'] - 0.5 * LAYOUT['ITEM_PITCH']
                         + LAYOUT['TEXT_DROP'] * LAYOUT['S_PRICE'])

# The goods' sections, in MarketBoards.ORDER's order: how many goods each takes, and the column it's chalked in
SECTIONS = [2, 5, 2, 4, 10]
SECTION_COLUMN = [0, 0, 1, 1, 2]

# Chalk colours (the game tints white chalk with these) and the ink's
COLOURS = {
    'CHALK': 0xECE7DC, 'CHALK_DEAR': 0xF0A89A, 'CHALK_CHEAP': 0xAEDCA0, 'CHALK_HEAD': 0xE8D79E, 'CHALK_FRESH': 0xFFFDF6,
    'INK': 0x2A1E1A, 'INK_TROUBLE': 0x5A1414, 'GILT': 0xE2BC6C, 'GILT_SHADOW': 0x24170F, 'GILT_GLINT': 0xFFF0C0,
}

# The papers, in Slip.Kind's order, and for each the corner its decoration takes (texels of the 64x32 paper; text
# keeps clear of it)
SLIP_KINDS = ['trouble', 'good', 'price', 'word', 'year']
SLIP_PX = (64, 32)
SLIP_KEEP = {'trouble': (47, 17, 64, 32), 'good': (0, 0, 9, 32), 'price': (0, 21, 16, 32), 'word': (45, 15, 64, 32),
             'year': (0, 18, 17, 32)}


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
    cv.put(rect(15, 2, 17, 4, N), PAL['darkwood'][3])
    # Iron straps at the corners
    for (bx, by) in ((2, 23), (28, 23), (2, 11), (28, 11)):
        cv.put(rect(bx, by, bx + 2, by + 2, N), PAL['iron'][3])
        cv.px(bx, by, PAL['iron'][5])
    outline(cv, 0.8)
    return cv


# ================================================================ the slips' papers

PAPERS = {
    'trouble': [(70, 62, 58), (122, 112, 102), (172, 162, 148), (206, 198, 184), (228, 222, 210), (242, 238, 228)],
    'good': [(76, 62, 42), (130, 112, 78), (184, 164, 120), (216, 198, 152), (234, 220, 176), (246, 236, 202)],
    'price': [(66, 64, 62), (114, 112, 106), (162, 158, 150), (198, 194, 184), (222, 218, 208), (238, 234, 224)],
    'word': PAL['parchment'],
    'year': [(68, 70, 60), (118, 122, 106), (170, 174, 154), (206, 210, 190), (228, 232, 214), (242, 244, 232)],
}
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
    # The edges a shade darker, as paper's are
    cv.over(m & (dist_in(m, 2) == 1), pal[2], 0.3)
    return cv


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
    H = SLIP_PX[1] * (len(parts) + 1) + 16
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
                 top_uv=None):
        self.X, self.Y, self.Z = X, Y, Z
        self.front, self.back, self.side, self.top = front, back, side, top or side
        self.rotation = rotation
        self.faces = faces
        self.top_uv = top_uv


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
    return out


TRIM_QUADS = {'#trim_iron': (0, 0), '#trim_chalk': (8, 0), '#trim_stone': (0, 8), '#trim_paper': (8, 8)}


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
        if b.rotation is None:
            Y0, Y1 = max(b.Y[0], off_y), min(b.Y[1], off_y + 16)
        else:
            Y0, Y1 = b.Y
            # A pitched board belongs to the top row only
            if row != 1:
                continue
        if X1 - X0 <= 1e-6 or Y1 - Y0 <= 1e-6:
            continue
        frm = [16 - (X1 - off_x), Y0 - off_y, b.Z[0]]
        to = [16 - (X0 - off_x), Y1 - off_y, b.Z[1]]
        faces = {}
        internal_left = X0 > b.X[0] + 1e-6        # the box goes on into the cell to the viewer's left
        internal_right = X1 < b.X[1] - 1e-6
        internal_down = b.rotation is None and Y0 > b.Y[0] + 1e-6
        internal_up = b.rotation is None and Y1 < b.Y[1] - 1e-6
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
    w('/**')
    w(' * The market board\'s art as the renderer draws it (see [MarketBoardRenderer]): where every chalk and ink glyph, each')
    w(' * piece of a chalk glyph and every other mark sits in the lettering atlas, and the numbers the face is laid out by.')
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
    w('    /** Where on each paper (texels of 64x32) its seal, ribbon, dust, stamp or sprig is: the writing keeps off it. */')
    w('    val PAPER_KEEP = arrayOf(' + ', '.join('intArrayOf(%d, %d, %d, %d)' % SLIP_KEEP[k] for k in SLIP_KINDS) + ')')
    w('    val SLIP_SHADOW = intArrayOf(%d, %d, %d, %d)' % slips_where['shadow'])
    w('    val TACK = intArrayOf(%d, %d, %d, %d)' % slips_where['tack'])
    w('    val TACK_SHADOW = intArrayOf(%d, %d, %d, %d)' % slips_where['tack_shadow'])
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
        MP.previews(assets, prev, jar, imgs, where, chalk, ink, slips_where)


if __name__ == '__main__':
    main()
