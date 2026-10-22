"""The Haul Board's painting: its front and back (96x64 texels over the whole 3x2 board), its materials, the little things on its
shelf, the crest (an animated strip) and the item's icon. All at the HD scale of market_board_art.py's, whose helpers and look it
shares (the same oak, iron, slate and stone, grain and wear), with its own seeds so no two boards' planks run alike.

Imported by haul_board_art.py (see haul_board_model.py for the geometry the paintings are laid to).
"""
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import market_board_art as MB
import haul_board_model as M
from market_board_art import R, RB, tx, ty, PW, PH, wood, lit_edges, iron, grain_1d, Relief, tube, oval, sheaf, OAK, STRAW
from hdkit import (PAL, Canvas, grid, fbm, rand, bands, poly, disc, ellipse, rect, shift, grow, outline, path_dist, dist_in, light,
                   cavity, glint)

# ================================================================ the front

def row_top(r):
    """The top of the slate's row r in texels of the front painting (the slate's top is its own)."""
    return ty(M.PANEL_Y[1]) + (M.ROWS_TOP + r * M.ROW_PITCH) * 32.0


def small_wheel(cv, cx, cy, r, pal, shade):
    """A wheel carved small: a ring, four spokes and a hub, in the palette's lights, a shadow under it to the lower right."""
    xs, ys = grid(cv.w, cv.h)
    d = np.hypot(xs - cx, ys - cy)
    ring = (d <= r) & (d > r - 1.15)
    spokes = (d <= r - 0.9) & ((np.abs(xs - cx) < 0.6) | (np.abs(ys - cy) < 0.6))
    hub = d < 1.0
    body = ring | spokes | hub
    sh = shift(body, 1, 1) & ~body
    cv.over(sh, shade, 0.7)
    cv.put(body, pal[3])
    cv.put(body & (xs - cx + ys - cy < 0) & (ring | hub), pal[4])
    cv.put(hub, pal[5])


def small_sheaf(cv, cx, cy, h, pal, shade):
    """A sheaf carved small: a wedge of straw tied at the waist."""
    xs, ys = grid(cv.w, cv.h)
    top, foot = cy - h, cy + h
    half = np.where(ys < cy, 0.9 + (cy - ys) / max(cy - top, 1) * 1.6, 0.9 + (ys - cy) / max(foot - cy, 1) * 1.3)
    body = (np.abs(xs - cx) < half) & (ys >= top) & (ys <= foot)
    sh = shift(body, 1, 1) & ~body
    cv.over(sh, shade, 0.7)
    cv.put(body, pal[3])
    cv.put(body & (np.floor(xs - cx + 0.5) % 2 == 0), pal[4])
    cv.put(body & (np.abs(ys - cy) < 0.6), pal[1])
    cv.put(body & (ys <= top + 1.0), pal[5])


