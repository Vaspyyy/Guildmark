"""The HD art kit: shared painting for Guildmark's 32x blocks and items, in the dark-fantasy look the shader
sets: muted colour, deep shadows that turn cool, warm light from the upper left, and a few hard tones per
material, the way pixel art is painted.

Shapes are height fields over the pixel grid. `light()` shades a height field, `bands()` snaps a value onto a
material's tones (with a little ordered dither where two tones meet), `outline()` draws the dark line round an
item and `rim()` darkens a shape's own edge. Only numpy and Pillow.
"""
import math

import numpy as np
from PIL import Image

_L = np.array([-0.58, -0.64, 0.5])
LIGHT = _L / np.linalg.norm(_L)

# Shadows lean toward a cold violet-black, light toward a warm candle white
SHADOW = np.array([12.0, 10.0, 22.0])
HILITE = np.array([255.0, 236.0, 196.0])

# Material tones, darkest first. Hue-shifted: dark ends cool, light ends warm, and nothing fully saturated.
PAL = {
    'gold': [(30, 20, 20), (66, 42, 26), (112, 76, 34), (160, 118, 52), (204, 162, 82), (236, 208, 140)],
    'brass': [(30, 22, 20), (62, 46, 30), (104, 80, 44), (146, 116, 62), (186, 156, 92), (218, 196, 140)],
    'iron': [(16, 16, 22), (34, 35, 44), (56, 58, 66), (84, 86, 92), (118, 118, 120), (160, 158, 154)],
    'steel': [(20, 22, 30), (44, 48, 60), (74, 80, 92), (110, 116, 126), (152, 156, 162), (200, 202, 200)],
    'blacksteel': [(10, 10, 16), (22, 22, 30), (38, 38, 50), (58, 60, 74), (86, 90, 106), (124, 130, 146)],
    'wood': [(22, 16, 16), (40, 28, 24), (60, 43, 33), (82, 59, 42), (106, 78, 54), (132, 101, 70)],
    'darkwood': [(14, 11, 12), (26, 19, 18), (40, 29, 25), (56, 41, 33), (74, 55, 43), (96, 73, 56)],
    'parchment': [(54, 40, 32), (96, 76, 56), (138, 114, 82), (174, 152, 112), (202, 184, 140), (224, 210, 170)],
    'parchment_dark': [(20, 14, 18), (36, 26, 30), (54, 40, 42), (74, 58, 56), (98, 80, 72), (124, 104, 90)],
    'wax': [(30, 8, 14), (64, 14, 22), (104, 24, 30), (144, 40, 38), (180, 70, 56), (210, 110, 84)],
    'leather': [(20, 14, 14), (40, 27, 23), (62, 42, 32), (86, 60, 42), (112, 82, 58), (140, 108, 78)],
    'blackleather': [(10, 8, 12), (20, 16, 20), (32, 26, 30), (46, 38, 42), (64, 54, 58), (86, 74, 76)],
    'stone': [(26, 26, 32), (44, 43, 48), (64, 62, 64), (88, 85, 84), (114, 109, 104), (142, 136, 126)],
    'slate': [(16, 18, 26), (28, 32, 42), (42, 47, 58), (58, 64, 76), (78, 86, 96), (104, 112, 120)],
    'moss': [(20, 28, 22), (32, 44, 30), (46, 60, 36), (64, 78, 44), (86, 98, 56)],
    'crimson': [(28, 8, 16), (54, 14, 22), (86, 22, 28), (118, 34, 36), (150, 54, 46), (178, 84, 64)],
    'cork': [(34, 22, 18), (62, 42, 30), (92, 64, 42), (122, 90, 58), (150, 116, 78)],
    'glass': [(14, 18, 24), (28, 36, 44), (46, 58, 66), (70, 86, 92), (104, 124, 126), (156, 176, 172)],
    'draught': [(70, 22, 12), (132, 50, 18), (190, 88, 28), (230, 136, 46), (250, 186, 86), (255, 228, 160)],
    'ember': [(60, 16, 10), (130, 40, 14), (196, 84, 24), (236, 140, 46), (252, 196, 100), (255, 238, 186)],
    'bone': [(40, 34, 34), (74, 66, 60), (112, 102, 90), (150, 138, 118), (186, 174, 148), (216, 206, 180)],
    'hide': [(24, 20, 16), (42, 36, 28), (64, 56, 42), (88, 78, 56), (114, 102, 72)],
    'troll': [(20, 24, 22), (36, 42, 36), (54, 62, 50), (74, 84, 64), (98, 108, 82), (124, 132, 100)],
    'ash': [(16, 15, 17), (30, 28, 30), (46, 43, 44), (64, 60, 60), (84, 80, 78), (106, 101, 96)],
    'ink': [(18, 12, 14), (34, 24, 24), (52, 38, 34)],
}

