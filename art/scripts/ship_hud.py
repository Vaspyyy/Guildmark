"""Guildmark's sailing HUD, painted at 4x with the HD art kit (hdkit.py) in the iron-and-gold of the other HUD sprites:
the wind rose (a dark chart-textured well in an iron ring with a gold rim, 32 ticks), the cog's plan to stand at its
centre, the wind's arrow and the no-go wedge (both turned about the rose's centre by the game), the sail gauge with its
frame, red canvas fill and gold ordered-setting mark, the anchor up and down, and the cog's icon for the compass strip
and the world map (the 1x designs below go through ui_hd.py's own remaster, so they sit exactly beside the older icons).

    python3 -B art/scripts/ship_hud.py src/main/resources/assets/guildmark [preview-dir [vanilla ascii.png]]

Writes textures/gui/sprites/ship/{rose, rose_hull, wind_arrow, nogo, sail_frame, sail_fill, sail_mark, anchor_up,
anchor_down}.png and sprites/compass/ship.png and sprites/map/ship.png. With a preview dir it also writes
ship/hud_mock.png (the whole HUD at GUI scale 3 over a sea and sky, sailing well on a reach and head to wind at anchor)
and ship/hud_sprites.png (every sprite large). Everything is seeded, so a rerun gives the same files.

Sizes: every ship/ sprite is painted at four texels to the GUI pixel it is drawn at, so a sprite drawn at W x H GUI
pixels is 4W x 4H texels. The compass and map icons are the 2x of the older ones (14x14 for 7x7, 22x22 for 11x11).

    sprite          PNG        drawn at (GUI px)   what the game does with it
    rose            224x224    56x56               the dial, drawn as it is; its centre is the ship's
    rose_hull       64x112     16x28               the cog's plan, bow up, centred on the dial's centre, never turned
    wind_arrow      32x176     8x44                points UP; turned about its image centre (the dial's centre) to the way the wind blows
    nogo            224x224    56x56               a 90 degree wedge centred on UP, its point at the image centre (the dial's centre);
                                                   turned about the centre to the direction the wind blows from
    sail_frame      32x176     8x44                the gauge's case, its slot 4x38 GUI px at (2, 3)
    sail_fill       32x176     8x44                the red canvas, painted only inside the slot; drawn at the frame's place and
                                                   cut off from the top so it grows from the bottom up
    sail_mark       40x12      10x3                the ordered setting's notch, centred across the gauge at the right height
    anchor_up       48x48      12x12               weighed: cold iron, dim
    anchor_down     48x48      12x12               down: bright gold
    compass/ship    14x14      7x7                 beside the other compass markers
    map/ship        22x22      11x11               beside the other map markers

The gauge's slot is rows 3 to 41 of the 44 (x 2 to 6), graduated in quarters. To show the sail let down to a fraction f of
it, draw the bottom n = round(38 f) rows of the slot: the part of sail_fill from v = 41 - n down to 41, at y = gaugeTop + 41 - n;
the notch for an order of f is centred at gaugeTop + 41 - 38 f. (Mapping f over all 44 rows instead lands the fill's top about
3 px low at the foot and 3 px high at the top, off the quarter marks.) The no-go wedge is NOGO_DEGREES (90) wide: the game's
Sailing.NO_GO, 45 either side of dead ahead, where the sail gives her no drive at all; change both together.

Painted for light from the upper left, like the rest of the UI. Drawn with nearest sampling the rotated sprites hold
up at GUI scales 1 to 4: their edges are soft and nothing that has to show is thinner than a GUI pixel (4 texels).
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from hdkit import (PAL, Canvas, grid, vnoise, fbm, rand, tone, mix, ramp, bands, poly, seg_dist, disc, ellipse, rect,
                   blur, edge, grow, dist_in, light, rim, outline, glint, bevel, paste, sheet)

K = 4                                   # texels to the GUI pixel
OUT = np.array([12.0, 11.0, 14.0])      # the dark outline of every frame
IRON, GOLD, WOOD, DARKWOOD = PAL['iron'], PAL['gold'], PAL['wood'], PAL['darkwood']
# The sail's canvas: a weathered madder red, warmer than the bars' blood red
SAIL = [(44, 14, 16), (88, 26, 24), (130, 40, 32), (164, 58, 42), (194, 88, 58), (222, 128, 86)]
SEED = 26


def smoothstep(a, b, x):
    t = np.clip((np.asarray(x, float) - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def polar(w, h=None, cx=None, cy=None):
    """Distance from a centre (texels), the angle clockwise from straight up (degrees, 0..360), and x and y from it."""
    h = h or w
    xs, ys = grid(w, h)
    dx = xs - (w / 2 if cx is None else cx)
    dy = ys - (h / 2 if cy is None else cy)
    return np.hypot(dx, dy), np.degrees(np.arctan2(dx, -dy)) % 360.0, dx, dy


def angdiff(a, b):
    """The signed difference a - b of two angles in degrees, in -180..180."""
    return (np.asarray(a, float) - b + 180.0) % 360.0 - 180.0


def cover(d, soft=0.7):
    """Anti-aliased coverage of a shape from its signed distance d (texels, positive inside)."""
    return np.clip(0.5 + np.asarray(d, float) / (2 * soft), 0.0, 1.0)


def to_image(cv):
    return cv.image()


# ================================================================ the wind rose

R_OUT = 112.0                           # the dial: 56 GUI px across, 224 texels
R_IRON_OUT, R_IRON_IN, R_GROOVE, R_GOLD_IN = 110.0, 100.5, 99.0, 94.0
R_WELL = 94.0                           # the well inside the gold rim, 23.5 GUI px in radius
TICK_OUT = 89.0                         # ticks stand just inside the rim


def ring_height(r):
    """The ring's profile as a height field in texels: a rounded iron band, a dark groove, a gold bead, the sunk well."""
    a = np.clip((R_IRON_OUT - r) / (R_IRON_OUT - R_IRON_IN), 0, 1)
    iron = np.where((r <= R_IRON_OUT) & (r >= R_IRON_IN), 0.5 + 3.4 * np.sin(np.pi * a) ** 0.7, 0.0)
    b = np.clip((R_GROOVE - r) / (R_GROOVE - R_GOLD_IN), 0, 1)
    gold = np.where((r < R_GROOVE) & (r >= R_GOLD_IN), 1.0 + 2.4 * np.sin(np.pi * b) ** 0.7, 0.0)
    return iron + gold


def tick_layer(W, ang, r, a, length, width, lit):
    """One tick as coverage and a lit (+1) or shaded (-1) side: a short bar standing radially at angle [a], its outer end at TICK_OUT."""
    across = angdiff(ang, a) * np.pi / 180 * r
    cov = cover(width / 2 - np.abs(across), 0.55) * cover(r - (TICK_OUT - length), 0.55) * cover(TICK_OUT - r, 0.55)
    ar = math.radians(a)
    facing = (math.cos(ar) * -0.58 + math.sin(ar) * -0.64)          # the across direction's dot with the light
    side = np.sign(across * facing) * smoothstep(0.0, width * 0.32, np.abs(across))
    return cov, side


