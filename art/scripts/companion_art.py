"""The companions' keepsake weapons, the gifts they give once they are Sworn: Greyhart, the Lamplighter's Censer, the
Thornwood Bow and the Unpicked Star.

They are painted at 32x with the HD art kit (hdkit.py) in the house style, each with its model and item definition, the
way faction_art.py did the Starwatch Staff, Widow's Kiss and Hearthbrand:

- `greyhart` is a greatsword on weaponkit's greatsword layout and model (so it sits in the hand like the netherite
  greatsword it is reforged from): blackened steel worn bright along its edges, a grey hart's head etched on the blade
  above the guard, a grey leather grip, a plain heavy cross-guard.
- `lamplighters_censer` is the iron censer's layout and model, in blackened brass, with a small steady flame showing
  through the fretwork of its burner.
- `thornwood_bow` is a recurve of black yew with a green leather grip and pale hart-horn tips, with the vanilla bow's
  four textures (standing, and the three stages of drawing it with a grey-fletched arrow) and the vanilla bow's item
  definition (the use-duration states) and display transforms.
- `unpicked_star` is the amethyst staff's layout and model: pale ash wound with a strip of faded midnight-blue cloth, a
  small silver star sewn on at the head, a cold white light caged above it.

    python3 -B art/scripts/companion_art.py src/main/resources/assets/guildmark [preview-dir]

Writes the textures (`textures/item/<id>.png`, and the bow's `thornwood_bow_pulling_<n>.png`), the models and the item
definitions. Everything is seeded, so a rerun gives the same files.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from hdkit import PAL, Canvas, vnoise, light, tone, disc, path_dist
import weaponkit
import magic_hd as mh
from faction_art import (N, S_, W_, nb, at_px, along, lerp_w, ridge_shade, sw_path, wrap, metal, finish, seed_for, SILVER, EMBER,
                         write_json, BG_DARK, BACKDROPS, tiles)

# ================================================================ palettes

GREY_LEATHER = [(12, 12, 16), (26, 26, 32), (46, 46, 54), (70, 70, 80), (98, 98, 108), (130, 130, 140)]     # a cool grey hide
HART_RAMP = [(92, 96, 108), (122, 126, 138), (152, 156, 166), (186, 190, 198)]                                # the etched badge: pale steel, worn
BLACKENED = [(9, 10, 16), (22, 24, 34), (40, 44, 58), (62, 68, 86), (94, 102, 122), (142, 150, 170)]       # blackened steel, a cool sheen
BLACKBRASS = [(14, 10, 10), (34, 25, 19), (62, 45, 29), (100, 73, 41), (146, 110, 58), (196, 158, 94)]       # brass blackened with age
ASH = [(34, 30, 30), (70, 62, 56), (112, 102, 88), (154, 142, 120), (190, 178, 150), (222, 211, 184)]       # pale ash wood
MIDNIGHT = [(18, 20, 38), (36, 42, 72), (58, 68, 106), (86, 98, 136), (116, 128, 164), (150, 162, 192)]     # faded midnight-blue cloth
YEW = [(6, 6, 8), (16, 13, 14), (30, 23, 21), (50, 38, 31), (74, 57, 44), (104, 81, 60)]                    # black yew, a warm grain in it
GREEN_LEATHER = [(8, 18, 14), (18, 36, 26), (32, 60, 40), (50, 86, 54), (74, 114, 70), (104, 144, 94)]      # a strip of green leather
HORN = [(30, 26, 26), (66, 58, 52), (112, 102, 88), (158, 146, 124), (198, 188, 164), (230, 222, 198)]      # pale hart horn
STRING = (140, 132, 114)
COLD = [(24, 34, 58), (56, 82, 124), (108, 146, 188), (176, 208, 232), (226, 242, 252), (255, 255, 255)]    # a cold white light


def at_xy(s, w):
    """Pixel position (x, y down) of the sprite point (s, w)."""
    return at_px(s, w)


# ================================================================ Greyhart

# The hart's head etched on the blade, drawn texel by texel on the blade's own diagonals: the face runs down the middle toward the
# guard, and two antlers leave the cranium as a lyre, each a staircase up the blade with a tine or two. (dx, dy) are texels
# from the poll; the right antler is the left one reflected across the blade's axis, (dx, dy) -> (-dy, -dx).
HART_AT = (16, 15)
_FACE = [(0, 0), (-1, 1), (-2, 2), (-3, 3), (-1, 0), (0, 1), (-2, 1), (-1, 2)]          # the poll, the long face, the cheeks
_ANTLER = [(-1, -1), (0, -2), (1, -3), (2, -4), (3, -5), (0, -3), (1, -4)]             # the beam, then its two tines
HART_TEXELS = _FACE + _ANTLER + [(-dy, -dx) for dx, dy in _ANTLER]


def hart_mask():
    """The etched hart's head as a mask over the sprite."""
    m = np.zeros((N, N), bool)
    for dx, dy in HART_TEXELS:
        x, y = HART_AT[0] + dx, HART_AT[1] + dy
        if 0 <= x < N and 0 <= y < N:
            m[y, x] = True
    return m