def paint_front():
    cv = Canvas(PW, PH)
    xs, ys = grid(PW, PH)
    allm = np.ones((PH, PW), bool)
    # Unused corners first (nothing samples them), so the sheet isn't patchy if anything ever does
    wood(cv, allm, PAL['darkwood'], 401, False, base=0.4)

    # The posts and their stone collars
    for k, (x0, x1) in enumerate((M.POST_L, M.POST_R)):
        m = R(x0, x1, M.POST_Y[0], M.POST_Y[1])
        lit, dark = lit_edges(m, True)
        wood(cv, m, PAL['darkwood'], 420 + k, True, base=0.46, edge_lit=lit, edge_dark=dark, knots=2)
        foot = m & (ys > ty(5.0))
        cv.over(foot, PAL['darkwood'][0], np.clip((ys - ty(5.0)) / 10.0, 0, 0.5))
        cv.over(foot & (fbm(PW, PH, 3, 430 + k) > 0.55), PAL['moss'][2], 0.35)
    for k, (x0, x1) in enumerate((M.COLLAR_L, M.COLLAR_R)):
        m = R(x0, x1, M.COLLAR_Y[0], M.COLLAR_Y[1])
        t = 0.45 + 0.25 * (fbm(PW, PH, 2, 440 + k) - 0.5) + 0.15 * (ys < ty(M.COLLAR_Y[1]) + 0.9)
        cv.put(m, bands(PAL['stone'], t, 0.3))

    # The rail, and the chalk ledge in front of the slate's foot with the chalk's dust along it
    m = R(M.RAIL_X[0], M.RAIL_X[1], M.RAIL_Y[0], M.RAIL_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['darkwood'], 450, False, base=0.45, edge_lit=lit, edge_dark=dark, knots=2)
    m = R(M.LEDGE_X[0], M.LEDGE_X[1], M.LEDGE_Y[0], M.LEDGE_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['wood'], 452, False, base=0.42, edge_lit=lit, edge_dark=dark, knots=0)
    cv.over(m & (rand(PW, PH, 453) > 0.78), (170, 168, 160), 0.3)

    paint_slate(cv, xs, ys)

    # The mullion
    m = R(M.MULLION_X[0], M.MULLION_X[1], M.PANEL_Y[0], M.PANEL_Y[1])
    lit, dark = lit_edges(m, True)
    wood(cv, m, PAL['darkwood'], 480, True, base=0.48, edge_lit=lit, edge_dark=dark, knots=1)

    paint_hooks(cv, xs, ys)

    # The header plank: a carved groove inside its edges, a wheel carved at one end and a sheaf at the other
    m = R(M.HEADER_X[0], M.HEADER_X[1], M.HEADER_Y[0], M.HEADER_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['darkwood'], 500, False, base=0.5, edge_lit=lit, edge_dark=dark, knots=1)
    hy0, hy1 = ty(M.HEADER_Y[1]), ty(M.HEADER_Y[0])
    groove_top = m & (ys >= hy0 + 1) & (ys < hy0 + 2) & (xs > 10) & (xs < PW - 10)
    groove_bot = m & (ys >= hy1 - 2) & (ys < hy1 - 1) & (xs > 10) & (xs < PW - 10)
    cv.over(groove_top, PAL['darkwood'][0], 0.8)
    cv.over(groove_bot, PAL['darkwood'][5], 0.25)
    mid = (hy0 + hy1) / 2
    for cx in (4.5, PW - 4.5):
        dm, h = disc(cx, mid, 2.7, PW, PH)
        cv.over(dm, PAL['darkwood'][1], 0.75)
    small_wheel(cv, 4.5, mid, 2.5, PAL['gold'], PAL['darkwood'][0])
    small_sheaf(cv, PW - 4.5, mid, 2.3, STRAW, PAL['darkwood'][0])
    for cx in (4.5, PW - 4.5):
        for side in (-1, 1):
            vx = int(cx + side * 4.2)
            cv.over(rect(vx, hy0 + 2, vx + 1, hy1 - 2, PW, PH), PAL['darkwood'][1], 0.6)

    # The iron straps at the corners, riveted
    for mirror in (False, True):
        for k, (x0, x1, y0, y1) in enumerate(MB.strap_masks(mirror)):
            sm = R(x0, x1, y0, y1)
            ys_m, xs_m = np.nonzero(sm)
            rivets = []
            if len(xs_m):
                if (x1 - x0) > (y1 - y0):
                    for f in (0.25, 0.8):
                        rivets.append((int(xs_m.min() + f * (xs_m.max() - xs_m.min())), int((ys_m.min() + ys_m.max()) / 2)))
                elif (y1 - y0) > 3:
                    for f in (0.2, 0.75):
                        rivets.append((int((xs_m.min() + xs_m.max()) / 2), int(ys_m.min() + f * (ys_m.max() - ys_m.min()))))
            iron(cv, sm, 510 + k + 7 * mirror, rivets)
    return cv