# Gem tones, as used on locks and in settings: deep, a lit body, and a hot glint
GEMS = {
    'grey': [(30, 30, 36), (66, 66, 74), (112, 112, 118), (164, 162, 160), (214, 212, 206)],
    'green': [(10, 30, 20), (24, 70, 40), (50, 124, 64), (110, 186, 104), (204, 240, 180)],
    'blue': [(10, 18, 44), (24, 48, 104), (44, 92, 170), (100, 156, 226), (200, 228, 255)],
    'violet': [(26, 10, 40), (60, 24, 96), (104, 50, 158), (160, 104, 216), (232, 200, 255)],
    'amber': [(50, 20, 8), (120, 56, 14), (196, 110, 28), (244, 178, 70), (255, 236, 170)],
    'red': [(40, 6, 10), (96, 14, 20), (160, 32, 32), (220, 84, 64), (255, 190, 160)],
    'frost': [(20, 30, 50), (50, 80, 120), (98, 142, 190), (164, 206, 238), (232, 248, 255)],
}

BAYER = (np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) + 0.5) / 16.0 - 0.5


# ================================================================ grids and noise

def grid(w, h=None):
    """Pixel-centre coordinates."""
    h = h or w
    ys, xs = np.mgrid[0:h, 0:w].astype(float)
    return xs + 0.5, ys + 0.5


def vnoise(w, h, cell, seed):
    """Smooth value noise in 0..1, tiling at the texture's edges, with cells about `cell` pixels wide."""
    rng = np.random.default_rng(seed)
    gw, gh = max(1, round(w / cell)), max(1, round(h / cell))
    g = rng.random((gh, gw))
    fx = np.arange(w) * gw / w
    fy = np.arange(h) * gh / h
    x0, y0 = np.floor(fx).astype(int), np.floor(fy).astype(int)
    tx, ty = fx - x0, fy - y0
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    x0, y0 = x0 % gw, y0 % gh
    x1, y1 = (x0 + 1) % gw, (y0 + 1) % gh
    a = g[y0][:, x0] * (1 - tx) + g[y0][:, x1] * tx
    b = g[y1][:, x0] * (1 - tx) + g[y1][:, x1] * tx
    return a * (1 - ty[:, None]) + b * ty[:, None]


def fbm(w, h, cell, seed, octaves=3, gain=0.5):
    """Layered value noise in 0..1: big shapes with finer grain over them."""
    out = np.zeros((h, w))
    amp, total = 1.0, 0.0
    for i in range(octaves):
        out += vnoise(w, h, max(1.0, cell / 2 ** i), seed + 101 * i) * amp
        total += amp
        amp *= gain
    return out / total


def rand(w, h, seed):
    return np.random.default_rng(seed).random((h, w))


# ================================================================ colour

def tone(c, k):
    """A colour pushed toward the cold shadow (k < 0) or the warm light (k > 0); k in -1..1."""
    c = np.asarray(c, float)
    k = np.asarray(k, float)
    if c.ndim == 1 and k.ndim > 0:
        c = np.broadcast_to(c, k.shape + (3,))
    kk = k[..., None] if k.ndim else k
    dark = c + (SHADOW - c) * np.clip(-kk, 0, 1)
    light = c + (HILITE - c) * np.clip(kk, 0, 1) * 0.75
    return np.where(kk < 0, dark, light)