def rose():
    W = 56 * K
    r, ang, dx, dy = polar(W)
    xs, ys = grid(W)
    cx = W / 2
    cv = Canvas(W)
    grain = fbm(W, W, 5, SEED + 1)
    fine = rand(W, W, SEED + 2)
    # ---- the well: a dark chart under glass, darkening toward the rim and faintly lit about the middle
    well = r < R_WELL
    pool = np.exp(-(r / 62.0) ** 2)
    base = np.array([13.0, 15.0, 22.0])
    mott = fbm(W, W, 18, SEED + 5)
    col = base[None, None, :] * (0.74 + 0.62 * pool[..., None] + (mott[..., None] - 0.5) * 0.7 + (grain[..., None] - 0.5) * 0.35)
    col = col * (1 + (fine[..., None] - 0.5) * 0.10)
    cv.put(well, col, 0.82 + 0.10 * smoothstep(60, 94, r))
    # The chart: depth contours (the isolines of a slow noise), sixteen rhumb lines, dashed ranges, soundings
    depth = fbm(W, W, 70, SEED + 6, octaves=4) * 9.0
    contour = np.clip(1.0 - np.abs((depth % 1.0) - 0.5) * 2 / 0.16, 0, 1) * (r > 8)
    cv.over(well, np.array([104.0, 142.0, 156.0]), np.where(well, contour * 0.13, 0))
    chart = np.zeros((W, W))
    for k in range(16):
        a = k * 22.5
        dd = np.abs(angdiff(ang, a)) * np.pi / 180 * r
        main = (k % 2 == 0)
        line = np.clip(1.15 - dd / (1.1 if main else 0.8), 0, 1) * (r > (14 if main else 26)) * (r < (80 if main else 70))
        chart = np.maximum(chart, line * (0.22 if main else 0.13) * (1 - 0.6 * smoothstep(54, 82, r)))
    for rad, wd, gap in ((34.0, 0.9, 3), (62.0, 0.9, 5)):
        dash = (np.sin(np.radians(ang) * gap * 6 + 0.4) > -0.15)
        chart = np.maximum(chart, np.clip(1.1 - np.abs(r - rad) / wd, 0, 1) * dash * 0.17)
    soundings = (rand(W, W, SEED + 3) > 0.9965) & (r < 80) & (r > 12)
    chart = np.maximum(chart, soundings * 0.24)
    # the ship's own axis, bow to stern, a little stronger
    chart = np.maximum(chart, np.clip(1.1 - np.abs(dx) / 0.9, 0, 1) * (r > 20) * (r < 76) * 0.16)
    cv.over(well, np.array([200.0, 170.0, 112.0]), np.where(well, chart, 0))
    # ---- the ring
    H = ring_height(r)
    lt = light(H, 1.7)
    iron_m = (r <= R_IRON_OUT) & (r >= R_IRON_IN)
    ti = 0.58 + 0.9 * lt + (grain - 0.5) * 0.30 + (fine - 0.5) * 0.10
    ti = ti - 0.18 * smoothstep(R_IRON_IN + 2.2, R_IRON_IN, r)
    cv.put(iron_m, bands(IRON, ti, 0.2))
    groove = (r < R_IRON_IN) & (r >= R_GROOVE)
    cv.put(groove, np.broadcast_to(OUT, (W, W, 3)))
    gold_m = (r < R_GROOVE) & (r >= R_GOLD_IN)
    tg = 0.56 + 1.0 * lt + (grain - 0.5) * 0.3
    cv.put(gold_m, bands(GOLD, tg, 0.25))
    outer = (r <= R_OUT) & (r > R_IRON_OUT)
    cv.put(outer, np.broadcast_to(OUT, (W, W, 3)))
    cv.a = np.where(outer, 0.92, cv.a)
    cv.a = np.where(iron_m | groove | gold_m, 1.0, cv.a)
    # the well's own shadow under the gold, a dark rim line and a soft inner shade
    lip = (r < R_GOLD_IN) & (r >= R_GOLD_IN - 2.5)
    cv.over(lip, OUT, 0.85)
    cv.a = np.where(lip, np.maximum(cv.a, 0.94), cv.a)
    cv.over(well, OUT, 0.30 * smoothstep(R_GOLD_IN - 12, R_GOLD_IN - 2, r))
    # ---- the ticks: 32 of them, a larger one every 45 degrees (the bow has its pointer)
    for k in range(1, 32):
        a = k * 11.25
        major = k % 4 == 0
        length, width = (14.0, 4.6) if major else (8.0, 3.4)
        cov, side = tick_layer(W, ang, r, a, length, width, True)
        cov = cov * well
        # the tick's shadow, down and to the right
        shadow = np.roll(np.roll(cov, 1, axis=0), 1, axis=1) * (1 - cov)
        cv.over(well, OUT, shadow * 0.7)
        tt = (0.74 if major else 0.52) + 0.2 * side + 0.05 * (grain - 0.5)
        colr = bands(GOLD, tt, 0.0)
        cv.over(well, colr, cov * (1.0 if major else 0.88))
    # ---- the bow's pointer: a gilt wedge standing over the ring's top, pointing in
    ptr = poly([(cx - 6.2, 1.8), (cx + 6.2, 1.8), (cx + 6.2, 8.5), (cx + 3.4, 14.5), (cx, 20.5), (cx - 3.4, 14.5), (cx - 6.2, 8.5)], W)
    ph = dist_in(ptr, 5) / 5.0
    halo = grow(ptr, 2) & ~ptr
    cv.over(halo, OUT, 0.92)
    cv.a = np.where(halo, np.maximum(cv.a, 0.95), cv.a)
    cv.put(ptr, bands(GOLD, 0.54 + 0.9 * light(ph * 3.0, 1.0) + 0.16 * (ys < 9), 0.2), 1.0)
    # ---- gilt studs on the iron at the beam and the stern
    for a in (90.0, 180.0, 270.0):
        sx = cx + math.sin(math.radians(a)) * 105.2
        sy = cx - math.cos(math.radians(a)) * 105.2
        m, d = disc(sx, sy, 3.6, W)
        cv.over(grow(m, 1) & ~m, OUT, 0.9)
        cv.put(m, bands(GOLD, 0.5 + 0.55 * d - 0.35 * (((xs - sx) + (ys - sy)) > 0), 0.1), 1.0)
        cv.px(int(sx - 1.4), int(sy - 1.4), GOLD[5])
    # round off the very edge so the circle isn't a staircase
    cv.a = np.where(r <= R_OUT + 1, cv.a * cover(R_OUT - r, 0.6), 0)
    return cv.image()


# ================================================================ the wind's arrow and the no-go wedge

def aa_mask(draw, w, h, ss=8):
    """A shape drawn by [draw(ImageDraw, scale)] at [ss] times the size and brought down: anti-aliased coverage 0..1."""
    big = Image.new('L', (w * ss, h * ss), 0)
    draw(ImageDraw.Draw(big), ss)
    small = big.resize((w, h), Image.BOX)
    return np.asarray(small, float) / 255.0


def bez(p0, p1, p2, n=12):
    """Points along a quadratic curve."""
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / n for i in range(n + 1))]