def paint_slate(cv, xs, ys):
    """The slate: dark and cold, rubbed smooth, scratched, ghosts of old chalk in the rows where the writing goes, and ruled: a
    double rule under the title, a faint rule under every row and one down the ledger's column of ages and roads."""
    m = R(M.SLATE_X[0], M.SLATE_X[1], M.PANEL_Y[0], M.PANEL_Y[1])
    big = fbm(PW, PH, 12, 461)
    t = 0.2 + 0.07 * (big - 0.5) + 0.035 * (fbm(PW, PH, 3, 462) - 0.5)
    x0, x1, y0, y1 = tx(M.SLATE_X[0]), tx(M.SLATE_X[1]), ty(M.PANEL_Y[1]), ty(M.PANEL_Y[0])
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    t += 0.03 * np.exp(-(((xs - cx) / 22) ** 2 + ((ys - cy) / 16) ** 2))
    cv.put(m, bands(PAL['slate'], t, 0.32))
    rng = np.random.default_rng(464)
    for r in range(M.ROWS):
        yy = row_top(r) + 0.062 * 32 - 1.5 + rng.normal(0, 0.3)
        for col in range(2):
            if rng.random() < 0.5:
                continue
            gx0 = x0 + 2 + col * 24 + rng.uniform(0, 4)
            gx1 = gx0 + rng.uniform(7, 15)
            g = (np.abs(ys - yy) < 1.0) & (xs > gx0) & (xs < gx1)
            cv.over(g & m & (rand(PW, PH, 470 + r * 3 + col) > 0.6), (96, 104, 114), 0.07 + 0.06 * rng.random())
            cv.over((np.abs(ys - yy) < 1.6) & (xs > gx0 - 2) & (xs < gx1 + 3) & m, (70, 78, 90), 0.08)
    # A wide swipe of the rag, and scratches
    swipe = (np.abs((ys - y0) - 0.28 * (xs - x0) - 9) < 3.0) & m
    cv.over(swipe, (86, 94, 106), 0.11)
    for _ in range(5):
        sx, sy = rng.uniform(x0 + 2, x1 - 6), rng.uniform(y0 + 2, y1 - 2)
        ln = rng.uniform(3, 7)
        ang = rng.uniform(-0.5, 0.5)
        for s in range(int(ln * 2)):
            px, py = int(sx + s * 0.5 * math.cos(ang)), int(sy + s * 0.5 * math.sin(ang))
            if m[min(max(py, 0), PH - 1), min(max(px, 0), PW - 1)]:
                cv.over(rect(px, py, px + 1, py + 1, PW, PH), (104, 112, 120), 0.2)
    # The ruling: a double rule under the title, a rule under each row (the chalk's dust, broken here and there)
    xa, xb = x0 + M.PAD_L * 32 - 1, x1 - M.PAD_R * 32 + 1
    tt = int(round(y0 + (M.ROWS_TOP - 0.014) * 32))
    for k, yy in enumerate((tt - 1, tt + 1)):
        cv.over((ys == yy) & (xs >= xa) & (xs < xb) & m & (rand(PW, PH, 490 + k) > 0.12), (150, 156, 164), 0.34)
    for r in range(1, M.ROWS):
        yy = int(round(row_top(r))) - 1
        cv.over((ys == yy) & (xs >= xa) & (xs < xb) & m & (rand(PW, PH, 495 + r) > 0.24), (126, 134, 144), 0.26)
    col_x = int(round(x0 + (M.PAD_L + M.LAYOUT['HOURGLASS_X'] - 0.028) * 32))
    cv.over((xs == col_x) & (ys >= round(row_top(0))) & (ys < y1 - 1) & m & (rand(PW, PH, 489) > 0.3), (126, 134, 144), 0.2)
    # The frame's shadow on the sunken slate: along the top (the header) and the left (the post)
    cv.over(m & (ys < y0 + 1), PAL['slate'][0], 0.75)
    cv.over(m & (ys < y0 + 2) & (ys >= y0 + 1), PAL['slate'][0], 0.35)
    cv.over(m & (xs < x0 + 1), PAL['slate'][0], 0.6)
    cv.over(m & (xs >= x1 - 1), PAL['slate'][4], 0.12)


