"""The motes in the air of the regions: ash flakes and embers over the ashen highlands, wisps and gnats in
the marsh, snow blown off the peaks, glints in the crystal deeps, pollen over the meadows and the steppe,
spores on the mushroom isles, sand off the dunes, dust in the caves and bits of leaf in the old woods.

    python3 art/scripts/region_particles.py <assets dir> [preview.png]

Every sprite is 16x16, twice vanilla's 8x8 particles. Most are painted pale and tinted in the game, so one
set serves a whole range of colours (the preview tints them as the game does). Writes
textures/particle/<id>_<n>.png and particles/<id>.json for each particle, and an optional contact sheet:
every sprite large on a night sky and a day sky, then again at about the size the game draws them. All
randomness is seeded, so a rerun gives the same files.
"""
import json
import math
import os
import random
import sys

from PIL import Image, ImageDraw

S = 16
CL = (0, 0, 0, 0)
# Supersampling for shapes and strokes, so their edges get a fair coverage
SS = 8


# ---- Painting ----

def blank():
    return Image.new("RGBA", (S, S), CL)


def clamp(v, lo=0.0, hi=1.0):
    return lo if v < lo else hi if v > hi else v


def ramp(colors, t):
    """A colour from a list of stops, for t in 0..1."""
    t = clamp(t, 0.0, 0.9999) * (len(colors) - 1)
    i = int(t)
    f = t - i
    a, b = colors[i], colors[i + 1]
    return tuple(int(round(a[k] * (1 - f) + b[k] * f)) for k in range(3))


def over(img, x, y, rgb, a):
    """Lays a pixel of colour [rgb] at opacity [a] (0..255) over whatever is there already."""
    if not (0 <= x < S and 0 <= y < S) or a <= 0:
        return
    r0, g0, b0, a0 = img.getpixel((x, y))
    fa = a / 255.0
    fb = a0 / 255.0 * (1 - fa)
    out = fa + fb
    if out <= 0:
        return
    c = tuple(int(round((rgb[k] * fa + (r0, g0, b0)[k] * fb) / out)) for k in range(3))
    img.putpixel((x, y), c + (int(round(out * 255)),))


def coverage_of(draw_fn):
    """How much of each pixel a shape covers, 0..1: [draw_fn] draws it in white on a supersampled canvas."""
    big = Image.new("L", (S * SS, S * SS), 0)
    draw_fn(ImageDraw.Draw(big))
    small = big.resize((S, S), Image.BOX)
    return [[small.getpixel((x, y)) / 255.0 for x in range(S)] for y in range(S)]


def polygon(points, k=1.0):
    """Coverage of a polygon (pixel units, the sprite spanning 0..16), squeezed sideways to [k] of its width
    about the middle: how a flat flake looks as it turns edge-on."""
    pts = [((8 + (x - 8) * k) * SS, y * SS) for x, y in points]
    return coverage_of(lambda d: d.polygon(pts, fill=255))


def stroke(points, width, k=1.0):
    pts = [((8 + (x - 8) * k) * SS, y * SS) for x, y in points]
    return coverage_of(lambda d: d.line(pts, fill=255, width=max(1, int(width * SS))))


def rim(inside, x, y):
    """Whether pixel (x, y) of a filled shape touches its outside."""
    for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + ox, y + oy
        if not (0 <= nx < S and 0 <= ny < S) or not inside[ny][nx]:
            return True
    return False


def soft_dot(img, cx, cy, radius, colors, falloff=1.4, peak=255, squash=1.0):
    """A soft round glow centred on (cx, cy) in pixel units: colours run from the middle out, and it fades
    to nothing at [radius]. [squash] above 1 stretches it upward."""
    for y in range(S):
        for x in range(S):
            dx = x + 0.5 - cx
            dy = (y + 0.5 - cy) / squash
            d = math.hypot(dx, dy) / radius
            if d >= 1:
                continue
            over(img, x, y, ramp(colors, d), peak * (1 - d) ** falloff)


# ---- Ash and leaves: flat flecks that tumble as they fall ----

