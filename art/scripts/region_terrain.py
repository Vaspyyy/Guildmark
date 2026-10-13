"""Ground cover for the wild lands: what grows, lies and hangs in the marsh, the ashen highlands, the frozen
peaks and the crystal deeps (region/terrain in the mod places it as new land is made).

    python3 -B art/scripts/region_terrain.py src/main/resources/assets/guildmark [preview-dir]

- The marsh: bog rot (a carpet of rotting leaves, peat and sickly moss), bog lanterns (crook-stalked fungi, each
  with a glowing pod hung from its hook) and cattails (a two-block reed that stands in the shallows).
- The ashen highlands: ash (layers, like snow), charred logs (the burnt trees) and ember vents (cracked rock that
  smokes, its cracks glowing and slowly breathing).
- The frozen peaks: icicles (they hang like pointed dripstone, and fall like it) and hoarfrost (feathery frost
  on rock faces, like glow lichen).
- The crystal deeps: glimmer clusters in three sizes, glimmerstone (deepslate veined with crystal) and glimmer
  crystal (the spires).

Every texture is 32x32, twice vanilla's. What glows (the lantern pods, the vent cracks, the crystal veins and
cores) is drawn again on a cutout overlay that the block models give full brightness (light_emission), so it
shines at night without lighting the land around it. Writes the textures, block and item models, blockstates
and item definitions, and, given a preview dir, a sheet of every texture. All randomness is seeded, so a rerun
gives the same files.
"""
import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw

S = 32
# Supersampling for shapes and strokes, so their edges get a fair coverage
SS = 8
NS = "guildmark"


# ---- Noise and colour ----

def tile_noise(cells, seed, size=S, cells_y=None):
    """Smooth value noise in 0..1 on a [cells] lattice (across) by [cells_y] (down) that wraps at the edges,
    so the texture tiles."""
    cy = cells_y or cells
    g = np.random.default_rng(seed).random((cy, cells))

    def axis(n):
        u = np.arange(size) * n / size
        i0 = np.floor(u).astype(int) % n
        f = u - np.floor(u)
        return i0, (i0 + 1) % n, f * f * (3 - 2 * f)

    x0, x1, fx = axis(cells)
    y0, y1, fy = axis(cy)
    fx, fy = fx[None, :], fy[:, None]
    top = g[np.ix_(y0, x0)] * (1 - fx) + g[np.ix_(y0, x1)] * fx
    bottom = g[np.ix_(y1, x0)] * (1 - fx) + g[np.ix_(y1, x1)] * fx
    return top * (1 - fy) + bottom * fy


def fbm(cells, seed, octaves=3, gain=0.5, size=S, cells_y=None):
    """Layered tile noise, finer each octave, in 0..1."""
    out = np.zeros((size, size))
    total = 0.0
    amp = 1.0
    for o in range(octaves):
        c = cells * 2 ** o
        out += amp * tile_noise(min(c, size), seed + 101 * o, size, None if cells_y is None else min(cells_y * 2 ** o, size))
        total += amp
        amp *= gain
    return out / total


def ramp(stops, t):
    """Colours along a list of RGB stops, for t (an array) in 0..1."""
    stops = np.asarray(stops, float)
    t = np.clip(np.asarray(t, float), 0.0, 0.99999) * (len(stops) - 1)
    i = np.floor(t).astype(int)
    f = (t - i)[..., None]
    return stops[i] * (1 - f) + stops[i + 1] * f


def stretch(a):
    """Rescales an array to span 0..1."""
    lo, hi = float(a.min()), float(a.max())
    return (a - lo) / (hi - lo) if hi > lo else a * 0


def image(rgb, alpha=None):
    """An RGBA image from float RGB (0..255) and alpha (0..1, or all opaque)."""
    h, w = rgb.shape[:2]
    a = np.ones((h, w)) if alpha is None else alpha
    px = np.dstack([np.clip(rgb, 0, 255), np.clip(a, 0, 1) * 255]).round().astype(np.uint8)
    return Image.fromarray(px, "RGBA")


def cover(draw_fn, size=S, height=None):
    """How much of each pixel a shape covers, 0..1: [draw_fn](draw, k) draws it in white on a canvas [k]
    times larger, in pixel units times k. The canvas is [size] across and [height] (or [size]) down."""
    h = height or size
    big = Image.new("L", (size * SS, h * SS), 0)
    draw_fn(ImageDraw.Draw(big), SS)
    return np.asarray(big.resize((size, h), Image.BOX), float) / 255.0


