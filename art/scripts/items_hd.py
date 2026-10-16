"""Guildmark's items, the quest notes and the quest board at 32x, painted with the HD art kit (hdkit.py) in the
dark-fantasy look: the Guild Mark coin, the contract scroll, the quest board, the relic caches, the Warden's
Flask at its four levels, the crown shard, the Dusk Bridle, the Lantern Wisp, the four older creatures'
spawn eggs, the ten quest notes and the board's own planks and frame.

    python3 -B art/scripts/items_hd.py src/main/resources/assets/guildmark [preview-dir]

Writes only textures; the item and block models already point at them. Everything is seeded, so a rerun gives
the same files.
"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import (PAL, GEMS, Canvas, grid, vnoise, fbm, rand, tone, mix, ramp, bands, poly, seg_dist, path_dist,
                   disc, ellipse, rect, blur, edge, grow, dist_in, light, cavity, rim, outline, glint, sheet,
                   bevel, paste)

N = 32


# ================================================================ the Guild Mark

def crest_mask(cx, cy, s=1.0):
    """The guild's shield: flat top with notched corners, sides that curve in to a point."""
    pts = [(-5.2, -6.6), (5.2, -6.6), (5.6, -5.6), (5.4, -0.5), (4.2, 3.2), (0, 7.4), (-4.2, 3.2), (-5.4, -0.5), (-5.6, -5.6)]
    return poly([(cx + x * s, cy + y * s) for x, y in pts], N)


def star_mask(cx, cy, r, w=0.36):
    pts = []
    for i in range(8):
        a = i * math.pi / 4 - math.pi / 2
        rr = r if i % 2 == 0 else r * w
        pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
    return poly(pts, N)


def guild_mark():
    xs, ys = grid(N)
    cx, cy = 15.6, 15.0
    cv = Canvas(N)
    # The coin's edge, seen below the face: a thick milled band
    edge_m, _ = ellipse(cx, cy + 2.2, 13.2, 13.0, N)
    face_m, dome = ellipse(cx, cy, 13.2, 13.0, N)
    band = edge_m & ~face_m
    mill = (xs.astype(int) % 2 == 0)
    cv.put(band, bands(PAL['gold'], 0.3 + 0.12 * mill - 0.16 * ((xs - cx) / 13), 0))
    # The face: a dished field lit as a shallow dome, a raised rim, a ring of beads and the stamped crest
    r = np.hypot((xs - cx) / 13.2, (ys - cy) / 13.0)
    grain = fbm(N, N, 5, 7)
    t = 0.5 + 0.55 * light(dome * 2.5, 1.0) + 0.1 * (grain - 0.5)
    rim_m = face_m & (r > 0.8)
    t = np.where(rim_m, 0.66 + 0.3 * bevel(rim_m) + 0.25 * light(dome * 2.5, 1.0), t)
    inner_edge = face_m & (r <= 0.8) & (r > 0.72)
    t = np.where(inner_edge, t - 0.18, t)
    crest = crest_mask(cx, cy + 0.2, 1.0)
    star = star_mask(cx, cy - 0.8, 4.2, 0.34) & crest
    t = np.where(crest, 0.68 + 0.32 * bevel(crest) + 0.06 * (grain - 0.5), t)
    t = np.where(star, 0.38 + 0.32 * bevel(star, raised=False), t)
    # Grime gathers round the stamping
    halo = grow(crest, 1) & ~crest & face_m
    t = np.where(halo, t - 0.14, t)
    cv.put(face_m, bands(PAL['gold'], t, 0.3))
    for i in range(18):
        a = i / 18 * math.tau
        bx, by = int(cx + math.cos(a) * 9.6), int(cy + math.sin(a) * 9.45)
        cv.px(bx, by, PAL['gold'][5] if math.cos(a) + math.sin(a) < 0.3 else PAL['gold'][4])
        cv.px(bx + 1, by + 1, PAL['gold'][2])
    # Old wear: a scratch or two across the field
    for (x0, y0, x1, y1) in ((6, 19, 10, 22), (20, 6, 24, 9)):
        d, _ = seg_dist(x0, y0, x1, y1, N)
        cv.over(face_m & (d < 0.5) & ~crest, tone(PAL['gold'][4], 0.1), 0.6)
    outline(cv, 0.84)
    glint(cv, 8, 6, 2)
    return cv.image()


# ================================================================ the contract

def contract():
    xs, ys = grid(N)
    A, B, r = (6.5, 24.5), (24.0, 8.5), 4.6
    d, t = seg_dist(*A, *B, N)
    ax, ay = B[0] - A[0], B[1] - A[1]
    L = math.hypot(ax, ay)
    ux, uy = ax / L, ay / L
    # Signed distance across the roll: positive toward the lower right
    side = (xs - A[0]) * (-uy) + (ys - A[1]) * ux
    along = ((xs - A[0]) * ux + (ys - A[1]) * uy) / L
    body = (np.abs(side) <= r) & (along >= 0) & (along <= 1)
    # Paper rounded over at the near end
    cap_a, _ = ellipse(*A, 1, 1, N)
    end = (along < 0) & (np.hypot(xs - A[0], ys - A[1]) <= r) & (((xs - A[0]) * ux + (ys - A[1]) * uy) > -1.8)
    cv = Canvas(N)
    hgt = np.sqrt(np.clip(1 - (side / r) ** 2, 0, 1))
    grain = fbm(N, N, 5, 3)
    lt = light(hgt * 3.0, 1.0)
    tt = 0.5 + 0.9 * lt + 0.1 * (grain - 0.5) + 0.1 * hgt
    # The outer wrap's edge runs along the roll
    seam = (np.abs(side - r * 0.32) < 0.55) & (along > 0.06)
    tt = np.where(seam, tt - 0.32, tt)
    tt = np.where((side > r * 0.32) & (side < r * 0.32 + 1.1), tt + 0.1, tt)
    cv.put(body | end, bands(PAL['parchment'], tt))
    # The far end: the roll seen end on, a spiral of paper
    ex = B[0] + ux * 0.4
    ey = B[1] + uy * 0.4
    px = (xs - ex) * ux + (ys - ey) * uy
    py = side - ((ex - A[0]) * (-uy) + (ey - A[1]) * ux)
    e = np.hypot(px / 1.9, py / r)
    cap = e <= 1
    ang = np.arctan2(py / r, px / 1.9)
    spiral = (np.mod(e * 3.2 + ang / math.tau, 1.0) < 0.4) & (e < 0.92)
    ct = np.where(spiral, 0.22, 0.62 - 0.25 * e)
    cv.put(cap, bands(PAL['parchment'], ct))
    cv.put(cap & (e < 0.22), PAL['parchment'][0])
    # A red cord round the middle, the guild's seal on it, and two ribbon tails hanging down
    mid = 0.52
    cord = body & (np.abs(along - mid) < 0.055)
    ct = 0.55 + 1.6 * lt - 0.3 * (np.abs(along - mid) > 0.03)
    cv.put(cord, bands(PAL['crimson'], ct))
    sx, sy = A[0] + ax * mid + (-uy) * 0.6, A[1] + ay * mid + ux * 0.6
    for (ex2, ey2, fork) in ((sx - 2.5, sy + 9.0, 1), (sx + 1.6, sy + 9.6, -1)):
        dd, tt2 = seg_dist(sx, sy, ex2, ey2, N)
        tail = (dd < 1.25) & ~((tt2 > 0.86) & (np.abs((xs - ex2) * 0.8 + (ys - ey2) * 0.6) < 0.7))
        cv.put(tail & ~(grid(N)[1] > ey2 + 0.3), bands(PAL['crimson'], 0.45 + 0.35 * fork * 0.4 + 0.25 * (dd < 0.5)))
    seal_m, sd = disc(sx, sy, 4.1, N)
    wob = vnoise(N, N, 2, 11)
    seal_m = seal_m | (disc(sx, sy, 4.6, N)[0] & (wob > 0.55))
    sh = sd * 0.8 + blur(star_mask(sx, sy, 2.6, 0.4).astype(float), 1) * -0.5
    ring = np.abs(np.hypot(xs - sx, ys - sy) - 3.0) < 0.5
    sh = sh + ring * 0.35
    st = 0.5 + 1.1 * light(sh, 2.6) + 0.12 * sd
    cv.put(seal_m, bands(PAL['wax'], st))
    outline(cv, 0.8)
    glint(cv, int(sx - 2), int(sy - 2), 1, (255, 214, 190))
    return cv.image()