ASH = [(78, 74, 72), (112, 107, 103), (146, 141, 136), (180, 176, 171), (210, 207, 202), (234, 232, 228)]

ASH_SHAPES = [
    # a broad, ragged flake
    [(2.4, 6.6), (4.4, 4.2), (6.6, 4.0), (8.0, 2.6), (10.4, 3.4), (12.8, 3.0), (13.8, 5.8), (12.6, 8.0),
     (13.6, 10.6), (11.0, 12.4), (8.6, 11.6), (6.4, 13.2), (3.8, 12.0), (3.2, 9.4)],
    # a smaller, jagged scrap
    [(4.0, 5.4), (6.6, 3.6), (8.4, 4.8), (10.2, 3.4), (12.0, 5.6), (11.0, 7.8), (12.4, 10.0), (10.0, 12.6),
     (7.6, 11.4), (5.4, 12.4), (4.6, 9.4), (3.4, 7.8)],
]

# How each of the four tumbling frames shows its flake: squeezed to this much of its width, and lit this
# much darker (or lighter) as it tips away from the light. The fourth is its underside coming round.
TUMBLE = [(1.0, 0.0), (0.58, -0.1), (0.2, -0.2), (0.58, -0.04)]


def fleck(points, k, shade, palette, seed, flecks=(), rib=None):
    """A flat fleck from a polygon, turned so it shows [k] of its width: lit from the upper left, darker at
    its rim, soft where its edge only half covers a pixel. [flecks] are spots (in the flat shape's own
    coordinates) painted dark, [rib] a line painted light across it."""
    rng = random.Random(seed)
    cov = polygon(points, k)
    inside = [[cov[y][x] >= 0.5 for x in range(S)] for y in range(S)]
    ribs = stroke(rib, 0.9, k) if rib else None
    img = blank()
    noise = [[rng.random() for _ in range(S)] for _ in range(S)]
    for y in range(S):
        for x in range(S):
            c = cov[y][x]
            if c < 0.22:
                continue
            t = 0.7 + shade - 0.03 * (x - 8) * k - 0.035 * (y - 8) + (noise[y][x] - 0.5) * 0.14
            if not inside[y][x]:
                # The ragged fringe: darker and see-through
                img.putpixel((x, y), ramp(palette, t - 0.28) + (int(90 + 120 * c),))
                continue
            if rim(inside, x, y):
                t -= 0.2
            if ribs and ribs[y][x] > 0.45:
                t += 0.16
            img.putpixel((x, y), ramp(palette, t) + (255,))
    for fx, fy in flecks:
        x = int(8 + (fx - 8) * k)
        y = int(fy)
        if 0 <= x < S and inside[y][x]:
            img.putpixel((x, y), ramp(palette, 0.12 + shade) + (255,))
    return img


def ash_flakes():
    """Two flakes of ash, four frames each as it tumbles: sprite 4 * shape + frame."""
    char = [[(6.0, 8.0), (10.0, 6.0), (9.0, 10.0)], [(7.0, 7.0), (9.0, 9.0)]]
    out = []
    for s, shape in enumerate(ASH_SHAPES):
        for f, (k, shade) in enumerate(TUMBLE):
            # The underside is more charred
            spots = char[s] + ([(8.0, 9.0), (7.0, 5.0)] if f == 3 else [])
            out.append(fleck(shape, k, shade, ASH, 100 + s * 10 + f, spots))
    return out


# Painted pale and neutral, so the game can tint them green, olive, brown or rust (or the pale garden's grey)
LEAF = [(86, 86, 78), (124, 124, 114), (160, 160, 148), (194, 194, 182), (222, 222, 210), (240, 240, 230)]

