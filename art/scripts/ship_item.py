"""The cog's item: a little cog in profile at 32x, painted with the HD art kit (hdkit.py) in the house style: a clinker hull of
old dark oak with a tarred black wale and a dull green weedy band, the stern castle with its amber windows and a lit lantern, the
bowsprit, one mast with a fighting top, a yard, hemp stays, a long two-tailed pennant, and the great sail of oxblood canvas (shown
full face, as an icon does) with the Guild Mark crest in faded gold across its middle. Seeded and drawn by rule, so a rerun gives
the same file.

    python3 -B art/scripts/ship_item.py <assets dir> [preview dir]

Writes textures/item/cog.png, models/item/cog.json and items/cog.json (the same shape as the companions' keepsakes), and with a
preview dir a zoomed sheet of the item on slot backdrops. ship.py calls `write_item` itself, with everything else.
"""
import json
import math
import os
import sys
import zlib

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from hdkit import PAL, Canvas, bands, light, poly, disc, vnoise, tone, rim, outline, glint, grow

N = 32

# the cog's own tones, darkest first (nothing fully saturated, shadows turn cool, light turns warm)
OAK = [(12, 9, 10), (26, 19, 17), (44, 32, 26), (64, 47, 36), (88, 66, 49), (116, 88, 64)]                  # old dark oak, weathered
TAR = [(6, 6, 9), (12, 11, 15), (22, 20, 25), (34, 32, 38), (50, 46, 52)]                                    # the tarred wale
WEED = [(10, 16, 14), (18, 28, 22), (30, 44, 32), (46, 62, 42)]                                               # the dull green weed band
DECK = [(30, 22, 18), (62, 46, 34), (98, 76, 54), (136, 108, 76), (168, 140, 100)]                           # worn oak of the castle's deck
HEMP = [(34, 28, 22), (70, 58, 42), (112, 94, 66), (156, 136, 98), (196, 178, 132)]                           # rope
MADDER = [(26, 6, 12), (52, 12, 20), (84, 20, 26), (116, 30, 34), (148, 48, 44), (176, 76, 62)]             # oxblood canvas
CREST = [(52, 34, 18), (104, 74, 34), (156, 118, 54), (196, 160, 82), (226, 196, 128), (244, 228, 176)]    # faded gold
GLASS = [(18, 30, 38), (40, 66, 76), (74, 106, 112), (116, 148, 148), (170, 196, 188)]                     # the sea's pale crests
AMBER = [(70, 30, 12), (150, 70, 20), (222, 128, 40), (252, 190, 90), (255, 232, 170)]                       # window light
IRON = PAL['iron']


def seed_for(*keys):
    return zlib.crc32('/'.join(str(k) for k in keys).encode())


def curve(pts, xs):
    """A smooth line through [pts] (x, y), sampled at [xs] (a Catmull-Rom spline, so the hull's lines run fair)."""
    pts = np.asarray(pts, float)
    P = np.vstack([pts[0] * 2 - pts[1], pts, pts[-1] * 2 - pts[-2]])
    ts = np.linspace(0, len(pts) - 1, 400)
    seg = np.minimum(ts.astype(int), len(pts) - 2)
    u = (ts - seg)[:, None]
    p0, p1, p2, p3 = P[seg], P[seg + 1], P[seg + 2], P[seg + 3]
    out = 0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u ** 2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u ** 3)
    order = np.argsort(out[:, 0])
    return np.interp(xs, out[order, 0], out[order, 1])


def tn(pal, t, dither=0.3):
    """The tone of [pal] for [t] (0..1): one colour for a number, an (N, N, 3) image for a field."""
    if np.ndim(t) == 0:
        return bands(pal, np.full((1, 1), float(t)), 0.0)[0, 0]
    return bands(pal, t, dither)