def ribbon(points, widths):
    """A polygon following [points] (x, y), [widths] wide at each: a blade, a stalk, a crack."""
    left, right = [], []
    for i, (x, y) in enumerate(points):
        a = points[max(i - 1, 0)]
        b = points[min(i + 1, len(points) - 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / n, dx / n
        w = widths[i] / 2
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
    return left + right[::-1]


def wrapped(draw_fn):
    """[draw_fn] drawn nine times, a texture's width apart, so whatever crosses an edge comes back on the far
    side and the texture tiles."""
    def all_nine(d, k):
        for ox in (-S, 0, S):
            for oy in (-S, 0, S):
                draw_fn(d, k, ox, oy)
    return all_nine


def lay(rgb, alpha, colour, a):
    """Paints [colour] (RGB, or an HxWx3 array) over rgb/alpha where [a] (0..1, HxW) says, in place."""
    a = np.clip(a, 0, 1)[..., None]
    c = np.asarray(colour, float)
    rgb[:] = rgb * (1 - a) + c * a
    if alpha is not None:
        alpha[:] = np.maximum(alpha, a[..., 0])


def cutout(a, at=0.5):
    """Alpha made all or nothing, as the game's cutout layer wants it."""
    return (a >= at).astype(float)


def neighbours(mask, wrap=True):
    """How many of each pixel's four neighbours are set."""
    m = mask.astype(int)
    if wrap:
        return np.roll(m, 1, 0) + np.roll(m, -1, 0) + np.roll(m, 1, 1) + np.roll(m, -1, 1)
    p = np.pad(m, 1)
    return p[:-2, 1:-1] + p[2:, 1:-1] + p[1:-1, :-2] + p[1:-1, 2:]


def left_edge(mask):
    """Pixels of [mask] with nothing of it to their left (any shape of array, nothing wrapping)."""
    return mask & ~np.pad(mask, ((0, 0), (1, 0)))[:, :-1]


def right_edge(mask):
    return mask & ~np.pad(mask, ((0, 0), (0, 1)))[:, 1:]


def edge_light(mask, dx, dy, wrap=True):
    """Pixels of [mask] whose neighbour at (-dx, -dy) is outside it: the side a light from that way catches."""
    m = mask.astype(bool)
    if wrap:
        other = np.roll(np.roll(m, dy, 0), dx, 1)
    else:
        p = np.pad(m, 1)
        other = p[1 - dy:S + 1 - dy, 1 - dx:S + 1 - dx]
    return m & ~other


# ---- The ashen highlands ----

ASH = [(58, 55, 53), (86, 82, 79), (114, 110, 106), (141, 137, 132), (168, 164, 159), (194, 191, 186)]


def ash():
    """Fallen ash: soft grey powder, combed into faint ripples by the wind, a few bits of charcoal through it
    and paler flakes lying on top."""
    n = fbm(4, 11, 3) * 0.7 + fbm(16, 12, 1) * 0.3
    # Ripples across it, bent by the noise (whole waves across the tile, so it still tiles)
    y, x = np.mgrid[0:S, 0:S]
    warp = (fbm(2, 13, 2) - 0.5) * 9
    ripple = np.sin(2 * np.pi * (x * 1 + y * 2 + warp) / S * 2)
    t = 0.24 + stretch(n) * 0.34 + ripple * 0.045
    rgb = ramp(ASH, t)
    # A cool cast in the hollows, a warm one on the rises
    rgb += ((n - 0.5) * 10)[..., None] * np.array([1.0, 0.5, -0.6])
    # Each ripple's crest catches the light, its lee falls in shade
    crest = np.sin(2 * np.pi * (x * 1 + y * 2 + warp + 1.2) / S * 2) - ripple
    rgb += (crest * 4)[..., None]
    rng = np.random.default_rng(14)
    r = rng.random((S, S))
    # Charcoal bits, one or two pixels each
    for _ in range(7):
        cx, cy = rng.integers(0, S, 2)
        for i in range(rng.integers(1, 3)):
            rgb[cy % S, (cx + i) % S] = ramp(ASH, 0.02 + rng.random() * 0.1)
    flake = (r > 0.985) & (n > 0.4)
    rgb[flake] = ramp(ASH, 0.8 + rng.random(flake.sum()) * 0.2)
    return image(rgb)


CHAR = [(12, 11, 11), (24, 22, 21), (36, 33, 32), (50, 47, 46), (66, 63, 64), (88, 87, 92)]


def charred_log():
    """The side of a burnt trunk: the bark's ridges burnt black and checked across by cracks into scales, each
    scale catching a dull silvery sheen along its upper edge, a little brown left in the deepest char, and ash
    caught in some of the cracks."""
    rng = np.random.default_rng(21)
    ridges = 5
    y, x = np.mgrid[0:S, 0:S]
    # The ridges wander a little from side to side down the trunk
    wander = (tile_noise(4, 22)[:, :1] - 0.5) * 5 + (tile_noise(8, 23)[:, 1:2] - 0.5) * 2
    phase = (x + wander) * ridges / S
    which = np.floor(phase).astype(int) % ridges
    u = phase - np.floor(phase)
    furrow = u < 0.2
    face = np.clip((u - 0.2) / 0.8, 0, 1)
    n = fbm(8, 24, 2)
    t = 0.6 - face * 0.32 + (n - 0.5) * 0.18
    rgb = ramp(CHAR, t)
    rgb[furrow] = ramp(CHAR, 0.02 + u[furrow] * 0.4)
    # A little brown left in the char here and there
    brown = np.clip(fbm(4, 25, 2) - 0.55, 0, 1) * 60
    rgb += brown[..., None] * np.array([1.0, 0.55, 0.2]) * (~furrow)[..., None]
    # Checked across into long scales: each ridge cracked every so often down its length
    for r in range(ridges):
        yy = int(rng.integers(0, 9))
        while yy < S:
            row = yy % S
            on = (which[row] == r) & ~furrow[row]
            rgb[row, on] = ramp(CHAR, 0.03 + rng.random(on.sum()) * 0.08)
            # The scale under the crack catches the light along its top, its left end most
            below = (row + 1) % S
            lit = (which[below] == r) & ~furrow[below] & (u[below] < 0.6)
            rgb[below, lit] = np.minimum(rgb[below, lit] * 1.15 + 9, 130)
            if rng.random() < 0.2:
                ashy = on & (rng.random(S) < 0.5)
                rgb[row, ashy] = ramp(ASH, 0.35 + rng.random(ashy.sum()) * 0.2)
            yy += int(rng.integers(6, 12))
    return image(rgb)


def charred_log_top():
    """The cut end of a burnt trunk: blackened rings round a split heart, the bark round the edge char."""
    y, x = np.mgrid[0:S, 0:S] + 0.5
    cx = cy = S / 2
    r = np.hypot(x - cx, y - cy)
    ang = np.arctan2(y - cy, x - cx)
    n = fbm(8, 31, 2)
    rings = 0.5 + 0.5 * np.sin(r * 1.35 + n * 2.5)
    wood = [(30, 24, 20), (46, 37, 30), (62, 50, 40), (78, 64, 52)]
    rgb = ramp(wood, 0.25 + rings * 0.5 + (n - 0.5) * 0.3)
    # Darker toward the rim, where it burnt longest
    rgb *= np.clip(1.15 - r / 22, 0.55, 1.0)[..., None]
    # Splits from the heart outward
    rng = np.random.default_rng(32)
    for a0 in rng.uniform(-np.pi, np.pi, 5):
        a = a0 + (fbm(4, int(a0 * 1000) % 997, 1) - 0.5) * 0.4
        d = np.abs(np.angle(np.exp(1j * (ang - a))))
        split = (d * r < 0.7) & (r > 2.5) & (r < 13.5)
        rgb[split] = ramp(CHAR, 0.05 + rng.random(split.sum()) * 0.08)
    # The bark ring: char scales, as on the side
    bark = (np.maximum(np.abs(x - cx), np.abs(y - cy)) > 13.5) | (r > 15.2)
    side = np.asarray(charred_log())[..., :3].astype(float)
    rgb[bark] = side[bark]
    heart = r < 1.6
    rgb[heart] = CHAR[0]
    return image(rgb)


ROCK = [(20, 17, 17), (31, 27, 26), (43, 38, 36), (56, 50, 47), (70, 63, 59), (86, 78, 73)]
# The glow in a vent's cracks, from the cooler edges to the hottest middles
EMBER = [(150, 40, 16), (214, 78, 24), (250, 128, 40), (255, 182, 82), (255, 228, 150)]
VENT_FRAMES = 8


def crack_network(seed, starts, length, branch, wrap=True, size=S, avoid=None):
    """Cracks wandering through rock: returns how hot each crack pixel is (0 outside the cracks, toward 1 in
    the widest, oldest middles) and how far along its crack each lies, for the glow's slow breathing."""
    rng = np.random.default_rng(seed)
    heat = np.zeros((size, size))
    along = np.zeros((size, size))
    walkers = [(float(x), float(y), float(rng.uniform(0, 2 * np.pi)), length, 1.0, float(rng.uniform(0, 30)))
               for x, y in starts]
    while walkers:
        x, y, a, left, w, s = walkers.pop()
        for _ in range(int(left)):
            px, py = int(round(x)) % size, int(round(y)) % size
            if not wrap and not (0 <= round(x) < size and 0 <= round(y) < size):
                break
            if avoid is not None and avoid[py, px]:
                break
            heat[py, px] = max(heat[py, px], w)
            along[py, px] = s
            if w > 0.75:
                # The widest stretches open a pixel to one side
                ox, oy = (1, 0) if abs(math.sin(a)) > abs(math.cos(a)) else (0, 1)
                qx, qy = (px + ox) % size, (py + oy) % size
                heat[qy, qx] = max(heat[qy, qx], w * 0.8)
                along[qy, qx] = s
            a += rng.normal(0, 0.45)
            x += math.cos(a)
            y += math.sin(a)
            s += 1
            w = max(0.25, w - rng.uniform(0.0, 0.06))
            left -= 1
            if rng.random() < branch and left > 4:
                walkers.append((x, y, a + rng.choice([-1, 1]) * rng.uniform(0.6, 1.3), left * 0.6, w * 0.7, s))
    return heat, along


def dilate(mask, steps=1, wrap=True):
    m = mask.copy()
    for _ in range(steps):
        if wrap:
            m = m | np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1)
        else:
            p = np.pad(m, 1)
            m = m | p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:]
    return m