def greyhart():
    """Bram's old captain's greatsword, reforged: a broad blade of blackened steel worn bright along its edges and nicked, the
    Greyharts' grey hart's head etched above the guard, a plain heavy cross-guard, a grip wrapped in grey leather and a heavy
    wheel pommel. Laid out as weaponkit's greatsword, so it sits in the hand as the other greatswords do."""
    cv = Canvas(N)
    BS = BLACKENED
    half = lerp_w(S_, [(17, 3.0), (26, 2.95), (32, 2.2), (39, 1.8), (42.5, 1.15), (44.6, 0.0)])
    blade = along(17, 44.8) & (np.abs(W_) <= half)
    # a few nicks in the edge, where it has met other steel
    for s, w in ((24.5, -2.7), (31.0, 2.3), (36.0, -1.9), (40.5, 1.5)):
        blade &= ~(np.hypot(S_ - s, W_ - w) < 0.55)
    lit, h = ridge_shade(W_, half)
    bt = 0.27 + 0.34 * lit + 0.1 * h + 0.05 * (vnoise(N, N, 3, seed_for('greyhart', 'blade')) - 0.5)
    fuller = along(29.5, 40.0) & (np.abs(W_ + 0.15) < 0.5)
    bt = np.where(fuller, 0.2 + 0.12 * (W_ < -0.2), bt)
    edge_line = blade & (np.abs(W_) > half - 0.72)
    bt = np.where(edge_line & (W_ > 0), 0.74, np.where(edge_line, 0.4, bt))
    cv.put(blade, nb(BS, bt))
    # bare steel showing where the black has worn off, along the lit edge and in scratches across the flat
    bare = edge_line & (W_ > 0) & (vnoise(N, N, 2.2, seed_for('greyhart', 'wear')) > 0.42)
    cv.put(bare, nb(PAL['steel'], 0.7 + 0.2 * (W_ > half - 0.4), 0.0))
    for pts in (((20.0, -1.9), (23.2, -2.2)), ((33.0, 0.4), (36.6, 0.9)), ((38.4, -0.5), (40.6, -0.6))):
        d, _ = sw_path(list(pts))
        cv.put(blade & (d < 0.4), nb(PAL['steel'], 0.66, 0.0))
    # the hart, etched in pale grey, its antlers catching a little light
    hart = hart_mask() & blade
    cv.put(hart, nb(HART_RAMP, 0.6 + 0.3 * (W_ > 0.2) - 0.25 * (W_ < -0.5), 0.0))
    # the ricasso, and the plain, heavy cross-guard: a straight bar, squared ends, a block at its middle
    IRON = PAL['iron']
    ricasso = along(13.4, 17) & (np.abs(W_) <= 1.3)
    metal(cv, ricasso, BS, 0.46)
    bar = (np.abs(S_ - 12.5) < 1.35) & (np.abs(W_) <= 5.9)
    metal(cv, bar, IRON, 0.52, 0.16 * (S_ - 12.5 > 0.3) - 0.14 * (S_ - 12.5 < -0.5))
    block = (np.abs(S_ - 12.8) < 1.85) & (np.abs(W_) <= 1.9)
    metal(cv, block, IRON, 0.6, 0.1 * (S_ - 12.8 > 0.5))
    cv.put(bar & (np.abs(np.abs(W_) - 5.9) < 0.5), nb(IRON, 0.36, 0.0))                  # the squared ends, a shade darker
    # the grip, wrapped in grey leather, and the collar
    grip = along(4.0, 11.6) & (np.abs(W_) <= 1.15)
    wrap(cv, grip, GREY_LEATHER, 4.0, 2.0, seed_for('greyhart', 'grip'), base=0.7)
    metal(cv, along(11.0, 11.9) & (np.abs(W_) <= 1.45), IRON, 0.5)
    # the pommel: a heavy wheel
    px, py = at_px(2.55, 0)
    pom, d = disc(px, py, 2.1, N)
    cv.put(pom, nb(IRON, 0.46 + 1.1 * light(d * 2.2, 1.0) + 0.22 * d))
    gx, gy = at_px(35.0, 1.0)
    return finish(cv, [(int(gx), int(gy), 1, (255, 250, 236))])