LEAF_SHAPES = [
    # the tip of a broad leaf, torn across
    ([(3.2, 9.4), (4.4, 6.6), (7.0, 4.4), (10.6, 2.8), (13.6, 2.4), (12.8, 5.4), (11.2, 8.4), (9.0, 10.6),
      (7.8, 9.8), (6.8, 11.6), (5.2, 10.6), (4.0, 12.4)],
     [(4.6, 10.2), (8.4, 6.6), (13.0, 2.9)]),
    # a small whole leaf, curled at one edge
    ([(3.0, 13.0), (3.8, 10.0), (5.6, 7.0), (8.4, 4.8), (12.8, 3.0), (12.2, 6.2), (10.8, 9.2), (8.4, 11.4),
      (5.6, 12.6)],
     [(3.4, 12.6), (8.0, 8.0), (12.4, 3.4)]),
]


def leaf_bits():
    """Two bits of leaf, four frames each as it tumbles: sprite 4 * shape + frame."""
    out = []
    for s, (shape, rib) in enumerate(LEAF_SHAPES):
        for f, (k, shade) in enumerate(TUMBLE):
            # Its underside is paler and shows no rib
            out.append(fleck(shape, k, shade + (0.06 if f == 3 else 0.0), LEAF, 200 + s * 10 + f,
                             rib=rib if f != 3 else None))
    return out


# ---- Glowing things ----

def embers():
    """Four frames of a drifting ember, a hot point in a soft halo, dimming and shrinking. Painted warm and
    tinted cooler as it dies."""
    hot = [(255, 248, 226), (255, 214, 140), (255, 158, 74), (240, 96, 40)]
    out = []
    for halo, core in [(4.6, 1.5), (3.9, 1.2), (3.1, 0.95), (2.3, 0.7)]:
        img = blank()
        soft_dot(img, 7.5, 7.5, halo, hot[1:], falloff=1.6, peak=210)
        for y in range(S):
            for x in range(S):
                d = math.hypot(x + 0.5 - 7.5, y + 0.5 - 7.5)
                if d < core + 0.35:
                    over(img, x, y, hot[0] if d < core else hot[1], 255)
        out.append(img)
    return out


def wisps():
    """A marsh wisp breathing: four frames of the same pale green fire, its glow spreading wider in each.
    The game picks the frame from its pulse."""
    glow = [(244, 255, 214), (206, 244, 140), (150, 214, 92), (96, 168, 64)]
    out = []
    for i, halo in enumerate([3.6, 4.5, 5.4, 6.3]):
        img = blank()
        # A haze that leans upward, like a flame's, and a bright heart low in it
        soft_dot(img, 7.5, 7.9, halo, glow[1:], falloff=1.8, peak=170 + 12 * i, squash=1.18)
        soft_dot(img, 7.5, 8.1, 1.9 + 0.2 * i, glow[:2], falloff=1.1, peak=255)
        out.append(img)
    return out


VIOLET = [(255, 255, 255), (226, 204, 255), (176, 124, 250), (118, 70, 214)]
AZURE = [(255, 255, 255), (204, 234, 255), (118, 184, 255), (60, 118, 226)]
ROSE = [(255, 255, 255), (255, 214, 236), (246, 132, 194), (196, 70, 140)]


def glint(colors, size):
    """A four-point sparkle of crystal light, its arms reaching [size] (0 to 3) out: a white heart, the
    arms paling to the crystal's colour and fading at their tips. The biggest also throws short rays."""
    reach = [1.6, 3.0, 4.8, 7.2][size]
    img = blank()
    cx = cy = 7
    soft_dot(img, cx + 0.5, cy + 0.5, 1.6 + size * 0.8, colors[1:], falloff=1.5, peak=70 + size * 20)
    for d in range(-7, 8):
        a = abs(d)
        if a > reach:
            continue
        t = a / (reach + 0.6)
        col = ramp(colors, t * 1.1)
        alpha = 255 * (1 - t) ** 0.7
        over(img, cx + d, cy, col, alpha)
        over(img, cx, cy + d, col, alpha)
    if size >= 2:
        for ox, oy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
            over(img, cx + ox, cy + oy, colors[1], 150 if size == 3 else 90)
            if size == 3:
                over(img, cx + 2 * ox, cy + 2 * oy, colors[2], 90)
    img.putpixel((cx, cy), colors[0] + (255,))
    return img