def vent_glow(heat, along, frames=VENT_FRAMES):
    """The cracks' glow as an animation strip: each frame the heat swells and ebbs, a slow wave running along
    every crack, so the rock seems to breathe."""
    mask = heat > 0
    strip = []
    for f in range(frames):
        phase = 2 * np.pi * f / frames
        pulse = 0.78 + 0.22 * np.sin(phase - along * 0.35)
        t = np.clip(heat * pulse, 0, 1)
        strip.append(image(ramp(EMBER, t), mask.astype(float)))
    out = Image.new("RGBA", (S, S * frames))
    for i, fr in enumerate(strip):
        out.paste(fr, (0, i * S))
    return out


def vent_rock(seed):
    """Dark volcanic rock, rough-grained and roughly layered."""
    n = fbm(4, seed, 3) * 0.6 + fbm(16, seed + 1, 1) * 0.4
    y = np.mgrid[0:S, 0:S][0]
    strata = np.sin(2 * np.pi * (y + (tile_noise(4, seed + 2) - 0.5) * 6) / S * 3) * 0.06
    rgb = ramp(ROCK, 0.12 + stretch(n) * 0.55 + strata)
    rng = np.random.default_rng(seed + 3)
    r = rng.random((S, S))
    rgb[r < 0.04] *= 0.7
    rgb[r > 0.97] = rgb[r > 0.97] * 1.25 + 6
    return rgb


def heat_rock(rgb, heat, wrap=True):
    """Rock beside a crack scorched dull red by the heat, the cracks themselves dark (the glow overlay shows
    what's in them)."""
    mask = heat > 0
    near = dilate(mask, 1, wrap) & ~mask
    nearer = dilate(mask, 2, wrap) & ~mask & ~near
    rgb[near] = rgb[near] * 0.55 + np.array([110, 40, 22]) * 0.45
    rgb[nearer] = rgb[nearer] * 0.8 + np.array([90, 36, 22]) * 0.2
    rgb[mask] = (34, 12, 8)


def ember_vent():
    """The side of an ember vent: dark, layered rock split by cracks that glow from the heat beneath."""
    rng = np.random.default_rng(41)
    rgb = vent_rock(42)
    # One crack starting in each quarter, so they spread over the whole face
    starts = [(qx * 16 + rng.uniform(2, 14), qy * 16 + rng.uniform(2, 14)) for qx in (0, 1) for qy in (0, 1)]
    heat, along = crack_network(43, starts, 18, 0.12)
    heat_rock(rgb, heat)
    return image(rgb), vent_glow(heat, along)


def ember_vent_top():
    """The top of an ember vent: a crater ringed with a raised lip, its throat glowing, and cracks running
    out from it across the rock."""
    rng = np.random.default_rng(51)
    rgb = vent_rock(52)
    y, x = np.mgrid[0:S, 0:S] + 0.5
    cx, cy = S / 2 + 0.3, S / 2 - 0.4
    r = np.hypot(x - cx, (y - cy) * 1.05) + (fbm(4, 53, 2) - 0.5) * 2.2
    # The lip, lit on its far side (the light comes from the top of the texture), shaded on the near
    lip = (r > 6.5) & (r < 9.5)
    face = np.cos(np.arctan2(y - cy, x - cx) + np.pi / 2)
    rgb[lip] = ramp(ROCK, 0.55 + face[lip] * 0.25)
    bowl = r <= 6.5
    rgb[bowl] = ramp(ROCK, np.clip(0.05 + (r[bowl] / 6.5) * 0.3 - face[bowl] * 0.1, 0, 1))
    # Cracks out from the crater
    angles = np.linspace(0, 2 * np.pi, 6, endpoint=False) + rng.uniform(0, 1)
    starts = [(cx + math.cos(a) * 9, cy + math.sin(a) * 9) for a in angles + rng.uniform(-0.3, 0.3, 6)]
    heat, along = crack_network(54, starts, 14, 0.1, wrap=False, avoid=None)
    heat[r < 9.5] = 0
    heat_rock(rgb, heat, wrap=False)
    # The glowing throat: a ragged pool, hottest in the middle, with crusted lumps going dark on it
    lumps = fbm(8, 55, 2)
    rt = r + (lumps - 0.5) * 2.0
    throat = rt < 4.4
    heat = heat.copy()
    heat[throat] = np.clip(1.0 - rt[throat] / 5.2 + (lumps[throat] - 0.5) * 0.5, 0.3, 1.0)
    crust = throat & (lumps > 0.68) & (rt > 1.6)
    heat[crust] = 0
    rgb[crust] = ramp(ROCK, 0.1 + lumps[crust] * 0.2)
    along[throat] = rt[throat] * 3
    rgb[throat & ~crust] = (40, 14, 8)
    return image(rgb), vent_glow(heat, along)


# ---- The marsh ----

PEAT = [(24, 20, 15), (35, 29, 20), (47, 39, 27), (60, 49, 33), (73, 60, 40)]
LEAF_ROT = [(108, 64, 28), (82, 54, 28), (92, 86, 38), (126, 100, 44), (60, 44, 26), (98, 72, 36)]
SICK_MOSS = [(44, 60, 26), (58, 78, 32), (76, 98, 40), (96, 118, 50)]


def leaf_shape(d, k, ox, oy, cx, cy, length, width, angle):
    """A rotting leaf: a pointed oval, as a polygon on the supersampled canvas."""
    pts = []
    for i in range(16):
        t = i / 15 * np.pi
        # Pointed at both ends: an oval pinched toward the tips
        r = width / 2 * math.sin(t) ** 0.8
        px = (t / np.pi - 0.5) * length
        for sgn in (1,) if i < 16 else ():
            pts.append((px, r))
    pts = pts + [(px, -r) for px, r in reversed(pts)]
    ca, sa = math.cos(angle), math.sin(angle)
    d.polygon([((cx + ox + px * ca - py * sa) * k, (cy + oy + px * sa + py * ca) * k) for px, py in pts], fill=255)