def mix(a, b, t):
    a, b = np.asarray(a, float), np.asarray(b, float)
    t = np.asarray(t, float)
    if t.ndim:
        t = t[..., None]
    return a + (b - a) * t


def ramp(stops, t):
    """A smooth colour along the stops, for t in 0..1 (array or scalar)."""
    s = np.asarray(stops, float)
    t = np.clip(np.asarray(t, float), 0, 1) * (len(s) - 1)
    i = np.minimum(np.floor(t).astype(int), len(s) - 2)
    f = (t - i)[..., None]
    return s[i] * (1 - f) + s[i + 1] * f


def bands(stops, t, dither=0.35):
    """The value snapped onto the material's tones. Where two tones meet, a 4x4 ordered dither mixes them
    over a strip `dither` of a tone wide, the way pixel artists blend."""
    s = np.asarray(stops, float)
    t = np.clip(np.asarray(t, float), 0, 1)
    h, w = t.shape
    d = np.tile(BAYER, (h // 4 + 1, w // 4 + 1))[:h, :w]
    i = np.clip(np.floor(t * (len(s) - 1) + 0.5 + d * dither), 0, len(s) - 1).astype(int)
    return s[i]


# ================================================================ shapes

def poly(pts, w, h=None):
    """Mask of the pixels whose centres fall inside a polygon (even-odd)."""
    h = h or w
    xs, ys = grid(w, h)
    inside = np.zeros((h, w), bool)
    n = len(pts)
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % n]
        if y0 == y1:
            continue
        cross = ((y0 > ys) != (y1 > ys)) & (xs < (x1 - x0) * (ys - y0) / (y1 - y0) + x0)
        inside ^= cross
    return inside


def seg_dist(x0, y0, x1, y1, w, h=None):
    """Each pixel centre's distance to a line segment, and how far along it (0..1) the nearest point is."""
    h = h or w
    xs, ys = grid(w, h)
    dx, dy = x1 - x0, y1 - y0
    L2 = max(dx * dx + dy * dy, 1e-9)
    t = np.clip(((xs - x0) * dx + (ys - y0) * dy) / L2, 0, 1)
    return np.hypot(xs - (x0 + t * dx), ys - (y0 + t * dy)), t


def path_dist(pts, w, h=None):
    """Distance to a polyline, and the running length along it at the nearest point."""
    h = h or w
    best = np.full((h, w), 1e9)
    along = np.zeros((h, w))
    run = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        d, t = seg_dist(x0, y0, x1, y1, w, h)
        L = math.hypot(x1 - x0, y1 - y0)
        closer = d < best
        best = np.where(closer, d, best)
        along = np.where(closer, run + t * L, along)
        run += L
    return best, along


def disc(cx, cy, r, w, h=None):
    """Mask of a disc, and the dome height over it (1 in the middle, 0 at the edge)."""
    h = h or w
    xs, ys = grid(w, h)
    d = np.hypot(xs - cx, ys - cy) / r
    return d <= 1, np.sqrt(np.clip(1 - d * d, 0, 1))


def ellipse(cx, cy, rx, ry, w, h=None):
    h = h or w
    xs, ys = grid(w, h)
    d = np.hypot((xs - cx) / rx, (ys - cy) / ry)
    return d <= 1, np.sqrt(np.clip(1 - d * d, 0, 1))


def rect(x0, y0, x1, y1, w, h=None):
    """Mask of the pixels x0 <= x < x1, y0 <= y < y1."""
    h = h or w
    m = np.zeros((h, w), bool)
    m[max(0, y0):max(0, y1), max(0, x0):max(0, x1)] = True
    return m


def blur(a, r=1, wrap=False):
    """A box blur of radius r (repeated twice, so it's soft), clamped or wrapping at the edges."""
    a = np.asarray(a, float)
    for _ in range(2):
        out = np.zeros_like(a)
        n = 0
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                out += shift(a, dx, dy, wrap)
                n += 1
        a = out / n
    return a


def shift(a, dx, dy, wrap=False):
    """The array moved by whole pixels; the edge repeats (or wraps)."""
    if wrap:
        return np.roll(np.roll(a, dy, axis=0), dx, axis=1)
    h, w = a.shape[:2]
    ys = np.clip(np.arange(h) - dy, 0, h - 1)
    xs = np.clip(np.arange(w) - dx, 0, w - 1)
    return a[ys][:, xs]


def edge(mask, wrap=False):
    """The mask's own border pixels (4-neighbourhood)."""
    m = mask.astype(bool)
    pad = np.zeros_like(m)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if wrap:
            pad |= ~np.roll(np.roll(m, dy, axis=0), dx, axis=1)
        else:
            s = np.zeros_like(m)
            h, w = m.shape
            ys0, ys1 = max(0, dy), h + min(0, dy)
            xs0, xs1 = max(0, dx), w + min(0, dx)
            s[ys0:ys1, xs0:xs1] = m[ys0 - dy:ys1 - dy, xs0 - dx:xs1 - dx]
            pad |= ~s
    return m & pad


def grow(mask, n=1, diagonal=False):
    m = mask.astype(bool)
    for _ in range(n):
        g = m.copy()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) + (((1, 1), (-1, -1), (1, -1), (-1, 1)) if diagonal else ()):
            g |= _moved(m, dx, dy)
        m = g
    return m