# ================================================================ the Lamplighter's Censer

# The burner, drawn by hand like the iron censer's (the same 11 x 11 ball, pole to pole along the sprite diagonal, lit from the
# upper left; the digits are tones of the brass), with a tall window cut through its fretwork: a small steady flame stands in it,
# a dark inside round it ('k'), and a few pinholes of the fretwork ('p') with a dim ember behind them.
CENSER_BALL = [
    "....543....",
    "..5544332..",
    ".455544332.",
    ".455kkok32.",
    "44k5koykk22",
    "34k4oywy2k1",
    "33k4owwy2k1",
    ".33kkoykk1.",
    ".233kkok21.",
    "..2222211..",
    "....211....",
]


def lamplighters_censer():
    """Ysolde's keepsake: the iron censer's haft, grip and burner (so it sits and swings as they do) in brass blackened with
    age, the burner a pierced ball with one small flame standing steady in the fretwork. Never goes out."""
    cv = Canvas(N)
    brass = BLACKBRASS
    cord = PAL['leather']
    mh.censer_haft(cv, brass, 2.2, 23.0, 1.0, 0.16, seed_for('lamplighter', 'haft'))
    grip = along(5.0, 12.4) & (np.abs(W_) <= 1.15)
    wrap(cv, grip, cord, 5.0, 1.9, seed_for('lamplighter', 'grip'), base=0.5)
    mh.knob(cv, brass, 1.9, 1.7, 0.45)
    mh.ring(cv, 12.6, 13.4, 1.4, brass, 0.55)
    mh.ring(cv, 19.2, 19.9, 1.25, brass, 0.5)
    mh.sphere_burner(cv, brass, [(22.8, 1.9), (24.6, 1.9), (24.6, 1.3), (26.0, 1.3), (26.0, 1.9), (27.0, 2.6)], base=0.5, k=1.3)
    fire = {'k': (22, 9, 8), 'r': EMBER[1], 'o': EMBER[3], 'y': EMBER[4], 'w': EMBER[5], 'p': EMBER[2]}
    # blackened: a tone darker all over, but for the sheen the old brass keeps where the hand and the light have worn it bright
    ball = [''.join(str(int(ch) - (0 if ch == '5' and j < 4 else 1)) if ch.isdigit() else ch for ch in row)
            for j, row in enumerate(CENSER_BALL)]
    mh.draw(cv, ball, *mh.BALL_AT, brass, fire)
    mh.draw(cv, mh.FINIAL, *mh.FINIAL_AT, brass)
    glow = np.zeros((N, N), bool)
    for j, row in enumerate(CENSER_BALL):
        for i, ch in enumerate(row):
            if ch in 'owyp':
                glow[mh.BALL_AT[1] + j, mh.BALL_AT[0] + i] = True
    return finish(cv, rim_skip=glow)


# ================================================================ the Unpicked Star

STAR_ARMS = [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2)]       # a four-pointed star, upright on the screen
STAR_DIAG = [(1, 1), (-1, 1), (1, -1), (-1, -1)]