def bog_rot():
    """The marsh floor gone to rot: dark peat, half-sunk leaves rotting in it (rust, umber, dead olive), patches
    of sickly moss, a slick of bog-water here and there and pale fungus pricking up through it all."""
    rng = np.random.default_rng(61)
    n = fbm(4, 62, 3)
    rgb = ramp(PEAT, 0.15 + stretch(n) * 0.7)
    grain = np.random.default_rng(63).random((S, S))
    rgb[grain < 0.08] *= 0.75
    # Leaves, each lit along one edge and darker down its middle
    for i in range(16):
        cx, cy = rng.uniform(0, S, 2)
        length, width = rng.uniform(6, 10), rng.uniform(2.6, 4.2)
        angle = rng.uniform(0, np.pi)
        cov = cover(wrapped(lambda d, k, ox, oy: leaf_shape(d, k, ox, oy, cx, cy, length, width, angle)))
        base = np.array(LEAF_ROT[i % len(LEAF_ROT)], float) * rng.uniform(0.8, 1.05)
        y, x = np.mgrid[0:S, 0:S] + 0.5
        # Across the leaf: which side of its middle each pixel is on
        dx = (x - cx + S / 2) % S - S / 2
        dy = (y - cy + S / 2) % S - S / 2
        side = -dx * math.sin(angle) + dy * math.cos(angle)
        along = dx * math.cos(angle) + dy * math.sin(angle)
        shade = np.clip(1.0 - side * 0.12, 0.7, 1.25)[..., None]
        col = base * shade
        rib = (np.abs(side) < 0.55) & (np.abs(along) < length / 2 - 1)
        col[rib] *= 0.72
        # Holes rotted through older leaves
        holes = (fbm(8, 64 + i, 1) > 0.72) if i % 3 == 0 else np.zeros((S, S), bool)
        a = cutout(cov) * ~holes
        lay(rgb, None, col, a * 0.9)
    # Sickly moss in patches, speckled
    moss = fbm(4, 65, 2)
    patch = moss > 0.6
    m = ramp(SICK_MOSS, np.clip((moss - 0.6) * 3 + grain * 0.4, 0, 1))
    rgb[patch] = m[patch]
    # A slick of bog-water: near-black, a sky-glint on its edge
    slick = fbm(4, 66, 2) > 0.74
    rgb[slick] = rgb[slick] * 0.35 + np.array([16, 22, 16]) * 0.65
    glint = slick & ~np.roll(slick, 1, 0)
    rgb[glint] = (92, 108, 92)
    # Pale fungus pricking up: a light dot with a dark speck under it
    for _ in range(6):
        fx, fy = rng.integers(0, S, 2)
        rgb[fy, fx] = (190, 184, 146)
        rgb[(fy + 1) % S, fx] = (60, 52, 36)
        if rng.random() < 0.5:
            rgb[fy, (fx + 1) % S] = (172, 166, 128)
    return image(rgb)


LANTERN_STALK = [(58, 62, 38), (92, 96, 60), (120, 124, 80), (154, 156, 108)]
POD_SKIN = [(36, 42, 20), (54, 64, 30), (74, 86, 40)]
POD_GLOW = [(110, 160, 54), (170, 216, 92), (216, 244, 146), (244, 255, 204)]


def crook(bx, top, hook, drop, wobble_seed):
    """A crook-stalk's path: up from the ground at [bx], leaning a little, then over in a hook that comes down
    at x [hook], [drop] pixels below the top of its arc. Returns the points and where the pod hangs from."""
    rng = np.random.default_rng(wobble_seed)
    pts = []
    rise = 31.6 - top
    side = 1 if hook > bx else -1
    span = abs(hook - bx)
    # The rise, bowing slightly
    bow = rng.uniform(-1.0, 1.0)
    for j in range(14):
        t = j / 13
        pts.append((bx + bow * math.sin(np.pi * t) + side * span * 0.15 * t ** 2, 31.6 - rise * t))
    # The hook: a half-ellipse over to [hook]
    sx, sy = pts[-1]
    rad = (hook - sx) / 2
    for j in range(1, 13):
        ang = np.pi * j / 12
        pts.append((sx + rad - rad * math.cos(ang), sy - abs(rad) * 0.7 * math.sin(ang)))
    ex, ey = pts[-1]
    pts.append((ex, ey + drop))
    return pts, (ex, ey + drop)


def bog_lantern():
    """A bog lantern: a pale fungal stalk that rises and crooks over like a shepherd's crook, with a pod hung
    from its hook that glows a sickly green-gold through its skin, a bead of glow under it, and a smaller one
    beside it. Seen from the side, as both planes of a cross model show it. Returns the plant and its glow."""
    rgb = np.zeros((S, S, 3))
    alpha = np.zeros((S, S))
    glow_rgb = np.zeros((S, S, 3))
    glow_a = np.zeros((S, S))
    y, x = np.mgrid[0:S, 0:S] + 0.5
    # (base x, top of the rise, hook x, drop to the pod, pod width, pod height, stalk width)
    for bx, top, hook, drop, pw, ph, sw in ((12.0, 6.0, 21.0, 1.5, 7.0, 9.5, 2.3), (8.0, 18.0, 3.5, 1.2, 4.4, 5.6, 1.6)):
        pts, (px0, py0) = crook(bx, top, hook, drop, int(bx * 7))
        widths = [sw * (1.0 - 0.45 * j / len(pts)) for j in range(len(pts))]
        stalk = cutout(cover(lambda d, k: d.polygon([(a * k, b * k) for a, b in ribbon(pts, widths)], fill=255)))
        sm = stalk > 0
        col = np.zeros((S, S, 3)) + LANTERN_STALK[2]
        col[right_edge(sm)] = LANTERN_STALK[0]
        col[left_edge(sm)] = LANTERN_STALK[3]
        # Faint darker spots up the stalk
        spots = sm & (fbm(16, int(bx), 1) > 0.66)
        col[spots] = LANTERN_STALK[1]
        lay(rgb, alpha, col, stalk)
        # The pod: a dark cap where it hangs, a glowing body, a dark tip with a bead of glow under it
        pcx = px0
        pcy = py0 + ph * 0.52
        body = cutout(cover(lambda d, k: d.ellipse([(pcx - pw / 2) * k, (py0 + ph * 0.08) * k, (pcx + pw / 2) * k, (py0 + ph) * k], fill=255))) > 0
        cap = cutout(cover(lambda d, k: d.ellipse([(pcx - pw * 0.32) * k, (py0 - 0.4) * k, (pcx + pw * 0.32) * k, (py0 + ph * 0.3) * k], fill=255))) > 0
        dist = np.hypot((x - pcx) / (pw / 2), (y - (pcy + ph * 0.08)) / (ph * 0.5))
        mott = fbm(8, int(pcx * 13), 1)
        heat = np.clip(1.1 - dist * 0.85, 0, 1)
        rim = body & (neighbours(body, wrap=False) < 4)
        # Veins of skin across the glow
        veins = body & (mott > 0.64) & (dist > 0.35)
        lit = body & ~rim & ~veins
        lay(rgb, alpha, ramp(POD_SKIN, mott), body | cap)
        gcol = ramp(POD_GLOW, heat)
        lay(rgb, alpha, gcol * 0.85, lit)
        lay(glow_rgb, glow_a, gcol, lit)
        rgb[cap & ~body] = POD_SKIN[0]
        rgb[left_edge(cap) & ~body] = POD_SKIN[2]
        bead_x, bead_y = int(pcx), int(py0 + ph + 0.5)
        if pw > 5:
            for yy, c in ((bead_y, POD_GLOW[2]), (bead_y + 1, POD_GLOW[1])):
                if yy < S:
                    rgb[yy, bead_x] = c
                    alpha[yy, bead_x] = 1
                    glow_rgb[yy, bead_x] = c
                    glow_a[yy, bead_x] = 1
    # A low tuft of rot and moss round the foot
    tuft = cutout(cover(lambda d, k: d.ellipse([4 * k, 28.5 * k, 22 * k, 35 * k], fill=255)))
    leafy = tuft * (fbm(8, 67, 1) > 0.4)
    lay(rgb, alpha, ramp(SICK_MOSS + [(60, 44, 26)], fbm(16, 68, 1)), leafy)
    return image(rgb, cutout(alpha)), image(glow_rgb, cutout(glow_a))


REED = [(40, 66, 30), (56, 90, 40), (74, 116, 50), (96, 140, 62), (122, 160, 78)]
HEAD = [(52, 32, 18), (78, 48, 26), (102, 66, 36), (128, 88, 50)]