# ================================================================ the quest board (item)

def planks(cv, mask, x0, y0, x1, y1, board_h, pal, seed, vertical=False, lift=0.0):
    xs, ys = grid(cv.w, cv.h)
    n = fbm(cv.w, cv.h, 4, seed)
    grain = vnoise(cv.w, cv.h, 2, seed + 5)
    pos = (xs - x0) if vertical else (ys - y0)
    row = np.floor(pos / board_h)
    within = pos - row * board_h
    # Grain runs along each board: stretched noise, a knot here and there
    xg, yg = (xs, ys) if not vertical else (ys, xs)
    streak = vnoise(cv.w, cv.h, 3, seed + 9)
    t = 0.5 + lift + 0.14 * (n - 0.5) + 0.1 * np.sin(yg * 2.1 + streak * 7 + row * 3.1) + 0.07 * np.sin(row * 2.3 + seed)
    t = np.where(within < 1.0, t + 0.1, t)
    t = np.where(within > board_h - 1.0, t - 0.34, t)
    cv.put(mask, bands(pal, t, 0.2))


def quest_board_item():
    xs, ys = grid(N)
    cv = Canvas(N)
    # Two posts
    for px in (5, 24):
        m = rect(px, 9, px + 3, 31, N)
        t = 0.42 + 0.22 * (xs - px < 1) - 0.18 * (xs - px >= 2) + 0.08 * (vnoise(N, N, 2, px) - 0.5)
        cv.put(m, bands(PAL['darkwood'], t))
    # The board: a dark frame round planks
    frame = rect(3, 9, 29, 26, N)
    inner = rect(5, 11, 27, 24, N)
    ft = 0.4 + 0.25 * (ys < 10) - 0.2 * (ys > 24.5) + 0.1 * (vnoise(N, N, 3, 4) - 0.5)
    cv.put(frame, bands(PAL['darkwood'], ft))
    planks(cv, inner, 5, 11, 27, 24, 4.4, PAL['wood'], 9)
    cv.over(inner & (ys < 12.2), PAL['wood'][0], 0.6)
    # The roof: slate shingles on a ridge beam, overhanging the posts
    roof = poly([(1, 10.5), (16, 2.0), (31, 10.5), (29, 11.5), (16, 4.4), (3, 11.5)], N) | poly([(1.4, 10.2), (16, 1.8), (30.6, 10.2), (16, 6.8)], N)
    row = np.floor((ys - 2) / 2.2)
    shingle = ((xs + row * 1.5) % 3.0) < 0.7
    rt = 0.55 - 0.03 * row + 0.12 * (xs < 16) - 0.25 * shingle + 0.1 * (vnoise(N, N, 2, 2) - 0.5)
    cv.put(roof, bands(PAL['slate'], rt))
    ridge = poly([(15, 1.4), (17, 1.4), (17.4, 3.4), (14.6, 3.4)], N)
    cv.put(ridge, PAL['darkwood'][3])
    # Notes pinned up, one turned dark (a lair), each with a red pin
    notes = [(6, 13, 6, 6, 'parchment', 0), (13, 12, 6, 7, 'parchment', 1), (20, 13, 6, 6, 'parchment', 2),
             (8, 19, 5, 4, 'parchment', 3), (17, 19, 6, 4, 'parchment_dark', 4)]
    for (nx, ny, nw, nh, pal, s) in notes:
        m = rect(nx, ny, nx + nw, ny + nh, N)
        nt = 0.62 + 0.15 * (vnoise(N, N, 2, 30 + s) - 0.5) - 0.18 * (xs > nx + nw - 1) - 0.2 * (ys > ny + nh - 1)
        cv.put(m, bands(PAL[pal], nt))
        for ly in range(ny + 2, ny + nh - 1, 2):
            lm = rect(nx + 1, ly, nx + nw - 1 - (ly % 3 == 0), ly + 1, N)
            cv.over(lm, PAL['ink'][1] if pal == 'parchment' else (110, 40, 46), 0.55)
        cv.px(nx + nw // 2, ny, tone(PAL['wax'][3], 0.15))
        # its shadow on the planks
        sm = rect(nx + 1, ny + nh, nx + nw + 1, ny + nh + 1, N) & inner & ~cv.solid
        cv.over(rect(nx + nw, ny + 1, nx + nw + 1, ny + nh + 1, N) & inner, PAL['wood'][0], 0.6)
        cv.over(rect(nx + 1, ny + nh, nx + nw + 1, ny + nh + 1, N) & inner, PAL['wood'][0], 0.6)
    # Iron corner brackets
    for (bx, by) in ((3, 9), (27, 9), (3, 24), (27, 24)):
        cv.put(rect(bx, by, bx + 2, by + 2, N), PAL['iron'][3])
        cv.px(bx, by, PAL['iron'][4])
    outline(cv, 0.8)
    return cv.image()


# ================================================================ relic caches

CACHE_TIERS = {
    # bands, gem, studs; and a touch of something the higher tiers carry
    'common': dict(band='iron', gem='grey', wood='wood'),
    'uncommon': dict(band='iron', gem='green', wood='wood'),
    'rare': dict(band='steel', gem='blue', wood='wood'),
    'epic': dict(band='blacksteel', gem='violet', wood='darkwood', runes=(170, 120, 230)),
    'legendary': dict(band='gold', gem='amber', wood='darkwood', filigree=True),
}


def relic_cache(tier):
    look = CACHE_TIERS[tier]
    xs, ys = grid(N)
    cv = Canvas(N)
    x0, x1 = 3, 29
    lid_top, lid_bot, body_bot = 7, 15, 28
    # The lid: a half barrel seen from the front, so it rounds over toward the top
    lid = rect(x0, lid_top, x1, lid_bot, N) & ~((ys < lid_top + 1) & ((xs < x0 + 1) | (xs > x1 - 1)))
    body = rect(x0, lid_bot, x1, body_bot, N)
    curve = np.clip((ys - lid_top) / (lid_bot - lid_top), 0, 1)
    lid_t = 0.72 - 0.42 * curve + 0.12 * (fbm(N, N, 4, 3) - 0.5)
    seams = ((ys - lid_top) % 3.0 < 0.8) & (ys > lid_top + 1)
    cv.put(lid, bands(PAL[look['wood']], lid_t - 0.2 * seams))
    planks(cv, body, x0, lid_bot, x1, body_bot, 4.3, PAL[look['wood']], 12)
    # The lid's shadow over the body
    cv.over(body & (ys < lid_bot + 1.2), PAL[look['wood']][0], 0.7)
    # Metal bands: two straps over lid and body, a rim along the lid's edge and the foot
    metal = PAL[look['band']]
    straps = ((np.abs(xs - 8.5) < 1.6) | (np.abs(xs - 23.5) < 1.6)) & (lid | body)
    rims = ((np.abs(ys - (lid_bot - 0.5)) < 1.1) | (ys > body_bot - 2)) & (lid | body)
    corners = (((xs < x0 + 2.2) | (xs > x1 - 2.2)) & (lid | body))
    mm = straps | rims | corners
    mh = np.where(mm, 1.0, 0.0) + np.where(lid, 0.6 * (1 - curve), 0)
    mt = 0.5 + 1.2 * light(blur(mh, 1) * 2, 1.0) + 0.2 * (1 - curve) * lid - 0.1 * (ys > body_bot - 1)
    cv.put(mm, bands(metal, mt))
    # Rivets down the straps
    for sx in (8, 23):
        for ry in range(lid_top + 2, body_bot - 1, 4):
            cv.px(sx, ry, tone(metal[4], 0.1))
            cv.px(sx + 1, ry + 1, metal[1])
    # The lock plate and its gem
    plate = rect(12, 12, 20, 22, N) & ~((ys > 20) & ((xs < 13) | (xs > 19)))
    ph = blur(plate.astype(float), 1)
    cv.put(plate, bands(metal, 0.55 + 1.4 * light(ph * 2, 1.0)))
    gem = poly([(16, 13.2), (19.0, 16.2), (16, 19.4), (13.0, 16.2)], N)
    g = GEMS[look['gem']]
    gx, gy = xs - 16, ys - 16.2
    facet = np.where(gx + gy < 0, 0.75, 0.35) + np.where(gx - gy < 0, 0.1, -0.1)
    cv.put(gem, bands(g, facet, 0))
    cv.px(14, 15, g[4])
    cv.px(15, 14, g[4])
    cv.put(rect(15, 20, 17, 22, N), metal[0])       # the keyhole
    if look.get('runes'):
        for rx in (5, 26):
            for ry in (17, 20, 23):
                cv.px(rx, ry, look['runes'])
        cv.over(rect(11, 8, 21, 10, N) & ((xs.astype(int) % 2) == 0), look['runes'], 0.7)
    if look.get('filigree'):
        for (fx, fy) in ((5, 9), (25, 9), (5, 18), (25, 18)):
            cv.px(fx, fy, tone(metal[5], 0.2))
            cv.px(fx + 1, fy + 1, metal[4])
            cv.px(fx - 1, fy + 1, metal[4])
    outline(cv, 0.8)
    if tier in ('rare', 'epic', 'legendary'):
        glint(cv, 15, 15, 2 if tier == 'legendary' else 1, tuple(g[4]))
    if tier == 'legendary':
        glint(cv, 26, 8, 1)
    return cv.image()


# ================================================================ the Warden's Flask

FLASK_LEVELS = [('wardens_flask_empty', 0.0), ('wardens_flask_low', 0.42), ('wardens_flask_half', 0.72), ('wardens_flask', 1.0)]


def flask(fill):
    xs, ys = grid(N)
    cv = Canvas(N)
    cx, cy, R = 15.8, 20.2, 9.6
    body, dome = disc(cx, cy, R, N)
    neck = rect(13, 7, 19, 13, N)
    lip = rect(12, 6, 20, 8, N)
    collar = rect(12, 10, 20, 13, N)
    cork = rect(13, 2, 19, 7, N) & ~((ys < 3) & ((xs < 14) | (xs > 17)))
    glass = body | neck
    # The draught inside, its surface a flat line that catches the light
    floor_y, full_y = cy + R - 1.2, 9.5
    level = floor_y - (floor_y - full_y) * fill
    inside = disc(cx, cy, R - 1.2, N)[0] | rect(14, 8, 18, 13, N)
    liquid = inside & (ys >= level) if fill > 0 else np.zeros_like(inside)
    gl = light(dome * 3, 1.0)
    # Smoky glass, dark where it's empty
    gt = 0.32 + 0.55 * gl + 0.25 * (1 - dome) * (xs < cx)
    cv.put(glass, bands(PAL['glass'], gt))
    if fill > 0:
        heat = np.clip(1 - np.hypot(xs - (cx - 0.5), ys - (cy + 0.5)) / (R * 0.95), 0, 1)
        lt = 0.22 + 0.62 * heat + 0.3 * gl + 0.05 * (vnoise(N, N, 3, 5) - 0.5)
        lt = np.minimum(lt, 0.86) + np.where(heat > 0.78, 0.2, 0)
        cv.put(liquid, bands(PAL['draught'], lt, 0.25))
        surf = liquid & ~np.roll(liquid, 1, axis=0)
        cv.put(surf, tone(PAL['draught'][5], 0.1))
    # Glass walls: a dark ring inside the outline, a long curved shine at the upper left
    wall = body & ~disc(cx, cy, R - 1.0, N)[0]
    cv.over(wall, PAL['glass'][1], 0.55)
    shine = (np.abs(np.hypot(xs - cx, ys - cy) - (R - 2.6)) < 0.6) & (np.arctan2(ys - cy, xs - cx) > -2.75) & (np.arctan2(ys - cy, xs - cx) < -1.75)
    cv.put(shine & body, (236, 240, 228))
    cv.px(10, 18, (210, 220, 212))
    cv.px(22, 25, tone(PAL['glass'][4], -0.1))
    # A brass collar with a cord wound under it, the lip, and the cork sealed with wax
    cv.put(collar, bands(PAL['brass'], 0.5 + 0.35 * (ys < 11) - 0.3 * (xs > 18) + 0.2 * (xs < 14)))
    cv.put(rect(12, 12, 20, 13, N), PAL['brass'][1])
    cv.put(lip, bands(PAL['glass'], 0.62 - 0.3 * (xs > 18)))
    cv.put(cork, bands(PAL['cork'], 0.55 + 0.25 * (xs < 15) - 0.3 * (xs > 17) + 0.1 * (vnoise(N, N, 1.5, 8) - 0.5)))
    wax = rect(13, 2, 19, 4, N) | rect(13, 4, 14, 6, N) | rect(17, 4, 18, 5, N)
    cv.put(wax, bands(PAL['wax'], 0.6 - 0.25 * (xs > 16) + 0.2 * (ys < 3)))
    # A leather cord from the collar, looping down the side
    d, _ = path_dist([(19.5, 11.5), (23.5, 13.5), (25.5, 17.5)], N)
    cv.put((d < 0.8) & ~body, bands(PAL['leather'], 0.55 - 0.2 * (xs > 24)))
    outline(cv, 0.8)
    return cv.image()


# ================================================================ the crown shard

def crown_shard():
    xs, ys = grid(N)
    cv = Canvas(N)
    # A broken arc of the Hollow King's crown: one point still standing, the other snapped off
    band = poly([(5, 21), (24, 19), (26, 24.5), (23, 27.5), (8, 28.5), (4, 25.5)], N)
    points = poly([(6, 21), (8.5, 14), (11, 20.5)], N) | poly([(19, 19.6), (21.5, 15.5), (22.4, 16.8), (23.2, 19.2)], N)
    gold = band | points
    gh = blur(gold.astype(float), 1) * 1.5 + (band * (np.abs(ys - 24.5) < 1.2)) * 0.4
    gt = 0.5 + 1.1 * light(gh, 1.4) + 0.1 * (fbm(N, N, 4, 5) - 0.5)
    cv.put(gold, bands(PAL['gold'], gt))
    # Rubies set along the band, one lost (an empty socket)
    for (gx, gy, lost) in ((9, 25, False), (15, 24.5, True), (21, 24, False)):
        m, d = disc(gx, gy, 1.6, N)
        cv.put(m, bands(GEMS['red'] if not lost else PAL['iron'], 0.3 + 0.7 * light(d * 2, 1.0) + 0.3 * d))
    # The violet crystal grown through the break, the Hollow's mark on it
    crystal = poly([(12.0, 22.5), (17.5, 3.5), (21.5, 9.0), (18.5, 21.5)], N)
    cx_ = (xs - 12) * 0.95 - (ys - 22.5) * 0.3
    facet = np.where((xs - 17.5) * 18 - (ys - 3.5) * -1.2 * 6 < (ys - 3.5) * 0.2 * 6 + 0, 0, 0)
    ridge_d, along = seg_dist(17.5, 3.5, 15.0, 22.0, N)
    left = (xs - 17.5) - (ys - 3.5) * (15.0 - 17.5) / (22 - 3.5) < 0
    ct = np.where(left, 0.78, 0.4) - 0.25 * along + 0.12 * (ridge_d < 0.6)
    cv.put(crystal, bands(GEMS['violet'], ct, 0.5))
    small = poly([(18.5, 21), (23.5, 11.5), (25.5, 14.5), (21.5, 21.5)], N)
    cv.put(small & ~crystal, bands(GEMS['violet'], 0.45 + 0.25 * ((xs - 23.5) + (ys - 11.5) * 0.5 < 0), 0.5))
    cv.over(crystal & (ridge_d < 0.5) & (along < 0.7), GEMS['violet'][4], 0.8)
    outline(cv, 0.82)
    glint(cv, 17, 6, 2, (240, 220, 255))
    return cv.image()


# ================================================================ the Dusk Bridle

def dusk_bridle():
    xs, ys = grid(N)
    cv = Canvas(N)
    # Seen from the front as it hangs on a horse's long face: wide at the brow, narrowing to the nose
    straps = [
        [(9.5, 8), (12, 3.8), (16, 2.8), (20, 3.8), (22.5, 8)],       # headpiece over the poll
        [(9.5, 8), (11.0, 22.5)], [(22.5, 8), (21.0, 22.5)],          # cheekpieces
        [(7.5, 9.5), (24.5, 9.5)],                                    # browband
        [(10.6, 19), (21.4, 19)],                                     # noseband
    ]
    dmin = np.full((N, N), 9.0)
    for p in straps:
        d, _ = path_dist(p, N)
        dmin = np.minimum(dmin, d)
    # The reins: a loop hanging from the rings
    rein, _ = path_dist([(10.5, 24.5), (8, 28), (12, 30.5), (20, 30.5), (24, 28), (21.5, 24.5)], N)
    leather = dmin < 1.2
    lh = np.clip(1.2 - dmin, 0, None)
    lt = 0.42 + 1.0 * light(lh * 2, 1.0) + 0.2 * (dmin < 0.45) + 0.06 * (vnoise(N, N, 2, 3) - 0.5)
    stitch = (dmin < 0.35) & (((xs + ys).astype(int) % 3) == 0)
    cv.put(leather, bands(PAL['blackleather'], lt - 0.3 * stitch))
    cv.put((rein < 0.85) & ~leather, bands(PAL['blackleather'], 0.5 - 0.2 * (ys > 29.5) + 0.15 * (rein < 0.35)))
    steel = PAL['steel']
    # Buckles on the cheeks, rosettes where the browband meets them
    for (bx, by) in ((9, 13), (20, 13)):
        b = rect(bx, by, bx + 3, by + 3, N) & ~rect(bx + 1, by + 1, bx + 2, by + 2, N)
        cv.put(b, bands(steel, 0.6 + 0.3 * (ys < by + 1) - 0.3 * (xs > bx + 1.5)))
    for rx in (8.5, 23.5):
        m, d = disc(rx, 9.5, 1.8, N)
        cv.put(m, bands(steel, 0.45 + 1.2 * light(d * 2, 1.0) + 0.2 * d))
    # Rings at the mouth and the curb bit between them
    for (rx, ry) in ((10.5, 23.5), (21.5, 23.5)):
        rr = np.hypot(xs - rx, ys - ry)
        ring = np.abs(rr - 2.3) < 0.7
        cv.put(ring, bands(steel, 0.5 + 1.4 * light(np.clip(0.75 - np.abs(rr - 2.3), 0, 1) * 2, 1.0)))
    bit, _ = path_dist([(12.5, 23.5), (14.5, 21.8), (17.5, 21.8), (19.5, 23.5)], N)
    cv.put(bit < 0.8, bands(steel, 0.55 + 0.3 * (ys < 22.5) - 0.2 * (xs > 18)))
    # The lantern-blue stone on the browband, set in steel, glowing
    setting, sd = disc(16, 9.5, 3.0, N)
    cv.put(setting, bands(steel, 0.5 + 1.2 * light(sd * 2, 1.0)))
    stone, std = disc(16, 9.5, 2.0, N)
    cv.put(stone, bands(GEMS['frost'], 0.35 + 0.65 * std + 0.3 * light(std * 2, 1.0), 0))
    outline(cv, 0.8)
    glint(cv, 15, 8, 1, (236, 250, 255))
    return cv.image()


# ================================================================ the Lantern Wisp

def lantern_wisp():
    xs, ys = grid(N)
    cv = Canvas(N)
    cx, cy = 16, 19
    # A teardrop of cold fire: round below, drawn up into a curling tip
    ang = np.arctan2(ys - cy, xs - cx)
    lift = np.clip((cy - ys) / 14, 0, 1)
    sway = np.sin(lift * 4.0) * 2.2 * lift
    rad = 7.5 * (1 - lift ** 1.4) + 0.4
    d = np.hypot(xs - cx - sway, (ys - cy) * np.where(ys > cy, 1.0, 0.55)) / np.maximum(rad, 0.3)
    flame = d <= 1
    heat = np.clip(1 - d, 0, 1) * (1 - 0.6 * lift)
    core = np.hypot(xs - cx, ys - (cy + 0.5)) / 3.4
    t = 0.18 + 0.85 * heat + 0.45 * np.clip(1 - core, 0, 1)
    alpha = np.clip(0.55 + 0.9 * heat + 0.6 * (core < 1), 0, 1)
    cv.put(flame, bands(GEMS['frost'], t, 0.6), alpha)
    # Its halo, faint and wide, and a few sparks drifting off it
    halo = (np.hypot(xs - cx, ys - cy) < 10.5) & ~flame
    ha = np.clip(1 - np.hypot(xs - cx, ys - cy) / 10.5, 0, 1) * 0.55
    cv.put(halo & (ha > 0.12), GEMS['frost'][2], ha)
    for (sx, sy, a) in ((6, 9, 0.9), (25, 7, 0.8), (27, 21, 0.7), (4, 24, 0.6), (21, 3, 0.9)):
        cv.px(sx, sy, GEMS['frost'][4], a)
        cv.px(sx + 1, sy, GEMS['frost'][2], a * 0.5)
    return cv.image()


# ================================================================ spawn eggs

def egg_shape():
    """The egg outline the region Hollows' and the dragon's eggs share, and its normals."""
    m = np.zeros((N, N), bool)
    nx = np.zeros((N, N))
    ny = np.zeros((N, N))
    top, bottom, cx = 2.0, 30.0, 15.5
    for y in range(N):
        t = ((y + 0.5) - top) / (bottom - top) * 2 - 1
        if abs(t) >= 1:
            continue
        half = 10.2 * math.sqrt(1 - t * t) * (1 + 0.1 * t)
        for x in range(N):
            dx = (x + 0.5 - cx) / max(half, 0.01)
            if abs(dx) <= 1:
                m[y, x] = True
                nx[y, x] = dx
                ny[y, x] = t
    return m, nx, ny


def egg(zones, extra):
    """An egg in bands of material from the top down ((last row, palette, base tone, pattern)), shaded as a
    ball lit from the upper left, then the creature's own marks."""
    xs, ys = grid(N)
    m, nx, ny = egg_shape()
    k = -nx * 0.9 - ny * 0.6
    cv = Canvas(N)
    start = 0
    for (end, pal, base, pattern) in zones:
        z = m & (ys >= start) & (ys < end)
        t = base + 0.45 * k
        if pattern == 'mail':
            t = t + np.where(((ys.astype(int) % 2) == 0) & ((xs.astype(int) + (ys.astype(int) // 2)) % 2 == 0), 0.18, -0.1)
        elif pattern == 'weave':
            t = t + np.where((xs.astype(int) + ys.astype(int)) % 2 == 0, 0.08, 0)
        elif pattern == 'mottle':
            t = t + 0.3 * (fbm(N, N, 3, 9) - 0.5)
        elif pattern == 'plate':
            t = t + np.where(((ys - start) % 3.0) < 1, 0.15, 0)
        cv.put(z, bands(PAL[pal] if isinstance(pal, str) else pal, t, 0.4))
        start = end
    extra(cv, m, xs, ys)
    rim(cv, 0.5)
    return cv.image()


def soldier_egg():
    def marks(cv, m, xs, ys):
        cv.put(m & (np.abs(ys - 11.5) < 0.6), PAL['steel'][4])            # the brim catching light
        cv.put(m & (ys >= 21) & (ys < 22), PAL['brass'][3])               # tabard's gold hem
        for ex in (11, 18):                                               # cold eyes under the brim
            cv.put(rect(ex, 14, ex + 2, 15, N), (176, 214, 236))
            cv.put(rect(ex, 15, ex + 2, 16, N), (98, 140, 172))
        crest = poly([(15.5, 16.5), (18.5, 17.5), (17.5, 20.5), (15.5, 21.5), (13.5, 20.5), (12.5, 17.5)], N)
        cv.put(crest & m, PAL['brass'][3])
    return egg([(11, 'steel', 0.6, 'plate'), (16, 'iron', 0.12, None), (22, 'crimson', 0.55, 'weave'), (N, 'iron', 0.45, 'mail')], marks)


def warden_egg():
    def marks(cv, m, xs, ys):
        # A great helm's gold crown band, and one slit with a violet light in it
        cv.put(m & (ys >= 9) & (ys < 11), bands(PAL['gold'], 0.6 - 0.04 * (xs - 10)))
        for px in range(9, 23, 3):
            cv.px(px, 8, PAL['gold'][4])
        cv.put(rect(9, 14, 23, 15, N), (196, 170, 255))
        cv.put(rect(10, 15, 22, 16, N), (98, 70, 160))
        # The broken sigil on the breastplate, and the cloak's clasp
        cv.put(rect(15, 18, 17, 23, N), PAL['gold'][3])
        cv.put(rect(13, 19, 19, 20, N), PAL['gold'][3])
        cv.px(17, 21, PAL['blacksteel'][2])
    return egg([(13, 'blacksteel', 0.6, 'plate'), (17, 'blacksteel', 0.15, None), (24, 'blacksteel', 0.5, 'plate'), (N, 'moss', 0.45, 'weave')], marks)


def troll_egg():
    def marks(cv, m, xs, ys):
        # Moss on the scalp, a heavy brow, small red eyes and two tusks
        moss = m & (ys < 9) & (fbm(N, N, 3, 4) > 0.45)
        cv.put(moss, bands(PAL['moss'], 0.4 + 0.5 * fbm(N, N, 2, 6)))
        cv.put(m & (np.abs(ys - 12.5) < 0.7), PAL['troll'][4])
        cv.put(m & (np.abs(ys - 13.5) < 0.5), PAL['troll'][0])
        for ex in (11, 19):
            cv.put(rect(ex, 14, ex + 2, 15, N), (226, 76, 52))
            cv.px(ex, 15, (120, 30, 26))
        for tx in (10, 21):
            cv.put(rect(tx, 17, tx + 1, 20, N), PAL['bone'][4])
            cv.px(tx, 16, PAL['bone'][5])
        cv.put(m & (np.abs(ys - 20.5) < 0.6), PAL['troll'][1])
        # Hide wrap with a bone toggle, then earth-dark below
        cv.put(rect(15, 23, 17, 25, N), PAL['bone'][4])
    return egg([(15, 'troll', 0.55, 'mottle'), (22, 'troll', 0.42, 'mottle'), (27, 'hide', 0.55, 'weave'), (N, 'hide', 0.2, 'mottle')], marks)


def rider_egg():
    def marks(cv, m, xs, ys):
        # A black steel helm with a crest, its visor lit lantern-blue, and the lantern itself at the belt
        crest = m & (np.abs(xs - 15.5) < 1.2) & (ys < 10)
        cv.put(crest, bands(PAL['crimson'], 0.5 + 0.2 * (xs < 15.5)))
        cv.put(rect(10, 13, 22, 14, N), (176, 232, 255))
        cv.put(rect(10, 14, 22, 15, N), (70, 130, 186))
        for x in range(10, 22, 3):
            cv.px(x, 13, PAL['blacksteel'][1])
        lm, ld = disc(19.5, 22.5, 2.4, N)
        cv.put(lm, bands(GEMS['frost'], 0.4 + 0.6 * ld, 0))
        cv.put(rect(19, 19, 20, 21, N), PAL['steel'][3])
        cv.over(m & (np.hypot(xs - 19.5, ys - 22.5) < 5) & ~lm, GEMS['frost'][2], 0.25)
    return egg([(12, 'blacksteel', 0.6, 'plate'), (16, 'blacksteel', 0.1, None), (N, 'blackleather', 0.45, 'weave')], marks)


# ================================================================ quest notes (the board's notes)

# The note's face is the block texture's middle 24x24 (the model shows uv 2..14 of 16).
NOTE_TYPES = ['champion', 'clear', 'deliver', 'escort', 'fetch', 'hunt', 'lair', 'chart', 'salvage', 'slay', 'wanted']


def note_paper(kind, seed):
    xs, ys = grid(N)
    cv = Canvas(N)
    dark = kind == 'lair'
    pal = PAL['parchment_dark'] if dark else PAL['parchment']
    # Ragged edges, and the lower right corner curling up
    jag = vnoise(N, N, 1.5, seed)
    paper = rect(5, 6, 27, 28, N)
    paper &= ~((xs > 26) & (jag > 0.55)) & ~((ys > 27) & (jag > 0.5)) & ~((xs < 6) & (jag > 0.7))
    curl = poly([(27, 22), (27, 28), (21, 28)], N)
    paper &= ~curl
    t = 0.62 + 0.12 * (fbm(N, N, 7, seed) - 0.5) - 0.18 * ((xs > 25.5) | (ys > 26.5)) + 0.08 * (ys < 8)
    # An old fold across the middle
    t = np.where(np.abs(ys - 16.5) < 0.5, t - 0.16, t)
    t = np.where(np.abs(ys - 17.5) < 0.5, t + 0.06, t)
    cv.put(paper, bands(pal, t, 0.25))
    back = poly([(27, 22), (21, 28), (21.5, 22.5)], N)
    cv.put(back, bands(pal, 0.38 + 0.12 * (xs < 23), 0))
    # Water stains
    st = fbm(N, N, 6, seed + 40)
    cv.over(paper & (st > 0.64), pal[2], 0.45)
    if dark:
        burnt = paper & (dist_in(paper, 3) <= 2) & (jag > 0.45)
        cv.put(burnt, (14, 10, 12))
        cv.put(edge(paper) & ~burnt, (54, 22, 18))
    elif kind == 'champion':
        border = paper & (dist_in(paper, 3) == 2)
        cv.put(border, bands(PAL['gold'], 0.6 + 0.25 * bevel(paper & (dist_in(paper, 3) >= 2)), 0))
    elif kind == 'slay':
        # A field boss's bounty: ruled round in red ink
        border = paper & (dist_in(paper, 3) == 2)
        cv.put(border, bands(PAL['crimson'], 0.55 + 0.25 * bevel(paper & (dist_in(paper, 3) >= 2)), 0))
    elif kind == 'wanted':
        # A printed notice, ruled round in black ink
        cv.put(paper & (dist_in(paper, 3) == 2), bands(PAL['ink'], 0.3 + 0.2 * (st > 0.6), 0))
    # A heading and two lines of faded hand-writing
    ink = (160, 70, 72) if dark else (70, 50, 40)
    rng = np.random.default_rng(seed)
    if kind == 'wanted':
        # The heading in bold printed letters, a notch knocked out of each so they read as letters, and a red rule
        # under it
        for k in range(5):
            x = 9 + k * 3
            cv.put(rect(x, 9, x + 2, 12, N), PAL['ink'][0])
            cv.over(rect(x + int(rng.integers(0, 2)), 10, x + 1 + int(rng.integers(0, 2)), 11, N), pal[3], 0.8)
        cv.put(rect(9, 13, 23, 14, N), PAL['crimson'][3])
        cv.px(22, 13, PAL['crimson'][2])
    else:
        for (ly, x0, x1, a) in ((10, 10, 22, 0.85), (13, 8, 23, 0.5), (15, 8, 19, 0.5)):
            x = x0
            while x < x1:
                word = int(rng.integers(2, 5))
                for i in range(min(word, x1 - x)):
                    cv.over(rect(x + i, ly, x + i + 1, ly + 1, N), ink, a)
                    if ly == 10 and rng.random() < 0.3:
                        cv.over(rect(x + i, ly - 1, x + i + 1, ly, N), ink, a * 0.8)
                x += word + 1
    # The pin: an iron tack, its shadow on the paper
    cv.put(rect(15, 6, 17, 8, N), PAL['iron'][3])
    cv.px(15, 6, PAL['iron'][5])
    cv.px(16, 7, PAL['iron'][1])
    cv.over(rect(17, 7, 18, 9, N) & paper, pal[1], 0.7)
    return cv, paper, ink


# The washed blue of the sea on an old chart
SEA_INK = [(26, 36, 48), (40, 58, 72), (60, 86, 98), (88, 118, 124), (124, 152, 150), (164, 186, 176)]


def note_icon(kind):
    """The stamp in the lower half of a note, outlined in ink so it reads on the paper."""
    xs, ys = grid(N)
    cv = Canvas(N)
    if kind == 'champion':
        crown = poly([(10, 25), (10, 19), (13, 22), (16, 17.5), (19, 22), (22, 19), (22, 25)], N)
        cv.put(crown, bands(PAL['gold'], 0.6 + 0.3 * bevel(crown) - 0.12 * (ys > 23.5), 0))
        for gx in (12, 16, 20):
            cv.px(gx, 23, GEMS['red'][3])
    elif kind in ('clear', 'lair'):
        if kind == 'clear':
            for (x0, y0, x1, y1) in ((10, 18, 22, 27), (22, 18, 10, 27)):
                d, _ = seg_dist(x0, y0, x1, y1, N)
                cv.put(d < 0.75, PAL['bone'][3])
                for (bx, by) in ((x0, y0), (x1, y1)):
                    cv.put(disc(bx, by, 1.1, N)[0], PAL['bone'][4])
        skull = ellipse(16, 21, 4.5, 4.0, N)[0] | rect(13, 23, 20, 26, N)
        cv.put(skull, bands(PAL['bone'], 0.7 + 0.25 * bevel(skull) - 0.15 * (xs > 18.5), 0))
        eye = (196, 36, 30) if kind == 'lair' else PAL['ink'][0]
        cv.put(rect(13, 21, 15, 23, N), eye)
        cv.put(rect(17, 21, 19, 23, N), eye)
        cv.px(16, 23, PAL['ink'][0])
        for x in (14, 16, 18):
            cv.px(x, 25, PAL['ink'][1])
    elif kind == 'deliver':
        crate = rect(10, 18, 22, 26, N)
        cv.put(crate, bands(PAL['wood'], 0.62 + 0.2 * bevel(crate) - 0.1 * (ys > 23), 0))
        cv.put(crate & ((np.abs(xs - 16) < 1) | (np.abs(ys - 22) < 1)), PAL['cork'][1])
        cv.put(rect(15, 21, 17, 23, N), PAL['wax'][3])
        cv.px(15, 21, PAL['wax'][5])
    elif kind == 'escort':
        # A lantern to light a traveller's way
        cv.put(rect(15, 17, 17, 18, N), PAL['iron'][3])
        cage = rect(12, 19, 20, 26, N)
        cv.put(cage, PAL['iron'][2])
        glass = rect(13, 20, 19, 25, N)
        gd = np.hypot(xs - 16, ys - 22.5)
        cv.put(glass, bands(GEMS['amber'], 1.0 - gd / 4.0, 0))
        cv.put(rect(15, 20, 17, 25, N) & (gd > 2.2), PAL['iron'][2])
        cv.put(rect(11, 18, 21, 19, N) | rect(11, 26, 21, 27, N), PAL['iron'][3])
    elif kind == 'fetch':
        sack = ellipse(16, 22.8, 5.2, 3.9, N)[0] | poly([(14, 19.5), (18, 19.5), (19.5, 17.5), (12.5, 17.5)], N)
        cv.put(sack, bands(PAL['hide'], 0.75 + 0.25 * bevel(sack) - 0.3 * (xs > 18.5), 0))
        cv.put(rect(13, 19, 19, 20, N), PAL['crimson'][3])
        cv.put(rect(17, 22, 18, 25, N), PAL['hide'][1])
    elif kind == 'chart':
        # A scrap of sea chart: washed blue water, an isle drawn in at its far corner, and a compass star
        sea = rect(9, 17, 24, 27, N)
        wash = 0.55 + 0.14 * (fbm(N, N, 5, 71) - 0.5) - 0.08 * (ys > 24.5) + 0.06 * (xs < 12)
        cv.put(sea, bands(SEA_INK, wash, 0))
        # Swell lines drawn across the water
        for (wy, x0, x1) in ((19, 10, 15), (24, 17, 22), (26, 19, 23)):
            cv.over(rect(x0, wy, x1, wy + 1, N), SEA_INK[4], 0.7)
        isle = ellipse(20.0, 20.0, 3.4, 2.4, N)[0] | ellipse(17.8, 21.2, 1.8, 1.3, N)[0]
        cv.put(isle, bands(PAL['parchment'], 0.8 + 0.15 * bevel(isle) - 0.12 * (ys > 21), 0))
        cv.put(ellipse(20.4, 19.6, 1.8, 1.2, N)[0], PAL['moss'][3])
        cv.px(20, 19, PAL['moss'][4])
        cv.put(grow(isle, 1) & ~isle & sea, SEA_INK[1])
        # The chart's compass star in the open water: four long points and four short, north picked out in red
        for k in range(-3, 4):
            cv.px(12 + k, 23, PAL['gold'][4 if k < 0 else 3])
            cv.px(12, 23 + k, PAL['gold'][4 if k < 0 else 3])
        for (dx, dy) in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            cv.px(12 + dx, 23 + dy, PAL['gold'][2])
        cv.px(12, 23, PAL['gold'][5])
        cv.px(12, 20, PAL['crimson'][4])
        cv.put(edge(sea) & ~sea, PAL['ink'][1])
        return cv
    elif kind == 'salvage':
        # An anchor hauled up out of the swell, a turn of rope still on its ring
        shank = rect(15, 18, 17, 26, N)
        ring = disc(16, 16.8, 1.9, N)[0] & ~disc(16, 16.8, 0.9, N)[0]
        stock = rect(11, 19, 21, 21, N)
        arc = (np.abs(np.hypot(xs - 16, ys - 21.0) - 5.0) < 0.95) & (ys > 22.6)
        flukes = poly([(9.6, 22.4), (12.8, 22.0), (11.2, 25.0)], N) | poly([(22.4, 22.4), (19.2, 22.0), (20.8, 25.0)], N)
        anchor = shank | ring | stock | arc | flukes
        cv.put(anchor, bands(PAL['steel'], 0.62 + 0.3 * bevel(anchor) - 0.14 * (xs > 16.5) + 0.08 * (ys < 21), 0))
        cv.put(rect(11, 20, 21, 21, N), PAL['steel'][2])
        rope = path_dist([(17.4, 17.4), (19.4, 18.0), (20.0, 19.0)], N)[0] < 0.6
        cv.put(rope & ~shank & ~stock, PAL['cork'][4])
        # The swell it's coming up out of, foam along its crest
        crest = 26.2 + 0.6 * np.sin(xs * 1.1)
        swell = (ys > crest) & (ys < 28.5) & (xs > 8) & (xs < 24)
        cv.put(swell, bands(SEA_INK, 0.42 - 0.12 * (ys > 27.2), 0))
        cv.put(swell & (ys < crest + 1.0), SEA_INK[5])
    elif kind == 'slay':
        # A great beast's horned skull, a long muzzle and fangs, embers still in its eyes
        for side in (-1, 1):
            pts = [(16 + side * 3.4, 20.6), (16 + side * 5.6, 19.4), (16 + side * 6.8, 17.4), (16 + side * 6.4, 15.4)]
            d, run = path_dist(pts, N)
            w = 1.25 - 0.85 * np.clip(run / 6.6, 0, 1)
            horn = d < w
            cv.put(horn, bands(PAL['ash'], 0.5 + 0.3 * np.clip(run / 6.6, 0, 1) + 0.2 * bevel(horn) - 0.1 * (side > 0), 0))
            # Ridges round the horn
            cv.put(horn & (np.abs(run - 2.2) < 0.45), PAL['ash'][1])
            cv.put(horn & (np.abs(run - 4.0) < 0.4), PAL['ash'][1])
        crown = ellipse(16, 21.2, 4.4, 3.3, N)[0]
        muzzle = poly([(12.7, 22.0), (19.3, 22.0), (18.5, 26.8), (13.5, 26.8)], N)
        skull = crown | muzzle
        cv.put(skull, bands(PAL['bone'], 0.7 + 0.25 * bevel(skull) - 0.15 * (xs > 18.5) - 0.1 * (ys > 25.5), 0))
        # The brow juts over slanted sockets
        for side in (-1, 1):
            eye = poly([(16 + side * 3.1, 21.0), (16 + side * 0.8, 22.0), (16 + side * 0.9, 23.2), (16 + side * 2.9, 22.7)], N)
            cv.put(eye, PAL['ink'][0])
            cv.px(16 + side * 2 - (side > 0), 22, (196, 36, 30))
        cv.put(rect(13, 21, 19, 22, N) & skull & ~disc(16, 21.5, 0.8, N)[0], PAL['bone'][2])
        cv.px(15, 25, PAL['ink'][0])
        cv.px(16, 25, PAL['ink'][1])
        cv.px(17, 25, PAL['ink'][0])
        for fx in (14.2, 17.8):
            cv.put(poly([(fx - 0.9, 26.4), (fx + 0.9, 26.4), (fx, 28.6)], N), PAL['bone'][4])
    elif kind == 'wanted':
        # The outlaw's likeness in a printed frame: a hood pulled low, eyes glinting out of its shadow and a red
        # kerchief over the mouth, with a gold mark at its corner for the price on their head
        frame = rect(11, 15, 22, 25, N)
        cv.put(frame, bands(PAL['parchment'], 0.86 - 0.1 * (ys > 22) + 0.04 * (xs < 14), 0))
        hood = ellipse(16.5, 19.4, 3.6, 3.4, N)[0]
        shoulders = poly([(11, 25.5), (12.8, 21.6), (20.2, 21.6), (22, 25.5)], N)
        cloak = (hood | shoulders) & frame
        cv.put(cloak, bands(PAL['blackleather'], 0.5 + 0.4 * bevel(cloak) - 0.12 * (xs > 18.5), 0))
        shade = ellipse(16.5, 20.0, 2.3, 2.4, N)[0]
        cv.put(shade, PAL['ink'][0])
        cv.px(15, 19, (226, 204, 152))
        cv.px(17, 19, (178, 160, 120))
        kerchief = rect(13, 21, 21, 23, N) & (shade | grow(shade, 1)) & frame
        cv.put(kerchief, bands(PAL['crimson'], 0.5 + 0.35 * bevel(kerchief) - 0.1 * (xs > 17.5), 0))
        coin, dome = disc(21.0, 24.0, 1.9, N)
        cv.put(coin, bands(PAL['gold'], 0.4 + 0.45 * dome + 0.15 * bevel(coin), 0))
        cv.px(20, 23, PAL['gold'][5])
    elif kind == 'hunt':
        # Three claw rakes torn through the paper
        for x0 in (11, 15, 19):
            d, tt = seg_dist(x0, 17.5, x0 + 3, 26.5, N)
            w = 0.9 * np.sin(np.clip(tt, 0, 1) * math.pi) + 0.15
            cv.put(d < w, (40, 18, 18))
            cv.put((d < w * 0.5) & (tt > 0.2) & (tt < 0.8), (130, 32, 30))
        return cv
    outline(cv, 0.85)
    return cv


def quest_note(kind):
    cv, paper, ink = note_paper(kind, 10 + NOTE_TYPES.index(kind) * 7)
    paste(cv, note_icon(kind))
    return cv.image()


# ================================================================ the quest board's own wood

def board_panel():
    cv = Canvas(N)
    xs, ys = grid(N)
    planks(cv, np.ones((N, N), bool), 0, 0, N, N, 8, PAL['wood'], 21, lift=0.06)
    # Nail heads where each plank meets the frame, old pin holes and scraps of torn notes
    for py in (3, 11, 19, 27):
        for px in (2, 29):
            cv.px(px, py, PAL['iron'][2])
            cv.px(px, py + 1, PAL['iron'][0])
    rng = np.random.default_rng(4)
    for _ in range(6):
        x, y = rng.integers(3, 29), rng.integers(1, 31)
        cv.px(int(x), int(y), PAL['wood'][0])
    for (x, y, w, h) in ((6, 4, 3, 2), (21, 22, 2, 3)):
        m = rect(x, y, x + w, y + h, N)
        cv.put(m, bands(PAL['parchment'], 0.45 + 0.2 * (rand(N, N, x) > 0.5)))
        cv.px(x + w // 2, y, PAL['iron'][3])
    return cv.image()


def board_frame():
    cv = Canvas(N)
    xs, ys = grid(N)
    n = fbm(N, N, 6, 31)
    grain = vnoise(N, N, 2, 33)
    # No strong grain either way, so it reads the same on the frame's uprights and its crossbar
    t = 0.48 + 0.3 * (n - 0.5) + 0.12 * (grain - 0.5)
    cv.put(np.ones((N, N), bool), bands(PAL['darkwood'], t, 0.2))
    return cv.image()


# ================================================================ main

def build():
    out = {}
    out['item/guild_mark'] = guild_mark()
    out['item/contract'] = contract()
    out['item/quest_board'] = quest_board_item()
    for tier in CACHE_TIERS:
        out[f'item/relic_cache_{tier}'] = relic_cache(tier)
    for name, fill in FLASK_LEVELS:
        out[f'item/{name}'] = flask(fill)
    out['item/crown_shard'] = crown_shard()
    out['item/dusk_bridle'] = dusk_bridle()
    out['item/lantern_wisp'] = lantern_wisp()
    out['item/hollow_soldier_spawn_egg'] = soldier_egg()
    out['item/fallen_warden_spawn_egg'] = warden_egg()
    out['item/hill_troll_spawn_egg'] = troll_egg()
    out['item/dusk_rider_spawn_egg'] = rider_egg()
    for kind in NOTE_TYPES:
        out[f'block/quest_note_{kind}'] = quest_note(kind)
    out['block/quest_board_panel'] = board_panel()
    out['block/quest_board_frame'] = board_frame()
    return out


def main():
    assets = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    imgs = build()
    for key, img in imgs.items():
        path = os.path.join(assets, 'textures', key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
    if prev:
        os.makedirs(prev, exist_ok=True)
        keys = list(imgs)
        sheet([imgs[k] for k in keys], 5, 8, labels=[k.split('/')[-1][:22] for k in keys]).save(os.path.join(prev, 'items_hd.png'))


if __name__ == '__main__':
    main()