def put_line(cv, x0, y0, x1, y1, col, a=1.0):
    """A one-pixel line (Bresenham), over what is there."""
    x0, y0, x1, y1 = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        cv.px(x0, y0, col, a)
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def paint_item():
    """The 32 x 32 sprite, the bow to the left."""
    seed = seed_for('cog', 'item')
    cv = Canvas(N)
    xs = np.arange(N) + 0.5
    ys = np.arange(N)[:, None] + 0.5
    X = np.broadcast_to(xs[None, :], (N, N))
    Y = np.broadcast_to(ys, (N, N))
    noise = vnoise(N, N, 2.0, seed + 1)
    # ---- the hull: the sheer line along its top (high at the stem, low amidships), the bottom's line, the castle's block at the stern
    rail = curve([(2.0, 18.0), (4.0, 20.0), (8.0, 21.4), (14.0, 21.8), (20.0, 21.4), (21.0, 21.2)], xs)
    keel = curve([(2.6, 22.0), (3.8, 25.6), (6.0, 28.4), (10.0, 29.8), (20.0, 29.8), (25.0, 28.8), (29.5, 25.6)], xs)
    castle_top = 16.2
    in_castle = (X >= 21.0) & (X < 29.8)
    top = np.where(in_castle, castle_top, rail[None, :])
    hull = (X >= 2.0) & (X < 29.8) & (Y >= top) & (Y < keel[None, :] + 0.5)
    hull |= (X >= 1.9) & (X < 3.6) & (Y >= 16.8) & (Y < 21.0)                       # the stem rising past the rail at the bow
    d_top = Y - top
    d_bot = keel[None, :] + 0.5 - Y
    frac = np.clip((Y - rail[None, :]) / np.maximum(keel[None, :] - rail[None, :], 1e-3), 0, 1)
    # light from the upper left; the hull's belly bulges, so it darkens toward the keel
    t = 0.84 - 0.34 * np.clip(frac, 0, 1) + 0.12 * (noise - 0.5) - 0.006 * (X - 2.0)
    # clinker laps: dark lines between the strakes, each with a lit lip
    lap = (np.floor((Y - rail[None, :]) / 2.0) != np.floor((Y - 1.0 - rail[None, :]) / 2.0)) & (Y > rail[None, :] + 1.4)
    t = t - 0.17 * lap + 0.10 * np.roll(lap, 1, axis=0)
    cv.put(hull, tn(OAK, t, 0.3))
    # the wale: a tarred black band a third of the way down, lit along its top edge
    wale = hull & (frac > 0.30) & (frac < 0.44)
    cv.put(wale, tn(TAR, 0.30 + 0.5 * (frac < 0.37) + 0.1 * (noise - 0.5), 0.3))
    # the weed band below the waterline, dull and dark
    weed = hull & (frac > 0.80)
    cv.put(weed, tn(WEED, 0.20 + 0.60 * noise * np.clip(1.0 - (frac - 0.80) * 2.0, 0, 1), 0.4))
    cv.put(hull & (d_bot < 1.0) & (d_bot > 0.0) & (X > 5.0), tn(TAR, 0.35))                   # the keel line
    cv.put(hull & (d_top < 1.0) & ~in_castle, tn(OAK, 0.92 + 0.1 * (noise - 0.5), 0.2))        # a lit cap along the rail
    # nail dots on the strakes
    for x, y in ((5, 24), (8, 25), (12, 24), (16, 25), (19, 24), (24, 23)):
        if hull[y, x] and not wale[y, x]:
            cv.px(x, y, tone(cv.rgb[y, x], -0.45))
    # ---- the stern castle: its side wall with two amber windows, a deck rail on top with posts, a lit lantern on its post
    castle = in_castle & hull & (Y >= castle_top + 1.0) & (Y < 22.0)
    cv.put(castle, tn(OAK, 0.70 - 0.28 * np.clip((Y - castle_top) / 5.0, 0, 1) + 0.10 * (noise - 0.5), 0.3))
    cv.put(in_castle & hull & (Y < castle_top + 1.0), tn(DECK, 0.86 + 0.1 * (noise - 0.5), 0.2))
    for x in (23, 26):
        cv.put((X >= x) & (X < x + 2) & (Y >= 18.4) & (Y < 20.4), tn(AMBER, np.where(Y < 19.4, 0.80, 0.55), 0.0))
    for x in (21, 23, 25, 27):
        cv.px(x, 15, OAK[3])
        cv.px(x, 14, OAK[4])
    put_line(cv, 21, 14, 29, 14, OAK[5])
    for y in (15, 16):
        cv.px(29, y, IRON[2])
    for y in (12, 13):
        cv.px(29, y, IRON[2])
    cv.px(29, 11, AMBER[3])
    cv.px(30, 11, AMBER[2])
    cv.px(29, 10, AMBER[2])
    cv.px(30, 10, AMBER[1])
    cv.px(29, 9, PAL['brass'][3])
    cv.px(30, 9, PAL['brass'][2])
    # the bowsprit, rising to the left from the stem head, with its iron tip
    put_line(cv, 3, 17, 0, 13, OAK[4])
    put_line(cv, 4, 17, 1, 13, OAK[2])
    cv.px(0, 13, IRON[3])
    # ---- the sea she sits in: the hull below the waterline gone dark and teal, a pale crest along the water's edge
    wl = 27.2 + 0.45 * np.sin(xs * 0.85 + 1.0)
    under = hull & (Y >= wl[None, :])
    cv.over(under, np.array([22.0, 44.0, 54.0]), 0.62)
    crest = (np.abs(Y - wl[None, :]) < 0.6) & (X >= 3.0) & (X < 28.5) & (((np.floor(X * 0.5 + 0.3 * np.sin(X)) % 3) != 1))
    cv.put(crest & hull, tn(GLASS, 0.58 + 0.25 * (noise > 0.5), 0.0))
    # ---- the mast, behind the sail: its foot and head, the fighting top; the stays from the masthead, hemp
    for y in range(2, 22):
        cv.px(15, y, tn(OAK, 0.58 + 0.08 * ((y * 7) % 3 == 0)))
        cv.px(16, y, tn(OAK, 0.30))
    put_line(cv, 13, 7, 18, 7, OAK[5])
    put_line(cv, 14, 8, 17, 8, OAK[2])
    cv.px(15, 1, PAL['brass'][4])
    put_line(cv, 15, 2, 1, 12, HEMP[2])
    put_line(cv, 16, 2, 28, 13, HEMP[1])
    # ---- the yard, and the sail hung from it, full face: a belly, seams between the cloths, a bolt-rope border, the crest
    yard_y = 5
    for x in range(5, 27):
        cv.px(x, yard_y, tn(OAK, 0.72 - 0.34 * (x >= 17)))
    cv.px(4, yard_y, IRON[3])
    cv.px(27, yard_y, IRON[2])
    top_y, foot_y = 6.0, 19.0
    v = np.clip((Y - top_y) / (foot_y - top_y), 0, 1)
    belly = 1.0 * np.sin(np.pi * v)
    left = 7.0 - belly
    right = 25.0 + belly * 0.6
    foot = foot_y - 1.2 * np.exp(-((X - 16.0) / 7.5) ** 2)
    sail = (Y >= top_y) & (Y < foot) & (X >= left) & (X < right)
    u = (X - left) / np.maximum(right - left, 1.0)
    seam = (np.abs(u * 4.0 - np.round(u * 4.0)) < 0.5 / np.maximum(right - left, 1.0) * 1.1) & (np.round(u * 4.0) > 0) & (np.round(u * 4.0) < 4)
    # lit on the left and toward the top, the belly shading to the right and the foot
    st = 0.66 - 0.30 * u - 0.10 * v + 0.16 * np.sin(np.pi * u) * (1 - v) + 0.10 * (vnoise(N, N, 3.0, seed + 5) - 0.5)
    cv.put(sail, tn(MADDER, st - 0.18 * seam, 0.3))
    # the border and bolt-rope: a pale hemp line round the edge
    edge = sail & ((X < left + 1.0) | (X >= right - 1.0) | (Y < top_y + 1.0) | (Y >= foot - 1.0))
    cv.over(edge, np.array(HEMP[2], float), 0.55)
    for x in range(9, 24, 3):                                                                # a row of reef points
        if sail[9, x]:
            cv.px(x, 9, MADDER[1])
    # the Guild Mark crest: a gold heater shield, a deep field, a pearl
    cx, cy = 16.0, 11.2
    sw = 2.7 * np.cos(np.clip((Y - (cy + 0.6)) / 3.8, 0, 1) * math.pi / 2) ** 0.8
    sw = np.where(Y <= cy + 0.6, 2.7, sw)
    shield = (np.abs(X - cx) < sw) & (Y >= cy - 3.0) & (Y < cy + 4.6)
    field = (np.abs(X - cx) < sw - 0.95) & (Y >= cy - 2.0) & (Y < cy + 3.6)
    cv.put(shield & sail, tn(CREST, 0.66 + 0.28 * np.clip((cx - X) / 2.6, -1, 1) * 0.6, 0.2))
    cv.put(field & sail, tn(MADDER, 0.16, 0.0))
    cv.px(int(cx - 0.5), int(cy), CREST[4])
    cv.px(int(cx - 0.5), int(cy + 1), CREST[3])
    cv.px(int(cx + 0.5), int(cy + 1), CREST[2])
    # ---- the pennant at the masthead: a long two-tailed streamer, deep red with a gold band, flying aft
    for i, x in enumerate(range(17, 24)):
        cv.px(x, 1, tn(MADDER, 0.74 - 0.05 * i))
    for i, (x, y) in enumerate(((17, 2), (18, 2), (19, 2), (20, 3), (21, 3), (22, 2), (23, 3), (24, 3))):
        cv.px(x, y, tn(MADDER, 0.56 - 0.02 * i))
    cv.px(19, 1, CREST[3])
    cv.px(19, 2, CREST[2])
    return cv