def cattails():
    """Cattails, a reed two blocks tall, painted whole and cut in two: (lower, upper). Long narrow blades rise
    from the water, leaning, a few folding over near their tips, darker and wet at the foot; three brown
    velvety heads stand on their stalks above them, each with a thin spike."""
    rng = np.random.default_rng(71)
    H = 2 * S
    rgb = np.zeros((H, S, 3))
    alpha = np.zeros((H, S))
    yy, xx = np.mgrid[0:H, 0:S] + 0.5
    blades = []
    for i in range(10):
        x0 = 4 + i * 2.6 + rng.uniform(-1.2, 1.2)
        lean = rng.uniform(-0.12, 0.12) + (x0 - 16) * 0.008
        tip = rng.uniform(6, 34)
        fold = i % 4 == 1
        pts, widths = [], []
        for j in range(25):
            t = j / 24
            y = H + 0.5 - t * (H + 0.5 - tip)
            x = x0 + lean * (H - y)
            if fold and t > 0.72:
                x += (t - 0.72) ** 1.6 * 40 * (1 if lean >= 0 else -1)
                y += (t - 0.72) ** 2 * 50
            pts.append((x, y))
            widths.append(2.4 * (1 - t) ** 0.6 + 0.7)
        blades.append((pts, widths, i))
    for pts, widths, i in blades:
        m = cutout(cover(lambda d, k: d.polygon([(px * k, py * k) for px, py in ribbon(pts, widths)], fill=255), S, H))
        col = np.zeros((H, S, 3)) + REED[1 + i % 3]
        # A darker line down the blade's right edge, a lighter one down its left
        mb = m > 0
        col[right_edge(mb)] = REED[0]
        col[left_edge(mb)] = REED[min(4, 2 + i % 3)]
        lay(rgb, alpha, col, m)
    # The heads on their stalks
    for hx, hy, hh in ((10.5, 9.0, 8.0), (19.5, 5.0, 9.0), (26.0, 13.0, 7.0)):
        stalk = cutout(cover(lambda d, k: d.line([(hx * k, (hy + hh) * k), ((hx + 0.6) * k, H * k)], fill=255, width=int(1.3 * k)), S, H))
        lay(rgb, alpha, REED[2], stalk)
        spike = cutout(cover(lambda d, k: d.line([(hx * k, (hy - 4.2) * k), (hx * k, hy * k)], fill=255, width=int(1.1 * k)), S, H))
        lay(rgb, alpha, (128, 128, 76), spike)
        head = cutout(cover(lambda d, k: d.rounded_rectangle([(hx - 1.7) * k, hy * k, (hx + 1.7) * k, (hy + hh) * k], radius=1.5 * k, fill=255), S, H)) > 0
        t = 0.66 - (xx - hx) * 0.2 + (rng.random((H, S)) - 0.5) * 0.25
        col = ramp(HEAD, t)
        lay(rgb, alpha, col, head)
        rgb[right_edge(head)] = HEAD[0]
    # Wet and darker toward the foot, where it stands in the water
    dim = np.clip(yy - (H - 12), 0, 12) / 12 * 0.45
    rgb *= (1 - dim)[..., None]
    a = cutout(alpha)
    return image(rgb[S:], a[S:]), image(rgb[:S], a[:S])




# ---- The frozen peaks ----

# Icicle pieces, top to bottom of a hanging column, as (width at the top, width at the bottom) in pixels
ICICLE_PIECES = {"base": (22, 16), "middle": (16, 14), "frustum": (14, 8), "tip": (8, 0), "tip_merge": (8, 2)}
ICE = {"body": (184, 220, 248), "light": (238, 248, 255), "shade": (118, 166, 218), "ridge": (156, 198, 236)}


def icicle(piece, seed, widths=None, end=None, drop=True):
    """One piece of a hanging icicle, translucent: clear blue ice, a bright edge down its left where the light
    comes through, a deeper blue down its right, a few faint rings where it froze in layers, streaks and
    bubbles caught inside, and whiter, cloudier ice toward the root. The base carries a crust of snow where it
    hangs from; the tip ends in a point with a drop of meltwater under it."""
    top_w, bottom_w = widths or ICICLE_PIECES[piece]
    rng = np.random.default_rng(seed)
    rgb = np.zeros((S, S, 3))
    alpha = np.zeros((S, S))
    cx = S / 2 + 0.3
    end = end or (27.5 if piece == "tip" else S)
    # Where the rings fall, a few pixels apart
    rings = set()
    yy = int(rng.integers(1, 5))
    while yy < S:
        rings.add(yy)
        yy += int(rng.integers(4, 8))
    cloud = {"base": 0.5, "middle": 0.25, "frustum": 0.12, "tip": 0.0, "tip_merge": 0.0, "item": 0.3}[piece]
    for y in range(S):
        if y >= end:
            break
        t = y / end
        w = top_w + (bottom_w - top_w) * (t ** (0.8 if piece == "tip" else 1.0))
        # Bulges where it froze thicker
        w += 0.7 * math.sin(y * 2 * np.pi / 7.1 + seed) * (w / 16)
        if w < 0.6:
            continue
        lo, hi = cx - w / 2, cx + w / 2
        ring_end = rng.uniform(0.5, 0.84)
        whiter = cloud * (1 - y / S)
        for x in range(S):
            c = x + 0.5
            if c < lo or c > hi:
                continue
            u = (c - lo) / max(w, 0.01)
            body = np.array(ICE["body"], float) * (1 - whiter) + np.array((226, 240, 252)) * whiter
            col, a = body, 0.58 + whiter * 0.3
            if u < 0.17 or x == int(lo):
                col, a = ICE["light"], 0.92
            elif u > 0.84:
                col, a = ICE["shade"], 0.86
            elif 0.33 < u < 0.41 or 0.62 < u < 0.66:
                col, a = (212, 236, 255), 0.72
            if y in rings and 0.17 <= u <= ring_end:
                col, a = ICE["ridge"], 0.74
            rgb[y, x] = col
            alpha[y, x] = a
    inside = (alpha > 0.55) & (alpha < 0.8)
    bubbles = inside & (rng.random((S, S)) < 0.025)
    rgb[bubbles] = (250, 254, 255)
    alpha[bubbles] = 0.96
    if piece in ("base", "item"):
        # A crust of snow along the top, ragged underneath
        ragged = tile_noise(8, seed)[0]
        for x in range(S):
            if alpha[0, x] > 0 or abs(x + 0.5 - cx) < (13 if piece == "base" else 7):
                depth = int(2 + 2.5 * ragged[x] + rng.integers(0, 2))
                for y in range(depth):
                    rgb[y, x] = (244, 249, 255) if y < depth - 1 else (214, 230, 246)
                    alpha[y, x] = 1.0
    if drop and piece in ("tip", "item"):
        # The drop of meltwater beading under the point
        rgb[29, int(cx)] = ICE["light"]
        alpha[29, int(cx)] = 0.9
        rgb[30, int(cx)] = ICE["body"]
        alpha[30, int(cx)] = 0.75
    return image(rgb, alpha)


def icicles():
    """Every icicle piece hanging down (as placed under a ledge), for the preview: base, middle, frustum, tip
    and the merged tip."""
    return tuple(icicle(p, 81 + i) for i, p in enumerate(ICICLE_PIECES))


FROST = [(172, 204, 236), (204, 228, 250), (232, 244, 255), (252, 254, 255)]


