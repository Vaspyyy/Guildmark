"""The art for players' own stalls and for homestead shipping, painted at twice the screen size like the HD screen
sprites (ui_hd.py), straight from code with the HD art kit:

    python3 -B art/scripts/stall_art.py src/main/resources/assets/guildmark [preview-dir]

Writes to textures/gui/sprites/:
    stall/pennant, stall/pennant_paint    the stall screen's pennant (11 x 16 on screen): the rod, its cord and the
                                          cloth's outline, and over them the cloth in greys, painted in the renter's
                                          colour as it's drawn
    stall/till                            the stall's till (22 x 14), a little iron-bound coffer with its lid up
    stall/till_coins_low, _coins, _high   the coins in it, drawn over it: a few, full, heaped over the rim
    homestead/ribbon_paint, ribbon        the ledger's page ribbons (11 x 30): the cloth in greys (drawn first, in the
                                          homestead's colour), then its outline, selvage and stitching over it
    homestead/sign_build, sign_shipping   the signs stitched on the ribbons (7 x 7): a house, a cart wheel
and textures/entity/market/stall_pennant.png, the pennant hung over a rented stall (client/economy/StallPennants.kt):
the cloth (in greys, for the renter's colour) with its swallowtail, the rod, a length of cord and the cloth's roll,
each where the renderer's texture coordinates look for it on the 64 x 64 sheet. With a preview folder, a contact
sheet of everything, plain and painted in a few colours.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import PAL, Canvas, grid, fbm, rand, tone, ramp, poly, disc, ellipse, rect, grow, dist_in, seg_dist, glint, sheet

# The dark line round the screen sprites (ui_art's OUT, opaque)
LINE = (16, 13, 18)
HEMP = [(70, 54, 38), (112, 90, 62), (150, 126, 88), (184, 160, 116), (212, 192, 146)]
BONE = (226, 216, 190)


def greys(v):
    """Grey colours for values 0..1, for the parts painted over in a player's or a homestead's colour."""
    v = np.clip(np.asarray(v, float), 0, 1) * 255
    return np.stack([v, v, v], -1)


def ring(mask):
    """The pixels just outside a shape (4-neighbourhood)."""
    return grow(mask) & ~mask


def dashes(xs, ys, period=4, on=2):
    """Every other short run of pixels along a line, for stitching."""
    return ((np.floor(xs) + np.floor(ys)).astype(int) % period) < on


# ================================================================ the stall screen's pennant (11 x 16)

def pennant_gui():
    """The rod with brass knobs, hung from a nail by two cords, the cloth's outline (the cloth itself is the paint),
    and the paint: the cloth in greys, in the rod's shadow at the top, lit from the upper left, with a stitched
    border and a coin embroidered in its middle, cut in a swallowtail."""
    W, H = 22, 32
    xs, ys = grid(W, H)
    base, paint = Canvas(W, H), Canvas(W, H)
    cloth = poly([(3, 7), (19, 7), (19, 32), (11, 25), (3, 32)], W, H)

    # The cords up to the nail, and the nail
    for x1 in (3.5, 18.5):
        d, t = seg_dist(11, 1.2, x1, 4.6, W, H)
        base.put(d < 0.62, ramp(HEMP[1:4], 1 - t))
    nail, _ = disc(11, 1.3, 1.25, W, H)
    base.put(nail, PAL['iron'][4])

    # The rod and its knobs
    rod = rect(2, 4, 20, 7, W, H)
    wood = PAL['wood']
    grain = fbm(W, H, 3, 5)
    rows = np.array([wood[5], wood[4], wood[2]], float)
    rod_col = rows[np.clip(ys.astype(int) - 4, 0, 2)] * (0.94 + grain[..., None] * 0.12)
    base.put(rod, rod_col)
    knobs = np.zeros((H, W), bool)
    for cx in (2.2, 19.8):
        k, dome = disc(cx, 5.5, 2.1, W, H)
        knobs |= k
        base.put(k, ramp(PAL['brass'][1:6], dome * 0.7 + (5.5 - ys) * 0.12 + 0.2))
    solid = rod | knobs
    base.put(ring(solid) & ~cloth, LINE)
    base.put(ring(cloth) & ~solid, LINE)

    # The cloth
    v = 0.9 - 0.07 * (xs - 3) / 16 - 0.1 * np.clip((ys - 7) / 25, 0, 1)
    v -= 0.24 * np.clip(1 - (ys - 7) / 2.5, 0, 1)
    v += 0.05 * np.exp(-((xs - 7.5) / 1.8) ** 2) - 0.06 * np.exp(-((xs - 15) / 1.5) ** 2)
    v += (fbm(W, H, 2, 11) - 0.5) * 0.07
    inner = dist_in(cloth, 4)
    v = np.where((inner == 3) & (ys > 9.5) & dashes(xs, ys), 0.62, v)
    coin, _ = disc(11, 15.5, 3.7, W, H)
    hole, _ = disc(11, 15.5, 2.6, W, H)
    v = np.where(coin, 0.99, v)
    v = np.where(coin & ~hole, 0.62, v)
    v = np.where(np.abs(xs - 11) + np.abs(ys - 15.5) <= 1.6, 0.76, v)
    paint.put(cloth, greys(v))
    return base.image(), paint.image()


# ================================================================ the till (22 x 14)

TILL_W, TILL_H = 44, 28
# The coffer's mouth, seen from a little above: the back edge, then the front edge it's seen over
MOUTH = [(8, 8), (36, 8), (38, 13), (6, 13)]


def till():
    """A small coffer of planks with iron corner bands and a brass lock plate, its lid standing open behind."""
    W, H = TILL_W, TILL_H
    xs, ys = grid(W, H)
    cv = Canvas(W, H)
    wood = PAL['wood']
    grain = fbm(W, H, 4, 21) * 0.6 + fbm(W * 3, H, 3, 22)[:, ::3][:, :W] * 0.4

    # The lid, open behind: its inside, lit, with an iron edge along the top
    lid = poly([(11, 1), (33, 1), (35, 8), (9, 8)], W, H)
    lid_v = 0.55 + 0.25 * (1 - (ys - 1) / 7) + (grain - 0.5) * 0.25
    cv.put(lid, ramp(wood[1:6], lid_v))
    cv.put(lid & (ys < 2.5), PAL['iron'][3])
    cv.put(lid & (np.abs(xs - 22) < 0.6) & (ys > 2.5), wood[1])

    # The hollow: the back wall in shadow, the floor darker under the front
    mouth = poly(MOUTH, W, H)
    hollow_v = 0.42 - 0.3 * np.clip((ys - 8) / 5, 0, 1) + (grain - 0.5) * 0.12
    cv.put(mouth, ramp(wood[0:4], hollow_v))

    # The front: planks, their seams, darker toward the foot; the front edge of the top catching the light
    front = rect(6, 13, 38, 27, W, H)
    front_v = 0.62 - 0.22 * (ys - 13) / 14 + (grain - 0.5) * 0.3
    cv.put(front, ramp(wood[1:6], front_v))
    cv.put(front & (ys < 14), wood[5])
    for seam in (18, 23):
        cv.put(front & (np.abs(ys - seam - 0.5) < 0.5), wood[1])
    # The iron bands at the corners, with their rivets
    iron = PAL['iron']
    for x0 in (6, 35):
        band = front & (xs >= x0) & (xs < x0 + 3)
        cv.put(band, ramp(iron[1:6], 0.65 - 0.3 * (ys - 13) / 14 + (xs - x0) * -0.08))
        for ry in (15, 20.5, 25):
            r, dome = disc(x0 + 1.5, ry, 0.9, W, H)
            cv.put(r, ramp(PAL['brass'][2:6], dome))
    # The lock plate and its keyhole
    plate = rect(19, 16, 25, 23, W, H)
    cv.put(plate, ramp(PAL['brass'][1:6], 0.75 - 0.35 * (ys - 16) / 7 - (xs - 19) * 0.03))
    cv.put(rect(19, 22, 25, 23, W, H), PAL['brass'][1])
    key, _ = disc(22, 18.5, 0.9, W, H)
    cv.put(key | rect(21, 19, 23, 21, W, H) & (np.abs(xs - 22) < 0.6), PAL['iron'][0])

    shape = lid | mouth | front
    cv.put(ring(shape), LINE)
    return cv.image()


def _coin_pile(cv, top, seed, spill=()):
    """Coins heaped in the till's mouth up to [top] (a row for each column, or None where there's none), each coin a
    small gold oval, the ones in front last; and coins lying spilled on its front edge."""
    W, H = cv.w, cv.h
    xs, ys = grid(W, H)
    gold = PAL['gold']
    pile = np.zeros((H, W), bool)
    for x in range(W):
        t = top[x]
        if t is not None:
            pile[:, x] = (ys[:, x] >= t) & (ys[:, x] < 13)
    pile &= (xs > 6.5) & (xs < 37.5)
    # The heap's body: deep gold, darker low in the mouth
    body = 0.38 + 0.25 * np.clip((13 - ys) / 10, 0, 1) + (rand(W, H, seed) - 0.5) * 0.1
    cv.put(pile, ramp(gold[1:5], body))
    # The coins on its face, back to front
    rng = np.random.default_rng(seed)
    spots = [(x, y) for y in range(H) for x in range(W) if pile[y, x]]
    rng.shuffle(spots)
    coins = sorted(spots[:max(3, len(spots) // 7)], key=lambda p: p[1])
    for cx, cy in coins:
        m, dome = ellipse(cx + 0.5, cy + 0.5, 2.2, 1.1, W, H)
        m &= pile
        cv.put(m, ramp(gold[2:6], dome * 0.8 + 0.15))
        rim = m & ~ellipse(cx + 0.5, cy + 0.2, 1.4, 0.6, W, H)[0] & (ys > cy + 0.5)
        cv.put(rim, gold[2])
    spilled = np.zeros((H, W), bool)
    for cx, cy in spill:
        m, dome = ellipse(cx, cy, 2.4, 1.2, W, H)
        cv.put(m, ramp(gold[2:6], dome * 0.8 + 0.2))
        cv.put(m & (ys > cy + 0.4), gold[2])
        spilled |= m
    # The heap's outline where it stands up over the mouth's back edge or the lid, and round the spilled coins
    line = tone(gold[0], -0.4)
    cv.put(ring(pile) & ~poly(MOUTH, W, H) & (ys < 13) & ~spilled, line)
    cv.put(ring(spilled) & ~pile, line)
    return cv


def till_coins(level):
    """The coins in the till: a few on the floor ("low"), the mouth full ("mid"), or heaped over the rim ("high")."""
    cv = Canvas(TILL_W, TILL_H)
    if level == 'low':
        for cx, cy in ((15.5, 11.2), (21, 10.6), (26.5, 11.5), (31, 10.8)):
            m, dome = ellipse(cx, cy, 2.3, 1.05, TILL_W, TILL_H)
            cv.put(m, ramp(PAL['gold'][2:6], dome * 0.8 + 0.1))
            cv.put(m & (grid(TILL_W, TILL_H)[1] > cy + 0.3), PAL['gold'][2])
        return cv.image()
    if level == 'mid':
        top = [None] * TILL_W
        for x in range(7, 38):
            top[x] = 8.6 - 1.6 * max(0.0, 1 - ((x - 22) / 12) ** 2)
        return _coin_pile(cv, top, 31).image()
    top = [None] * TILL_W
    for x in range(7, 38):
        top[x] = 11.5 - 9.5 * max(0.0, 1 - ((x - 22) / 14.5) ** 2) ** 0.8
    _coin_pile(cv, top, 33, spill=((12.5, 13.6), (31.5, 14.2)))
    glint(cv, 19, 5, 2)
    glint(cv, 27, 8, 1)
    return cv.image()


# ================================================================ the ledger's ribbons (11 x 30) and their signs (7 x 7)

def ribbon():
    """A page ribbon, its forked tail at the foot (only as much of it is drawn as hangs out of the book): the paint,
    woven greys with a sheen down its left, and over it the outline, a darker selvage and a line of stitching each
    side."""
    W, H = 22, 60
    xs, ys = grid(W, H)
    cloth = poly([(1, -1), (21, -1), (21, 60), (11, 52), (1, 60)], W, H)
    v = 0.86 + 0.08 * np.exp(-((xs - 6) / 2.4) ** 2) - 0.14 * np.clip((xs - 14) / 6, 0, 1)
    v += np.where(np.floor(xs).astype(int) % 2 == 0, 0.015, -0.015)
    v += (fbm(W, H, 2, 41) - 0.5) * 0.06
    v -= 0.06 * np.clip((ys - 48) / 12, 0, 1)
    paint = Canvas(W, H)
    paint.put(cloth, greys(v))

    over = Canvas(W, H)
    over.put(ring(cloth) & (ys > 0), LINE)
    selvage = cloth & ((xs < 2) | (xs > 20))
    over.put(selvage, (0, 0, 0), 0.22)
    stitch = cloth & ((np.abs(xs - 3.5) < 0.5) | (np.abs(xs - 18.5) < 0.5)) & dashes(xs * 0, ys, 4, 2) & (dist_in(cloth, 3) >= 2)
    over.put(stitch, (20, 16, 22), 0.4)
    return over.image(), paint.image()


def sign(kind):
    """A sign stitched on a ribbon: a house for the build's page, a cart wheel for shipping, in bone with a dark line."""
    S = 14
    xs, ys = grid(S, S)
    cv = Canvas(S, S)
    if kind == 'build':
        roof = poly([(1.5, 7.2), (7, 1.5), (12.5, 7.2)], S)
        walls = rect(3, 7, 11, 13, S)
        cv.put(walls, tone(BONE, -0.12))
        cv.put(roof, BONE)
        cv.put(roof & (ys > 6.2), tone(BONE, -0.3))
        cv.put(rect(6, 9, 8, 13, S), PAL['wood'][1])
        shape = roof | walls
    else:
        d = np.hypot(xs - 7, ys - 7)
        rim_m = (d <= 5.6) & (d >= 4.1)
        spokes = np.zeros((S, S), bool)
        for a in range(4):
            ang = a * np.pi / 4
            dd, _ = seg_dist(7 - 4.4 * np.cos(ang), 7 - 4.4 * np.sin(ang), 7 + 4.4 * np.cos(ang), 7 + 4.4 * np.sin(ang), S)
            spokes |= dd < 0.55
        hub, _ = disc(7, 7, 1.6, S)
        cv.put(spokes, tone(BONE, -0.18))
        cv.put(rim_m, BONE)
        cv.put(rim_m & ((xs - 7) + (ys - 7) > 2.5), tone(BONE, -0.25))
        cv.put(hub, PAL['brass'][4])
        shape = rim_m | spokes | hub
    cv.put(ring(shape), LINE)
    return cv.image()