def _moved(m, dx, dy):
    h, w = m.shape
    s = np.zeros_like(m)
    ys0, ys1 = max(0, dy), h + min(0, dy)
    xs0, xs1 = max(0, dx), w + min(0, dx)
    s[ys0:ys1, xs0:xs1] = m[ys0 - dy:ys1 - dy, xs0 - dx:xs1 - dx]
    return s


def dist_in(mask, limit=8):
    """How many steps each pixel of the mask is from its outside (1 on the border), up to limit."""
    m = mask.astype(bool)
    out = np.zeros(m.shape)
    cur = m.copy()
    for i in range(1, limit + 1):
        out[cur] = i
        cur = cur & ~edge(cur)
        if not cur.any():
            break
    return out


# ================================================================ light

def light(height, strength=1.0):
    """Lambert light on a height field from the upper left, minus the light on flat ground: 0 on a flat,
    positive on slopes facing the light, negative on slopes turned away."""
    gy, gx = np.gradient(np.asarray(height, float))
    nx, ny, nz = -gx * strength, -gy * strength, np.ones_like(gx)
    n = np.sqrt(nx * nx + ny * ny + nz * nz)
    return (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / n - LIGHT[2]


def cavity(height, r=2):
    """Positive where the surface sits below its surroundings: recesses where grime gathers."""
    return np.clip(blur(height, r) - height, 0, None)


# ================================================================ canvas

class Canvas:
    """An RGBA image in floats, painted with masks."""

    def __init__(self, w, h=None):
        h = h or w
        self.w, self.h = w, h
        self.rgb = np.zeros((h, w, 3))
        self.a = np.zeros((h, w))

    def put(self, mask, col, alpha=1.0):
        mask = np.asarray(mask, bool)
        col = np.asarray(col, float)
        if col.ndim == 1:
            self.rgb[mask] = col
        else:
            self.rgb[mask] = col[mask]
        al = np.asarray(alpha, float)
        self.a[mask] = al[mask] if al.ndim else al

    def over(self, mask, col, t):
        """Blend a colour in by t (0..1, array or scalar) where the mask is set, keeping alpha."""
        mask = np.asarray(mask, bool)
        col = np.asarray(col, float)
        if col.ndim == 1:
            col = np.broadcast_to(col, self.rgb.shape)
        t = np.broadcast_to(np.asarray(t, float), mask.shape)
        self.rgb[mask] = self.rgb[mask] * (1 - t[mask, None]) + col[mask] * t[mask, None]

    def px(self, x, y, col, a=1.0):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.rgb[y, x] = col[:3]
            self.a[y, x] = a

    @property
    def solid(self):
        return self.a > 0.02

    def image(self):
        out = np.dstack([np.clip(self.rgb, 0, 255), np.clip(self.a, 0, 1) * 255]).round().astype(np.uint8)
        out[out[..., 3] == 0] = 0
        return Image.fromarray(out, 'RGBA')

    @staticmethod
    def of(img):
        arr = np.asarray(img.convert('RGBA'), float)
        c = Canvas(arr.shape[1], arr.shape[0])
        c.rgb = arr[..., :3].copy()
        c.a = arr[..., 3] / 255
        return c


def rim(cv, k=0.35, mask=None):
    """Darken the shape's own border, more on the sides turned from the light (bottom and right)."""
    m = cv.solid if mask is None else mask
    for (dx, dy), kk in (((1, 0), k * 1.2), ((0, 1), k * 1.35), ((-1, 0), k * 0.55), ((0, -1), k * 0.45)):
        border = m & ~_moved(m, -dx, -dy)
        cv.rgb[border] = tone(cv.rgb[border], -kk)


def outline(cv, k=0.78, col=None):
    """The dark line round an item, drawn just outside the shape: each outline pixel is the colour beside it
    pushed deep into shadow, so a gold coin gets a brown-black line and steel a blue-black one."""
    m = cv.solid
    ring = grow(m) & ~m
    acc = np.zeros(cv.rgb.shape)
    cnt = np.zeros(m.shape)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nm = _moved(m, dx, dy)
        src = np.zeros(cv.rgb.shape)
        h, w = m.shape
        ys0, ys1 = max(0, dy), h + min(0, dy)
        xs0, xs1 = max(0, dx), w + min(0, dx)
        src[ys0:ys1, xs0:xs1] = cv.rgb[ys0 - dy:ys1 - dy, xs0 - dx:xs1 - dx]
        acc += src * nm[..., None]
        cnt += nm
    avg = acc / np.maximum(cnt, 1)[..., None]
    line = tone(avg, -k) if col is None else np.broadcast_to(np.asarray(col, float), cv.rgb.shape)
    cv.rgb[ring] = line[ring]
    cv.a[ring] = 1.0


def glint(cv, x, y, size=2, col=(255, 248, 226)):
    """A four-point sparkle of light on metal or glass."""
    cv.px(x, y, col)
    for i in range(1, size + 1):
        c = tone(col, -0.12 * i)
        for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
            if 0 <= x + dx < cv.w and 0 <= y + dy < cv.h and cv.a[y + dy, x + dx] > 0:
                cv.rgb[y + dy, x + dx] = mix(cv.rgb[y + dy, x + dx], c, 0.75 - 0.2 * i)


# ================================================================ sheets

def sheet(images, scale=6, cols=None, bg=(40, 38, 46), pad=10, labels=None):
    """Nearest-neighbour contact sheet for previews."""
    from PIL import ImageDraw
    cols = cols or len(images)
    cw = max(i.width for i in images) * scale
    ch = max(i.height for i in images) * scale
    lh = 14 if labels else 0
    rows = (len(images) + cols - 1) // cols
    out = Image.new('RGBA', (cols * (cw + pad) + pad, rows * (ch + pad + lh) + pad), bg + (255,))
    d = ImageDraw.Draw(out)
    for i, im in enumerate(images):
        x = pad + (i % cols) * (cw + pad)
        y = pad + (i // cols) * (ch + pad + lh)
        big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
        out.alpha_composite(big, (x, y))
        if labels:
            d.text((x, y + ch + 1), labels[i], fill=(220, 214, 200, 255))
    return out


def bevel(mask, raised=True):
    """Where a stamped or carved shape's edges catch the light: +1 on the edges facing the upper left, -1 on
    those facing the lower right (swapped for a shape cut in), 0 elsewhere."""
    m = np.asarray(mask, bool)
    lit = (m & ~_moved(m, 1, 0)) | (m & ~_moved(m, 0, 1))
    dark = (m & ~_moved(m, -1, 0)) | (m & ~_moved(m, 0, -1))
    out = lit.astype(float) - dark.astype(float)
    return out if raised else -out


def paste(dst, src):
    """Lay one canvas over another."""
    a = src.a[..., None]
    dst.rgb = dst.rgb * (1 - a) + src.rgb * a
    dst.a = np.maximum(dst.a, src.a)