def hoarfrost():
    """Hoarfrost: feathery frost grown over a face of rock in fern-like sprays, each a main stem with side
    shoots at the ice's sixty degrees, the finest tips glinting, a little frost fur here and there. Cut out,
    so the rock shows through between the sprays."""
    rng = np.random.default_rng(91)
    level = np.zeros((S, S))

    def put(x, y, v):
        px, py = int(round(x)) % S, int(round(y)) % S
        level[py, px] = max(level[py, px], v)

    def spray(x, y, a, length, v, depth):
        for i in range(int(length)):
            put(x, y, v)
            if depth < 2 and i % 2 == 1 and i < length - 1:
                for side in (-1, 1):
                    if rng.random() < 0.7:
                        spray(x, y, a + side * np.pi / 3, max(1, (length - i) * 0.45), v - 0.25, depth + 1)
            x += math.cos(a)
            y += math.sin(a)
        put(x, y, 1.0)

    for _ in range(8):
        sx, sy = rng.uniform(0, S, 2)
        base = rng.uniform(0, 2 * np.pi)
        for k in range(3):
            if rng.random() < 0.85:
                spray(sx, sy, base + k * 2 * np.pi / 3 + rng.normal(0, 0.15), rng.uniform(4, 8), 0.75, 0)
    frost = level > 0
    fur = dilate(frost, 1) & ~frost & (rng.random((S, S)) < 0.06)
    rgb = np.zeros((S, S, 3))
    rgb[frost] = ramp(FROST, level[frost])
    rgb[fur] = FROST[0]
    return image(rgb, (frost | fur).astype(float))


# ---- The crystal deeps ----

GLIMMER = [(52, 26, 96), (84, 46, 150), (118, 72, 196), (156, 112, 230), (198, 164, 250), (238, 222, 255)]
GLIMMER_GLOW = [(150, 90, 230), (190, 140, 255), (226, 196, 255), (250, 240, 255)]


def shard(d, k, x, y, w, h, lean):
    """A crystal shard standing on (x, y), [w] wide and [h] tall, leaning by [lean] pixels at its tip: a
    six-sided prism seen from the side, its point a little off its middle."""
    tx = x + lean
    shoulder = 0.7
    d.polygon([((x - w / 2) * k, y * k), ((x - w / 2 + lean * shoulder) * k, (y - h * shoulder) * k),
               (tx * k, (y - h) * k), ((x + w / 2 + lean * shoulder) * k, (y - h * shoulder) * k),
               ((x + w / 2) * k, y * k)], fill=255)


def glimmer_cluster(size):
    """Glimmer crystals growing out of the rock, small, medium or large: violet shards in a fan, each lit on
    its left face, a deep purple down its right, its point near white, and an inner glow through the middle
    of every shard (the overlay). Returns the cluster and its glow."""
    specs = {
        "small": [(13.5, 5.0, 8.0, -1.0), (19.0, 4.0, 6.0, 1.2), (16.5, 3.2, 4.5, 0.2)],
        "medium": [(12.0, 5.5, 13.0, -2.5), (20.5, 5.0, 11.0, 2.5), (16.0, 6.5, 16.0, 0.3), (8.5, 4.0, 7.0, -2.0)],
        "large": [(9.0, 6.0, 17.0, -5.0), (23.5, 6.0, 15.0, 4.5), (16.0, 8.0, 26.0, 0.4), (12.0, 5.0, 12.0, -2.0),
                  (20.0, 5.5, 13.0, 2.2), (5.0, 4.0, 8.0, -3.0), (27.5, 4.0, 8.0, 3.0)],
    }[size]
    rgb = np.zeros((S, S, 3))
    alpha = np.zeros((S, S))
    glow_rgb = np.zeros((S, S, 3))
    glow_a = np.zeros((S, S))
    y, x = np.mgrid[0:S, 0:S] + 0.5
    # Back to front: the tallest stands in the middle, painted last
    for sx, w, h, lean in sorted(specs, key=lambda s: s[2]):
        m = cutout(cover(lambda d, k: shard(d, k, sx, 32.5, w, h, lean))) > 0
        # How far across the shard each pixel is (0 its left edge, 1 its right), leaning with it
        rise = np.clip((32.5 - y) / h, 0, 1)
        mid = sx + lean * rise * 0.85
        u = np.clip((x - (mid - w / 2)) / w, 0, 1)
        tone = np.where(u < 0.3, 0.78, np.where(u < 0.68, 0.55, 0.3))
        tone = tone + rise * 0.14 - (1 - rise) * 0.1
        # The point: its left facet in the light, its right in shade
        point = rise > 0.7
        tone = np.where(point, np.where(u < 0.5, 0.9, 0.42), tone)
        col = ramp(GLIMMER, tone)
        rgb[m] = col[m]
        alpha[m] = 1
        rgb[right_edge(m)] = GLIMMER[0]
        rgb[left_edge(m)] = GLIMMER[4]
        # The very point catches the light
        tip = m & (rise > 0.9)
        rgb[tip] = GLIMMER[5]
        # The glow: a line of light up the middle of the shard, brighter toward its point
        core = m & (np.abs(u - 0.45) < 0.2) & (rise > 0.12) & ~right_edge(m)
        g = ramp(GLIMMER_GLOW, 0.25 + rise * 0.6)
        glow_rgb[core] = g[core]
        glow_a[core] = 1
        glow_rgb[tip] = GLIMMER_GLOW[3]
        glow_a[tip] = 1
        # Under the glow overlay the base keeps the same pixels a little dimmer, for when nothing shines it up
        rgb[core] = g[core] * 0.8
    return image(rgb, alpha), image(glow_rgb, glow_a)


def glimmer_small():
    return glimmer_cluster("small")


def glimmer_medium():
    return glimmer_cluster("medium")


def glimmer_large():
    return glimmer_cluster("large")


DEEPSLATE = [(34, 34, 40), (46, 46, 54), (58, 58, 67), (72, 72, 82), (88, 88, 98)]


def glimmerstone():
    """Glimmerstone: deepslate shot through with veins of crystal, the veins and a few nodules of it glowing
    violet (the overlay). Tiles."""
    rng = np.random.default_rng(101)
    n = fbm(2, 102, 3, cells_y=6) * 0.65 + fbm(16, 103, 1) * 0.35
    rgb = ramp(DEEPSLATE, 0.15 + stretch(n) * 0.65)
    grain = rng.random((S, S))
    rgb[grain < 0.05] *= 0.8
    rgb[grain > 0.96] += 12
    starts = [(qx * 16 + rng.uniform(2, 14), qy * 16 + rng.uniform(2, 14)) for qx in (0, 1) for qy in (0, 1)]
    heat, along = crack_network(104, starts, 15, 0.15)
    vein = heat > 0
    glow_rgb = np.zeros((S, S, 3))
    glow_a = np.zeros((S, S))
    g = ramp(GLIMMER_GLOW, 0.2 + heat * 0.7)
    glow_rgb[vein] = g[vein]
    glow_a[vein] = 1
    rgb[vein] = ramp(GLIMMER, 0.35 + heat[vein] * 0.3)
    # The rock beside a vein takes a violet cast
    near = dilate(vein, 1) & ~vein
    rgb[near] = rgb[near] * 0.8 + np.array([90, 60, 140]) * 0.2
    # Nodules: little knots of crystal where the veins meet the rock's hollows
    for _ in range(4):
        nx, ny = rng.integers(0, S, 2)
        for dx, dy, c in ((0, 0, 3), (1, 0, 2), (0, 1, 1), (1, 1, 0), (2, 1, 1)):
            px, py = (nx + dx) % S, (ny + dy) % S
            rgb[py, px] = GLIMMER[c + 1]
            if c >= 2:
                glow_rgb[py, px] = GLIMMER_GLOW[c - 1]
                glow_a[py, px] = 1
    return image(rgb), image(glow_rgb, glow_a)