def finish(cv):
    """Edge darkening, the dark outline round the whole, a glint on the lantern and on the brass."""
    rim(cv, 0.30)
    outline(cv, 0.8)
    glint(cv, 30, 10, 1, (255, 236, 190))
    return cv.image()


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def write_item(assets):
    """Paints the item and writes its texture, model and item definition under [assets]; returns the image."""
    img = finish(paint_item())
    path = os.path.join(assets, 'textures', 'item', 'cog.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    write_json(os.path.join(assets, 'models', 'item', 'cog.json'),
               {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/cog'}})
    write_json(os.path.join(assets, 'items', 'cog.json'), {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/cog'}})
    return img


def preview(img, outdir):
    """The item zoomed, on the dark slot backdrop and in the size it really shows at (16 px of the 32)."""
    os.makedirs(outdir, exist_ok=True)
    big = img.resize((N * 14, N * 14), Image.NEAREST)
    sheet = Image.new('RGBA', (N * 14 * 2 + 3 * 16 + 16 * 3 + 16, N * 14 + 32), (46, 42, 54, 255))
    slot = Image.new('RGBA', (N * 14, N * 14), (24, 22, 30, 255))
    slot.alpha_composite(big)
    sheet.alpha_composite(slot, (16, 16))
    light_slot = Image.new('RGBA', (N * 14, N * 14), (139, 139, 139, 255))
    light_slot.alpha_composite(big)
    sheet.alpha_composite(light_slot, (16 + N * 14 + 16, 16))
    small = img.resize((N * 3, N * 3), Image.NEAREST)
    sheet.alpha_composite(small, (16 + 2 * (N * 14 + 16), 16))
    real = img.resize((N, N), Image.NEAREST)
    sheet.alpha_composite(real, (16 + 2 * (N * 14 + 16), 16 + N * 3 + 16))
    sheet.save(os.path.join(outdir, 'cog_item.png'))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit('usage: ship_item.py <assets dir> [preview dir]')
    made = write_item(sys.argv[1])
    if len(sys.argv) > 2:
        preview(made, sys.argv[2])
    print('wrote cog item')