def unpicked_star():
    """Lucan's keepsake: an apprentice's staff on the amethyst staff's layout, pale ash wound with a strip of his old robe (faded
    midnight blue, one end left to hang), a patch of that cloth at the head with the silver star he unpicked sewn back on, and
    above it a small cage of ash twigs round a cold white light."""
    cv = Canvas(N)
    c = 0.3 * np.sin(S_ * 0.23 + 0.9)
    haft = along(2.4, 37.0) & (np.abs(W_ - c) <= 0.85)
    g = vnoise(N, N, 2.6, seed_for('unpicked', 'haft')) - 0.5
    kn = np.zeros((N, N), bool)
    for ks, kw in ((17.5, 0.3), (24.5, -0.2), (6.4, 0.4)):
        kn |= np.hypot(S_ - ks, (W_ - kw) * 1.2) < 0.75
    t = 0.6 + 0.26 * (W_ - c > 0.25) - 0.24 * (W_ - c < -0.35) + 0.14 * g - 0.3 * kn
    cv.put(haft, nb(ASH, t, 0.3))
    # the strip of robe: close turns over the grip, a few loose turns higher up and the end left flying
    grip = along(9.0, 15.4) & (np.abs(W_ - c) <= 1.05)
    ph = ((S_ - 9.0) / 1.7) % 1.0
    cv.put(grip, nb(MIDNIGHT, 0.46 + 0.3 * (ph < 0.55) - 0.22 * (ph > 0.85) + 0.14 * (W_ - c > 0.3) - 0.2 * (W_ - c < -0.5), 0.15))
    for s0 in (19.4, 22.2, 25.0):
        turn = along(s0, s0 + 1.0) & (np.abs(W_ - c) <= 1.0)
        cv.put(turn, nb(MIDNIGHT, 0.5 + 0.2 * (W_ - c > 0.3) - 0.2 * (W_ - c < -0.5), 0.1))
    tail, _ = sw_path([(25.4, -0.6), (26.8, -1.6), (28.2, -2.0), (29.4, -2.8)])
    cv.put((tail <= 0.48) & ~haft, nb(MIDNIGHT, 0.56, 0.0))
    # a plain iron butt cap
    cap = along(0.8, 3.3) & (np.abs(W_) <= 1.2 - 0.25 * (S_ < 1.6))
    metal(cv, cap, PAL['iron'], 0.5)
    mh.ring(cv, 3.3, 4.2, 1.2, PAL['iron'], 0.5, c)
    # the cloth patch at the head, the star sewn on it
    patch = along(30.6, 36.4) & (np.abs(W_) <= 2.4 - 0.5 * (S_ > 35.0))
    pt = 0.36 + 0.3 * (W_ > 0.4) - 0.2 * (W_ < -0.8) + 0.12 * (vnoise(N, N, 1.6, seed_for('unpicked', 'cloth')) - 0.5)
    cv.put(patch, nb(MIDNIGHT, pt, 0.2))
    sx, sy = at_px(33.5, 0.0)
    cx, cy = int(math.floor(sx)), int(math.floor(sy))
    star = np.zeros((N, N), bool)
    for dx, dy in STAR_DIAG:
        cv.px(cx + dx, cy + dy, np.array(SILVER[2], float))
        star[cy + dy, cx + dx] = True
    for dx, dy in STAR_ARMS:
        cv.px(cx + dx, cy + dy, np.array(SILVER[4] if (dx, dy) != (0, 0) else SILVER[5], float))
        star[cy + dy, cx + dx] = True
    # the cage: two ash twigs springing from the patch and meeting over a cold light
    cage = np.zeros((N, N), bool)
    for side in (1, -1):
        d, tt = sw_path([(36.0, 0.9 * side), (37.4, 2.0 * side), (39.6, 2.5 * side), (41.8, 2.0 * side), (43.4, 0.9 * side), (44.2, 0.0)])
        prong = d < 0.55
        cage |= prong
        cv.put(prong, nb(ASH, (0.74 if side > 0 else 0.42) - 0.06 * tt / 12.0, 0.0))
    ox, oy = at_px(40.2, 0.0)
    ring_, od = disc(ox, oy, 1.9, N)
    core_, _ = disc(ox, oy, 0.95, N)
    cv.put(ring_, nb(COLD, 0.55 + 0.25 * od, 0.0))
    cv.put(core_, np.array(COLD[5], float))
    return finish(cv, rim_skip=ring_ | cage | star)


# ================================================================ the Thornwood Bow

# The bow lies on the vanilla bow's own diagonal: its belly toward the upper left, its two tips at the top right and the bottom
# left, the string between them, an arrow drawn through the grip toward the upper left. Everything is built on the symmetry
# about the line y = x (reflecting (x, y) -> (y, x) swaps the two limbs).
BOW_C, BOW_R, BOW_P = 28.8, 25.2, 2.6            # the limb's curve: a squared quarter ellipse, centre lower right
BOW_TIPS = ((28.0, 2.3), (2.3, 28.0))             # where the string ties on
DRAW_NOCKS = (None, 18.0, 20.4, 22.8)             # the nock's place on the diagonal in each frame: standing, then the three draws