def glimmer_crystal():
    """Glimmer crystal, the stuff of the spires: a block of violet crystal broken into facets, each facet lit
    a little differently and shading across itself, a fine bright line where facets meet on their lit side
    and a dark one on the other, sparks of light here and there. Translucent. Tiles."""
    rng = np.random.default_rng(111)
    count = 9
    pts = rng.uniform(0, S, (count, 2))
    tones = rng.uniform(0.32, 0.7, count)
    slopes = rng.uniform(-1, 1, (count, 2)) * 0.012
    y, x = np.mgrid[0:S, 0:S] + 0.5
    best = np.full((S, S), 1e9)
    second = np.full((S, S), 1e9)
    owner = np.zeros((S, S), int)
    rx = np.zeros((S, S))
    ry = np.zeros((S, S))
    for i, (px, py) in enumerate(pts):
        for ox in (-S, 0, S):
            for oy in (-S, 0, S):
                dx, dy = x - px - ox, y - py - oy
                dd = np.hypot(dx, dy)
                closer = dd < best
                second = np.where(closer, best, np.minimum(second, dd))
                owner = np.where(closer, i, owner)
                rx = np.where(closer, dx, rx)
                ry = np.where(closer, dy, ry)
                best = np.where(closer, dd, best)
    # Each facet shades across itself, as a flat face tilted to the light
    t = tones[owner] + rx * slopes[owner, 0] * 6 + ry * slopes[owner, 1] * 6
    rgb = ramp(GLIMMER, np.clip(t, 0.25, 0.8))
    alpha = np.full((S, S), 0.8)
    edge = (second - best) < 1.0
    up = np.roll(owner, 1, 0) != owner
    left = np.roll(owner, 1, 1) != owner
    lit = edge & (up | left)
    rgb[edge] = GLIMMER[1]
    rgb[lit] = GLIMMER[4]
    alpha[edge] = 0.9
    sparks = (rng.random((S, S)) < 0.01) & ~edge
    rgb[sparks] = (250, 244, 255)
    alpha[sparks] = 1.0
    return image(rgb, alpha)



def icicle_item():
    """The icicle as an item: a whole icicle, root to point, snow on its root."""
    return icicle("item", 89, widths=(11, 0), end=28.5)


# ---- Models, blockstates and item definitions ----

def block(name):
    return NS + ":block/" + name


def cube_with_glow(base, glow, light, faces=None):
    """A full block of [base] textures with a second, glowing skin of [glow] textures over it, given
    [light] (0 to 15) of its own brightness and shaded as a top face on every side, so it glows evenly: the
    glow's cut-out pixels shine at night, the rest is the block. [base] and [glow] map face names (or "side")
    to textures."""
    sides = ("north", "south", "east", "west")
    def tex_for(m, face):
        return m.get(face) or (m.get("side") if face in sides else None)
    def faces_of(m, key):
        out = {}
        for face in ("down", "up") + sides:
            t = tex_for(m, face)
            if t:
                out[face] = {"uv": [0, 0, 16, 16], "texture": "#" + key + "_" + face, "cullface": face}
        return out
    textures = {"particle": tex_for(base, "north")}
    for face in ("down", "up") + sides:
        if tex_for(base, face):
            textures["base_" + face] = tex_for(base, face)
        if tex_for(glow, face):
            textures["glow_" + face] = tex_for(glow, face)
    return {
        "parent": "minecraft:block/block",
        "textures": textures,
        "elements": [
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces_of(base, "base")},
            {"from": [0, 0, 0], "to": [16, 16, 16], "shade_direction_override": "up", "light_emission": light, "faces": faces_of(glow, "glow")},
        ],
    }


FACING_ROTATIONS = {"up": {}, "down": {"x": 180}, "north": {"x": 90}, "south": {"x": 90, "y": 180},
                    "east": {"x": 90, "y": 90}, "west": {"x": 90, "y": 270}}
MULTIFACE_ROTATIONS = {"north": {}, "east": {"uvlock": True, "y": 90}, "south": {"uvlock": True, "y": 180},
                       "west": {"uvlock": True, "y": 270}, "up": {"uvlock": True, "x": 270}, "down": {"uvlock": True, "x": 90}}
GLIMMER_SIZES = {"small_glimmer_cluster": "small", "glimmer_cluster": "medium", "large_glimmer_cluster": "large"}


def assets():
    """Every texture, as {path under textures/: (image, mcmeta or None)}."""
    out = {}

    def put(name, img, meta=None, kind="block"):
        out[kind + "/" + name] = (img, meta)

    animated = {"animation": {"frametime": 3, "interpolate": True}}
    put("ash", ash())
    put("charred_log", charred_log())
    put("charred_log_top", charred_log_top())
    side, side_glow = ember_vent()
    top, top_glow = ember_vent_top()
    put("ember_vent", side)
    put("ember_vent_glow", side_glow, animated)
    put("ember_vent_top", top)
    put("ember_vent_top_glow", top_glow, animated)
    put("bog_rot", bog_rot())
    lantern, lantern_glow = bog_lantern()
    put("bog_lantern", lantern)
    put("bog_lantern_glow", lantern_glow)
    bottom, upper = cattails()
    put("cattails_bottom", bottom)
    put("cattails_top", upper)
    for i, piece in enumerate(ICICLE_PIECES):
        # Standing up, the same piece upside down: no drop above its point
        put("icicle_down_" + piece, icicle(piece, 81 + i))
        put("icicle_up_" + piece, icicle(piece, 81 + i, drop=False).transpose(Image.FLIP_TOP_BOTTOM))
    put("icicle", icicle_item(), kind="item")
    put("hoarfrost", hoarfrost())
    for name, size in GLIMMER_SIZES.items():
        img, glow = glimmer_cluster(size)
        put(name, img)
        put(name + "_glow", glow)
    stone, stone_glow = glimmerstone()
    put("glimmerstone", stone)
    put("glimmerstone_glow", stone_glow)
    put("glimmer_crystal", glimmer_crystal())
    return out