def glints():
    """Three crystal colours, four sizes each: sprite 4 * colour + size."""
    return [glint(colors, size) for colors in (VIOLET, AZURE, ROSE) for size in range(4)]


# ---- Small floating things ----

CREAM = [(255, 255, 250), (250, 246, 230), (236, 228, 204)]
SEED = [(214, 176, 92), (176, 136, 64)]


def pollen():
    """Three bits of fluff off the meadow: a dandelion seed's down seen end on, a soft puff, and a seed
    trailing its tuft. Cream and gold, tinted whiter over the heartland and golden on the steppe."""
    out = []
    # The down: fine filaments all round a gold seed
    img = blank()
    for i in range(9):
        ang = i / 9 * math.tau + 0.3
        cov = stroke([(8 + math.cos(ang) * 1.2, 8 + math.sin(ang) * 1.2),
                      (8 + math.cos(ang) * 5.6, 8 + math.sin(ang) * 5.6)], 0.7)
        for y in range(S):
            for x in range(S):
                if cov[y][x] > 0.05:
                    d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 5.6
                    over(img, x, y, ramp(CREAM, d), min(1.0, cov[y][x] * 1.6) * 230 * (1 - d * 0.55))
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):
        over(img, x, y, SEED[0] if (x, y) == (7, 7) else SEED[1], 255)
    out.append(img)
    # The puff
    img = blank()
    soft_dot(img, 8, 8, 4.2, CREAM, falloff=0.9, peak=230)
    over(img, 7, 6, (255, 255, 255), 200)
    out.append(img)
    # The seed and its tuft, fanning up
    img = blank()
    for i in range(7):
        ang = math.radians(-90 + (i - 3) * 19)
        cov = stroke([(8, 9.6), (8 + math.cos(ang) * 5.8, 9.6 + math.sin(ang) * 5.8)], 0.7)
        for y in range(S):
            for x in range(S):
                if cov[y][x] > 0.05:
                    d = math.hypot(x + 0.5 - 8, y + 0.5 - 9.6) / 5.8
                    over(img, x, y, ramp(CREAM, d), min(1.0, cov[y][x] * 1.6) * 225 * (1 - d * 0.5))
    for x, y, c in ((7, 10, 0), (8, 10, 0), (7, 11, 1), (8, 11, 0), (8, 12, 1)):
        over(img, x, y, SEED[c], 255)
    out.append(img)
    return out


SPORE = [(255, 236, 250), (240, 176, 232), (196, 120, 214), (150, 86, 186)]


def spores():
    """Three spores off the mushroom isles: a round one in a faint haze, a pair stuck together, and a speck
    in a ring of haze. Violet and pink, glowing a little."""
    out = []
    img = blank()
    soft_dot(img, 7.5, 7.5, 4.6, SPORE[1:], falloff=2.0, peak=110)
    soft_dot(img, 7.5, 7.5, 2.6, SPORE[:3], falloff=0.35, peak=255)
    out.append(img)
    img = blank()
    soft_dot(img, 8, 8, 4.4, SPORE[1:], falloff=2.0, peak=90)
    soft_dot(img, 6.6, 9.0, 1.9, SPORE[:3], falloff=0.35, peak=255)
    soft_dot(img, 9.2, 6.6, 1.6, SPORE[:3], falloff=0.35, peak=255)
    out.append(img)
    img = blank()
    soft_dot(img, 7.5, 7.5, 4.0, SPORE[1:], falloff=1.4, peak=80)
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):
        over(img, x, y, SPORE[0] if (x, y) == (7, 7) else SPORE[1], 255)
    out.append(img)
    return out


SAND = [(170, 128, 78), (204, 166, 108), (228, 198, 142), (244, 224, 180)]