def bow_path(flex=0.0, n=240):
    """The limb's centreline from the lower tip over the grip to the upper tip as (x, y) points, the tips recurved away from
    the archer and drawn in a little by [flex] (0 slack, 1 full draw)."""
    pts = []
    for i in range(n + 1):
        f = i / n
        th = (math.pi / 2) * f
        x = BOW_C - BOW_R * math.cos(th) ** (2 / BOW_P)
        y = BOW_C - BOW_R * math.sin(th) ** (2 / BOW_P)
        up = max(0.0, (f - 0.74) / 0.26)
        lo = max(0.0, (0.26 - f) / 0.26)
        x += -1.1 * flex * up * up + (-2.0 * lo * lo) + 1.1 * flex * lo * lo
        y += -2.0 * up * up + 1.1 * flex * up * up - 1.1 * flex * lo * lo
        pts.append((x, y))
    return pts


def thornwood_bow(draw=0):
    """Kestrel's keepsake, a recurve of black yew with a strip of green leather round the grip and pale hart-horn tips, drawn
    in the vanilla bow's four textures: [draw] 0 the bow standing, 1 to 3 the three stages of drawing it, a grey-fletched arrow
    nocked, the string coming back with it."""
    flex = (0.0, 0.3, 0.65, 1.0)[draw]
    cv = Canvas(N)
    pts = bow_path(flex)
    d, run = path_dist(pts, N)
    L = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))
    f = run / L
    hw = np.interp(f, [0, 0.07, 0.3, 0.5, 0.7, 0.93, 1.0], [0.85, 1.2, 1.45, 1.75, 1.45, 1.2, 0.85])
    grip_ = np.abs(f - 0.5) < 0.075
    hw = np.where(grip_, hw + 0.25, hw)
    mask = d <= hw
    h = np.where(mask, np.sqrt(np.clip(1 - (d / np.maximum(hw, 0.1)) ** 2, 0, 1)), 0.0)
    lit = light(h * 2.4, 1.0)
    grain = vnoise(N, N, 1.8, seed_for('thornwood', 'grain')) - 0.5
    streak = 0.5 + 0.5 * np.sin(run * 1.7 + 6 * (vnoise(N, N, 5, seed_for('thornwood', 'streak')) - 0.5))
    wood = 0.3 + 1.5 * lit + 0.08 * grain + 0.07 * (streak > 0.82)
    cv.put(mask, nb(YEW, wood, 0.3))
    # the grip: turns of green leather, each lit on its upper edge
    ph = ((run - f.min()) / 1.5) % 1.0
    leather = 0.5 + 1.0 * lit + 0.3 * (ph < 0.5) - 0.22 * (ph > 0.86) + 0.06 * grain
    cv.put(mask & grip_, nb(GREEN_LEATHER, leather, 0.15))
    # the horn tips, with a groove where the string rides
    horn = mask & ((f < 0.085) | (f > 0.915))
    cv.put(horn, nb(HORN, 0.62 + 1.2 * lit + 0.1 * grain, 0.2))
    nock = mask & ((np.abs(f - 0.03) < 0.0075) | (np.abs(f - 0.97) < 0.0075))
    cv.put(nock, nb(HORN, 0.2, 0.0))
    img = finish(cv, [])
    cvl = Canvas.of(img)
    # the string, one texel wide, tip to tip or tip to nock to tip, drawn over the finished bow so it keeps no outline
    n = DRAW_NOCKS[draw]
    string = [BOW_TIPS[0], BOW_TIPS[1]] if n is None else [BOW_TIPS[0], (n, n), BOW_TIPS[1]]
    sd, _ = path_dist(string, N)
    on = sd <= 0.5
    cvl.put(on & ~(cvl.a > 0.9) | on & (cvl.a > 0.9) & (d > hw + 0.9), np.array(STRING, float))
    if n is not None:
        arrow(cvl, n)
    return cvl.image()