def wind_arrow():
    """The wind's arrow, pointing up: a faceted gold-and-white head, a shaft that thins to the tail, three pairs of curved feathers
    and wisps trailing behind, in a soft glow. Turned about its image centre, which is the middle of the shaft."""
    w, h = 8 * K, 44 * K
    cx = w / 2
    xs, ys = grid(w, h)

    def head(d, s):
        d.polygon([(cx * s, 1.2 * s), ((cx + 11.4) * s, 34.0 * s), (cx * s, 27.0 * s), ((cx - 11.4) * s, 34.0 * s)], fill=255)

    def shaft(d, s):
        d.polygon([((cx - 2.6) * s, 26 * s), ((cx + 2.6) * s, 26 * s), ((cx + 1.6) * s, 160 * s), ((cx - 1.6) * s, 160 * s)], fill=255)

    feathers = ((112.0, 25.0, 11.5, 4.6), (127.0, 25.0, 10.0, 4.4), (142.0, 24.0, 8.2, 4.0))

    def vane_pts(sx, y0, L, spread, thick):
        lead = bez((cx + sx * 1.0, y0), (cx + sx * spread * 0.45, y0 + L * 0.10), (cx + sx * spread, y0 + L))
        trail = bez((cx + sx * spread, y0 + L), (cx + sx * (spread - thick * 1.6), y0 + L * 0.62), (cx + sx * 1.0, y0 + L * 0.50))
        return lead + trail

    def vanes(d, s):
        for (y0, L, spread, thick) in feathers:
            for sx in (-1, 1):
                d.polygon([(x * s, y * s) for x, y in vane_pts(sx, y0, L, spread, thick)], fill=255)

    def wisps(d, s):
        for dxo, y1 in ((-4.2, 172.0), (4.2, 172.0), (0.0, 175.5)):
            d.polygon([((cx + dxo - 0.9) * s, 156 * s), ((cx + dxo + 0.9) * s, 156 * s), ((cx + dxo * 1.4 + 0.3) * s, y1 * s), ((cx + dxo * 1.4 - 0.3) * s, y1 * s)], fill=255)

    m_head, m_shaft, m_vane, m_wisp = (aa_mask(f, w, h) for f in (head, shaft, vanes, wisps))
    body = np.maximum.reduce([m_head, m_shaft, m_vane, m_wisp])
    # a soft dark outline round the head and shaft, and a glow round all of it
    ol = np.clip(blur(np.maximum(m_head, m_shaft), 2) * 2.6, 0, 1) * (1 - body)
    glow = np.clip(blur(body, 4) * 1.7, 0, 1) * (1 - body) * np.where(ys < 52, 1.0, 0.6)
    cv = Canvas(w, h)
    cv.rgb[...] = np.array([255.0, 226.0, 150.0])
    cv.a = glow * 0.20
    cv.rgb = np.where((ol > 0)[..., None], OUT, cv.rgb)
    cv.a = np.maximum(cv.a, ol * 0.80)
    gold_white = [(150, 114, 56), (190, 150, 84), (222, 188, 118), (240, 214, 150), (252, 238, 200), (255, 252, 238)]

    def lay(m, t, extra_alpha=1.0):
        c = bands(gold_white, np.clip(t, 0, 1), 0.0)
        a = (m * extra_alpha)[..., None]
        cv.rgb = cv.rgb * (1 - a) + c * a
        cv.a = np.maximum(cv.a, m * extra_alpha)
    along = np.clip((ys - 26) / 134.0, 0, 1)
    side_h = xs - cx
    # wisps and feathers: the lit side pale, the far side a shade deeper, dimming away from the shaft
    rach = np.exp(-(np.abs(side_h) / 1.0) ** 2)
    fade_f = np.clip(1.0 - np.abs(side_h) / 15.0, 0.3, 1.0) * np.clip(1.0 - (ys - 112) / 90.0, 0.35, 1.0)
    t_vane = np.where(side_h < 0, 0.86, 0.66) * fade_f + 0.12
    lay(m_wisp, 0.55 - 0.2 * along, 0.5 * np.clip(1.0 - (ys - 156) / 20.0, 0.1, 1.0))
    lay(m_vane, t_vane, 0.86)
    # the shaft: bright on its left, shaded on its right, thinning and dimming toward the tail
    t_shaft = 0.84 - 0.30 * along - 0.26 * np.clip(side_h / 2.6, -1, 1)
    lay(m_shaft, t_shaft, 0.97 - 0.18 * along)
    # the head: two facets, the left lit and the right in shade, a bright ridge down the middle
    t_head = np.where(side_h < 0, 0.96, 0.60) + 0.10 * np.exp(-(side_h / 0.9) ** 2)
    lay(m_head, t_head, 1.0)
    return cv.image()


NOGO_DEGREES = 90.0                     # the wedge's width: the game's Sailing.NO_GO, 45 either side, where the sail gives no drive


def nogo():
    """The no-go wedge: NOGO_DEGREES (90) of dull red, its point at the centre, centred on up. Soft at its edges, brighter along them and
    along an arc just outside the ticks, thin where the ship is and thinner over the ticks, which have to stay readable."""
    W = 56 * K
    r, ang, dx, dy = polar(W)
    half = NOGO_DEGREES / 2.0
    off = np.abs(angdiff(ang, 0.0))
    s = r * np.sin(np.radians(np.clip(half - off, -90, 90)))             # distance inside the nearer edge, texels
    s = np.where(off > 90, -r, s)
    inside = smoothstep(-2.4, 2.4, s)
    radial = smoothstep(10, 42, r) * (1 - 0.60 * smoothstep(66, 90, r)) * (1 - smoothstep(90.5, 93.5, r))
    grain = fbm(W, W, 9, SEED + 11)
    edge_line = np.exp(-(s / 2.2) ** 2) * (off < 90) * smoothstep(22, 46, r) * (1 - smoothstep(88, 94, r))
    arc = np.exp(-((r - 92.0) / 1.5) ** 2) * smoothstep(-1.5, 3.0, s) * (off < 90)
    a = inside * radial * (0.36 + 0.10 * (grain - 0.5)) + edge_line * 0.34 + arc * 0.50 * inside
    a = np.clip(a, 0, 0.62)
    k = np.clip(0.25 + 0.7 * edge_line + 0.55 * arc + 0.25 * smoothstep(30, 86, r), 0, 1)
    col = np.stack([138 + 78 * k, 34 + 30 * k, 32 + 24 * k], -1)
    cv = Canvas(W)
    cv.rgb = col
    cv.a = a
    return cv.image()


# ================================================================ the cog's plan

HULL_W, HULL_H = 16 * K, 28 * K          # 64 x 112: the canvas the game draws, centred on the dial's centre
PLAN_H = 26 * K                          # the plan itself is drawn on 64 x 104 and set in the middle of the canvas
BOW_Y, STERN_Y = 8.0, 96.0               # the hull's tip and its transom, so its middle is the plan's (and the canvas's)
HULL_HALF = 23.0                         # half the beam, texels


def hull_half_width(y):
    """The hull's half beam at [y] texels from the top: a bluff, rounded bow, the greatest beam abaft the middle and a squarish
    stern with rounded quarters, like a cog's."""
    u = np.clip((np.asarray(y, float) - BOW_Y) / (STERN_Y - BOW_Y), 0.0, 1.0)
    bow = HULL_HALF * np.sin(np.clip(u / 0.52, 0, 1) * np.pi / 2) ** 0.58
    stern = HULL_HALF - 5.0 * np.clip((u - 0.60) / 0.40, 0, 1) ** 2.3
    return np.where(u < 0.52, bow, stern)