def dust():
    """Three wisps of blown sand: a scatter of grains, a soft puff, and a puff trailing grains."""
    out = []
    rng = random.Random(31)

    def grains(img, n, spread, seed_rng):
        for _ in range(n):
            x = 8 + int(round(seed_rng.uniform(-spread, spread)))
            y = 8 + int(round(seed_rng.uniform(-spread * 0.7, spread * 0.7)))
            over(img, x, y, SAND[2], 255)
            if seed_rng.random() < 0.6:
                over(img, x + 1, y, SAND[1], 230)
            if seed_rng.random() < 0.4:
                over(img, x, y + 1, SAND[0], 210)
            if seed_rng.random() < 0.3:
                over(img, x - 1, y - 1, SAND[3], 160)

    img = blank()
    grains(img, 5, 4.2, rng)
    out.append(img)
    img = blank()
    soft_dot(img, 8, 8.4, 4.8, SAND[3:0:-1], falloff=1.3, peak=150, squash=0.72)
    out.append(img)
    img = blank()
    soft_dot(img, 9.0, 8.4, 3.8, SAND[3:0:-1], falloff=1.3, peak=130, squash=0.75)
    grains(img, 3, 3.6, rng)
    out.append(img)
    return out


def snow():
    """Three bits of blown snow: a soft grain, a pair of grains, and a tiny six-armed crystal."""
    white = [(255, 255, 255), (238, 246, 255), (204, 222, 246)]
    out = []
    img = blank()
    soft_dot(img, 8, 8, 3.3, white, falloff=0.55, peak=255)
    out.append(img)
    img = blank()
    soft_dot(img, 6.6, 9.0, 2.5, white, falloff=0.55, peak=255)
    soft_dot(img, 10.0, 6.4, 1.9, white, falloff=0.6, peak=235)
    out.append(img)
    img = blank()
    for ang in (0, 60, 120):
        a = math.radians(ang)
        cov = stroke([(8 - math.cos(a) * 4.2, 8 - math.sin(a) * 4.2), (8 + math.cos(a) * 4.2, 8 + math.sin(a) * 4.2)], 1.0)
        for y in range(S):
            for x in range(S):
                if cov[y][x] > 0.2:
                    d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / 4.4
                    over(img, x, y, ramp(white, d), 255 * min(1.0, cov[y][x] * 1.5) * (1 - d * 0.35))
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):
        over(img, x, y, white[0], 255)
    out.append(img)
    return out


def gnats():
    """A gnat with its wings up, then down: a dark speck with a glassy blur either side."""
    body = (36, 31, 26)
    shine = (86, 76, 64)
    wing = (214, 224, 236)
    out = []
    for frame in range(2):
        img = blank()
        if frame == 0:
            wings = [(5, 5, 130), (6, 5, 150), (5, 6, 150), (6, 6, 120), (10, 5, 130), (9, 5, 150), (10, 6, 150),
                     (9, 6, 120), (4, 4, 70), (11, 4, 70)]
        else:
            wings = [(5, 8, 110), (6, 8, 130), (4, 9, 80), (5, 9, 100), (10, 8, 110), (9, 8, 130), (11, 9, 80),
                     (10, 9, 100)]
        for x, y, a in wings:
            over(img, x, y, wing, a)
        for x, y in ((7, 6), (8, 6), (7, 7), (8, 7), (7, 8), (8, 8), (7, 9), (8, 9), (8, 10)):
            over(img, x, y, body, 255)
        over(img, 7, 6, shine, 255)
        out.append(img)
    return out


def motes():
    """Two specks of cave dust: a soft one and a sharp one in a faint haze. Pale, and tinted in the game."""
    grey = [(236, 232, 226), (206, 200, 192), (170, 164, 156)]
    out = []
    img = blank()
    soft_dot(img, 8, 8, 2.6, grey, falloff=0.8, peak=235)
    out.append(img)
    img = blank()
    soft_dot(img, 7.5, 7.5, 3.0, grey[1:], falloff=1.6, peak=90)
    over(img, 7, 7, grey[0], 255)
    out.append(img)
    return out


# ---- Writing it all out ----