def arrow(cv, n):
    """A grey-fletched arrow along the diagonal from its nock at (n, n) up through the grip to a steel head at the upper left:
    a two-texel shaft, three vanes of grey fletching at the nock, a small steel point."""
    for i in range(3, int(n)):
        cv.px(i, i, np.array(tone((176, 140, 92), 0.1 if i % 3 else 0.3), float))
        cv.px(i + 1, i, np.array(tone((176, 140, 92), -0.55), float))
    grey, greyd = (190, 192, 198), (112, 114, 126)
    for k in range(0, 5):
        i = int(n) - k
        cv.px(i - 1, i, np.array(grey if k % 2 == 0 else greyd, float))
        cv.px(i, i + 1, np.array(greyd if k % 2 == 0 else grey, float))
        if 1 <= k <= 3:
            cv.px(i - 2, i, np.array(greyd, float))
            cv.px(i, i + 2, np.array(grey, float))
    cv.px(int(n) + 1, int(n) + 1, np.array((70, 52, 40), float))          # the nock itself
    head = {(2, 2): (214, 218, 224), (1, 2): (150, 156, 168), (2, 1): (182, 188, 198), (1, 1): (236, 238, 242), (3, 3): (120, 126, 138),
            (0, 0): (150, 156, 168), (1, 0): (96, 100, 112), (0, 1): (96, 100, 112)}
    for (x, y), col in head.items():
        cv.px(x, y, np.array(col, float))


# ================================================================ writing the items

# vanilla's bow model, display transforms and all (assets/minecraft/models/item/bow.json), which the four bow models share
BOW_DISPLAY = {
    'thirdperson_righthand': {'rotation': [-80, 260, -40], 'translation': [-1, -2, 2.5], 'scale': [0.9, 0.9, 0.9]},
    'thirdperson_lefthand': {'rotation': [-80, -280, 40], 'translation': [-1, -2, 2.5], 'scale': [0.9, 0.9, 0.9]},
    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
}