def rose_hull():
    w, h = HULL_W, PLAN_H
    xs, ys = grid(w, h)
    cx = w / 2

    def tt(v):
        return np.broadcast_to(np.asarray(v, float), (h, w))
    cv = Canvas(w, h)
    grain = fbm(w, h, 4, SEED + 21)
    fine = rand(w, h, SEED + 22)
    hw = hull_half_width(ys)
    body = (np.abs(xs - cx) <= hw) & (ys >= BOW_Y) & (ys <= STERN_Y)
    # round the transom's corners
    for sx in (-1, 1):
        ccx, ccy = cx + sx * (hull_half_width(STERN_Y) - 5.0), STERN_Y - 5.0
        corner = ((xs - cx) * sx > hull_half_width(STERN_Y) - 5.0) & (ys > ccy)
        body &= ~(corner & (np.hypot(xs - ccx, ys - ccy) > 5.0))
    d_in = dist_in(body, 9)                  # texels from the hull's edge, 1 at the edge
    xn = (xs - cx) / np.maximum(hw, 1.0)     # -1..1 across the hull
    # ---- the sheer: a raised rail round the edge, lit on its upper left, and the deck inside it
    rail = body & (d_in <= 4)
    deck = body & ~rail
    # planks run bow to stern, closing in toward the stem: seams at fixed fractions of the beam
    u = (xn + 1.0) * 4.0
    plank = np.floor(u)
    seam = np.minimum(u % 1.0, 1.0 - u % 1.0) < 0.07
    butt = ((ys + (plank % 3) * 9.0) % 27.0) < 1.0
    t_deck = 0.60 + 0.10 * ((plank % 2) - 0.5) + 0.14 * (grain - 0.5) + 0.05 * (fine - 0.5) - 0.22 * seam - 0.18 * butt
    # the rail catches the light where it faces up and left, and sits in shade where it faces down and right
    left = np.where(xs < cx, 1.0, -1.0)
    t_rail = 0.42 + 0.26 * np.where(d_in <= 2, left * 0.9 + (ys < 40) * 0.5, 0.0) + 0.12 * (d_in == 4) + 0.10 * (grain - 0.5)
    cv.put(deck, bands(WOOD, tt(t_deck), 0.15))
    cv.put(rail, bands(DARKWOOD, tt(np.clip(t_rail, 0, 1) + 0.06), 0.1))
    # ---- the forecastle: a small raised deck at the bow, lighter, its after edge in shadow
    fore = deck & (ys > 17) & (ys < 31) & (np.abs(xn) < 0.94)
    cv.put(fore, bands(WOOD, tt(0.72 + 0.12 * ((ys - 17) < 2) + 0.1 * (grain - 0.5) - 0.2 * seam), 0.15))
    cv.over(fore & (ys > 29), OUT, 0.5)
    # ---- the stern castle: a raised deck over the aft quarter, and the ship's wheel on it
    castle = deck & (ys > 69)
    sc = (xs - cx) / 3.6
    seam_c = np.abs(sc % 1.0) < 0.1
    cv.put(castle, bands(WOOD, tt(0.76 + 0.08 * ((np.floor(sc) % 2) - 0.5) + 0.12 * (grain - 0.5) - 0.2 * seam_c), 0.15))
    cv.put(castle & (ys <= 71.5), bands(WOOD, tt(0.98), 0.0))
    cv.over(deck & (ys > 66.5) & (ys <= 69), OUT, 0.55)
    wm, wd = disc(cx, 82.0, 5.6, w, h)
    ringm = wm & (np.hypot(xs - cx, ys - 82.0) > 3.4)
    sp = np.zeros((h, w), bool)
    for k in range(6):
        dd, _ = seg_dist(cx, 82.0, cx + 5.4 * math.sin(math.radians(k * 60)), 82.0 - 5.4 * math.cos(math.radians(k * 60)), w, h)
        sp |= dd < 0.7
    cv.over(grow(wm, 1) & ~wm, OUT, 0.5)
    cv.put(ringm, bands(DARKWOOD, tt(0.8 + 0.2 * ((xs < cx) & (ys < 82.0))), 0.0))
    cv.put(wm & ~ringm & sp, bands(DARKWOOD, tt(0.9), 0.0))
    cv.put(wm & ~ringm & ~sp, bands(DARKWOOD, tt(0.1), 0.0))
    hubm, _ = disc(cx, 82.0, 1.3, w, h)
    cv.put(hubm, GOLD[4])
    # ---- the hatch abaft the mast: a dark grating
    hatch = deck & (np.abs(xs - cx) < 8) & (ys > 55) & (ys < 66)
    grate = ((np.floor(xs / 2.0) + np.floor(ys / 2.0)) % 2 == 0)
    cv.put(hatch, bands(DARKWOOD, tt(0.22 + 0.16 * grate), 0.0))
    hatch_rim = hatch & ((np.abs(xs - cx) > 6.6) | (ys < 56.5))
    cv.put(hatch_rim, bands(WOOD, tt(0.62 + 0.12 * ((xs < cx) | (ys < 56))), 0.0))
    # ---- the rudder behind the stern
    rud = (np.abs(xs - cx) <= 2.0) & (ys > STERN_Y - 1) & (ys <= STERN_Y + 6)
    cv.put(rud, bands(DARKWOOD, tt(0.55 + 0.25 * (xs < cx)), 0.0))
    allm = body | rud
    # ---- the yard across the mast, its shadow on the deck, and the mast's gilt head
    mast_y = 45.0
    yard = (np.abs(xs - cx) <= 29.5) & (np.abs(ys - mast_y) <= 2.5)
    cv.over(np.roll(np.roll(yard, 3, axis=0), 2, axis=1) & body & ~yard, OUT, 0.55)
    yh = np.where(yard, 1.0 - np.abs(ys - mast_y) / 2.5, 0.0)
    ty = 0.62 + 0.55 * light(yh * 2.4, 1.0) + 0.1 * (grain - 0.5) + 0.12 * (np.abs(xs - cx) > 25)
    yring = grow(yard, 2) & ~yard
    cv.over(yring, OUT, 0.93)
    cv.a = np.where(yring, np.maximum(cv.a, 0.93), cv.a)
    cv.put(yard, bands(WOOD, tt(ty), 0.1), 1.0)
    for sx in (-1, 1):
        cap = (np.abs(xs - (cx + sx * 28.0)) < 1.6) & (np.abs(ys - mast_y) <= 2.5)
        cv.put(cap, bands(DARKWOOD, tt(0.5), 0.0))
    lash = yard & (np.abs(xs - cx) < 5.5) & (np.abs(xs - cx) > 3.0) & ((np.floor(ys) % 2) == 0)
    cv.put(lash, bands(PAL['leather'], tt(0.55), 0.0))
    mm, md = disc(cx, mast_y, 3.4, w, h)
    cv.over(grow(mm, 1) & ~mm, OUT, 0.9)
    cv.a = np.where(grow(mm, 1), np.maximum(cv.a, 1.0), cv.a)
    cv.put(mm, bands(GOLD, tt(0.5 + 0.5 * md - 0.35 * (((xs - cx) + (ys - mast_y)) > 0)), 0.1), 1.0)
    cv.px(int(cx - 1), int(mast_y - 1), GOLD[5])
    # ---- the dark outline round the hull and the rudder
    ring = grow(allm, 2) & ~allm & ~(yard | yring | grow(mm, 1))
    cv.rgb[ring] = OUT
    cv.a = np.where(ring, np.maximum(cv.a, 0.92), cv.a)
    # a thin rim of light on the upper left of the plan, so it lifts off the dark well
    lit_edge = body & ~np.roll(np.roll(body, 1, axis=0), 1, axis=1) & ~yard
    cv.rgb[lit_edge] = tone(cv.rgb[lit_edge], 0.3)
    out = Image.new('RGBA', (HULL_W, HULL_H), (0, 0, 0, 0))
    out.paste(cv.image(), (0, (HULL_H - PLAN_H) // 2))
    return out


# ================================================================ the sail gauge

GAUGE_W, GAUGE_H = 8 * K, 44 * K         # 32 x 176
SLOT = (8, 12, 24, 164)                  # the slot inside the case, texels: x0, y0, x1, y1 (4 x 38 GUI px, at (2, 3))
GAUGE_STEPS = 4                          # graduations: the slot is quartered


def rrect_dist(x0, y0, x1, y1, xs, ys, rad=0.0):
    """Signed distance to a rounded rectangle: positive inside, texels."""
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    hx, hy = (x1 - x0) / 2 - rad, (y1 - y0) / 2 - rad
    qx, qy = np.abs(xs - cx) - hx, np.abs(ys - cy) - hy
    out = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - rad
    return -out


def sail_frame():
    w, h = GAUGE_W, GAUGE_H
    xs, ys = grid(w, h)
    x0, y0, x1, y1 = SLOT
    cv = Canvas(w, h)
    grain = fbm(w, h, 4, SEED + 31)
    fine = rand(w, h, SEED + 32)
    dout = rrect_dist(0, 0, w, h, xs, ys, 3.0)             # texels in from the case's edge
    din = -rrect_dist(x0, y0, x1, y1, xs, ys, 0.0)         # texels out from the slot
    slot = din <= 0
    case = dout > 0
    # the outline, a gold bead round the slot, and between them the iron: its height rounded across however thick it is
    outline_m = case & (dout <= 2.0)
    gold_m = case & ~slot & (din <= 2.4) & ~outline_m
    iron_m = case & ~slot & ~outline_m & ~gold_m
    u = np.clip((din - 2.4) / np.maximum((din - 2.4) + (dout - 2.0), 1e-6), 0, 1)
    H = np.where(iron_m, 0.5 + 3.2 * np.sin(np.pi * u) ** 0.7, 0.0)
    H = H + np.where(gold_m, 1.0 + 1.6 * np.sin(np.pi * np.clip((2.4 - din) / 2.4, 0, 1)) ** 0.7, 0.0)
    lt = light(H, 1.7)
    cv.put(outline_m, np.broadcast_to(OUT, (h, w, 3)), 0.92)
    cv.put(iron_m, bands(IRON, 0.58 + 0.9 * lt + 0.28 * (grain - 0.5) + 0.1 * (fine - 0.5), 0.2), 1.0)
    cv.put(gold_m, bands(GOLD, 0.56 + 1.0 * lt + 0.3 * (grain - 0.5), 0.25), 1.0)
    # the slot: a dark glass, shaded under the case's top and along its lit side
    depth = np.clip(-din / 5.0, 0, 1)
    well = slot & case
    base = np.array([12.0, 13.0, 19.0])
    cv.put(well, base[None, None, :] * (0.82 + 0.3 * depth[..., None] - 0.1 + (grain[..., None] - 0.5) * 0.4), 0.84)
    cv.over(well & (ys < y0 + 6), OUT, 0.45 * (1 - (ys - y0) / 6.0))
    cv.over(well & (xs < x0 + 2), OUT, 0.4)
    # graduations: a gold tick at each quarter on both sides, the middle one longer
    for k in range(GAUGE_STEPS + 1):
        ty = y1 - k * (y1 - y0) / GAUGE_STEPS
        big = k == GAUGE_STEPS // 2
        ln = 7.0 if big else 4.0
        for sx in (0, 1):
            xa, xb = (x0, x0 + ln) if sx == 0 else (x1 - ln, x1)
            m = well & (xs >= xa) & (xs <= xb) & (np.abs(ys - ty) <= (1.2 if k not in (0, GAUGE_STEPS) else 0.001))
            cv.put(m, bands(GOLD, np.full((h, w), 0.72 if big else 0.55), 0.0), 0.95)
    # a gilt rivet at each end of the case
    for ry in (6.0, h - 6.0):
        m, d = disc(w / 2, ry, 3.4, w, h)
        cv.over(grow(m, 1) & ~m & case, OUT, 0.85)
        cv.put(m, bands(GOLD, 0.5 + 0.55 * d - 0.35 * (((xs - w / 2) + (ys - ry)) > 0), 0.1), 1.0)
        cv.px(int(w / 2 - 1.3), int(ry - 1.3), GOLD[5])
    cv.a = np.where(case, cv.a * cover(dout, 0.6), 0.0)
    return cv.image()


def sail_fill():
    """The canvas that fills the slot, painted only inside it: a red lit from the left and rounded across, with a weave, a cloth seam
    down the middle, a reefing band at each quarter and a bolt-rope along the foot. Cut off from the top, it grows from the bottom."""
    w, h = GAUGE_W, GAUGE_H
    xs, ys = grid(w, h)
    x0, y0, x1, y1 = SLOT
    cv = Canvas(w, h)
    slot = (xs >= x0) & (xs < x1) & (ys >= y0) & (ys < y1)
    fx = np.clip((xs - x0) / (x1 - x0), 0, 1)           # 0 at the lit left side, 1 at the right
    fy = (y1 - ys) / (y1 - y0)                          # 0 at the foot, 1 at the head
    grain = fbm(w, h, 4, SEED + 33)
    fine = rand(w, h, SEED + 34)
    weave = ((np.floor(ys / 2.0) + np.floor(xs / 2.0)) % 2) * 0.05 + (np.floor(ys / 2.0) % 2) * 0.03
    # rounded across the slot: the lit face on the left, a deep shade on the right
    t = 0.74 - 0.52 * fx ** 1.3 + 0.10 * np.sin(np.pi * np.clip(fx * 1.2, 0, 1)) + weave * 0.8 + 0.09 * (grain - 0.5) + 0.04 * (fine - 0.5)
    # a seam of stitching down the middle
    seam = (np.abs(xs - (x0 + x1) / 2) < 0.8) & ((np.floor(ys / 3.0) % 2) == 0)
    t = t - 0.20 * seam
    # a reefing band at each quarter: a darker, taut strip with the reef-points' knots along it
    for k in range(1, GAUGE_STEPS):
        yb = y1 - k * (y1 - y0) / GAUGE_STEPS
        band = np.abs(ys - yb) <= 2.0
        t = t - 0.16 * band + 0.1 * (np.abs(ys - (yb - 2.5)) < 0.6)
        knot = (np.abs(ys - yb) < 1.0) & (np.abs(((xs - x0) % 5.0) - 2.5) < 1.1)
        t = t + 0.26 * knot
    # the foot: a bolt-rope along the bottom, pale and twisted
    foot = (ys >= y1 - 3.5) & (ys < y1)
    twist = ((xs + ys * 1.2) % 4.0) < 2.0
    t = np.where(foot, 0.48 + 0.14 * twist - 0.22 * (ys >= y1 - 1.0) + 0.08 * (ys < y1 - 3.0), t)
    colr = bands(SAIL, np.clip(t, 0, 1), 0.2)
    cv.put(slot, colr, 1.0)
    return cv.image()


def sail_mark():
    """The ordered setting's notch: a gold bar across the gauge that ends in a barb at each side, dark-edged so it shows on the
    red canvas and on the empty slot alike. 10 x 3 GUI px, to be centred on the gauge at the ordered height."""
    w, h = 10 * K, 3 * K
    xs, ys = grid(w, h)
    cy = h / 2
    barb = poly([(0.3, 0.3), (6.0, cy), (0.3, h - 0.3)], w, h)
    barb2 = poly([(w - 0.3, 0.3), (w - 6.0, cy), (w - 0.3, h - 0.3)], w, h)
    bar = (xs > 3.0) & (xs < w - 3.0) & (np.abs(ys - cy) <= 2.7)
    shape = barb | barb2 | bar
    d_in = dist_in(shape, 4)
    ring = grow(shape, 1.0 > 0) & ~shape
    cv = Canvas(w, h)
    ht = np.minimum(d_in, 3) / 3.0
    t = 0.55 + 0.85 * light(ht * 2.6, 1.0) + 0.2 * (ys < cy - 1.0)
    cv.over(ring, OUT, 0.95)
    cv.a = np.where(ring, 0.95, cv.a)
    cv.put(shape, bands(GOLD, t, 0.2), 1.0)
    cv.rgb[ring] = OUT
    return cv.image()


# ================================================================ the anchor

def anchor_shape():
    """The anchor's mask at 48 x 48: a ring, a stock, a shank, a crown with its two arms and a fluke on each."""
    n = 48
    ss = 8
    img = Image.new('L', (n * ss, n * ss), 0)
    d = ImageDraw.Draw(img)
    S = lambda *pts: [(x * ss, y * ss) for x, y in pts]
    cx = 24.0
    # the ring (the eye) the cable is bent to
    d.ellipse([(cx - 5.4) * ss, (1.0) * ss, (cx + 5.4) * ss, (11.8) * ss], fill=255)
    d.ellipse([(cx - 2.6) * ss, (3.7) * ss, (cx + 2.6) * ss, (9.1) * ss], fill=0)
    # the stock: a bar across the top of the shank, with a knob at either end
    d.rounded_rectangle([(cx - 12.0) * ss, 12.0 * ss, (cx + 12.0) * ss, 15.4 * ss], radius=1.5 * ss, fill=255)
    for sx in (-1, 1):
        d.ellipse([(cx + sx * 12.2 - 2.5) * ss, 11.2 * ss, (cx + sx * 12.2 + 2.5) * ss, 16.2 * ss], fill=255)
    # the shank: a long taper, thicker toward the crown
    d.polygon(S((cx - 2.1, 11.5), (cx + 2.1, 11.5), (cx + 2.7, 38.5), (cx - 2.7, 38.5)), fill=255)
    # the crown: a curve under the shank that rises into the arms
    cyc, rad, th = 27.0, 14.6, 3.7
    d.arc([(cx - rad) * ss, (cyc - rad) * ss, (cx + rad) * ss, (cyc + rad) * ss], start=22, end=158, fill=255, width=int(th * ss))
    # the arms' flukes: a broad pointed palm turned up and out at each end of the crown
    for sx in (-1, 1):
        ax, ay = cx + sx * 13.6, 31.0
        d.polygon(S((ax - sx * 1.6, ay + 3.6), (ax + sx * 6.4, ay + 1.4), (ax + sx * 5.6, ay - 7.6), (ax - sx * 2.6, ay - 1.2)), fill=255)
    d.ellipse([(cx - 3.2) * ss, 38.0 * ss, (cx + 3.2) * ss, 42.6 * ss], fill=255)
    return np.asarray(img.resize((n, n), Image.BOX), float) / 255.0 > 0.5


def anchor(down):
    n = 48
    m = anchor_shape()
    xs, ys = grid(n)
    grain = fbm(n, n, 3, SEED + 41)
    cv = Canvas(n)
    dm = dist_in(m, 5)
    hgt = np.minimum(dm, 3.5) / 3.5
    lt = light(hgt * 3.0, 1.0)
    pal = GOLD if down else PAL['steel']
    base = 0.58 if down else 0.40
    t = base + 0.95 * lt + 0.10 * (grain - 0.5)
    if down:
        # a soft warm light behind it: the anchor is holding
        pool = np.clip(1 - np.hypot(xs - 24, ys - 24) / 23.0, 0, 1) ** 1.6
        cv.rgb[...] = np.array([226.0, 176.0, 92.0])
        cv.a = pool * 0.22
    ring = grow(m, 2) & ~m
    cv.rgb[ring] = OUT
    cv.a = np.where(ring, 0.95 if down else 0.80, cv.a)
    cv.put(m, bands(pal, t, 0.2), 1.0 if down else 0.80)
    if down:
        for (gx, gy) in ((21, 6), (11, 12), (14, 28)):
            if m[gy, gx]:
                glint(cv, gx, gy, 1)
    return cv.image()


# ================================================================ the compass and map icons
# 1x designs, put through ui_hd.remaster as the older markers are (the compass keeps its full dark outline, the map's is
# thinned to ink), so they stand exactly beside them: the cog under sail, a red canvas on a brown hull, a gold masthead.

COMPASS_SHIP = ["..og...",
                "..oRoo.",
                "..oRRRo",
                "..oRrRo",
                "ooonoon",
                "onnnnno",
                ".ooooo."]
MAP_SHIP = ["....k......",
            "....kg.....",
            "....kRRk...",
            "....kRRRk..",
            "....kRRRRk.",
            "....kRRRRk.",
            "....kRRRk..",
            "....kkkk...",
            "kk..k...kk.",
            ".kkkkkkkkk.",
            "..kkkkkkk.."]


def compass_ship():
    import ui_art
    import ui_hd
    return ui_hd.remaster(ui_art.from_rows(COMPASS_SHIP, ui_art.MARK_PAL), seed=21, thin=False)


def map_ship():
    import ui_art
    import ui_hd
    return ui_hd.remaster(ui_art.from_rows(MAP_SHIP, ui_art.MAP_PAL), seed=22, thin=True)


# ================================================================ previews: the HUD as the game would draw it

REPO_ASSETS = os.path.normpath(os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'guildmark'))


def tex_of(img):
    return np.asarray(img.convert('RGBA'), float) / 255.0


def asset(assets, rel):
    """A file under the assets dir, or else under the repo's own (so a trial run into another directory still has the
    older sprites and fonts to draw beside the new ones)."""
    for base in (assets, REPO_ASSETS):
        p = os.path.join(base, rel)
        if os.path.exists(p):
            return p
    return None


def over(dst, src_rgb, src_a, mask=None):
    """dst (h, w, 3) floats 0..1, painted over in place by src_rgb with coverage src_a."""
    a = src_a if mask is None else src_a * mask
    dst[:] = dst * (1 - a[..., None]) + src_rgb * a[..., None]


def blit(dst, tex, x, y, w, h, S, rot=0.0, pivot=None, scissor=None, tint=None, alpha=1.0):
    """Draw tex (floats h x w x 4) into dst the way the GUI would at scale S: its box is (x, y, w, h) GUI px, turned
    rot degrees clockwise about pivot (GUI px, default the box centre), sampled with NEAREST, cut by scissor (x0, y0,
    x1, y1 in GUI px), faded to [alpha]."""
    th, tw = tex.shape[:2]
    H, W = dst.shape[:2]
    px, py = (x + w / 2, y + h / 2) if pivot is None else pivot
    r = math.radians(rot)
    c, s = math.cos(r), math.sin(r)
    # bounds of the turned box, in screen px
    corners = [(x, y), (x + w, y), (x, y + h), (x + w, y + h)]
    pts = [((cx - px) * c - (cy - py) * s + px, (cx - px) * s + (cy - py) * c + py) for cx, cy in corners]
    sx0 = max(0, int(math.floor(min(p[0] for p in pts) * S)) - 1)
    sx1 = min(W, int(math.ceil(max(p[0] for p in pts) * S)) + 1)
    sy0 = max(0, int(math.floor(min(p[1] for p in pts) * S)) - 1)
    sy1 = min(H, int(math.ceil(max(p[1] for p in pts) * S)) + 1)
    if sx1 <= sx0 or sy1 <= sy0:
        return
    yy, xx = np.mgrid[sy0:sy1, sx0:sx1]
    gx, gy = (xx + 0.5) / S, (yy + 0.5) / S
    # undo the turn
    rx, ry = gx - px, gy - py
    lx = rx * c + ry * s + px
    ly = -rx * s + ry * c + py
    u = np.floor((lx - x) / w * tw).astype(int)
    v = np.floor((ly - y) / h * th).astype(int)
    ok = (u >= 0) & (u < tw) & (v >= 0) & (v < th)
    if scissor is not None:
        ok &= (gx >= scissor[0]) & (gx < scissor[2]) & (gy >= scissor[1]) & (gy < scissor[3])
    u, v = np.clip(u, 0, tw - 1), np.clip(v, 0, th - 1)
    t = tex[v, u]
    rgb, a = t[..., :3], t[..., 3] * ok * alpha
    if tint is not None:
        rgb = rgb * np.asarray(tint, float)
    over(dst[sy0:sy1, sx0:sx1], rgb, a)


def blit_nine(dst, tex, x, y, w, h, S, lw, lh, border):
    """A nine-slice sprite (nominal size lw x lh GUI px, border in GUI px) drawn at (x, y, w, h), its middle tiled."""
    th, tw = tex.shape[:2]
    kx, ky = tw / lw, th / lh
    bx, by = border * kx, border * ky
    H, W = dst.shape[:2]
    sx0, sy0, sx1, sy1 = int(x * S), int(y * S), int((x + w) * S), int((y + h) * S)
    yy, xx = np.mgrid[sy0:sy1, sx0:sx1]
    gx, gy = (xx + 0.5) / S - x, (yy + 0.5) / S - y

    def axis(g, size, b, k, t):
        u = np.where(g < border, g * k,
                     np.where(g >= size - border, t - (size - g) * k, b + ((g - border) * k) % (t - 2 * b)))
        return np.clip(np.floor(u).astype(int), 0, int(t) - 1)
    u, v = axis(gx, w, bx, kx, tw), axis(gy, h, by, ky, th)
    t = tex[v, u]
    over(dst[sy0:sy1, sx0:sx1], t[..., :3], t[..., 3])


class BitmapFont:
    """Minecraft's bitmap fonts: white glyphs in a grid of cells, each as wide as its rightmost lit column plus a gap."""

    def __init__(self, path, rows, cell=None):
        im = Image.open(path).convert('RGBA')
        self.rows = rows
        cols = max(len(r) for r in rows)
        cw, ch = im.width // cols, im.height // len(rows)
        self.cell = (cw, ch)
        self.glyph = {}
        a = np.asarray(im)[..., 3]
        for ri, row in enumerate(rows):
            for ci, chr_ in enumerate(row):
                if chr_ in self.glyph or chr_ == '\u0000':
                    continue
                cellimg = im.crop((ci * cw, ri * ch, (ci + 1) * cw, (ri + 1) * ch))
                lit = np.where(a[ri * ch:(ri + 1) * ch, ci * cw:(ci + 1) * cw].max(axis=0) > 0)[0]
                self.glyph[chr_] = (cellimg, (int(lit.max()) + 2) if len(lit) else 4)

    def width(self, text):
        return sum(self.glyph.get(c, self.glyph.get('?', (None, 4)))[1] for c in text)

    def draw(self, dst, text, x, y, S, color, shadow=True):
        """Text at GUI (x, y) (the cell's top-left), each font pixel S screen px; Minecraft's drop shadow is a quarter-bright copy a GUI px down and right."""
        def run(ox, oy, col):
            cx = x + ox
            for c in text:
                g = self.glyph.get(c, self.glyph.get('?'))
                if g is None:
                    continue
                t = tex_of(g[0])
                blit(dst, t, cx, y + oy, self.cell[0], self.cell[1], S, tint=col)
                cx += g[1]
        col = np.array(color, float) / 255.0
        if shadow:
            run(1, 1, col * 0.25)
        run(0, 0, col)


def load_fonts(assets, ascii_path):
    rows = [''] * 16
    for code in range(32, 127):
        rows[code // 16] += chr(code)
    rows = [r.ljust(16, '\u0000') for r in rows]
    plain = None
    if ascii_path and os.path.exists(ascii_path):
        plain = BitmapFont(ascii_path, rows)
    hp = asset(assets, 'textures/font/heading.png')
    heading = BitmapFont(hp, ['ABCDEFGHIJKLMNOP', 'QRSTUVWXYZ012345', "6789.,'\u2019-:!?&()/"]) if hp else None
    return plain, heading


def value_noise(u, v, seed):
    """Smooth lattice noise in 0..1 at arbitrary coordinates (arrays)."""
    iu, iv = np.floor(u).astype(np.int64), np.floor(v).astype(np.int64)
    fu, fv = u - iu, v - iv
    fu, fv = fu * fu * (3 - 2 * fu), fv * fv * (3 - 2 * fv)

    def h(a, b):
        n = (a * 374761393 + b * 668265263 + seed * 2147483647) & 0x7FFFFFFF
        n = ((n ^ (n >> 13)) * 1274126177) & 0x7FFFFFFF
        return (n ^ (n >> 16)) / 0x7FFFFFFF
    a = h(iu, iv) * (1 - fu) + h(iu + 1, iv) * fu
    b = h(iu, iv + 1) * (1 - fu) + h(iu + 1, iv + 1) * fu
    return a * (1 - fv) + b * fv


def sea_and_sky(W, H, seed=3, dusk=0.0):
    """A cold sea under a low pale sky: the backdrop the HUD is judged against (screen px, floats 0..1, h x w x 3)."""
    yy, xx = np.mgrid[0:H, 0:W].astype(float)
    yh = H * 0.36
    sky_t = np.clip(yy / yh, 0, 1)
    top, mid, low = np.array([62, 84, 124]), np.array([138, 156, 180]), np.array([226, 206, 178])
    sky = np.where(sky_t[..., None] < 0.6, top + (mid - top) * (sky_t[..., None] / 0.6),
                   mid + (low - mid) * ((sky_t[..., None] - 0.6) / 0.4))
    cl = value_noise(xx / 90.0, yy / 22.0, seed) * 0.6 + value_noise(xx / 40.0, yy / 10.0, seed + 5) * 0.4
    cloud = np.clip((cl - 0.52) * 3.0, 0, 1) * np.clip(1 - sky_t * 0.9, 0, 1) * 0.55
    sky = sky + (np.array([236, 230, 222]) - sky) * cloud[..., None]
    # a low sun and its glow
    sx, sy = W * 0.34, yh * 0.90
    glow = np.exp(-(((xx - sx) / (W * 0.12)) ** 2 + ((yy - sy) / (H * 0.09)) ** 2))
    sky = sky + (np.array([255, 226, 160]) - sky) * np.clip(glow, 0, 1)[..., None] * 0.7
    # the sea, in perspective: crests run along the horizon and close up toward it
    d = np.maximum(yy - yh, 0.0) + 2.0
    z = 1.0 / d
    wx, wy = (xx - W * 0.5) * (0.02 + 2.2 * z), 15.0 * np.log(d)
    swell = value_noise(wx * 0.18, wy * 0.5, seed + 1)
    crest = np.sin(wy * 2.6 + swell * 7.0 + value_noise(wx * 0.5, wy * 0.8, seed + 4) * 3.0)
    chop = value_noise(wx * 0.9, wy * 2.4, seed + 2)
    waves = np.clip(crest * 0.55 + (chop - 0.5) * 1.1, -1, 1)
    near, far = np.array([16, 38, 56]), np.array([92, 126, 146])
    depth1 = np.clip((yy - yh) / (H - yh), 0, 1) ** 0.7
    depth = depth1[..., None]
    sea = far + (near - far) * depth
    sea = sea + waves[..., None] * np.array([26, 30, 30]) * (0.35 + 0.65 * (1 - depth))
    foam = np.clip((waves - 0.62) * 4.0, 0, 1) * (1 - depth1) ** 0.5
    sea = sea + (np.array([226, 234, 238]) - sea) * foam[..., None] * 0.55
    # the sun's road on the water
    road = np.exp(-((xx - sx) / (W * 0.04 + (yy - yh) * 0.30)) ** 2) * np.clip(waves * 0.6 + 0.55, 0, 1) * (1 - depth1 * 0.6)
    sea = sea + (np.array([255, 224, 170]) - sea) * np.clip(road, 0, 1)[..., None] * 0.75
    out = np.where((yy < yh)[..., None], sky, sea)
    # a soft haze where they meet
    haze = np.exp(-((yy - yh) / (H * 0.025)) ** 2)[..., None]
    out = out + (np.array([214, 206, 196]) - out) * haze * 0.55
    return np.clip(out / 255.0, 0, 1)


def hud_state(assets, imgs, fonts, size, wind_yaw, ordered, sail, anchor_down, knots, hull, name, S, seed, strength=0.6, lost=None):
    """One frame of the sailing HUD low on the right of a GUI that is size = (w, h) GUI px at scale S, over the sea, laid out
    the way the game lays it (ShipHud.kt): the dial with the sail gauge on its right and the anchor under the gauge, her name
    over the dial, her speed (or why she makes none) under it and the hull bar under that. wind_yaw is the way the wind blows
    toward, degrees clockwise from the bow; strength its freshness (0 to 1)."""
    gw, gh = size
    dst = sea_and_sky(gw * S, gh * S, seed)
    plain, heading = fonts
    sprite = lambda key: tex_of(imgs[key])
    x0, y0 = gw - 8 - 8 - 5 - 56, gh - 8 - 24 - 56      # the dial's top left: a margin of 8, the gauge and its gap, room below for text and bar
    cx, cy = x0 + 28, y0 + 28
    rel = (wind_yaw + 180.0 + 180.0) % 360.0 - 180.0     # where the wind comes from, against the bow
    in_irons = sail > 0.1 and abs(rel) < 45.0
    # the dial, the wedge of wind it can't sail into (centred on where the wind comes from), the cog, the wind's arrow
    blit(dst, sprite('ship/rose'), x0, y0, 56, 56, S)
    blit(dst, sprite('ship/nogo'), x0, y0, 56, 56, S, rot=rel, pivot=(cx, cy), alpha=0.9 if in_irons else 0.55)
    blit(dst, sprite('ship/rose_hull'), cx - 8, cy - 14, 16, 28, S)
    reach = 0.6 + 0.4 * strength                         # the arrow lengthens as the wind freshens
    blit(dst, sprite('ship/wind_arrow'), cx - 4, cy - 22 * reach, 8, 44 * reach, S, rot=wind_yaw, pivot=(cx, cy), alpha=0.6 + 0.4 * strength)
    # the sail gauge: the canvas rises from the foot of its slot (4 x 38 GUI px at (2, 3)), the gold notch is the ordered setting
    gx, gy = x0 + 56 + 5, y0
    blit(dst, sprite('ship/sail_frame'), gx, gy, 8, 44, S)
    top = gy + 41 - int(round(38 * sail))
    blit(dst, sprite('ship/sail_fill'), gx, gy, 8, 44, S, scissor=(gx, top, gx + 8, gy + 44))
    my = gy + 41 - int(round(38 * ordered)) - 1
    blit(dst, sprite('ship/sail_mark'), gx - 1, my, 10, 3, S)
    # the anchor under it, dim when weighed and gold when down
    blit(dst, sprite('ship/anchor_down' if anchor_down else 'ship/anchor_up'), gx + 4 - 6, gy + 44 + 2, 12, 12, S)
    # her name over the dial, her speed (or why she makes none) under it, her hull
    gold, warn = (232, 192, 122), (224, 122, 90)
    if heading:
        nm = name.upper()
        heading.draw(dst, nm, int(round(cx - heading.width(nm) / 2.0)), y0 - 11 + 2, S, gold)
    if plain:
        if anchor_down:
            text, col = 'At anchor', gold
        elif in_irons and knots < 0.05:
            text, col = 'Head to wind', warn
        else:
            text, col = f'{knots:.1f} kn', (255, 255, 255)
        plain.draw(dst, text, int(round(cx - plain.width(text) / 2.0)), y0 + 56 + 3, S, col)
    bar_y = y0 + 56 + 3 + 11
    bar_frame = asset(assets, 'textures/gui/sprites/hud/bar_frame.png')
    bar_fill = asset(assets, 'textures/gui/sprites/hud/bar_health.png')
    bar_trail = asset(assets, 'textures/gui/sprites/hud/bar_trail.png')
    if bar_frame and bar_fill:
        blit_nine(dst, tex_of(Image.open(bar_frame)), x0, bar_y, 56, 9, S, 32, 9, 3)
        if lost is not None and bar_trail and lost > hull:
            blit(dst, tex_of(Image.open(bar_trail)), x0 + 3, bar_y + 3, int(round(50 * lost)), 3, S)
        blit(dst, tex_of(Image.open(bar_fill)), x0 + 3, bar_y + 3, int(round(50 * hull)), 3, S)
    return dst


def swatches(assets, imgs, fonts, S, size=(232, 44)):
    """The compass strip with older markers beside the cog's, and the map's icons on parchment, at scale S: two panels."""
    sp = lambda p: tex_of(Image.open(asset(assets, 'textures/gui/sprites/' + p + '.png')))
    W, H = size
    comp = sea_and_sky(W * S, H * S, 9)
    if asset(assets, 'textures/gui/sprites/compass/strip.png'):
        blit(comp, sp('compass/strip'), (W - 110) // 2, 8, 110, 13, S)
        names = ['village', 'contract', 'ship', 'lair', 'town']
        for i, n in enumerate(names):
            tex = tex_of(imgs['compass/ship']) if n == 'ship' else sp('compass/' + n)
            blit(comp, tex, (W - 110) // 2 + 14 + i * 20, 11, 7, 7, S)
    yy, xx = np.mgrid[0:H * S, 0:W * S].astype(float)
    parch = np.array([210, 186, 142]) / 255.0 + (value_noise(xx / 30.0, yy / 30.0, 4)[..., None] - 0.5) * 0.08
    mp = np.clip(parch, 0, 1)
    for i, n in enumerate(['village', 'town', 'hall', 'ship', 'wardfire', 'vault']):
        tex = tex_of(imgs['map/ship']) if n == 'ship' else sp('map/' + n)
        blit(mp, tex, (W - 6 * 16) // 2 + i * 16 + 2, 16, 11, 11, S)
    return comp, mp


def to_png(arr):
    return Image.fromarray((np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8), 'RGB')


def write_previews(imgs, assets, outdir, ascii_path=None):
    """ship/hud_mock.png: the whole HUD at GUI scale 3 over a sea in three states (a reach; head to wind at anchor; in irons
    with a damaged hull), laid out as the game's ShipHud.kt lays it, with the compass strip and the map beside the older
    markers. ship/hud_sprites.png: every sprite, large."""
    os.makedirs(outdir, exist_ok=True)
    S = 3
    fonts = load_fonts(assets, ascii_path)
    size = (232, 112)
    states = [
        ('On a reach, sail at three quarters', dict(wind_yaw=-100.0, ordered=0.75, sail=0.75, anchor_down=False, knots=5.4, hull=0.82, strength=0.55)),
        ('Head to wind, anchored', dict(wind_yaw=180.0, ordered=0.0, sail=0.0, anchor_down=True, knots=0.0, hull=0.82, strength=0.25)),
        ('In irons, hull hurt', dict(wind_yaw=160.0, ordered=1.0, sail=1.0, anchor_down=False, knots=0.0, hull=0.35, strength=1.0, lost=0.58)),
    ]
    panels = [hud_state(assets, imgs, fonts, size, name='Saltwren', S=S, seed=3 + 2 * i, **kw) for i, (_, kw) in enumerate(states)]
    plain, _ = fonts
    for p, (caption, _) in zip(panels, states):
        if plain:
            plain.draw(p, caption, 6, 4, S, (240, 232, 214))
    comp, mp = swatches(assets, imgs, fonts, S)
    gap = 6
    pw, ph = panels[0].shape[1], panels[0].shape[0]
    page = np.zeros((ph * 2 + gap, pw * 2 + gap, 3)) + 0.05
    page[:ph, :pw] = panels[0]
    page[:ph, pw + gap:] = panels[1]
    page[ph + gap:, :pw] = panels[2]
    page[ph + gap:ph + gap + comp.shape[0], pw + gap:] = comp
    page[ph + gap + comp.shape[0] + gap:ph + gap + comp.shape[0] + gap + mp.shape[0], pw + gap:] = mp
    if plain:
        plain.draw(page, 'compass and map markers', pw // S + 2 + 3, (ph + gap + comp.shape[0] + gap + mp.shape[0]) // S + 4, S, (200, 196, 188), shadow=False)
    to_png(page).save(os.path.join(outdir, 'hud_mock.png'))
    # every sprite large on a dark checker, in a row each
    keys = list(imgs)
    rows, x, y, rowh = [], 8, 8, 0
    sheet_w = 1500
    items = []
    for k in keys:
        im = imgs[k]
        sc = 4 if k.startswith('ship/') and im.width > 100 else (3 if k.startswith('ship/') else 8)
        big = im.resize((im.width * sc, im.height * sc), Image.NEAREST)
        if x + big.width + 8 > sheet_w:
            x, y, rowh = 8, y + rowh + 12, 0
        items.append((big, x, y))
        x += big.width + 14
        rowh = max(rowh, big.height)
    h = y + rowh + 12
    ck = np.indices((h, sheet_w)).sum(axis=0) // 8 % 2
    base = np.where(ck[..., None] == 0, 34, 44).astype(np.uint8) * np.ones((h, sheet_w, 3), np.uint8)
    out = Image.fromarray(base, 'RGB').convert('RGBA')
    for big, bx, by in items:
        out.alpha_composite(big, (bx, by))
    out.convert('RGB').save(os.path.join(outdir, 'hud_sprites.png'))
    print('previews in', outdir)


# ================================================================ main

def build():
    return {'ship/rose': rose(), 'ship/wind_arrow': wind_arrow(), 'ship/nogo': nogo(), 'ship/rose_hull': rose_hull(),
            'ship/sail_frame': sail_frame(), 'ship/sail_fill': sail_fill(), 'ship/sail_mark': sail_mark(),
            'ship/anchor_up': anchor(False), 'ship/anchor_down': anchor(True),
            'compass/ship': compass_ship(), 'map/ship': map_ship()}


GUI_SIZE = {'ship/rose': (56, 56), 'ship/rose_hull': (16, 28), 'ship/wind_arrow': (8, 44), 'ship/nogo': (56, 56),
            'ship/sail_frame': (8, 44), 'ship/sail_fill': (8, 44), 'ship/sail_mark': (10, 3), 'ship/anchor_up': (12, 12),
            'ship/anchor_down': (12, 12), 'compass/ship': (7, 7), 'map/ship': (11, 11)}


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return
    assets = argv[1]
    imgs = build()
    for key, img in imgs.items():
        path = os.path.join(assets, 'textures/gui/sprites', key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        gw, gh = GUI_SIZE[key]
        print(f'{key:16s} {img.width:3d}x{img.height:<3d} png   drawn at {gw}x{gh} GUI px')
    if len(argv) > 2:
        write_previews(imgs, assets, os.path.join(argv[2], 'ship'), argv[3] if len(argv) > 3 else None)


if __name__ == '__main__':
    main(sys.argv)