def models():
    """Every block model, blockstate, item model and item definition, as {path under assets: json}."""
    out = {}

    def model(name, data):
        out["models/block/" + name + ".json"] = data

    def state(name, data):
        out["blockstates/" + name + ".json"] = data

    def item(name, model_ref):
        out["items/" + name + ".json"] = {"model": {"type": "minecraft:model", "model": model_ref}}

    def flat_item(name, layer0):
        out["models/item/" + name + ".json"] = {"parent": "minecraft:item/generated", "textures": {"layer0": layer0}}
        item(name, NS + ":item/" + name)

    # Bog rot: a carpet, turned at random so the rot doesn't repeat in rows
    model("bog_rot", {"parent": "minecraft:block/carpet", "textures": {"wool": block("bog_rot")}})
    state("bog_rot", {"variants": {"": [{"model": block("bog_rot")}] + [{"model": block("bog_rot"), "y": r} for r in (90, 180, 270)]}})
    item("bog_rot", block("bog_rot"))
    # Bog lantern: a cross whose glowing pixels shine
    model("bog_lantern", {"parent": "minecraft:block/cross_emissive",
                          "textures": {"cross": block("bog_lantern"), "cross_emissive": block("bog_lantern_glow")}})
    state("bog_lantern", {"variants": {"": {"model": block("bog_lantern")}}})
    out["models/item/bog_lantern.json"] = {"parent": "minecraft:item/generated",
                                           "textures": {"layer0": block("bog_lantern"), "layer1": block("bog_lantern_glow")}}
    item("bog_lantern", NS + ":item/bog_lantern")
    # Cattails: two crosses, one a block
    for half in ("bottom", "top"):
        model("cattails_" + half, {"parent": "minecraft:block/cross", "textures": {"cross": block("cattails_" + half)}})
    state("cattails", {"variants": {"half=lower": {"model": block("cattails_bottom")}, "half=upper": {"model": block("cattails_top")}}})
    flat_item("cattails", block("cattails_top"))
    # Ash: layers like snow's
    for layers in range(1, 8):
        h = layers * 2
        model("ash_height" + str(h), {"parent": "minecraft:block/snow_height" + str(h),
                                      "textures": {"particle": block("ash"), "texture": block("ash")}})
    model("ash_block", {"parent": "minecraft:block/cube_all", "textures": {"all": block("ash")}})
    state("ash", {"variants": {"layers=" + str(n): {"model": block("ash_height" + str(n * 2) if n < 8 else "ash_block")} for n in range(1, 9)}})
    item("ash", block("ash_height2"))
    # Charred log: a log
    model("charred_log", {"parent": "minecraft:block/cube_column", "textures": {"end": block("charred_log_top"), "side": block("charred_log")}})
    model("charred_log_horizontal", {"parent": "minecraft:block/cube_column_horizontal",
                                     "textures": {"end": block("charred_log_top"), "side": block("charred_log")}})
    state("charred_log", {"variants": {"axis=x": {"model": block("charred_log_horizontal"), "x": 90, "y": 90},
                                       "axis=y": {"model": block("charred_log")},
                                       "axis=z": {"model": block("charred_log_horizontal"), "x": 90}}})
    item("charred_log", block("charred_log"))
    # Ember vent: rock with its cracks glowing over it
    model("ember_vent", cube_with_glow({"side": block("ember_vent"), "up": block("ember_vent_top"), "down": block("ember_vent")},
                                       {"side": block("ember_vent_glow"), "up": block("ember_vent_top_glow")}, 15))
    state("ember_vent", {"variants": {"": {"model": block("ember_vent")}}})
    item("ember_vent", block("ember_vent"))
    # Icicles: like pointed dripstone, every piece hanging down or standing up
    variants = {}
    for piece in ICICLE_PIECES:
        for way in ("down", "up"):
            name = "icicle_" + way + "_" + piece
            model(name, {"parent": "minecraft:block/cross", "textures": {"cross": block(name)}})
            variants["thickness=" + piece + ",vertical_direction=" + way] = {"model": block(name)}
    state("icicle", {"variants": variants})
    flat_item("icicle", NS + ":item/icicle")
    # Hoarfrost: on any face, like glow lichen
    model("hoarfrost", {"ambientocclusion": False, "textures": {"particle": block("hoarfrost"), "frost": block("hoarfrost")},
                        "elements": [{"from": [0, 0, 0.1], "to": [16, 16, 0.1],
                                      "faces": {"north": {"uv": [16, 0, 0, 16], "texture": "#frost"},
                                                "south": {"uv": [0, 0, 16, 16], "texture": "#frost"}}}]})
    parts = []
    none = {face: "false" for face in ("down", "east", "north", "south", "up", "west")}
    for face, rot in MULTIFACE_ROTATIONS.items():
        apply = dict({"model": block("hoarfrost")}, **rot)
        parts.append({"apply": apply, "when": {face: "true"}})
        parts.append({"apply": apply, "when": dict(none)})
    state("hoarfrost", {"multipart": parts})
    flat_item("hoarfrost", block("hoarfrost"))
    # Glimmer clusters: crosses with a glow, turned to the face they grow from
    for name in GLIMMER_SIZES:
        model(name, {"parent": "minecraft:block/cross_emissive", "textures": {"cross": block(name), "cross_emissive": block(name + "_glow")}})
        state(name, {"variants": {"facing=" + f: dict({"model": block(name)}, **rot) for f, rot in FACING_ROTATIONS.items()}})
        out["models/item/" + name + ".json"] = {"parent": "minecraft:item/generated",
                                                "textures": {"layer0": block(name), "layer1": block(name + "_glow")}}
        item(name, NS + ":item/" + name)
    # Glimmerstone: deepslate with its veins glowing
    model("glimmerstone", cube_with_glow({"side": block("glimmerstone"), "up": block("glimmerstone"), "down": block("glimmerstone")},
                                         {"side": block("glimmerstone_glow"), "up": block("glimmerstone_glow"), "down": block("glimmerstone_glow")}, 15))
    state("glimmerstone", {"variants": {"": {"model": block("glimmerstone")}}})
    item("glimmerstone", block("glimmerstone"))
    # Glimmer crystal: translucent, lit from within
    model("glimmer_crystal", {"parent": "minecraft:block/block", "textures": {"particle": block("glimmer_crystal"), "all": block("glimmer_crystal")},
                              "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "light_emission": 10,
                                            "faces": {f: {"uv": [0, 0, 16, 16], "texture": "#all", "cullface": f}
                                                      for f in ("down", "up", "north", "south", "west", "east")}}]})
    state("glimmer_crystal", {"variants": {"": {"model": block("glimmer_crystal")}}})
    item("glimmer_crystal", block("glimmer_crystal"))
    return out


def dumps(data):
    """JSON as models are written by hand: indented, but a short list of numbers kept on one line."""
    text = json.dumps(data, indent=2)
    return re.sub(r"\[\s+([-\d.,\s]+?)\s+\]", lambda m: "[" + ", ".join(x.strip() for x in m.group(1).split(",")) + "]", text) + "\n"


def write(root):
    for path, (img, meta) in assets().items():
        full = os.path.join(root, "textures", path + ".png")
        os.makedirs(os.path.dirname(full), exist_ok=True)
        img.save(full)
        if meta:
            with open(full + ".mcmeta", "w") as f:
                f.write(dumps(meta))
    for path, data in models().items():
        full = os.path.join(root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(dumps(data))


def sheet(path):
    """Every texture large on a contact sheet, for checking by eye."""
    items = list(assets().items())
    Z = 5
    cols = 6
    cell = S * Z + 16
    rows = (len(items) + cols - 1) // cols
    out = Image.new("RGBA", (cols * cell, rows * (cell + 14)), (52, 56, 64, 255))
    d = ImageDraw.Draw(out)
    for i, (name, (img, _)) in enumerate(items):
        frame = img.crop((0, 0, S, S))
        x, y = (i % cols) * cell + 8, (i // cols) * (cell + 14) + 8
        out.alpha_composite(frame.resize((S * Z, S * Z), Image.NEAREST), (x, y))
        d.text((x, y + S * Z + 2), name.split("/")[-1], fill=(225, 225, 225, 255))
    out.save(path)


def main():
    root = sys.argv[1]
    write(root)
    if len(sys.argv) > 2:
        os.makedirs(sys.argv[2], exist_ok=True)
        sheet(os.path.join(sys.argv[2], "region_terrain_textures.png"))


if __name__ == "__main__":
    main()