# ================================================================ the pennant over a rented stall (64 x 64 sheet)

def pennant_sheet():
    """The renderer's sheet: the cloth (u 0..0.5, v 0..0.75) in greys with its hem round the rod, stitched borders,
    an embroidered coin and a swallowtail cut; the rod (u 0.5..1, v 0..0.0625) with brass caps; a length of twisted
    cord (u 0.5..0.5625, v 0.125..0.625); and the cloth's roll seen from the side (u 0..0.5, v 0.8125..0.9375)."""
    S = 64
    xs, ys = grid(S, S)
    cv = Canvas(S, S)

    cloth = poly([(0, 0), (32, 0), (32, 48), (16, 37), (0, 48)], S) & (xs < 32)
    v = 0.88 - 0.05 * np.clip(ys / 48, 0, 1) + (fbm(S, S, 2, 51) - 0.5) * 0.06
    v += np.where(np.floor(xs).astype(int) % 2 == 0, 0.012, -0.012)
    inner = dist_in(cloth, 5)
    v -= np.where((inner <= 2) & (ys > 5), 0.07, 0)
    v = np.where(ys < 5, 0.76 + (fbm(S, S, 2, 52) - 0.5) * 0.05, v)
    v = np.where((np.abs(ys - 5.5) < 0.5) & dashes(xs, ys * 0, 4, 2), 0.6, v)
    v = np.where((inner == 4) & (ys > 7) & dashes(xs * 0, ys, 4, 2), 0.64, v)
    coin, _ = disc(16, 21, 7.5, S)
    hole, _ = disc(16, 21, 6.2, S)
    v = np.where(coin, 0.98, v)
    v = np.where(coin & ~hole, 0.6, v)
    v = np.where(np.abs(xs - 16) + np.abs(ys - 21) <= 3.4, 0.8, v)
    v = np.where(np.abs(xs - 16) + np.abs(ys - 21) <= 1.2, 0.98, v)
    cv.put(cloth, greys(v))

    # The rod: lit along its top, brass caps at its ends
    rod = rect(32, 0, 64, 4, S)
    wood = PAL['wood']
    rows = np.array([wood[5], wood[4], wood[3], wood[2]], float)
    grain = fbm(S, S, 4, 53)
    cv.put(rod, rows[np.clip(ys.astype(int), 0, 3)] * (0.92 + grain[..., None] * 0.16))
    caps = rod & ((xs < 35) | (xs >= 61))
    brass = np.array(PAL['brass'][2:6][::-1], float)
    cv.put(caps, brass[np.clip(ys.astype(int), 0, 3)])

    # A length of cord, twisted
    cord = rect(32, 8, 36, 40, S)
    twist = ((np.floor(ys) + np.floor(xs - 32)) % 4 < 2)
    cv.put(cord, np.where(twist[..., None], np.array(HEMP[3], float), np.array(HEMP[2], float)) * (0.92 + grain[..., None] * 0.14))
    cv.put(cord & (xs >= 35), HEMP[1])

    # The roll: a cylinder of the cloth, lit from above
    roll = rect(0, 52, 32, 60, S)
    shade = np.array([0.6, 0.78, 0.9, 0.96, 0.92, 0.82, 0.68, 0.52])
    rv = shade[np.clip(ys.astype(int) - 52, 0, 7)] + (fbm(S, S, 2, 54) - 0.5) * 0.05
    rv = np.where((xs < 1.5) | (xs > 30.5), rv - 0.1, rv)
    rv = np.where((np.abs(xs - 3.5) < 0.5) | (np.abs(xs - 28.5) < 0.5), rv - 0.12, rv)
    cv.put(roll, greys(rv))
    return cv.image()