def one_model(name):
    return {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/' + name}}


def bow_definition(name='thornwood_bow'):
    """The bow's item definition, vanilla's own (assets/minecraft/items/bow.json) with the models swapped: the standing model,
    and while the bow is being drawn one of three by how long it has been drawn (use_duration / 20: pulling_1 from 0.65,
    pulling_2 from 0.9, pulling_0 before)."""
    def model(suffix=''):
        return {'type': 'minecraft:model', 'model': f'guildmark:item/{name}{suffix}'}
    return {'model': {
        'type': 'minecraft:condition',
        'on_false': model(),
        'on_true': {
            'type': 'minecraft:range_dispatch',
            'entries': [{'model': model('_pulling_1'), 'threshold': 0.65}, {'model': model('_pulling_2'), 'threshold': 0.9}],
            'fallback': model('_pulling_0'),
            'property': 'minecraft:use_duration',
            'scale': 0.05,
        },
        'property': 'minecraft:using_item',
    }}


# name -> (painter, the weapon geometry its model is solved with); None: the vanilla bow, whose model and display are its own
HELD = {'greyhart': (greyhart, weaponkit.GREATSWORD), 'lamplighters_censer': (lamplighters_censer, weaponkit.CENSER),
        'thornwood_bow': (thornwood_bow, None), 'unpicked_star': (unpicked_star, weaponkit.STAFF)}


def write_items(assets):
    """Paint every keepsake and write its texture, model and item definition; returns {name: image} in the order they are listed
    (the bow's three drawing frames follow it, named thornwood_bow_pulling_<n>)."""
    made = {}
    models = os.path.join(assets, 'models', 'item')
    defs = os.path.join(assets, 'items')

    def texture(name, img):
        path = os.path.join(assets, 'textures', 'item', name + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path, optimize=True)

    for name, (paint, geo) in HELD.items():
        made[name] = paint()
        texture(name, made[name])
        if geo is not None:
            write_json(os.path.join(models, name + '.json'), weaponkit.model('guildmark:item/' + name, geo))
            write_json(os.path.join(defs, name + '.json'), one_model(name))
        else:
            write_json(os.path.join(models, name + '.json'),
                       {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/' + name}, 'display': BOW_DISPLAY})
            for n in range(3):
                frame = f'{name}_pulling_{n}'
                made[frame] = paint(n + 1)
                texture(frame, made[frame])
                write_json(os.path.join(models, frame + '.json'),
                           {'parent': 'guildmark:item/' + name, 'textures': {'layer0': 'guildmark:item/' + frame}})
            write_json(os.path.join(defs, name + '.json'), bow_definition(name))
    return made


# ================================================================ previews

# existing weapons the keepsakes stand beside in the hotbar sheet (textures/item/<name>.png; the vanilla bow is shown when a
# directory with its bow.png is given), in slots: each class's older ones, then the keepsake
COMPARE = (('greatsword', ['iron_greatsword', 'diamond_greatsword', 'netherite_greatsword'], 'greyhart'),
           ('censer', ['iron_censer', 'gilded_censer', 'blazing_censer'], 'lamplighters_censer'),
           ('bow', ['bow'], 'thornwood_bow'),
           ('staff', ['amethyst_staff', 'starwatch_staff'], 'unpicked_star'))


def load_item(assets, name, vanilla=None):
    """An existing item texture at 32 x 32 (the vanilla bow, 16 x 16, doubled), or None when it is not to be had."""
    path = os.path.join(vanilla, name + '.png') if name == 'bow' and vanilla else os.path.join(assets, 'textures', 'item', name + '.png')
    if not os.path.exists(path):
        return None
    im = Image.open(path).convert('RGBA')
    if im.size != (32, 32):
        im = im.resize((32, 32), Image.NEAREST)
    return im


def short(name):
    """A name that fits under a slot: the item class is left off (iron_censer: iron)."""
    for tail in ('_greatsword', '_censer', '_staff', '_bow', '_star'):
        if name.endswith(tail) and name != 'thornwood_bow':
            return name[:-len(tail)]
    return name.replace('_bow', '')


def slots(images, names, mine, bg, zoom=1):
    """A hotbar of [images], each in its slot with its name beneath, the keepsakes' slots picked out."""
    slot = 40 * zoom
    label = 12
    out = Image.new('RGBA', (len(images) * slot + 8, slot + 8 + label), (*bg, 255))
    d = ImageDraw.Draw(out)
    ink = (226, 220, 206, 255) if sum(bg) < 400 else (40, 38, 46, 255)
    slot_bg = tuple(int(c * 0.78) for c in bg)
    for i, (im, name) in enumerate(zip(images, names)):
        x = 4 + i * slot
        edge_col = (214, 178, 96, 255) if name in mine else tuple(int(c * 0.55) for c in bg) + (255,)
        d.rectangle([x, 4, x + slot - 2, 4 + slot - 2], fill=(*slot_bg, 255), outline=edge_col)
        pic = im.resize((32 * zoom, 32 * zoom), Image.NEAREST) if zoom != 1 else im
        out.alpha_composite(pic, (x + 4 * zoom - 1, 4 + 4 * zoom - 1))
        d.text((x + 1, slot + 6), short(name)[:max(5, slot // 6)], fill=ink)
    return out


def item_previews(made, assets, outdir, vanilla=None):
    """The sheets: the keepsakes large (the bow's three draws too), and each beside the older weapons of its class as a hotbar
    shows them, 1x and 2x, on a dark backdrop and a light one. Returns the combined sheet."""
    mine = [n for n in made if not n.startswith('thornwood_bow_pulling')]
    images = list(made.values())
    names = list(made)
    row, labels = [], []
    for cls, olds, new in COMPARE:
        for n in olds:
            im = load_item(assets, n, vanilla)
            if im is not None:
                row.append(im)
                labels.append(n if n != 'bow' else 'vanilla bow')
        row.append(made[new])
        labels.append(new)
    parts = {}
    for key, bg in BACKDROPS:
        big = tiles(images, names, 8, 4, bg)
        big.save(os.path.join(outdir, f'keepsakes_{key}.png'))
        bar1, bar2 = slots(row, labels, mine, bg), slots(row, labels, mine, bg, 2)
        bar1.save(os.path.join(outdir, f'keepsakes_hotbar_{key}.png'))
        parts[key] = (big, bar1, bar2)
    width = max(max(p.width for p in ps) for ps in parts.values())
    height = sum(p.height for ps in parts.values() for p in ps)
    sh = Image.new('RGBA', (width, height), (*BG_DARK, 255))
    y = 0
    for key, bg in BACKDROPS:
        for p in parts[key]:
            sh.alpha_composite(Image.new('RGBA', (width, p.height), (*bg, 255)), (0, y))
            sh.alpha_composite(p, (0, y))
            y += p.height
    sh.save(os.path.join(outdir, 'companion_art_sheet.png'))
    return sh


# ================================================================ main

def main(assets, previews_dir=None, vanilla=None):
    made = write_items(assets)
    if previews_dir:
        os.makedirs(previews_dir, exist_ok=True)
        item_previews(made, assets, previews_dir, vanilla)
    print('companion_art: ' + ', '.join(made))


if __name__ == '__main__':
    args = sys.argv[1:]
    if not 1 <= len(args) <= 3:
        raise SystemExit('usage: python3 -B art/scripts/companion_art.py <assets dir> [preview dir [vanilla item textures dir]]')
    main(*args)