def paint_hooks(cv, xs, ys):
    """The panel the tickets hang on: upright planks pricked with the holes of old pins, a scrap or two of old tickets under
    rusted tacks, and the two rails with a boss under every peg."""
    m = R(M.HOOKS_X[0], M.HOOKS_X[1], M.PANEL_Y[0], M.PANEL_Y[1])
    nx0 = tx(M.HOOKS_X[0])
    plank = np.floor((xs - nx0) / 5.0)
    within = (xs - nx0) - plank * 5
    t = 0.54 + 0.07 * np.sin(plank * 2.3 + 1) + 0.16 * (grain_1d(PW, 530, 1.3)[np.clip(xs.astype(int), 0, PW - 1)] - 0.5)
    t += 0.05 * (fbm(PW, PH, 4, 531) - 0.5)
    t = np.where(within < 1.0, t - 0.3, t)
    t = np.where((within >= 1.0) & (within < 2.0), t + 0.08, t)
    cv.put(m, bands(PAL['darkwood'], t, 0.22))
    holes = m & (rand(PW, PH, 532) > 0.972) & (within >= 1.0)
    cv.put(holes, PAL['darkwood'][1])
    # Scraps of old tickets under tacks, low on the panel where no ticket hangs
    top = ty(M.PANEL_Y[1])
    for (sx, sy, w, h) in ((nx0 + 2, ty(11.5), 3, 2), (nx0 + 12, ty(10.5), 2, 3)):
        sm = rect(sx, sy, sx + w, sy + h, PW, PH)
        cv.put(sm, bands(PAL['parchment'], 0.45 + 0.2 * (rand(PW, PH, sx) > 0.5), 0))
        cv.px(sx + w // 2, sy, (110, 62, 40))
    cv.over(m & (ys < top + 1), PAL['darkwood'][0], 0.7)
    cv.over(m & (xs < nx0 + 1), PAL['darkwood'][0], 0.45)
    # The rails: oak with the grain along them, an iron cap at each end, a boss under every peg and a shadow on the planks below
    for k, c in enumerate(M.PEG_Y):
        rm = R(M.HOOK_RAIL_X[0], M.HOOK_RAIL_X[1], c - M.RAIL_H / 2, c + M.RAIL_H / 2)
        lit, dark = lit_edges(rm, False)
        wood(cv, rm, PAL['darkwood'], 540 + k, False, base=0.5, edge_lit=lit, edge_dark=dark, knots=0)
        for (a, b) in ((M.HOOK_RAIL_X[0], M.HOOK_RAIL_X[0] + 1.2), (M.HOOK_RAIL_X[1] - 1.2, M.HOOK_RAIL_X[1])):
            iron(cv, R(a, b, c - M.RAIL_H / 2, c + M.RAIL_H / 2), 550 + k, [(int(tx((a + b) / 2)), int(ty(c)))])
        for x in M.HOOK_X:
            bm, h = disc(tx(x) + 0.0, ty(c) + 0.0, 1.7, PW, PH)
            cv.put(bm & rm, bands(PAL['iron'], 0.4 + 0.3 * (h - 0.5) - 0.18 * ((xs - tx(x)) > 0.5), 0.2))
            cv.px(int(tx(x)), int(ty(c)), PAL['iron'][0])
        below = R(M.HOOK_RAIL_X[0] - 0.4, M.HOOK_RAIL_X[1] + 0.4, c - M.RAIL_H / 2 - 0.5, c - M.RAIL_H / 2) & m
        cv.over(below, PAL['darkwood'][0], 0.55)


# ================================================================ the back

def paint_back():
    cv = Canvas(PW, PH)
    xs, ys = grid(PW, PH)
    wood(cv, np.ones((PH, PW), bool), PAL['darkwood'], 602, False, base=0.4)
    for k, (x0, x1) in enumerate((M.POST_L, M.POST_R)):
        m = RB(x0, x1, M.POST_Y[0], M.POST_Y[1])
        lit, dark = lit_edges(m, True)
        wood(cv, m, PAL['darkwood'], 620 + k, True, base=0.44, edge_lit=lit, edge_dark=dark, knots=2)
        foot = m & (ys > ty(5.0))
        cv.over(foot, PAL['darkwood'][0], np.clip((ys - ty(5.0)) / 10.0, 0, 0.5))
    for k, (x0, x1) in enumerate((M.COLLAR_L, M.COLLAR_R)):
        m = RB(x0, x1, M.COLLAR_Y[0], M.COLLAR_Y[1])
        t = 0.42 + 0.25 * (fbm(PW, PH, 2, 640 + k) - 0.5) + 0.15 * (ys < ty(M.COLLAR_Y[1]) + 0.9)
        cv.put(m, bands(PAL['stone'], t, 0.3))
    m = RB(M.RAIL_X[0], M.RAIL_X[1], M.RAIL_Y[0], M.RAIL_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['darkwood'], 650, False, base=0.42, edge_lit=lit, edge_dark=dark)

    # The cladding: upright planks, each its own tone, dark seams, the odd knot
    m = RB(M.PANEL_X[0], M.PANEL_X[1], M.PANEL_Y[0], M.PANEL_Y[1])
    px0 = tx(48 - M.PANEL_X[1])
    plank = np.floor((xs - px0) / 7.0)
    within = (xs - px0) - plank * 7
    t = 0.42 + 0.06 * np.sin(plank * 2.9 + 1.4) + 0.15 * (grain_1d(PW, 660, 1.4)[np.clip(xs.astype(int), 0, PW - 1)] - 0.5)
    t += 0.06 * (fbm(PW, PH, 5, 661) - 0.5)
    t = np.where(within < 1.0, t - 0.3, t)
    t = np.where((within >= 1.0) & (within < 2.0), t + 0.09, t)
    cv.put(m, bands(PAL['darkwood'], t, 0.22))
    rng = np.random.default_rng(662)
    ys_m, xs_m = np.nonzero(m)
    for _ in range(4):
        k = int(rng.integers(0, len(xs_m)))
        km, kh = ellipse(xs_m[k] + 0.5, ys_m[k] + 0.5, 1.0, 1.6, PW, PH)
        cv.over(km & m, PAL['darkwood'][0], 0.6)
    cv.over(m & (ys < ty(M.PANEL_Y[1]) + 1), PAL['darkwood'][0], 0.6)

    # The battens, bolted through the posts, nailed to every plank
    for k, (y0, y1) in enumerate(M.BATTENS_Y):
        bm = RB(M.BATTEN_X[0], M.BATTEN_X[1], y0, y1)
        lit, dark = lit_edges(bm, False)
        wood(cv, bm, PAL['wood'], 670 + k, False, base=0.38, edge_lit=lit, edge_dark=dark, knots=1)
        yb = ty((y0 + y1) / 2)
        for p in range(int(M.PANEL_X[0] * 2) + 3, int(M.PANEL_X[1] * 2), 7):
            cv.px(PW - p, yb, PAL['iron'][2])
        for (x0, x1) in (M.POST_L, M.POST_R):
            bx = tx(48 - (x0 + x1) / 2)
            cv.put(rect(bx - 1, yb - 1, bx + 1, yb + 1, PW, PH), PAL['iron'][3])
            cv.px(bx - 1, yb - 1, PAL['iron'][5])
            cv.px(bx, yb, PAL['iron'][1])
        sh = RB(M.PANEL_X[0], M.PANEL_X[1], y0 - 0.5, y0) & m
        cv.over(sh, PAL['darkwood'][0], 0.55)

    # The header's back, a wheel burnt into it, ringed
    m = RB(M.HEADER_X[0], M.HEADER_X[1], M.HEADER_Y[0], M.HEADER_Y[1])
    lit, dark = lit_edges(m, False)
    wood(cv, m, PAL['darkwood'], 680, False, base=0.46, edge_lit=lit, edge_dark=dark, knots=1)
    hy = (ty(M.HEADER_Y[1]) + ty(M.HEADER_Y[0])) / 2
    d = np.hypot(xs - PW / 2, ys - hy)
    ring = m & (d <= 3.1) & (d > 2.1)
    spokes = m & (d <= 2.1) & ((np.abs(xs - PW / 2) < 0.6) | (np.abs(ys - hy) < 0.6) | (np.abs(np.abs(xs - PW / 2) - np.abs(ys - hy)) < 0.6))
    burnt = ring | spokes | (m & (d < 0.8))
    cv.over(burnt, (20, 12, 10), 0.7)
    cv.over(grow(burnt) & ~burnt & m, (40, 24, 16), 0.35)
    return cv


# ================================================================ materials

def paint_wood():
    return MB.tile_wood(False, 710)


def paint_post():
    return MB.tile_wood(True, 711, base=0.47)


def paint_cap():
    return MB.paint_cap()


def paint_trim():
    return MB.paint_trim()


# ================================================================ the shelf's small things

def paint_props():
    """The props texture, 64x64: the brass of the bell (as a profile across its width, a slice of rows for each step of it),
    the inkwell's dark glass, the ink, a quill's feather and its edge, the pewter collar and the bell's knob. Each takes the
    texels in haul_board_model.PROPS."""
    N = 64
    cv = Canvas(N)
    xs, ys = grid(N)
    cv.put(np.ones((N, N), bool), PAL['brass'][2])

    def region(name):
        x0, y0, x1, y1 = M.PROPS[name]
        return rect(x0, y0, x1, y1, N), xs - x0, ys - y0, x1 - x0, y1 - y0

    # The brass: a rounded profile across (the light from the upper left), each step's top edge lit, its foot in shadow, a
    # green bloom here and there where the damp stands
    m, u, v, w, h = region('brass')
    f = u / w
    prof = 0.34 + 0.52 * np.exp(-((f - 0.3) / 0.2) ** 2) + 0.12 * np.exp(-((f - 0.72) / 0.1) ** 2) - 0.2 * (f > 0.86)
    for (fa, fb) in M.BELL_V:                         # the slice of rows each step of the bell takes
        a, b = int(round(fa * h)), int(round(fb * h))
        inside = (v >= a) & (v < b)
        prof = np.where(inside & (v < a + 1), prof + 0.07, prof)
        prof = np.where(inside & (v >= b - 1), prof - 0.09, prof)
    prof = prof + 0.04 * (fbm(N, N, 3, 720) - 0.5)
    cv.put(m, bands(PAL['brass'], prof, 0.25))
    cv.over(m & (fbm(N, N, 3, 721) > 0.72), PAL['moss'][3], 0.28)
    # The inkwell's glass: dark, a streak of the window down it, a bubble or two in the thick of it
    m, u, v, w, h = region('glass')
    f = u / w
    g = 0.2 + 0.5 * np.exp(-((f - 0.2) / 0.07) ** 2) + 0.16 * np.exp(-((f - 0.46) / 0.05) ** 2) - 0.1 * (f > 0.8) + 0.04 * (fbm(N, N, 4, 722) - 0.5)
    g = np.where(v < 1, g + 0.12, g)
    cv.put(m, bands(PAL['glass'], g, 0.25))
    for (bx, by) in ((43, 20), (50, 14), (47, 25)):
        cv.px(bx, by, PAL['glass'][5])
    # The ink: black with a blue sheen
    m, u, v, w, h = region('ink')
    inkc = np.stack([14 + 0 * u, 12 + 0 * u, 22 + 0 * u], -1)
    cv.put(m, inkc)
    cv.over(m & (np.hypot(u - 4.5, v - 4.5) < 2.6), (70, 74, 112), 0.55)
    cv.over(m & (np.hypot(u - 4.0, v - 4.0) < 1.2), (150, 156, 196), 0.6)
    # A feather's vane (the quill is the shaft in a few steps of it): cream, the barbs slanting across it, its outer edge brown
    m, u, v, w, h = region('vane')
    barb = np.sin((v * 1.1 + u * 1.7) * 1.9) > 0.15
    t = np.where(barb, 0.8, 0.66) + 0.05 * (u < w / 2) - 0.0 * v
    cv.put(m, bands(PAL['bone'], t, 0.2))
    cv.put(m & ((u < 1) | (u >= w - 1)), PAL['leather'][4])
    # The shaft: pale, shaded across it
    m, u, v, w, h = region('edge')
    cv.put(m, bands(PAL['bone'], 0.62 + 0.22 * (u < 1) - 0.18 * (u >= w - 1) + 0.0 * v, 0))
    # The pewter collar
    m, u, v, w, h = region('pewter')
    t = 0.45 + 0.3 * np.exp(-((u / w - 0.28) / 0.18) ** 2) - 0.2 * (u / w > 0.8) + 0.05 * (fbm(N, N, 3, 723) - 0.5)
    cv.put(m, bands(PAL['steel'], t, 0.25))
    # The knob: a bright brass disc with a glint
    m, u, v, w, h = region('knob')
    d = np.hypot(u - w / 2 + 0.5, v - h / 2 + 0.5)
    cv.put(m, bands(PAL['brass'], 0.62 + 0.3 * (1 - d / (w / 2)) - 0.12 * (u > w / 2), 0.2))
    cv.px(M.PROPS['knob'][0] + 2, M.PROPS['knob'][1] + 2, PAL['brass'][5])
    return cv


# ================================================================ the crest

CREST_W, CREST_H = 128, 56
CREST_FRAMES = 12
CREST_HOLD = 160                                # ticks the resting frame is shown before the glint crosses
CREST_STEP = 2                                  # ticks a frame of the glint is shown

INDIGO = [(8, 12, 30), (14, 22, 54), (24, 38, 88), (38, 58, 124), (60, 86, 160), (96, 124, 196)]


def wheel(rel, fld, cx, cy, Rr, spokes, turn, seed):
    """A cart wheel carved up out of the field: a gilt felloe with a groove inside it, spokes, a swelling hub with an iron cap."""
    W, H = rel.W, rel.H
    xs, ys = grid(W, H)
    d = np.hypot(xs - cx, ys - cy)
    ang = np.arctan2(ys - cy, xs - cx)
    # The felloe: a ring, round in section, a groove inside it
    ring = (d >= Rr - 3.4) & (d <= Rr)
    hr = np.sqrt(np.clip(1 - ((d - (Rr - 1.7)) / 1.7) ** 2, 0, 1))
    seam = np.sin(ang * 8 + turn * 8) > 0.97
    rel.lay(ring & fld, 'gilt', 0.58 + 0.06 * (fbm(W, H, 4, seed) - 0.5) - 0.12 * seam, 0.95 + 0.75 * hr)
    groove = (d >= Rr - 4.4) & (d < Rr - 3.4)
    rel.lay(groove & fld, 'field', 0.12, -0.5)
    tyre = (d >= Rr - 1.0) & (d <= Rr)
    rel.lay(tyre & fld, 'gilt', 0.74, 1.5)
    # Spokes
    for s in range(spokes):
        a = turn + s * 2 * math.pi / spokes
        pts = [(cx + math.cos(a) * 3.5, cy + math.sin(a) * 3.5), (cx + math.cos(a) * (Rr - 3.6), cy + math.sin(a) * (Rr - 3.6))]
        m, h = tube(pts, 1.55, 1.15, W, H)
        rel.lay(m & fld, 'gilt', 0.55 + 0.1 * h, 0.55 + 0.8 * h)
    # The hub and its cap
    m, h = disc(cx, cy, 5.2, W, H)
    rel.lay(m & fld, 'gilt', 0.6 + 0.1 * h, 0.8 + 1.1 * h)
    m, h = disc(cx, cy, 2.5, W, H)
    rel.lay(m & fld, 'iron', 0.5 + 0.12 * h, 1.4 + 1.2 * h)
    one = np.zeros((H, W), bool)
    one[int(cy), int(cx)] = True
    rel.lay(one & fld, 'iron', 0.9, 2.8)


def crest_scene():
    """The carved and painted crest as a Relief: oak frame with iron studs, an indigo field with a lattice and a gilt stud at every
    crossing, a sheaf and a wheel, each shadowed on the field. Gives it and the masks the glint is laid over."""
    W, H = CREST_W, CREST_H
    xs, ys = grid(W, H)
    pals = {'oak': OAK, 'gilt': PAL['gold'], 'field': INDIGO, 'straw': STRAW, 'iron': PAL['iron']}
    rel = Relief(W, H, pals)
    # The frame: oak, its grain along each member, an arris lit on its outer edge and a sunk bead round the field
    fw = 5
    inner = (xs >= fw) & (xs < W - fw) & (ys >= fw) & (ys < H - fw)
    t = 0.46 + 0.12 * MB.fibres(W, H, 730, False) + 0.05 * (fbm(W, H, 6, 731) - 0.5)
    sides = ((xs < fw) | (xs >= W - fw)) & ~((ys < fw) | (ys >= H - fw))
    t = np.where(sides, 0.46 + 0.12 * MB.fibres(W, H, 732, True) + 0.05 * (fbm(W, H, 6, 733) - 0.5), t)
    t = t + 0.14 * ((xs < 1) | (ys < 1)) - 0.2 * ((xs >= W - 1) | (ys >= H - 1))
    rel.lay(np.ones((H, W), bool), 'oak', t, 0.0)
    bead = ~inner & (xs >= fw - 2) & (xs < W - fw + 2) & (ys >= fw - 2) & (ys < H - fw + 2)
    rel.lay(bead, 'oak', 0.3, -0.5)
    # The field, lit a little brighter at its middle, a diamond lattice over it and a stud where it crosses
    fld = inner
    glow = np.exp(-(((xs - W / 2) / 52) ** 2 + ((ys - H / 2) / 26) ** 2))
    base = 0.34 + 0.12 * glow + 0.05 * (fbm(W, H, 6, 734) - 0.5)
    lat = ((xs + ys) % 14 == 0) | ((xs - ys) % 14 == 0)
    base = base + 0.06 * lat
    rel.lay(fld, 'field', base, -0.3)
    for gy in range(fw + 3, H - fw, 14):
        for gx in range(fw + 3, W - fw, 14):
            if (gx // 14 + gy // 14) % 2 == 0 and fld[gy, gx]:
                one = np.zeros((H, W), bool)
                one[gy, gx] = True
                rel.lay(one, 'gilt', 0.42, 0.2)
    # Iron studs at the corners and the middle of the top
    for (sx, sy) in ((2.6, 2.6), (W - 3.6, 2.6), (2.6, H - 3.6), (W - 3.6, H - 3.6), (W / 2, 2.4)):
        m, h = disc(sx + 0.5, sy + 0.5, 2.0, W, H)
        rel.lay(m, 'iron', 0.52 + 0.25 * (h - 0.5), 1.0 + 0.9 * h)
    # The sheaf to the left and the wheel to the right, each a shadow on the field to the lower right
    cy = 28
    sheaf(rel, fld, 37.0, 29.0, 11.0, 44.0, 13.5, 4.2, 9.0, 11, 7.4, 'straw', 0.56, 740)
    wheel(rel, fld, 91.0, cy, 16.5, 8, 0.3, 741)
    emb = fld & ~rel.of('field') & ~rel.of('oak')
    for (dx, dy, k) in ((1, 1, 0.13), (2, 2, 0.06)):
        shd = shift(emb, dx, dy) & fld & rel.of('field')
        rel.base = np.where(shd, rel.base - k, rel.base)
    return rel, fld


def crest_frame(rel, fld, k):
    """Frame k of the crest: the scene lit, and (frames 1..CREST_FRAMES-1) a glint crossing the gilt, the straw and the studs
    from the left, a diagonal band a few texels wide. Frame 0 is the crest at rest."""
    W, H = CREST_W, CREST_H
    xs, ys = grid(W, H)
    base0 = rel.base.copy()
    if k > 0:
        f = (k - 1) / (CREST_FRAMES - 2)
        c = -16 + f * (W + 32 + 0.5 * H)
        band = np.exp(-(((xs + 0.5 * ys) - c) / 7.0) ** 2)
        shiny = rel.of('gilt', 'straw', 'iron')
        rel.base = np.where(shiny, base0 + 0.5 * band, base0)
    cv = rel.paint(strength=1.3)
    rel.base = base0
    gilt = rel.of('gilt')
    cv.over(gilt & (fbm(W, H, 3, 745) > 0.72) & (rand(W, H, 746) > 0.45), (118, 54, 34), 0.32)
    cv.over(fld & rel.of('field') & ((xs < 8) | (xs > W - 9) | (ys < 8)) & (rand(W, H, 747) > 0.9), OAK[2], 0.4)
    cv.a = np.ones((H, W))
    return cv


def paint_crest():
    """The crest's strip: CREST_FRAMES frames of CREST_W x CREST_H, one under another."""
    rel, fld = crest_scene()
    frames = [crest_frame(rel, fld, k).image() for k in range(CREST_FRAMES)]
    strip = Image.new('RGBA', (CREST_W, CREST_H * CREST_FRAMES))
    for k, im in enumerate(frames):
        strip.paste(im, (0, k * CREST_H))
    return strip


def crest_meta():
    frames = [{'index': 0, 'time': CREST_HOLD}] + list(range(1, CREST_FRAMES))
    return {'animation': {'width': CREST_W, 'height': CREST_H, 'frametime': CREST_STEP, 'frames': frames}}