# ================================================================ previews

def painted(img, rgb):
    """[img] in a colour, the way the screen and the renderer tint it."""
    a = np.asarray(img, float)
    out = a.copy()
    out[..., :3] = a[..., :3] * np.array(rgb, float) / 255
    return Image.fromarray(out.round().astype(np.uint8), 'RGBA')


def over(*layers):
    out = Image.new('RGBA', layers[0].size, (0, 0, 0, 0))
    for layer in layers:
        out.alpha_composite(layer)
    return out


def preview(out_dir, made):
    os.makedirs(out_dir, exist_ok=True)
    colours = [(176, 46, 38), (60, 68, 170), (94, 124, 22), (254, 216, 61), (249, 255, 254)]
    p, pp = made['stall/pennant'], made['stall/pennant_paint']
    r, rp = made['homestead/ribbon'], made['homestead/ribbon_paint']
    t = made['stall/till']
    gui = [p, pp] + [over(p, painted(pp, c)) for c in colours]
    gui += [t] + [over(t, made['stall/till_coins_' + k] if k else made['stall/till_coins']) for k in ('low', '', 'high')]
    gui += [r, rp] + [over(painted(rp, c), r) for c in colours]
    labels = ['pennant', 'paint'] + ['painted'] * 5 + ['till', 'low', 'coins', 'high', 'ribbon', 'paint'] + ['painted'] * 5
    sheet(gui, 5, 7, labels=labels).save(os.path.join(out_dir, 'stall_screen_sprites.png'))
    # The ribbons as the ledger hangs them, with their signs placed as it places them
    hung = []
    for i, c in enumerate(colours):
        img = over(painted(rp, c), r)
        s = made['homestead/sign_' + ('shipping' if i % 2 else 'build')]
        length = 25 if i % 2 == 0 else 18
        cut = img.crop((0, 60 - length * 2, 22, 60))
        cut.alpha_composite(s, (4, length * 2 - 14 - 12))
        canvas = Image.new('RGBA', (22, 60), (0, 0, 0, 0))
        canvas.alpha_composite(cut, (0, 0))
        hung.append(canvas)
    signs = [made['homestead/sign_build'], made['homestead/sign_shipping']]
    sheet(hung + signs, 6, 7, labels=['open', 'shut', 'open', 'shut', 'open', 'build', 'shipping']).save(os.path.join(out_dir, 'ledger_ribbons.png'))
    # The sheet as the renderer colours it: the cloth and its roll in the renter's colour, the rod and cord as they are
    sheet_img = made['entity/market/stall_pennant']
    cloth_part = Image.new('L', sheet_img.size, 0)
    cloth_part.paste(255, (0, 0, 32, 64))
    tinted = [Image.composite(painted(sheet_img, c), sheet_img, cloth_part) for c in colours[:3]]
    sheet([sheet_img] + tinted, 5, 4, labels=['stall_pennant.png', 'red', 'blue', 'green']).save(os.path.join(out_dir, 'stall_pennant_texture.png'))


# ================================================================ main

def build():
    made = {}
    made['stall/pennant'], made['stall/pennant_paint'] = pennant_gui()
    made['stall/till'] = till()
    made['stall/till_coins_low'] = till_coins('low')
    made['stall/till_coins'] = till_coins('mid')
    made['stall/till_coins_high'] = till_coins('high')
    made['homestead/ribbon'], made['homestead/ribbon_paint'] = ribbon()
    made['homestead/sign_build'] = sign('build')
    made['homestead/sign_shipping'] = sign('shipping')
    made['entity/market/stall_pennant'] = pennant_sheet()
    return made


def main():
    assets = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    made = build()
    for key, img in made.items():
        folder = 'textures' if key.startswith('entity/') else 'textures/gui/sprites'
        path = os.path.join(assets, folder, key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
    if prev:
        preview(prev, made)


if __name__ == '__main__':
    main()