# Each particle: its sprites, and the tints the game gives it (shown in the preview)
PARTICLES = [
    ("ash_flake", ash_flakes, [(0.95, 0.94, 0.92), (0.8, 0.79, 0.77)]),
    ("ash_ember", embers, [(1.0, 0.92, 0.82), (1.0, 0.55, 0.35)]),
    ("marsh_wisp", wisps, [(0.86, 1.0, 0.6), (0.7, 1.0, 0.5)]),
    ("marsh_gnat", gnats, [(1.0, 1.0, 1.0)]),
    ("snow_flurry", snow, [(0.95, 0.97, 1.0)]),
    ("crystal_glint", glints, [(1.0, 1.0, 1.0)]),
    ("pollen", pollen, [(1.0, 0.98, 0.9), (1.0, 0.86, 0.56)]),
    ("mushroom_spore", spores, [(1.0, 0.86, 0.96), (0.86, 0.78, 1.0)]),
    ("sand_dust", dust, [(1.0, 0.97, 0.92)]),
    ("cave_mote", motes, [(0.86, 0.83, 0.78), (0.62, 0.78, 0.95)]),
    ("leaf_bit", leaf_bits, [(0.42, 0.56, 0.26), (0.64, 0.46, 0.24), (0.86, 0.87, 0.82)]),
]


def tinted(img, tint):
    px = img.load()
    out = img.copy()
    po = out.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            po[x, y] = (int(r * tint[0]), int(g * tint[1]), int(b * tint[2]), a)
    return out


def preview(sets, path):
    """The contact sheet: each particle's sprites big on a night sky and a day sky in each of its tints,
    and then at small sizes, smoothed as the game draws them far off."""
    big = 8
    night, day = (24, 28, 40, 255), (158, 182, 206, 255)
    rows = []
    for name, sprites, tints in sets:
        for tint in tints:
            rows.append((name, [tinted(s, tint) for s in sprites]))
    cols = max(len(r[1]) for r in rows)
    cell = S * big + 6
    small_w = 3 * (48 + 24 + 12 + 16)
    w = 130 + cols * cell * 2 + 20 + small_w
    h = len(rows) * (cell + 4) + 10
    sheet = Image.new("RGBA", (w, h), (60, 62, 70, 255))
    draw = ImageDraw.Draw(sheet)
    for r, (name, sprites) in enumerate(rows):
        y = 6 + r * (cell + 4)
        draw.text((6, y + cell // 2 - 6), name, fill=(230, 230, 230, 255))
        for half, bg in enumerate((night, day)):
            x0 = 130 + half * (cols * cell + 10)
            for i, s in enumerate(sprites):
                tile = Image.new("RGBA", (cell, cell), bg)
                up = s.resize((S * big, S * big), Image.NEAREST)
                tile.alpha_composite(up, (3, 3))
                sheet.paste(tile, (x0 + i * cell, y))
        # At game sizes: about 48, 24 and 12 pixels across, the first sprite of each row
        x0 = 130 + 2 * (cols * cell + 10)
        for half, bg in enumerate((night, day)):
            tile = Image.new("RGBA", (small_w // 2, cell), bg)
            x = 6
            for size in (48, 24, 12):
                s = sprites[0].resize((size, size), Image.BILINEAR)
                tile.alpha_composite(s, (x, (cell - size) // 2))
                x += size + 8
            sheet.paste(tile, (x0 + half * (small_w // 2), y))
    sheet.save(path)


def main():
    assets = sys.argv[1]
    preview_path = sys.argv[2] if len(sys.argv) > 2 else None
    tex = os.path.join(assets, "textures/particle")
    defs = os.path.join(assets, "particles")
    os.makedirs(tex, exist_ok=True)
    os.makedirs(defs, exist_ok=True)
    sets = []
    count = 0
    for name, paint, tints in PARTICLES:
        sprites = paint()
        for i, img in enumerate(sprites):
            img.save(os.path.join(tex, f"{name}_{i}.png"))
        with open(os.path.join(defs, f"{name}.json"), "w") as f:
            json.dump({"textures": [f"guildmark:{name}_{i}" for i in range(len(sprites))]}, f, indent=2)
            f.write("\n")
        sets.append((name, sprites, tints))
        count += len(sprites)
    if preview_path:
        preview(sets, preview_path)
    print("region particles: %d particles, %d sprites" % (len(PARTICLES), count))


if __name__ == "__main__":
    main()
