"""Guildmark's skill tree art, at twice the GUI size like the other screens (see ui_hd.py): a 14x14 sprite is a
28x28 PNG. Dark iron sockets with a tarnished-gold line, bevel light from the upper left, one-texel outlines.

    python3 -B art/scripts/tree_art.py src/main/resources/assets/guildmark [preview-dir]

Writes sprites to textures/gui/sprites/tree/ (sockets, hover glow, edges, plus/minus buttons) and its icon/,
glyph/ and stat/ folders, and the tree screen's background and nebula to textures/gui/tree/. The preview dir
gets icons.png (everything at 3x), mock-tree.png (a mock of the screen) and background_3x3.png (the tile check).
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import (PAL, GEMS, LIGHT, Canvas, grid, vnoise, fbm, rand, tone, mix, ramp, bands, blur, dist_in,
                   grow, edge, outline)
import ui_hd

K = 2                                    # texels per GUI pixel
SS = 8                                   # supersampling for shapes: a texel takes what covers most of it

OUT = np.array((12.0, 11.0, 14.0))       # the screens' outline colour
IRON = np.array(PAL['iron'], float)
GOLD = np.array(PAL['gold'], float)
BONE = np.array(PAL['bone'], float)
EMBER = np.array(PAL['ember'], float)
PALE = GOLD * 0.5 + BONE * 0.5           # pale gold: gold dulled toward bone, for a socket that can be learned
LXY = LIGHT[:2] / np.linalg.norm(LIGHT[:2])   # toward the light, in the picture plane (up and left)


def clamp01(a):
    return np.clip(a, 0.0, 1.0)


def smoothstep(a, b, x):
    t = clamp01((np.asarray(x, float) - a) / (b - a))
    return t * t * (3 - 2 * t)


def grain(w, h, seed, amount=0.06):
    """Fine grain and a slower mottle, as a multiplier near 1, for the iron and gold."""
    return 1 + (vnoise(w, h, 3, seed) - 0.5) * amount * 2 + (rand(w, h, seed + 7) - 0.5) * amount


# ================================================================ shapes

class Sketch:
    """Shapes drawn on a supersampled picture and cut down to hard-edged texel masks (a texel is in when more than
    half covered), so thin strokes keep an even width. Coordinates are in texels, y down, texel (0, 0) the top left."""

    def __init__(self, n, ss=SS, zoom=1.0):
        self.n, self.ss, self.z = n, ss, zoom
        self.c = n / 2

    def T(self, x, y):
        """A point of the drawing (design coordinates) on the picture: the drawing is scaled about the middle."""
        return self.c + (x - self.c) * self.z, self.c + (y - self.c) * self.z

    def W(self, w):
        """A stroke width on the picture: scaled, but a line that was a texel wide stays one."""
        return max(w * self.z, min(w, 1.0))

    def _mk(self, fn):
        im = Image.new('L', (self.n * self.ss,) * 2, 0)
        fn(ImageDraw.Draw(im), self.ss)
        a = np.asarray(im, float) / 255
        return a.reshape(self.n, self.ss, self.n, self.ss).mean((1, 3)) >= 0.5

    def poly(self, pts):
        pts = [self.T(x, y) for x, y in pts]
        return self._mk(lambda d, s: d.polygon([(x * s, y * s) for x, y in pts], fill=255))

    def circ(self, cx, cy, r):
        cx, cy = self.T(cx, cy)
        r *= self.z
        return self._mk(lambda d, s: d.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=255))

    def ell(self, cx, cy, rx, ry):
        cx, cy = self.T(cx, cy)
        rx, ry = rx * self.z, ry * self.z
        return self._mk(lambda d, s: d.ellipse([(cx - rx) * s, (cy - ry) * s, (cx + rx) * s, (cy + ry) * s], fill=255))

    def rect(self, x0, y0, x1, y1):
        x0, y0 = self.T(x0, y0)
        x1, y1 = self.T(x1, y1)
        return self._mk(lambda d, s: d.rectangle([x0 * s, y0 * s, x1 * s - 1, y1 * s - 1], fill=255))

    def rrect(self, x0, y0, x1, y1, r):
        x0, y0 = self.T(x0, y0)
        x1, y1 = self.T(x1, y1)
        r *= self.z
        return self._mk(lambda d, s: d.rounded_rectangle([x0 * s, y0 * s, x1 * s - 1, y1 * s - 1], r * s, fill=255))

    def seg(self, x0, y0, x1, y1, w0, w1=None, caps=(True, True)):
        """A stroke from (x0, y0) to (x1, y1), w0 wide at the start and w1 at the end, round at either end if asked."""
        w1 = w0 if w1 is None else w1
        x0, y0 = self.T(x0, y0)
        x1, y1 = self.T(x1, y1)
        w0, w1 = self.W(w0), self.W(w1)

        def f(d, s):
            dx, dy = x1 - x0, y1 - y0
            L = math.hypot(dx, dy) or 1e-6
            nx, ny = -dy / L, dx / L
            pts = [(x0 + nx * w0 / 2, y0 + ny * w0 / 2), (x1 + nx * w1 / 2, y1 + ny * w1 / 2),
                   (x1 - nx * w1 / 2, y1 - ny * w1 / 2), (x0 - nx * w0 / 2, y0 - ny * w0 / 2)]
            d.polygon([(x * s, y * s) for x, y in pts], fill=255)
            if caps[0]:
                d.ellipse([(x0 - w0 / 2) * s, (y0 - w0 / 2) * s, (x0 + w0 / 2) * s, (y0 + w0 / 2) * s], fill=255)
            if caps[1]:
                d.ellipse([(x1 - w1 / 2) * s, (y1 - w1 / 2) * s, (x1 + w1 / 2) * s, (y1 + w1 / 2) * s], fill=255)
        return self._mk(f)

    def path(self, pts, w0, w1=None, caps=(True, True)):
        """A stroke along a polyline, its width running from w0 to w1 along its length, round joins."""
        w1 = w0 if w1 is None else w1
        lens = [math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:])]
        tot = sum(lens) or 1e-6
        run = 0.0
        m = np.zeros((self.n, self.n), bool)
        for i, ((xa, ya), (xb, yb)) in enumerate(zip(pts, pts[1:])):
            wa = w0 + (w1 - w0) * run / tot
            wb = w0 + (w1 - w0) * (run + lens[i]) / tot
            m |= self.seg(xa, ya, xb, yb, wa, wb, caps=(caps[0] if i == 0 else True, caps[1] if i == len(lens) - 1 else True))
            run += lens[i]
        return m

    def arc(self, cx, cy, r, a0, a1, w0, w1=None, steps=40, ry=None):
        """A thick arc from angle a0 to a1 (degrees, 0 to the right, 90 down)."""
        ry = r if ry is None else ry
        pts = [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / steps)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / steps))) for i in range(steps + 1)]
        return self.path(pts, w0, w1)

    def star(self, cx, cy, ro, ri, n=4, rot=-90):
        pts = []
        for i in range(n * 2):
            a = math.radians(rot + i * 180 / n)
            rr = ro if i % 2 == 0 else ri
            pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
        return self.poly(pts)


def smooth(pts, steps=8, closed=True):
    """A Catmull-Rom curve through the points, as a dense polyline."""
    P = [np.array(p, float) for p in pts]
    n = len(P)
    out = []
    for i in range(n if closed else n - 1):
        p0, p1, p2, p3 = P[(i - 1) % n], P[i % n], P[(i + 1) % n], P[(i + 2) % n]
        if not closed:
            p0 = P[max(i - 1, 0)]
            p3 = P[min(i + 2, n - 1)]
        for t in np.linspace(0, 1, steps, endpoint=False):
            out.append(tuple(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3)))
    if not closed:
        out.append(tuple(P[-1]))
    return out


def bez(p0, p1, p2, p3, steps=24):
    """A cubic Bezier as a polyline."""
    out = []
    for i in range(steps + 1):
        t = i / steps
        a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return out


# ================================================================ icons

def moved(m, dx, dy):
    """The mask shifted by whole texels (what was at x, y is now at x + dx, y + dy)."""
    h, w = m.shape
    out = np.zeros_like(m)
    ys0, ys1 = max(0, dy), h + min(0, dy)
    xs0, xs1 = max(0, dx), w + min(0, dx)
    out[ys0:ys1, xs0:xs1] = m[ys0 - dy:ys1 - dy, xs0 - dx:xs1 - dx]
    return out


def edge_sets(m):
    """The texels on a mask's lit edges (nothing above or left of them) and its shadowed ones (nothing below or right)."""
    top, left = m & ~moved(m, 0, 1), m & ~moved(m, 1, 0)
    bot, right = m & ~moved(m, 0, -1), m & ~moved(m, -1, 0)
    return top | left, bot | right


class Icon:
    """A small engraved icon: parts laid down one over another, each a mask in a material (a palette of tones) with its
    upper-left edge bright and its lower-right edge dark, then a fine dark outline round the lot."""

    def __init__(self, n, zoom=1.0):
        self.n = n
        self.sk = Sketch(n, zoom=zoom)
        self.cv = Canvas(n)
        gx, gy = self.gx, self.gy = grid(n)
        # the drawing's own coordinates of each texel, so a test like `ys > 27` means the same at any zoom
        self.xs = n / 2 + (gx - n / 2) / zoom
        self.ys = n / 2 + (gy - n / 2) / zoom
        self.post = []

    # ---- painting
    def fill(self, m, pal, base=0.56, lit=0.24, dark=0.28, grad=0.0, dither=0.0, sep=0.0, flat=False):
        """Paint a mask in a material. `grad` shades it across (bright toward the upper left); `sep` draws a dark line
        round it where it lies over other parts, to part it from them."""
        pal = np.asarray(pal, float)
        m = np.asarray(m, bool)
        if not m.any():
            return m
        if pal.ndim == 1:                       # one colour: laid flat
            self.paint(m, pal)
            return m
        if sep:
            ring = grow(m) & ~m & self.cv.solid
            self.cv.rgb[ring] = tone(self.cv.rgb[ring], -sep)
        t = np.full(m.shape, base, float)
        if grad:
            ys, xs = np.nonzero(m)
            cx, cy = xs.mean() + 0.5, ys.mean() + 0.5
            ext = max(xs.max() - xs.min(), ys.max() - ys.min(), 1) / 2 + 0.5
            t = t + grad * ((self.gx - cx) * LXY[0] + (self.gy - cy) * LXY[1]) / ext
        if not flat:
            li, da = edge_sets(m)
            t = t + lit * li - dark * da
        self.cv.put(m, bands(pal, clamp01(t), dither), 1.0)
        return m

    def paint(self, m, col, alpha=1.0):
        self.cv.put(np.asarray(m, bool), np.asarray(col, float), alpha)

    def px(self, x, y, col):
        x, y = self.sk.T(x, y)
        self.cv.px(int(x), int(y), np.asarray(col, float))

    def tint(self, m, col, k):
        """Mix a colour into what is already there."""
        m = np.asarray(m, bool) & self.cv.solid
        self.cv.rgb[m] = mix(self.cv.rgb[m], np.asarray(col, float), k)

    def groove(self, m, deep=0.55, catch=0.28):
        """Engrave a line into what is there: darker along the line, a catch of light just below and right of it."""
        m = np.asarray(m, bool) & self.cv.solid
        under = moved(m, 1, 1) & self.cv.solid & ~m
        self.cv.rgb[under] = tone(self.cv.rgb[under], catch)
        self.cv.rgb[m] = tone(self.cv.rgb[m], -deep)

    def glint(self, x, y, col=(255, 248, 226), arms=1):
        self.px(x, y, col)
        x, y = self.sk.T(x, y)
        for i in range(1, arms + 1):
            for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
                xx, yy = int(x) + dx, int(y) + dy
                if 0 <= xx < self.n and 0 <= yy < self.n and self.cv.a[yy, xx] > 0:
                    self.cv.rgb[yy, xx] = mix(self.cv.rgb[yy, xx], col, 0.7 - 0.2 * i)

    def halo(self, cx, cy, r, col, a=0.5, power=2.0, over=False):
        """A soft glow of light, laid on after the outline (on top of the icon if `over`)."""
        d = np.hypot(self.xs - cx, self.ys - cy) / r
        w = clamp01(1 - d) ** power * a
        if not over:
            w = np.where(self.cv.a > 0.5, 0.0, w)
        al = self.cv.a
        new_a = 1 - (1 - al) * (1 - w)
        wt = np.where(new_a > 0, w / np.maximum(new_a, 1e-6), 0.0)[..., None]
        self.cv.rgb = self.cv.rgb * (1 - wt) + np.asarray(col, float) * wt
        self.cv.a = new_a

    def after(self, fn):
        self.post.append(fn)

    def finish(self, k=0.8):
        outline(self.cv, k)
        for fn in self.post:
            fn()
        return self.cv.image()


def view_icon(draw, n=32, zoom=1.0):
    ic = Icon(n, zoom)
    draw(ic)
    return ic.finish()


# ---- shared materials
def mat(name):
    return {'gold': GOLD, 'bone': BONE, 'iron': IRON, 'steel': np.array(PAL['steel'], float), 'wood': np.array(PAL['wood'], float),
            'ember': EMBER}[name]


# ---- shapes shared by the icons

def round_part(ic, cx, cy, m, pal, base=0.56, lit=0.3, dark=0.3, width=1.0):
    """A round part shaded by where its rim faces: bright toward the upper left, dark toward the lower right, the
    middle flat. (The square edges `fill` shades by would speckle a small circle.)"""
    d = dist_in(m, 4)
    ang = ((ic.xs - cx) * LXY[0] + (ic.ys - cy) * LXY[1]) / np.maximum(np.hypot(ic.xs - cx, ic.ys - cy), 1e-6)
    rim = m & (d <= width + 0.01)
    t = np.where(rim, base + np.where(ang > 0, lit, dark) * ang, base)
    ic.cv.put(m, bands(np.asarray(pal, float), clamp01(t), 0.0), 1.0)


def compass_star(sk, cx, cy, long, short, inner):
    """Eight points, the four on the axes long, the four between short."""
    pts = []
    for k in range(8):
        a = math.radians(-90 + k * 45)
        r = long if k % 2 == 0 else short
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        a2 = math.radians(-90 + k * 45 + 22.5)
        pts.append((cx + inner * math.cos(a2), cy + inner * math.sin(a2)))
    return sk.poly(pts)


def grain_shape(sk, cx, cy, ux, uy, rl, rw):
    """A kernel: an ellipse rl long along (ux, uy) and rw across."""
    n = math.hypot(ux, uy)
    ux, uy = ux / n, uy / n
    pts = []
    for k in range(16):
        t = 2 * math.pi * k / 16
        x, y = rl * math.cos(t), rw * math.sin(t)
        pts.append((cx + x * ux - y * uy, cy + x * uy + y * ux))
    return sk.poly(pts)


# ---- skill icons (32 x 32): gold and bone, engraved

def mirror(pts, w=32):
    return [(w - x, y) for x, y in pts]


def icon_blades(ic):
    sk = ic.sk
    blade = sk.poly([(13, 22), (13, 7.5), (16, 1.2), (19, 7.5), (19, 22)])
    ic.fill(blade, BONE, base=0.70, lit=0.24, dark=0.30)
    ic.groove(sk.rect(15, 7, 17, 21), deep=0.38, catch=0.16)
    ic.fill(sk.rect(15, 24, 17, 28), mat('wood'), base=0.55, lit=0.2, dark=0.25, sep=0.7)
    for y in (25, 27):
        ic.groove(sk.rect(15, y, 17, y + 1), deep=0.35, catch=0.0)
    guard = sk.poly([(7, 21.5), (9.5, 20.4), (22.5, 20.4), (25, 21.5), (24.2, 24.2), (21.5, 23.6), (10.5, 23.6), (7.8, 24.2)])
    ic.fill(guard, GOLD, base=0.62, sep=0.7)
    ic.fill(sk.circ(16, 29, 1.9), GOLD, base=0.58, lit=0.3, dark=0.3, sep=0.7)
    ic.fill(sk.rect(15, 21, 17, 23), np.array(GEMS['red']), base=0.6, flat=True)
    ic.glint(14, 5, arms=0)


def icon_heavy_arms(ic):
    sk = ic.sk
    ic.fill(sk.rect(15, 4, 17, 29), GOLD, base=0.45, lit=0.25, dark=0.25)
    for y in (22, 24, 26):
        ic.groove(sk.rect(15, y, 17, y + 1), deep=0.4, catch=0.0)
    left = [(14, 6), (9.5, 3), (5.5, 2.8), (3.2, 6.2), (2.6, 9.8), (3.2, 13.4), (5.5, 16.8), (9.5, 16.5), (14, 13.5)]
    ic.fill(sk.poly(left), BONE, base=0.60, lit=0.26, dark=0.3, sep=0.7, grad=0.25)
    ic.fill(sk.poly(mirror(left)), BONE, base=0.54, lit=0.26, dark=0.3, sep=0.7, grad=0.25)
    ic.fill(sk.rect(13, 8.5, 19, 11.5), GOLD, base=0.62, sep=0.7)
    ic.fill(sk.poly([(14.5, 4.5), (16, 1.2), (17.5, 4.5)]), BONE, base=0.6)
    ic.fill(sk.circ(16, 29, 1.8), GOLD, base=0.55)


def icon_archery(ic):
    sk = ic.sk
    nock = (7.0, 16.0)
    tip_a, tip_b = (19.0, 3.0), (19.0, 29.0)
    # the bow: an arc bulging right, thickest at the grip
    pts = [(19.0 + 7.5 * (1 - ((y - 16) / 13) ** 2), y) for y in np.linspace(3, 29, 34)]
    half = len(pts) // 2
    bow = sk.path(pts[:half + 1], 1.4, 3.2) | sk.path(pts[half:], 3.2, 1.4)
    # the string pulled back to the arrow's nock
    string = sk.seg(*tip_a, *nock, 1.0) | sk.seg(*tip_b, *nock, 1.0)
    ic.fill(string, BONE, base=0.62, flat=True)
    ic.fill(sk.rect(7, 15, 28, 17), GOLD, base=0.58, lit=0.3, dark=0.3)
    # feathers at the tail
    for (x0, sgn) in ((7.5, 1), (11.0, 1)):
        ic.fill(sk.poly([(x0, 16), (x0 + 1.2, 12.6), (x0 + 3.6, 12.6), (x0 + 2.6, 16)]), BONE, base=0.66, flat=True, sep=0.5)
        ic.fill(sk.poly([(x0, 16), (x0 + 1.2, 19.4), (x0 + 3.6, 19.4), (x0 + 2.6, 16)]), BONE, base=0.5, flat=True, sep=0.5)
    ic.fill(sk.poly([(31.2, 16), (25.2, 12.4), (26.2, 16), (25.2, 19.6)]), BONE, base=0.72, lit=0.3, dark=0.3, sep=0.6)
    ic.fill(bow, GOLD, base=0.56, lit=0.3, dark=0.3, sep=0.7)
    ic.fill(sk.rect(23, 14, 27, 18), GOLD, base=0.5, lit=0.2, dark=0.3, sep=0.6)     # the grip's wrap


def shield_poly(w=1.0):
    pts = [(5, 4), (27, 4), (27, 15)] + bez((27, 15), (27, 22), (21, 27), (16, 29.6))[1:] + bez((16, 29.6), (11, 27), (5, 22), (5, 15))[1:]
    return pts


def icon_guarding(ic):
    sk = ic.sk
    outer = sk.poly(shield_poly())
    face = outer.copy()
    for _ in range(2):
        face = face & ~edge(face)
    ic.fill(outer, GOLD, base=0.58, lit=0.3, dark=0.3)
    ic.fill(face, IRON, base=0.42, lit=0.18, dark=0.2, grad=0.35)
    ic.fill(sk.rect(14, 6.5, 18, 27) & face, GOLD, base=0.55, lit=0.25, dark=0.3)
    ic.fill(sk.rect(7.5, 11, 24.5, 15) & face, GOLD, base=0.55, lit=0.25, dark=0.3)
    ic.fill(sk.circ(16, 13, 3.2), GOLD, base=0.7, lit=0.3, dark=0.3, sep=0.8)
    ic.glint(15, 12, arms=0)


def cleaf(sk, p0, p1, w, bend=0.0, taper=0.8):
    """A feather bowed sideways by `bend` at its middle: a lens along a quadratic curve."""
    p0, p1 = np.array(p0, float), np.array(p1, float)
    d = p1 - p0
    L = np.hypot(*d)
    nrm = np.array([-d[1], d[0]]) / L
    left, right = [], []
    for i in range(0, 29):
        t = i / 28
        c = p0 + d * t + nrm * bend * 4 * t * (1 - t)
        tg = d + nrm * bend * 4 * (1 - 2 * t)
        tg = tg / np.hypot(*tg)
        nn = np.array([-tg[1], tg[0]])
        hw = w / 2 * (math.sin(math.pi * min(1.0, t ** 0.78)) ** taper)
        left.append(tuple(c + nn * hw))
        right.append(tuple(c - nn * hw))
    return sk.poly(left + right[::-1])


def icon_evasion(ic):
    sk = ic.sk
    # a whirlwind: crescents of wind stacked and swaying, narrowing to the foot
    levels = [(15.5, 5.2, 12.5, 3.0, 3.6), (17.0, 10.6, 11.0, 2.8, 3.6), (15.0, 16.0, 9.0, 2.6, 3.4), (16.6, 21.2, 6.8, 2.3, 3.2), (15.8, 26.0, 4.2, 1.9, 3.0)]
    for i, (cx, cy, rx, ry, w) in list(enumerate(levels))[::-1]:
        pts = [(cx + rx * math.cos(math.radians(a)), cy + ry * math.sin(math.radians(a))) for a in np.linspace(188, -8, 31)]
        half = len(pts) // 2
        arc = sk.path(pts[:half + 1], 1.2, w) | sk.path(pts[half:], w, 1.2)
        ic.fill(arc, BONE, base=0.84 - 0.07 * i, lit=0.3, dark=0.3, sep=0.9)
    for (x, y, k) in ((4.0, 12.0, 4), (27.5, 8.0, 3), (26.5, 19.5, 4)):
        ic.fill(sk.circ(x, y, 1.0), GOLD, base=0.7 if k == 4 else 0.55, lit=0.3, dark=0.3)


def icon_armor(ic):
    sk = ic.sk
    torso = sk.poly([(8.5, 10), (23.5, 10), (22.5, 19), (24.5, 28), (16, 30.4), (7.5, 28), (9.5, 19)])
    ic.fill(torso, BONE, base=0.58, lit=0.26, dark=0.3, grad=0.3)
    # the ridge down the middle and the lames of the belly
    ic.fill(sk.rect(15, 11, 17, 24), BONE, base=0.88, lit=0.1, dark=0.2)
    for y in (19.5, 22.5):
        ic.groove(sk.path([(9.8, y), (22.2, y)], 1.0), deep=0.5, catch=0.15)
    ic.groove(sk.path([(10, 15), (14.5, 17), (14.5, 18)], 1.0), deep=0.5, catch=0.0)
    ic.groove(sk.path([(22, 15), (17.5, 17), (17.5, 18)], 1.0), deep=0.5, catch=0.0)
    ic.fill(sk.poly([(7.6, 25), (24.4, 25), (24.6, 28), (16, 30.4), (7.4, 28)]), GOLD, base=0.58, lit=0.3, dark=0.3, sep=0.7)
    # pauldrons, sloping off the neck
    for sgn in (1, -1):
        def X(x):
            return x if sgn > 0 else 32 - x
        sh = sk.poly([(X(11.5), 6), (X(6.5), 6.8), (X(2.8), 10.5), (X(4.0), 15.0), (X(8.5), 15.5), (X(10.5), 11.5)])
        ic.fill(sh, GOLD, base=0.6, lit=0.3, dark=0.3, grad=0.3, sep=0.8)
        ic.groove(sk.path([(X(4.8), 12.2), (X(9.4), 12.2)], 1.0), deep=0.5, catch=0.2)
    # the collar
    ic.fill(sk.ell(16, 7.2, 4.8, 3.2), IRON, base=0.12, flat=True, sep=0.7)
    ic.fill(sk.arc(16, 7.2, 4.8, 0, 180, 1.6, ry=3.2), GOLD, base=0.6, lit=0.3, dark=0.3)


def icon_stealth(ic):
    sk = ic.sk
    blk = np.array(PAL['blacksteel'], float)
    hood = sk.poly(smooth([(16, 1.5), (23, 4), (26.5, 11), (27, 18), (29, 23.5), (28.5, 28), (24, 30.4), (8, 30.4), (3.5, 28), (3, 23.5), (5, 18), (5.5, 11), (9, 4)], 6))
    ic.fill(hood, blk, base=0.42, lit=0.32, dark=0.24, grad=0.5)
    face = sk.ell(16, 16.5, 6.4, 8.2)
    ic.fill(face, (8, 7, 10), base=0.5, flat=True)
    ring = grow(face) & ~face
    ic.fill(ring, GOLD, base=0.58, flat=False, lit=0.2, dark=0.3)
    ic.px(13, 16, BONE[5])
    ic.px(14, 16, BONE[5])
    ic.px(18, 16, BONE[5])
    ic.px(19, 16, BONE[5])
    ic.fill(sk.circ(16, 25, 1.9), GOLD, base=0.62, lit=0.3, dark=0.3, sep=0.8)
    ic.groove(sk.path([(16, 27), (16, 30)], 1.0), deep=0.5, catch=0.0)


def icon_smithing(ic):
    sk = ic.sk
    anvil = sk.poly([(2, 16.5), (9, 15), (28, 15), (28, 19), (23, 20.5), (21.5, 23), (22.5, 26.5), (27, 28), (27, 30.5), (6, 30.5), (6, 28), (10.5, 26.5), (11.5, 23), (9.5, 20.5), (5.5, 19)])
    ic.fill(anvil, IRON, base=0.46, lit=0.3, dark=0.3, grad=0.3)
    ic.fill(sk.rect(9, 15, 28, 17.5), BONE, base=0.62, lit=0.3, dark=0.25)
    ic.fill(sk.rect(2.5, 16, 9, 17.5) & anvil, BONE, base=0.52, lit=0.3, dark=0.25)
    # the hammer, raised above the horn
    handle = sk.seg(14.0, 9.0, 27.0, 2.4, 2.4)
    ic.fill(handle, mat('wood'), base=0.55, lit=0.25, dark=0.25)
    hx, hy, a = 11.0, 9.2, math.radians(-27)
    c, sn = math.cos(a), math.sin(a)
    hw, hh = 5.0, 3.2
    head = sk.poly([(hx + c * x - sn * y, hy + sn * x + c * y) for x, y in ((-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh))])
    ic.fill(head, mat('steel'), base=0.62, lit=0.3, dark=0.3, sep=0.7)
    ic.fill(sk.rect(13.5, 7.8, 15.5, 11.8), GOLD, base=0.6, flat=True)
    # sparks
    for (x, y, k) in ((16.5, 12.5, 5), (20, 13, 4), (13, 13.3, 4), (18.5, 10.5, 3)):
        ic.px(x, y, EMBER[k])


def icon_alchemy(ic):
    sk = ic.sk
    body = sk.circ(16, 20.5, 9.2)
    neck = sk.rect(14, 5, 18, 13)
    flask = body | neck
    ic.fill(flask, np.array(PAL['glass'], float), base=0.5, lit=0.3, dark=0.3, grad=0.3)
    yy = ic.ys
    wave = 19.6 + 0.9 * np.sin(ic.xs * 0.9)
    liquid = body & (yy > wave)
    g = np.array(GEMS['green'], float)
    ic.fill(liquid, g, base=0.55, lit=0.0, dark=0.0, grad=0.3, flat=True)
    surf = liquid & ~moved(liquid, 0, 1)
    ic.paint(surf, g[3])
    ic.paint(liquid & (ic.ys > 27) & (np.hypot(ic.xs - 16, ic.ys - 20.5) > 8), g[1])
    for (x, y, r) in ((12, 23, 1.0), (19, 25, 1.0), (17, 16, 1.0)):
        m = sk.circ(x, y, r)
        ic.paint(m, g[4] if y > 19 else BONE[4])
    # glass shine
    ic.paint(sk.arc(16, 20.5, 7.0, 188, 238, 1.2), BONE[5])
    # lip and stopper
    ic.fill(sk.rect(12, 4, 20, 6.4), GOLD, base=0.6, lit=0.3, dark=0.3, sep=0.7)
    ic.fill(sk.rect(14, 1.2, 18, 4.4), mat('wood'), base=0.55, lit=0.25, dark=0.25, sep=0.6)


def icon_cooking(ic):
    sk = ic.sk
    # flames licking up either side of the pot
    for (x, h, bend) in ((5.5, 9, -1.2), (10.5, 6, 0.5), (21.5, 6, -0.5), (26.5, 9, 1.2)):
        fl = teardrop(sk, x, 30.6 - h, 30.8, 4.8, bend=bend, tc=0.6)
        ic.fill(fl, EMBER, base=0.5, flat=True)
        ic.fill(teardrop(sk, x, 30.6 - h * 0.55, 30.8, 2.4, bend=bend * 0.4, tc=0.6), EMBER, base=0.86, flat=True)
    body = sk.rrect(6.5, 13, 25.5, 26.5, 5.0)
    ic.fill(body, IRON, base=0.5, lit=0.3, dark=0.3, grad=0.45, sep=0.8)
    for (x, y) in ((5.0, 16.8), (27.0, 16.8)):
        ic.fill(sk.circ(x, y, 2.8) & ~sk.circ(x, y, 1.3), GOLD, base=0.55, lit=0.3, dark=0.3)
    rim = sk.ell(16, 13.5, 10.6, 3.3)
    ic.fill(rim, GOLD, base=0.62, lit=0.3, dark=0.3, sep=0.7)
    soup = sk.ell(16, 13.8, 8.4, 1.9)
    ic.fill(soup, EMBER, base=0.3, flat=True)
    ic.paint(soup & (ic.ys < 13.6) & (ic.xs < 17), EMBER[2])
    # steam
    for (x0, k) in ((12.5, 0), (19.5, 1)):
        pts = [(x0 + 1.9 * math.sin(i * 1.25 + 0.5 * k), 10.2 - i * 1.4) for i in range(7)]
        ic.fill(sk.path(pts, 1.6, 1.0), BONE, base=0.62, flat=True)


def icon_enchanting(ic):
    sk = ic.sk
    cover = sk.poly([(2, 12), (2, 27.5), (16, 31), (30, 27.5), (30, 12), (16, 15), ])
    ic.fill(cover, GOLD, base=0.5, lit=0.25, dark=0.3)
    lp = sk.poly([(3.5, 12), (15, 15), (15, 28), (3.5, 25)])
    rp = sk.poly(mirror([(3.5, 12), (15, 15), (15, 28), (3.5, 25)]))
    ic.fill(lp, BONE, base=0.74, lit=0.22, dark=0.24, grad=0.3, sep=0.6)
    ic.fill(rp, BONE, base=0.70, lit=0.22, dark=0.24, grad=0.3, sep=0.6)
    for k in range(4):
        y = 17.5 + 2.4 * k
        ic.groove(sk.path([(5.5, y - 3.2 + 0.1), (13, y - 0.9)], 0.9), deep=0.38, catch=0.0)
        ic.groove(sk.path([(26.5, y - 3.2 + 0.1), (19, y - 0.9)], 0.9), deep=0.38, catch=0.0)
    ic.fill(sk.rect(15, 14.5, 17, 29), (30, 24, 34), flat=True)
    # the glow rising from the pages
    ic.after(lambda: ic.halo(16, 7.5, 9.5, (150, 120, 255), 0.55, 2.0))
    star = sk.star(16, 7.5, 6.2, 1.5, 4)
    ic.after(lambda: (ic.paint(star, (240, 232, 255)), ic.paint(sk.star(16, 7.5, 3.0, 1.2, 4), (255, 255, 255))))
    for (x, y) in ((6.5, 7.0), (26.5, 9.0), (10.5, 3.0)):
        ic.after(lambda x=x, y=y: ic.px(x, y, (206, 190, 255)))


def icon_mining(ic):
    sk = ic.sk
    u = np.array([1.0, -1.0]) / math.sqrt(2)
    v = np.array([1.0, 1.0]) / math.sqrt(2)
    H = np.array([21.0, 11.0])
    for (x, y, r, k) in ((24.5, 26.8, 3.0, 0), (19.5, 28.2, 2.0, 1)):
        ic.fill(sk.circ(x, y, r), np.array(PAL['stone'], float), base=0.5 + 0.1 * k, lit=0.3, dark=0.3, sep=0.7)
    ic.paint(sk.circ(25.5, 25.5, 0.9), GEMS['blue'][3])
    handle = sk.seg(*(H - u * 21), *(H - u * 1), 3.4)
    ic.fill(handle, mat('wood'), base=0.55, lit=0.28, dark=0.28)
    pts = [tuple(H + v * (s * 11.0) - u * (4.6 * s * s) + u * 0.8) for s in np.linspace(-1, 1, 25)]
    head = sk.path(pts[:13], 1.6, 7.0) | sk.path(pts[12:], 7.0, 1.6)
    ic.fill(head, mat('steel'), base=0.62, lit=0.3, dark=0.3, sep=0.8, grad=0.25)
    ic.fill(sk.circ(*H, 3.0), GOLD, base=0.6, lit=0.3, dark=0.3, sep=0.8)


def icon_woodcutting(ic):
    sk = ic.sk
    wood = mat('wood')
    log = sk.rrect(2.5, 21.0, 26.5, 30.0, 3.0)
    ic.fill(log, wood, base=0.5, lit=0.3, dark=0.3, grad=0.4)
    for (x0, x1, y) in ((5, 12, 24.2), (13, 21, 26.4), (7, 15, 28.6)):
        ic.groove(sk.path([(x0, y), (x1, y)], 1.0), deep=0.45, catch=0.1)
    end = sk.ell(26.5, 25.5, 3.2, 4.6)
    ic.fill(end, wood, base=0.82, lit=0.15, dark=0.3, sep=0.8)
    ic.groove(sk.ell(26.5, 25.5, 1.9, 2.8) & ~sk.ell(26.5, 25.5, 0.9, 1.7), deep=0.4, catch=0.0)
    handle = sk.seg(27.5, 2.0, 15.5, 15.5, 2.8)
    ic.fill(handle, GOLD, base=0.5, lit=0.28, dark=0.28)
    head = sk.poly([(18.0, 11.2), (12.0, 11.2), (6.5, 6.8), (4.2, 10.5), (3.4, 15.0), (4.2, 19.6), (6.5, 23.2), (12.0, 18.8), (18.0, 18.8)])
    ic.fill(head, mat('steel'), base=0.62, lit=0.3, dark=0.3, sep=0.8, grad=0.3)
    ic.groove(sk.path([(8.5, 12.5), (13.0, 14.2)], 1.0), deep=0.4, catch=0.1)
    ic.fill(sk.rect(14.4, 11.6, 18.6, 18.6), GOLD, base=0.58, lit=0.3, dark=0.3, sep=0.7)


def icon_farming(ic):
    sk = ic.sk
    moss = np.array(PAL['moss'], float)
    for sgn in (-1, 1):
        ic.fill(cleaf(sk, (16, 30.4), (16 + sgn * 11.5, 14.0), 5.0, 1.8 * sgn, taper=0.7), moss, base=0.56 if sgn < 0 else 0.46, lit=0.3, dark=0.3, grad=0.3)
        ic.groove(sk.path([(16 + sgn * 1.8, 28.0), (16 + sgn * 8.5, 18.0)], 0.9), deep=0.3, catch=0.0)
    ic.fill(sk.rect(15, 12, 17, 31), GOLD, base=0.4, lit=0.25, dark=0.25)
    # whiskers up either side of the ear
    for sgn in (-1, 1):
        ic.fill(sk.seg(16 + sgn * 3.0, 9.0, 16 + sgn * 4.4, 1.2, 0.9), GOLD, base=0.8, flat=True)
        ic.fill(sk.seg(16 + sgn * 1.4, 5.0, 16 + sgn * 2.0, 0.4, 0.9), GOLD, base=0.7, flat=True)
    for k, y in enumerate((8.4, 11.4, 14.4, 17.4, 20.4)):
        for sgn in (1, -1):
            m = grain_shape(sk, 16 + sgn * 2.0, y, sgn * 0.55, -0.83, 2.9, 1.55)
            ic.fill(m, GOLD, base=0.7 if sgn < 0 else 0.58, lit=0.3, dark=0.3, sep=0.55)
    ic.fill(grain_shape(sk, 16, 5.4, 0, -1, 3.0, 1.4), GOLD, base=0.72, lit=0.3, dark=0.3, sep=0.55)
    ic.fill(sk.rect(13.6, 22.4, 18.4, 24.8), np.array(PAL['crimson'], float), base=0.58, lit=0.3, dark=0.3, sep=0.8)


def icon_fishing(ic):
    sk = ic.sk
    ic.fill(sk.seg(27.0, 3.0, 15.5, 12.4, 1.0), BONE, base=0.62, flat=True)
    pts = [(21.0 + 5.5 * math.cos(math.radians(a)), 23.0 + 5.5 * math.sin(math.radians(a))) for a in np.linspace(180, 0, 22)]
    hook = sk.path([(15.5, 12.5), (15.5, 23.0)], 2.3, 2.3) | sk.path(pts, 2.3, 2.3) | sk.path([pts[-1], (26.5, 17.5)], 2.3, 1.6)
    ic.fill(hook, BONE, base=0.62, lit=0.3, dark=0.3, sep=0.8)
    ic.fill(sk.poly([(26.5, 16.6), (23.4, 19.8), (24.8, 17.0)]), BONE, base=0.7, lit=0.3, dark=0.3)
    ic.fill(sk.circ(15.5, 12.0, 2.0) & ~sk.circ(15.5, 12.0, 0.9), BONE, base=0.6, lit=0.3, dark=0.3)
    # the float, riding the line
    fl = sk.circ(23.0, 7.0, 3.4)
    ic.fill(fl, BONE, base=0.8, lit=0.2, dark=0.3, sep=0.7)
    ic.fill(fl & (ic.ys < 7.0), np.array(PAL['crimson'], float), base=0.68, lit=0.3, dark=0.2)
    ic.fill(sk.rect(22, 2.0, 24, 4.0), BONE, base=0.6, flat=True)


def icon_trade(ic):
    sk = ic.sk
    ic.fill(sk.rect(15, 8, 17, 27), GOLD, base=0.52, lit=0.3, dark=0.3)
    ic.fill(sk.poly([(9, 30), (23, 30), (21, 26.5), (11, 26.5)]), GOLD, base=0.58, lit=0.3, dark=0.3, sep=0.7)
    # beam tipped toward the full pan
    L, R = (5.5, 8.4), (26.5, 11.4)
    ic.fill(sk.seg(*L, *R, 2.4), GOLD, base=0.6, lit=0.3, dark=0.3, sep=0.7)
    ic.fill(sk.circ(16, 9.9, 2.6), GOLD, base=0.66, lit=0.3, dark=0.3, sep=0.8)
    for (cx, cy) in (L, R):
        py = cy + 10.5
        ic.fill(sk.path([(cx - 4.5, py), (cx, cy)], 1.0), BONE, base=0.5, flat=True)
        ic.fill(sk.path([(cx + 4.5, py), (cx, cy)], 1.0), BONE, base=0.5, flat=True)
        pan = sk.ell(cx, py, 5.2, 2.0) | sk.poly([(cx - 5.2, py), (cx + 5.2, py), (cx + 3.4, py + 2.6), (cx - 3.4, py + 2.6)])
        ic.fill(pan & (ic.ys >= py - 0.5), GOLD, base=0.56, lit=0.3, dark=0.3, sep=0.7)
    # coins heaped in the low pan
    for (x, y) in ((24.0, 19.6), (28.0, 19.4), (26.0, 18.0)):
        ic.fill(sk.ell(x, y, 2.0, 1.6), GOLD, base=0.72, lit=0.3, dark=0.3, sep=0.6)
    ic.fill(sk.rect(3.5, 17.4, 7.5, 20), mat('steel'), base=0.6, lit=0.3, dark=0.3, sep=0.6)


def icon_sorcery(ic):
    sk = ic.sk
    ic.fill(sk.rect(15, 13, 17, 30), mat('wood'), base=0.55, lit=0.28, dark=0.28)
    for y in (17.5, 25):
        ic.fill(sk.rect(14.2, y, 17.8, y + 1.6), GOLD, base=0.62, lit=0.3, dark=0.3, sep=0.6)
    cradle = sk.arc(16, 7.5, 8.0, 18, 162, 2.5, 2.0, ry=7.0)
    ic.fill(cradle, GOLD, base=0.6, lit=0.3, dark=0.3, sep=0.8)
    ic.fill(sk.poly([(16, 12.4), (13.8, 15), (16, 16.5), (18.2, 15)]), GOLD, base=0.6, lit=0.3, dark=0.3, sep=0.7)
    frost = np.array(GEMS['frost'], float)
    star = sk.star(16, 6.6, 6.4, 2.8, 5)
    ic.fill(star, frost, base=0.86, lit=0.14, dark=0.3, sep=0.7)
    ic.paint(sk.star(16, 6.9, 3.2, 1.5, 5), (255, 255, 255))
    ic.after(lambda: ic.halo(16, 6.6, 10.5, (110, 170, 255), 0.55, 2.0))
    for (x, y) in ((5.5, 11.0), (26.5, 12.0), (7.0, 5.0), (25.5, 5.5)):
        ic.after(lambda x=x, y=y: ic.px(x, y, (186, 218, 255)))


def icon_prayer(ic):
    sk = ic.sk
    c = 16.0
    for k in range(16):
        a = math.radians(k * 22.5 - 90)
        long = k % 2 == 0
        r0, r1 = 9.6, 15.2 if long else 12.8
        half = 0.20 if long else 0.15
        tip = (c + r1 * math.cos(a), c + r1 * math.sin(a))
        b0 = (c + r0 * math.cos(a - half), c + r0 * math.sin(a - half))
        b1 = (c + r0 * math.cos(a + half), c + r0 * math.sin(a + half))
        ic.fill(sk.poly([b0, tip, b1]), GOLD, base=0.66 if long else 0.54, lit=0.3, dark=0.3, sep=0.6)
    ic.fill(sk.circ(c, c, 9.6), GOLD, base=0.46, lit=0.3, dark=0.3, sep=0.8)
    ic.fill(sk.circ(c, c, 7.8), GOLD, base=0.66, lit=0.3, dark=0.3, grad=0.4)
    ic.groove(sk.circ(c, c, 6.2) & ~sk.circ(c, c, 5.2), deep=0.45, catch=0.2)
    ic.fill(sk.rect(15, 11, 17, 21), BONE, base=0.82, lit=0.15, dark=0.25)
    ic.fill(sk.rect(11, 14.2, 21, 16.2), BONE, base=0.82, lit=0.15, dark=0.25)


def icon_guild(ic):
    sk = ic.sk
    cloth = sk.poly([(7.5, 4.5), (24.5, 4.5), (24.5, 26.5), (16, 21.0), (7.5, 26.5)])
    blue = np.array([(14, 16, 34), (26, 30, 60), (38, 44, 84), (54, 64, 116), (78, 94, 158), (118, 136, 204)], float)
    ic.fill(cloth, blue, base=0.42, lit=0.3, dark=0.3, grad=0.5)
    inner = cloth.copy()
    for _ in range(2):
        inner = inner & ~edge(inner)
    ic.fill(cloth & ~inner & (ic.ys > 5.5), GOLD, base=0.6, lit=0.3, dark=0.3)
    # a fold of the cloth
    ic.fill(sk.rect(12.5, 6.5, 13.5, 20) & inner, blue, base=0.5, flat=True)
    # the guild's diamond
    c = (16.0, 13.4)
    rx, ry = 4.6, 5.8
    u = (ic.xs - c[0]) / rx
    v = (ic.ys - c[1]) / ry
    d = np.abs(u) + np.abs(v)
    m = d <= 1.0
    ic.fill(m, GOLD, base=0.7, lit=0.3, dark=0.3, sep=0.7)
    ic.paint(m & (u < 0) & (v < 0), GOLD[5])
    ic.paint(m & (u >= 0) & (v >= 0), GOLD[3])
    ic.paint(m & (np.abs(u) < 0.25) & (np.abs(v) < 0.25), (255, 246, 214))
    # the pole
    ic.fill(sk.rect(8, 2.0, 24, 4.6), GOLD, base=0.62, lit=0.3, dark=0.3, sep=0.7)
    for x in (8, 24):
        ic.fill(sk.circ(x, 3.3, 2.0), GOLD, base=0.7, lit=0.3, dark=0.3, sep=0.7)
    for x in (9.0, 23.0):
        ic.fill(sk.rect(x - 1, 26, x + 1, 29.5), GOLD, base=0.55, lit=0.3, dark=0.3)


# ---- stat icons (32 x 32): a colour each, as the stats are coloured in text

STAT_COLORS = {'vigor': (208, 87, 63), 'focus': (134, 169, 230), 'endurance': (143, 203, 122), 'might': (208, 138, 78),
               'finesse': (224, 204, 134), 'arcana': (168, 139, 227), 'faith': (242, 213, 140)}


def tint_pal(c):
    """Six tones of one colour: cool shadows up to a warm glint, the colour itself fourth."""
    c = np.array(c, float)
    return np.array([tone(c, -0.86), tone(c, -0.62), tone(c, -0.34), c, tone(c, 0.5), tone(c, 0.95)])


def heart_points(cx, cy, w, h, steps=64):
    """A heart, w wide and h tall, its top lobes' dip at (cx, top)."""
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((x, -y))
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    return [(cx + (x - (x0 + x1) / 2) / (x1 - x0) * w, cy + (y - (y0 + y1) / 2) / (y1 - y0) * h) for x, y in pts]


def draw_heart(ic, cx, cy, w, h, col, rim=True, shine=True):
    sk = ic.sk
    pal = tint_pal(col)
    heart = sk.poly(heart_points(cx, cy, w, h))
    ic.fill(heart, pal, base=0.62, lit=0.22, dark=0.3, grad=0.5)
    if rim:
        inner = heart.copy()
        for _ in range(2):
            inner = inner & ~edge(inner)
        ic.fill(heart & ~inner, GOLD, base=0.58, lit=0.3, dark=0.3, grad=0.3)
        ic.fill(inner, pal, base=0.62, lit=0.2, dark=0.3, grad=0.6)
    if shine:
        hx, hy = cx - w * 0.22, cy - h * 0.24
        ic.paint(sk.ell(hx, hy, w * 0.1, h * 0.07), pal[5])
        ic.px(hx + w * 0.12, hy + 1.5, pal[4])


def icon_stat_vigor(ic):
    draw_heart(ic, 16, 16.5, 27, 24, STAT_COLORS['vigor'])


def crystal(ic, cx, cy, w, h, col, top=0.30, bot=0.24, glint=True):
    """A double-pointed crystal: a prism of three faces under a pyramid at either end, lit from the upper left."""
    sk = ic.sk
    pal = tint_pal(col)
    x0, x1 = cx - w / 2, cx + w / 2
    xa, xb = cx - w * 0.17, cx + w * 0.17
    t0, b1 = cy - h / 2, cy + h / 2
    sh, sl = t0 + h * top, b1 - h * bot
    ic.fill(sk.poly([(cx, t0), (x1, sh), (x1, sl), (cx, b1), (x0, sl), (x0, sh)]), pal, base=0.5, flat=True)
    faces = [([(cx, t0), (x0, sh), (xa, sh)], 0.80), ([(cx, t0), (xa, sh), (xb, sh)], 0.97), ([(cx, t0), (xb, sh), (x1, sh)], 0.55),
             ([(x0, sh), (xa, sh), (xa, sl), (x0, sl)], 0.66), ([(xa, sh), (xb, sh), (xb, sl), (xa, sl)], 0.86),
             ([(xb, sh), (x1, sh), (x1, sl), (xb, sl)], 0.34),
             ([(x0, sl), (xa, sl), (cx, b1)], 0.50), ([(xa, sl), (xb, sl), (cx, b1)], 0.64), ([(xb, sl), (x1, sl), (cx, b1)], 0.22)]
    for poly, t in faces:
        ic.fill(sk.poly(poly), pal, base=t, flat=True)
    if glint:
        ic.paint(sk.rect(x0 + 1, sh + 1, x0 + 2, sh + 1 + h * 0.22), pal[5])
        ic.px(xa - 1, sh - 1, pal[5])


def icon_stat_focus(ic):
    col = STAT_COLORS['focus']
    crystal(ic, 5.5, 21.5, 6.0, 12.0, col, glint=False)
    crystal(ic, 26.5, 12.5, 6.0, 12.0, col, glint=False)
    crystal(ic, 16.0, 16.0, 14.0, 28.0, col)
    ic.after(lambda: ic.halo(16, 16, 16, (120, 170, 255), 0.22, 2.2))


def icon_stat_endurance(ic):
    sk = ic.sk
    pal = tint_pal(STAT_COLORS['endurance'])
    for sgn in (1, -1):
        def X(x):
            return x if sgn > 0 else 32 - x
        lung = sk.poly(smooth([(X(14.0), 12.5), (X(9.5), 11.5), (X(5.0), 15.5), (X(3.2), 21.5), (X(4.2), 27.0), (X(8.5), 28.8), (X(13.5), 26.5)], 8))
        ic.fill(lung, pal, base=0.6, lit=0.24, dark=0.3, grad=0.5)
        for (a, b2, c) in (((X(13.2), 14.5), (X(9.6), 18.5), (X(7.4), 21.0)), ((X(13.2), 17.5), (X(9.6), 22.0), (X(7.4), 25.2)), ((X(10.8), 16.0), (X(8.0), 15.5), (X(6.2), 17.0))):
            ic.groove(sk.path([a, b2, c], 1.0), deep=0.5, catch=0.15)
    ic.fill(sk.rect(14, 3, 18, 12), GOLD, base=0.58, lit=0.3, dark=0.3)
    for y in (5.5, 8.0, 10.5):
        ic.groove(sk.rect(14, y, 18, y + 1), deep=0.45, catch=0.0)
    for sgn in (1, -1):
        X = (lambda x: x) if sgn > 0 else (lambda x: 32 - x)
        ic.fill(sk.path([(X(17), 11.5), (X(14.5), 14.2), (X(13.4), 17.6)], 2.4, 2.0), GOLD, base=0.55, lit=0.3, dark=0.3, sep=0.7)


def icon_stat_might(ic):
    sk = ic.sk
    pal = tint_pal(STAT_COLORS['might'])
    # the fingers' knuckles, four in a row, then the curled fingers over the palm
    for i in range(4):
        x = 5.6 + 5.2 * i
        ic.fill(sk.rrect(x, 4.5, x + 5.0, 14.5, 2.4), pal, base=0.6, lit=0.26, dark=0.3, grad=0.4, sep=0.7)
    ic.fill(sk.rrect(5.4, 13.0, 26.6, 22.5, 3.0), pal, base=0.58, lit=0.26, dark=0.3, grad=0.4, sep=0.7)
    for i in range(1, 4):
        ic.groove(sk.path([(5.6 + 5.2 * i - 0.1, 13.6), (5.6 + 5.2 * i - 0.1, 17.5)], 1.0), deep=0.55, catch=0.1)
    ic.fill(sk.path([(10.0, 20.6), (19.5, 18.6)], 4.6, 4.0), pal, base=0.7, lit=0.26, dark=0.3, sep=0.6)
    ic.fill(sk.rect(7.5, 22.5, 25.5, 29.0), GOLD, base=0.56, lit=0.3, dark=0.3, sep=0.9)
    ic.groove(sk.rect(7.5, 25, 25.5, 26), deep=0.45, catch=0.2)
    for x in (10.0, 22.0):
        ic.px(x, 23.5, GOLD[5])
    ic.glint(9, 7, arms=0, col=pal[5])


def icon_stat_finesse(ic):
    sk = ic.sk
    pal = tint_pal(STAT_COLORS['finesse'])
    base, tip = np.array((10.0, 22.0)), np.array((27.5, 3.5))
    d = tip - base
    L = np.hypot(*d)
    a = d / L
    nrm = np.array((-a[1], a[0]))
    bend = -2.2
    vane = cleaf(sk, tuple(base), tuple(tip), 9.4, bend, taper=0.75)
    ic.fill(vane, pal, base=0.66, lit=0.24, dark=0.3, grad=0.5)
    cen = lambda t: base + d * t + nrm * bend * 4 * t * (1 - t)
    for i in range(1, 11):
        c = cen(i / 11.5)
        for sgn in (1, -1):
            e = c + nrm * sgn * 3.2 + a * 2.2
            ic.groove(sk.path([tuple(c), tuple(e)], 0.9) & vane, deep=0.34, catch=0.0)
    rachis = [tuple(cen(t)) for t in np.linspace(0.0, 0.98, 14)]
    ic.paint(sk.path(rachis, 1.1) & vane, pal[5])
    # the quill, with a nib
    ic.fill(sk.seg(*base, 4.6, 27.4, 2.2, 2.0), BONE, base=0.78, lit=0.2, dark=0.3, sep=0.6)
    ic.fill(sk.poly([(5.6, 26.2), (3.4, 28.4), (2.0, 30.4), (1.8, 27.6), (4.0, 25.4)]), GOLD, base=0.6, lit=0.3, dark=0.3, flat=True)


def icon_stat_arcana(ic):
    sk = ic.sk
    pal = tint_pal(STAT_COLORS['arcana'])
    ic.fill(compass_star(sk, 16, 16, 15.8, 12.2, 5.8), pal, base=0.62, lit=0.26, dark=0.3, grad=0.4)
    round_part(ic, 16, 16, sk.circ(16, 16, 7.2), GOLD, base=0.56, lit=0.34, dark=0.3, width=1.4)
    ic.paint(sk.circ(16, 16, 5.4), (16, 12, 26))
    # the rune Algiz, glowing
    for (x0, y0, x1, y1) in ((12.9, 12.3, 16, 16.3), (19.1, 12.3, 16, 16.3), (16, 16.3, 16, 20.6)):
        ic.paint(sk.seg(x0, y0, x1, y1, 1.7), pal[5])
    ic.after(lambda: ic.halo(16, 16, 17, (170, 130, 255), 0.30, 2.0))


def icon_stat_faith(ic):
    sk = ic.sk
    pal = tint_pal(STAT_COLORS['faith'])
    # a spark of light rising from the cup
    ic.paint(sk.star(16, 5.4, 5.6, 1.3, 4), pal[5])
    ic.paint(sk.rect(15, 6, 17, 11), pal[4])
    cup = sk.poly(smooth([(6.5, 11.8), (25.5, 11.8), (24.5, 16.8), (20.5, 20.6), (16, 21.8), (11.5, 20.6), (7.5, 16.8)], 7))
    ic.fill(cup, GOLD, base=0.58, lit=0.28, dark=0.3, grad=0.5)
    ic.groove(sk.path([(9.6, 15.6), (22.4, 15.6)], 1.0), deep=0.4, catch=0.15)
    ic.fill(sk.ell(16, 11.8, 9.8, 2.5), GOLD, base=0.84, lit=0.2, dark=0.3, sep=0.8)
    ic.paint(sk.ell(16, 11.9, 8.2, 1.5), (255, 226, 148))
    ic.paint(sk.ell(15, 11.6, 4.4, 0.8), (255, 248, 214))
    ic.fill(sk.rect(14.4, 21, 17.6, 26.4), GOLD, base=0.6, lit=0.3, dark=0.3)
    round_part(ic, 16, 23.6, sk.circ(16, 23.6, 2.7), GOLD, base=0.68, lit=0.3, dark=0.3)
    ic.fill(sk.poly([(9.5, 29.8), (22.5, 29.8), (20.5, 26.4), (11.5, 26.4)]), GOLD, base=0.58, lit=0.3, dark=0.3, sep=0.8)
    ic.after(lambda: ic.halo(16, 6.5, 10.0, (255, 224, 130), 0.5, 2.0))


# ---- glyphs (20 x 20): the small marks in the small nodes

def icon_glyph_health(ic):
    draw_heart(ic, 10, 10.2, 15.5, 14, STAT_COLORS['vigor'], rim=False)


def icon_glyph_stamina(ic):
    sk = ic.sk
    pal = np.array([(18, 34, 16), (38, 76, 28), (70, 130, 48), (118, 188, 76), (178, 224, 106), (234, 246, 170)], float)
    bolt = sk.poly([(12.6, 1.2), (3.6, 11.6), (8.6, 11.6), (6.4, 18.8), (16.4, 8.0), (11.2, 8.0)])
    ic.fill(bolt, pal, base=0.64, lit=0.26, dark=0.3, grad=0.5)
    ic.px(11, 3, pal[5])
    ic.px(10, 4, pal[5])


def icon_glyph_focus(ic):
    crystal(ic, 10, 10, 9.0, 17.6, STAT_COLORS['focus'])


def icon_glyph_damage(ic):
    sk = ic.sk
    for (hx, hy, tx, ty, b) in ((3.4, 16.6, 16.8, 3.2, 0.92), (16.6, 16.6, 3.2, 3.2, 0.74)):
        ic.fill(sk.seg(hx, hy, tx, ty, 3.2, 1.2, caps=(False, False)), BONE, base=b, lit=0.3, dark=0.3, sep=0.9)
        u = np.array((tx - hx, ty - hy)) / math.hypot(tx - hx, ty - hy)
        v = np.array((-u[1], u[0]))
        g = np.array((hx, hy)) + u * 3.6
        ic.fill(sk.seg(*(g - v * 3.2), *(g + v * 3.2), 1.9, caps=(True, True)), GOLD, base=0.62, lit=0.3, dark=0.3, sep=0.9)
        p = np.array((hx, hy)) - u * 0.4
        ic.fill(sk.circ(*p, 1.5), GOLD, base=0.62, lit=0.3, dark=0.3, sep=0.9)


def icon_glyph_defence(ic):
    sk = ic.sk
    pts = [(3.5, 2.5), (16.5, 2.5), (16.5, 9.5)] + bez((16.5, 9.5), (16.5, 14.5), (13, 17), (10, 18.6))[1:] + bez((10, 18.6), (7, 17), (3.5, 14.5), (3.5, 9.5))[1:]
    outer = sk.poly(pts)
    face = outer.copy()
    for _ in range(2):
        face = face & ~edge(face)
    ic.fill(outer, GOLD, base=0.6, lit=0.3, dark=0.3)
    ic.fill(face, IRON, base=0.46, lit=0.2, dark=0.2, grad=0.35)
    ic.fill(sk.rect(9, 4, 11, 16) & face, GOLD, base=0.58, lit=0.25, dark=0.3)


def icon_glyph_speed(ic):
    sk = ic.sk
    top = [(1.2, 6.8), (8.0, 6.8), (11.0, 6.7)] + [(12.4 + 2.5 * math.cos(math.radians(t)), 4.4 + 2.5 * math.sin(math.radians(t))) for t in range(95, -170, -18)]
    ic.fill(sk.path(top, 1.0, 1.9), BONE, base=0.82, lit=0.3, dark=0.3)
    ic.fill(sk.path([(4.0, 10.6), (10.0, 10.5), (17.8, 10.5)], 1.0, 2.0), BONE, base=0.7, lit=0.3, dark=0.3)
    bot = [(1.2, 14.4), (7.0, 14.4), (9.2, 14.5)] + [(10.0 + 2.3 * math.cos(math.radians(t)), 16.6 + 2.3 * math.sin(math.radians(t))) for t in range(-95, 175, 18)]
    ic.fill(sk.path(bot, 1.0, 1.9), BONE, base=0.58, lit=0.3, dark=0.3)


def icon_glyph_craft(ic):
    sk = ic.sk
    ic.fill(sk.seg(3.5, 17.0, 13.0, 7.5, 2.4), mat('wood'), base=0.55, lit=0.25, dark=0.25)
    a = math.radians(-45)
    c, sn = math.cos(a), math.sin(a)
    hx, hy = 12.8, 7.2
    head = sk.poly([(hx + c * x - sn * y, hy + sn * x + c * y) for x, y in ((-3.2, -4.6), (3.2, -4.6), (3.2, 4.6), (-3.2, 4.6))])
    ic.fill(head, mat('steel'), base=0.66, lit=0.3, dark=0.3, sep=0.8)
    ic.px(16.5, 13.5, EMBER[4])
    ic.px(14.5, 15.5, EMBER[3])


def icon_glyph_gather(ic):
    sk = ic.sk
    u = np.array([1.0, -1.0]) / math.sqrt(2)
    v = np.array([1.0, 1.0]) / math.sqrt(2)
    H = np.array([12.0, 7.0])
    ic.fill(sk.seg(*(H - u * 13), *(H - u * 0.5), 2.4), mat('wood'), base=0.55, lit=0.25, dark=0.25)
    pts = [tuple(H + v * (s * 6.6) - u * (2.8 * s * s) + u * 0.6) for s in np.linspace(-1, 1, 17)]
    head = sk.path(pts[:9], 1.2, 4.0) | sk.path(pts[8:], 4.0, 1.2)
    ic.fill(head, mat('steel'), base=0.66, lit=0.3, dark=0.3, sep=0.8)


def icon_glyph_coin(ic):
    sk = ic.sk
    round_part(ic, 10, 10, sk.circ(10, 10, 8.0), GOLD, base=0.5, lit=0.4, dark=0.26, width=1.0)
    round_part(ic, 10, 10, sk.circ(10, 10, 6.0), GOLD, base=0.72, lit=-0.3, dark=-0.3, width=1.0)   # sunk: light and shade swap
    dia = (np.abs(ic.xs - 10) + np.abs(ic.ys - 10)) <= 3.0
    ic.paint(dia, GOLD[3])
    ic.paint(dia & ((ic.xs - 10) + (ic.ys - 10) < 0), GOLD[5])
    ic.paint(dia & ((ic.xs - 10) + (ic.ys - 10) >= 2.5), GOLD[2])
    ic.px(5, 6, GOLD[5])


def icon_glyph_xp(ic):
    sk = ic.sk
    pal = tint_pal((250, 224, 130))
    ic.fill(sk.star(10, 10, 9.0, 2.2, 4), pal, base=0.84, lit=0.1, dark=0.25, flat=False)
    ic.fill(sk.star(10, 10, 4.4, 1.9, 4, rot=-45), pal, base=0.92, flat=True)
    ic.px(5, 4, pal[4])
    ic.px(15, 15, pal[4])


def icon_glyph_flask(ic):
    sk = ic.sk
    glass = np.array(PAL['glass'], float)
    amber = np.array(GEMS['amber'], float)
    body = sk.circ(10, 13.2, 5.9)
    neck = sk.rect(8.0, 4.4, 12.0, 9.6)
    ic.fill(body | neck, glass, base=0.6, lit=0.3, dark=0.3, grad=0.3)
    liquid = body & (ic.ys > 12.4)
    ic.fill(liquid, amber, base=0.56, flat=True, grad=0.3)
    ic.paint(liquid & ~moved(liquid, 0, 1) & (ic.ys < 14), amber[3])
    ic.px(7, 15, amber[4])
    ic.px(7, 8, BONE[5])
    ic.px(7, 9, BONE[4])
    ic.fill(sk.rect(7.2, 3.2, 12.8, 5.2), GOLD, base=0.62, lit=0.3, dark=0.3, sep=0.7)
    ic.fill(sk.rect(8.2, 0.6, 11.8, 3.4), mat('wood'), base=0.55, lit=0.25, dark=0.25, sep=0.6)


def icon_glyph_stealth(ic):
    sk = ic.sk
    xs = np.linspace(0.8, 19.2, 20)
    hh = 5.0 * np.clip(1 - ((xs - 10) / 9.2) ** 2, 0, 1) ** 0.7
    eye = sk.poly([(x, 10 - h) for x, h in zip(xs, hh)] + [(x, 10 + h) for x, h in zip(xs[::-1], hh[::-1])])
    ic.fill(eye, BONE, base=0.84, lit=0.18, dark=0.3)
    round_part(ic, 10, 10, sk.circ(10, 10, 4.3), GOLD, base=0.62, lit=0.3, dark=0.3)
    ic.paint(sk.circ(10, 10, 2.0), (12, 10, 14))
    ic.px(8, 8, BONE[5])
    slash = sk.seg(18.5, 1.5, 1.5, 18.5, 1.2)
    ic.paint(grow(grow(slash)) & ic.cv.solid, (20, 16, 24))
    ic.paint(slash, GOLD[5])


def icon_glyph_luck(ic):
    sk = ic.sk
    top = sk.poly([(10, 2.0), (17.0, 5.5), (10, 9.0), (3.0, 5.5)])
    left = sk.poly([(3.0, 5.5), (10, 9.0), (10, 18.0), (3.0, 14.2)])
    right = sk.poly([(17.0, 5.5), (10, 9.0), (10, 18.0), (17.0, 14.2)])
    ic.fill(top, BONE, base=0.92, lit=0.1, dark=0.2, flat=True)
    ic.fill(left, BONE, base=0.7, lit=0.1, dark=0.2, flat=True)
    ic.fill(right, BONE, base=0.46, lit=0.1, dark=0.2, flat=True)
    pip = (60, 20, 22)
    ic.paint(sk.circ(10, 5.5, 1.1), pip)
    for (x, y) in ((5.3, 8.4), (7.8, 12.6)):
        ic.paint(sk.circ(x, y, 0.9), pip)
    for (x, y) in ((12.2, 9.6), (14.2, 11.4), (12.2, 13.2)):
        ic.paint(sk.circ(x, y, 0.8), (36, 12, 14))


def icon_glyph_reach(ic):
    sk = ic.sk
    for i, (x, h) in enumerate(((4.0, 9.0), (7.0, 11.0), (10.0, 11.5), (13.0, 9.5))):
        ic.fill(sk.rrect(x, 15.0 - h, x + 2.6, 12.0, 1.3), BONE, base=0.76 - 0.04 * i, lit=0.3, dark=0.3, sep=0.7)
    ic.fill(sk.rrect(3.6, 9.5, 16.0, 16.5, 2.6), BONE, base=0.7, lit=0.3, dark=0.3, sep=0.7)
    ic.fill(sk.path([(4.5, 13.0), (1.8, 9.2)], 2.4, 2.0), BONE, base=0.76, lit=0.3, dark=0.3, sep=0.8)
    ic.fill(sk.rect(4.5, 16.2, 15.5, 19.0), GOLD, base=0.58, lit=0.3, dark=0.3, sep=0.8)


def icon_glyph_resist(ic):
    sk = ic.sk
    round_part(ic, 10, 10, sk.circ(10, 10, 8.4), GOLD, base=0.56, lit=0.4, dark=0.3, width=1.2)
    inner = sk.circ(10, 10, 6.2)
    ic.paint(inner, (16, 14, 24))
    ic.paint(inner & ~moved(inner, -1, -1) & (ic.xs + ic.ys > 20), GOLD[2])
    for (x0, y0, x1, y1) in ((6.6, 6.2, 10, 10.6), (13.4, 6.2, 10, 10.6), (10, 10.6, 10, 15.6)):
        ic.paint(sk.seg(x0, y0, x1, y1, 1.7), BONE[5])


def icon_glyph_skill(ic):
    sk = ic.sk
    cover = np.array(PAL['crimson'], float)
    ic.fill(sk.rrect(4.0, 2.5, 16.0, 17.5, 1.6), cover, base=0.5, lit=0.3, dark=0.3, grad=0.3)
    ic.fill(sk.rect(15.0, 4.0, 17.0, 16.4), BONE, base=0.8, lit=0.1, dark=0.3, sep=0.7)
    ic.fill(sk.rect(4.0, 2.5, 6.4, 17.5), cover, base=0.38, lit=0.2, dark=0.3)
    d = (np.abs(ic.xs - 10.6) + np.abs(ic.ys - 9.5)) <= 3.4
    ic.fill(d, GOLD, base=0.68, lit=0.3, dark=0.3, sep=0.6)
    for y in (4.2, 14.8):
        ic.fill(sk.rect(4.0, y, 16.0, y + 0.8), GOLD, base=0.56, flat=True)


# ================================================================ sockets

def octa(dx, dy):
    """The distance to the middle measured so its level sets are regular octagons (flat sides on the axes), and
    the light on each facet: +1 facing it, -1 facing away."""
    ax, ay = np.abs(dx), np.abs(dy)
    diag = (ax + ay) / math.sqrt(2)
    d = np.maximum(np.maximum(ax, ay), diag)
    on_diag = (diag > ax) & (diag > ay)
    nx = np.where(on_diag, np.sign(dx) * 0.7071, np.where(ax >= ay, np.sign(dx), 0.0))
    ny = np.where(on_diag, np.sign(dy) * 0.7071, np.where(ay > ax, np.sign(dy), 0.0))
    return d, nx * LXY[0] + ny * LXY[1]


def maps(n, ss=1, c=None):
    """Offsets from the middle of an n-texel sprite, sampled `ss` times per texel, with the round and the octagonal
    distance, and the light on each."""
    c = n / 2 if c is None else c
    N = n * ss
    ys, xs = np.mgrid[0:N, 0:N]
    dx, dy = (xs + 0.5) / ss - c, (ys + 0.5) / ss - c
    r = np.hypot(dx, dy)
    rr = np.maximum(r, 1e-6)
    rl = (dx / rr) * LXY[0] + (dy / rr) * LXY[1]
    o, ol = octa(dx, dy)
    return dict(x=dx, y=dy, r=r, rl=rl, o=o, ol=ol)


def ring_shader(pal, base, k, facing=1, metric='r', dither=0.0, g=1.0, alpha=1.0):
    """A band of metal: lit by the slope's angle (a slope facing out is bright toward the upper left)."""
    def f(S, m):
        lf = S.t['rl'] if metric == 'r' else S.t['ol']
        col = bands(pal, clamp01(base + k * facing * lf), dither) * (1 + (S.g[..., None] - 1) * g)
        return col, alpha
    return f


def flat_shader(col, alpha=1.0):
    def f(S, m):
        return np.broadcast_to(np.asarray(col, float), (S.n, S.n, 3)), alpha
    return f


def dome_shader(cx, cy, r, pal, base=0.6, k=0.7):
    """A little round stud: a dome lit from the upper left."""
    def f(S, m):
        xs, ys = S.t['x'] + S.n / 2, S.t['y'] + S.n / 2
        dd = np.hypot(xs - cx, ys - cy)
        dome = np.sqrt(clamp01(1 - (dd / r) ** 2))
        lit = -((xs - cx) * LXY[0] + (ys - cy) * LXY[1]) / max(r, 1e-6)
        t = base + k * (0.42 * lit * (1 - dome * 0.5) + 0.32 * (dome - 0.55))
        return bands(pal, clamp01(t), 0.0), 1.0
    return f


def gem_shader(cx, cy, rx, ry, pal, base=0.55, k=0.5):
    """A rhombus cut like a gem: the upper-left facet bright, the lower-right dark."""
    def f(S, m):
        xs, ys = S.t['x'] + S.n / 2, S.t['y'] + S.n / 2
        u, v = (xs - cx) / rx, (ys - cy) / ry
        fac = np.where((u < 0) & (v < 0), 1.0, np.where((u >= 0) & (v >= 0), -1.0, 0.0))
        t = base + k * (0.5 * fac + 0.25 * (1 - np.clip(np.abs(u) + np.abs(v), 0, 1)))
        return bands(pal, clamp01(t), 0.0), 1.0
    return f


class Socket:
    """A node socket made of bands painted from the outside in. Each band is a mask in a supersampled picture (a
    ring of the round or octagonal distance, a disc, a polygon); a texel takes the band that covers most of it,
    which keeps thin rings clean. A band's shader then colours its texels."""

    def __init__(self, n, seed):
        self.n = n
        self.h = maps(n, SS)
        self.t = maps(n, 1)
        self.g = grain(n, n, seed)
        self.idx = np.zeros((n * SS, n * SS), np.int16)
        self.shaders = [None]

    def put(self, hmask, shader):
        self.shaders.append(shader)
        self.idx[hmask] = len(self.shaders) - 1

    def ring(self, r0, r1, shader, metric='r'):
        d = self.h['r'] if metric == 'r' else self.h['o']
        self.put((d <= r0) & (d > r1), shader)

    def disc(self, cx, cy, r, shader, line=0.0):
        """A disc at (cx, cy) in texels; `line` adds a dark ring round it first."""
        d = np.hypot(self.h['x'] + self.n / 2 - cx, self.h['y'] + self.n / 2 - cy)
        if line:
            self.put(d <= r + line, flat_shader(OUT))
        self.put(d <= r, shader)

    def render(self):
        n = self.n
        blocks = self.idx.reshape(n, SS, n, SS).transpose(0, 2, 1, 3).reshape(n, n, SS * SS)
        ids = len(self.shaders)
        counts = np.stack([(blocks == i).sum(-1) for i in range(ids)], -1)
        counts[..., 0] -= SS * SS // 2          # empty only wins when it covers more than half
        tex = counts.argmax(-1)
        cv = Canvas(n)
        for i in range(1, ids):
            m = tex == i
            if m.any():
                col, a = self.shaders[i](self, m)
                cv.put(m, col, a)
        self.tex = tex
        return cv


PIT = np.array((14.0, 13.0, 18.0))
AMBER = np.array((236.0, 150.0, 46.0))


def pit_shader(rp, state, strength=1.0, dy=0.0):
    """The hollow in the middle: near black so an icon reads on it, shadowed under the upper left of the rim and
    catching a little light under the lower right; warmer toward the rim the further the node has come."""
    def f(S, m):
        r = np.hypot(S.t['x'], S.t['y'] - dy)
        t = clamp01(r / rp)
        col = PIT * (1 + 0.30 * (t ** 3) * (-S.t['rl']))[..., None]
        if state == 'open':
            col = mix(col, (170.0, 122.0, 58.0), (t ** 3.0) * 0.30 * strength)
        elif state == 'lit':
            col = mix(col, AMBER, (t ** 3.0) * 0.52 * strength)
        return col, 0.95
    return f


def tones(state):
    """(rim palette, body palette, body base tone): what a state's metal is made of."""
    return {'locked': (IRON, IRON, 0.46), 'open': (PALE, IRON, 0.46), 'lit': (GOLD, GOLD, 0.60)}[state]


def socket_minor(state, seed=0):
    s = Socket(28, seed)
    rim, body, base = tones(state)
    hi = 0.64 if state != 'locked' else 0.52
    s.ring(14.4, 0, flat_shader(OUT, 0.94))
    s.ring(13.4, 0, ring_shader(rim, hi, 0.40, g=0.6))
    s.ring(12.4, 0, ring_shader(body, base, 0.16, g=0.8))
    s.ring(11.3, 0, ring_shader(body, base - 0.14, 0.34, facing=-1, g=0.5))
    s.ring(10.3, 0, flat_shader((8, 7, 10), 0.95))
    s.ring(9.3, 0, pit_shader(9.3, state))
    return s.render().image()


def socket_notable(state, seed=0, variant=0):
    s = Socket(44, seed)
    rim, body, base = tones(state)
    hi = 0.64 if state != 'locked' else 0.52
    sp = {'locked': IRON, 'open': PALE, 'lit': GOLD}[state]
    sb = 0.66 if state != 'locked' else 0.62
    s.ring(22.4, 0, flat_shader(OUT, 0.94))
    s.ring(21.4, 0, ring_shader(rim, hi, 0.40, g=0.6))
    s.ring(20.2, 0, ring_shader(body, base, 0.16, g=0.8))
    s.ring(19.0, 0, ring_shader(body, base - 0.14, 0.34, facing=-1, g=0.5))
    s.ring(18.0, 0, flat_shader(OUT, 0.97))
    s.ring(17.0, 0, ring_shader(body, base + 0.04, 0.34, g=0.7))
    s.ring(15.9, 0, ring_shader(body, base - 0.10, 0.30, facing=-1, g=0.5))
    s.ring(14.9, 0, flat_shader((8, 7, 10), 0.95))
    s.ring(13.9, 0, pit_shader(13.9, state))
    if variant == 0:
        for sx, sy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            cx, cy = 22 + sx * 12.9, 22 + sy * 12.9
            s.disc(cx, cy, 2.6, dome_shader(cx, cy, 2.6, sp, sb), line=0.9)
    elif variant == 1:
        for sx, sy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            cx, cy = 22 + sx * 14.0, 22 + sy * 14.0
            s.disc(cx, cy, 2.4, dome_shader(cx, cy, 2.4, sp, sb), line=0.9)
    elif variant == 2:
        for k in range(8):
            a = k * math.pi / 4 + math.pi / 8
            cx, cy = 22 + 18.5 * math.cos(a), 22 + 18.5 * math.sin(a)
            s.disc(cx, cy, 1.9, dome_shader(cx, cy, 1.9, sp, sb), line=0.8)
    elif variant == 3:
        for k in range(4):
            a = k * math.pi / 2
            cx, cy = 22 + 18.5 * math.cos(a), 22 + 18.5 * math.sin(a)
            s.disc(cx, cy, 2.6, dome_shader(cx, cy, 2.6, sp, sb), line=0.9)
    return s.render().image()


RUNES = {
    # strokes on a 5 x 7 grid, (x0, y0, x1, y1)
    'fehu': [(1, 0, 1, 6), (1, 2, 4, 0), (1, 4, 4, 2)],
    'uruz': [(0, 6, 0, 0), (0, 0, 3, 2), (3, 2, 3, 6)],
    'thurs': [(1, 0, 1, 6), (1, 1, 4, 3), (4, 3, 1, 5)],
    'ansuz': [(1, 0, 1, 6), (1, 0, 4, 2), (1, 2, 4, 4)],
    'raido': [(0, 0, 0, 6), (0, 0, 3, 1), (3, 1, 0, 3), (0, 3, 4, 6)],
    'kenaz': [(3, 0, 0, 3), (0, 3, 3, 6)],
    'gebo': [(0, 0, 4, 6), (4, 0, 0, 6)],
    'algiz': [(0, 0, 2, 3), (4, 0, 2, 3), (2, 3, 2, 6)],
}
RUNE_ORDER = ['fehu', 'ansuz', 'kenaz', 'algiz', 'thurs', 'raido', 'gebo', 'uruz']


def rune_pixels(name):
    px = set()
    for x0, y0, x1, y1 in RUNES[name]:
        n = max(abs(x1 - x0), abs(y1 - y0))
        for i in range(n + 1):
            px.add((round(x0 + (x1 - x0) * i / max(n, 1)), round(y0 + (y1 - y0) * i / max(n, 1))))
    return px


def carve(cv, px, ox, oy, state):
    """Cut a mark into the metal: a dark groove with the lower edge catching the light; in a lit socket it glows."""
    n = cv.w
    pts = {(ox + x, oy + y) for x, y in px}
    if state == 'lit':
        halo = set()
        for x, y in pts:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if (x + dx, y + dy) not in pts:
                    halo.add((x + dx, y + dy))
        for x, y in halo:
            if 0 <= x < n and 0 <= y < n and cv.a[y, x] > 0:
                cv.rgb[y, x] = mix(cv.rgb[y, x], (214.0, 112.0, 30.0), 0.42)
        for x, y in pts:
            cv.px(x, y, (255, 232, 160))
        return
    groove = (20, 19, 26) if state == 'locked' else (58, 48, 36)
    for x, y in pts:
        if 0 <= x < n and 0 <= y < n:
            cv.px(x, y, groove)
    edge_col = (98, 100, 108) if state == 'locked' else (208, 184, 130)
    for x, y in pts:
        if (x + 1, y + 1) not in pts and 0 <= x + 1 < n and 0 <= y + 1 < n and cv.a[y + 1, x + 1] > 0:
            cv.rgb[y + 1, x + 1] = mix(cv.rgb[y + 1, x + 1], edge_col, 0.55)


def socket_keystone(state, seed=0):
    n = 60
    s = Socket(n, seed)
    rim, body, base = tones(state)
    hi = 0.66 if state != 'locked' else 0.54
    sp = {'locked': IRON, 'open': PALE, 'lit': GOLD}[state]
    sb = 0.68 if state != 'locked' else 0.62
    s.ring(30.6, 0, flat_shader(OUT, 0.94), 'o')
    s.ring(29.5, 0, ring_shader(rim, hi, 0.42, g=0.6, metric='o'), 'o')
    s.ring(28.3, 0, ring_shader(body, base + 0.02, 0.16, g=0.8, metric='o'), 'o')
    s.ring(27.2, 0, flat_shader(OUT, 0.97), 'o')
    s.ring(26.2, 0, ring_shader(body, base - (0.02 if state == 'lit' else 0.10), 0.10, g=0.9, metric='o'), 'o')
    # the inner ring, round
    s.ring(19.6, 0, ring_shader(body, base - 0.14, 0.34, facing=-1, g=0.5))
    s.ring(18.6, 0, flat_shader({'locked': (8, 7, 10), 'open': tuple(PALE[3]), 'lit': tuple(GOLD[4])}[state], 0.97))
    s.ring(17.6, 0, pit_shader(17.6, state))
    # studs on the facets' middles, a dome each
    for k in range(8):
        a = k * math.pi / 4
        cx, cy = 30 + 23.1 * math.cos(a), 30 + 23.1 * math.sin(a)
        s.disc(cx, cy, 1.9, dome_shader(cx, cy, 1.9, sp, sb), line=0.8)
    cv = s.render()
    # runes in the corners of the octagon
    for k in range(8):
        a = math.radians(22.5 + 45 * k)
        cx, cy = 30 + 25.4 * math.cos(a), 30 + 25.4 * math.sin(a)
        carve(cv, rune_pixels(RUNE_ORDER[k]), int(round(cx - 2)), int(round(cy - 3)), state)
    return cv.image()


def teardrop(sk, cx, top, bottom, w, bend=0.0, tc=0.62, steps=40):
    """A flame: pointed at the top, leaning by `bend`, widest low down and round at the bottom."""
    left, right = [], []
    for i in range(steps + 1):
        t = i / steps
        y = top + t * (bottom - top)
        if t <= tc:
            hw = w / 2 * (t / tc) ** 1.7
        else:
            hw = w / 2 * math.sqrt(max(0.0, 1 - ((t - tc) / (1 - tc)) ** 2))
        x = cx + bend * (1 - t) ** 2
        left.append((x - hw, y))
        right.append((x + hw, y))
    return sk.poly(left + right[::-1])


def socket_root(seed=0):
    n = 52
    s = Socket(n, seed)
    s.ring(22.4, 0, flat_shader(OUT, 0.94))
    s.ring(21.4, 0, ring_shader(GOLD, 0.70, 0.42, g=0.6))
    s.ring(19.8, 0, ring_shader(GOLD, 0.62, 0.16, g=0.8))
    s.ring(18.0, 0, ring_shader(GOLD, 0.50, 0.34, facing=-1, g=0.5))
    s.ring(16.9, 0, flat_shader(OUT, 0.97))
    s.ring(15.9, 0, ring_shader(IRON, 0.40, 0.34, g=0.7))
    s.ring(14.6, 0, ring_shader(IRON, 0.28, 0.30, facing=-1, g=0.5))
    s.ring(13.7, 0, flat_shader((8, 7, 10), 0.95))

    def pit_f(S, m):
        x, y = S.t['x'], S.t['y'] - 1.0
        r = np.hypot(x, y)
        t = clamp01(r / 12.7)
        col = PIT * (1 + 0.3 * (t ** 3) * (-S.t['rl']))[..., None]
        glow = clamp01(1 - r / 13.0) ** 2.2 * 0.60 + (t ** 3.0) * 0.14
        return mix(col, (232.0, 118.0, 28.0), glow), 0.96
    s.ring(12.7, 0, pit_f)
    # four gilt diamonds on the cardinals, a stud on each diagonal
    for (cx, cy, rx, ry) in ((26, 5.6, 3.8, 5.6), (26, 46.4, 3.8, 5.6), (5.6, 26, 5.6, 3.8), (46.4, 26, 5.6, 3.8)):
        u, v = (s.h['x'] + 26 - cx) / rx, (s.h['y'] + 26 - cy) / ry
        s.put((np.abs(u) + np.abs(v)) <= 1.0 + 0.9 / min(rx, ry), flat_shader(OUT))
        s.put((np.abs(u) + np.abs(v)) <= 1.0, gem_shader(cx, cy, rx, ry, GOLD, 0.64, 0.62))
    for sx, sy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        cx, cy = 26 + sx * 14.0, 26 + sy * 14.0
        s.disc(cx, cy, 2.3, dome_shader(cx, cy, 2.3, GOLD, 0.72), line=0.9)
    cv = s.render()
    # the Wardfire: a hearth of two stones, and a flame of three tongues
    sk = Sketch(n)
    yy = np.mgrid[0:n, 0:n][0].astype(float)
    for (x0, x1, y0, y1) in ((19.4, 26.2, 35.0, 38.8), (25.8, 32.6, 35.0, 38.8)):
        m = sk.rrect(x0, y0, x1, y1, 1.6)
        cv.put(grow(m) & ~m, OUT)
        cv.put(m, bands(IRON, np.full((n, n), 0.5), 0.0))
        cv.put(m & ~np.roll(m, 1, 0), bands(IRON, np.full((n, n), 0.78), 0.0))
        cv.put(m & ~np.roll(m, -1, 0), bands(IRON, np.full((n, n), 0.28), 0.0))
    outer = teardrop(sk, 26.2, 12.4, 35.6, 14.6, bend=2.2)
    mid = teardrop(sk, 26.0, 18.4, 35.0, 10.0, bend=1.4)
    core = teardrop(sk, 25.9, 24.4, 34.4, 5.4, bend=0.7)
    cv.put(grow(outer) & ~outer, EMBER[1])
    cv.put(outer, bands(EMBER, clamp01(0.42 + 0.14 * (yy - 13) / 23), 0.0))
    cv.put(mid, bands(EMBER, clamp01(0.74 - 0.04 * (yy - 19) / 17), 0.0))
    cv.put(core, bands(EMBER, np.full((n, n), 1.0), 0.0))
    return cv.image()


# ================================================================ hover glow and edges

def hover_glow():
    """A soft round gold light, 36 GUI pixels across, drawn behind a node under the cursor: strongest in the middle
    (the node covers that), fading to nothing at the edge. Colour is kept all the way out so it blends cleanly."""
    n = 72
    m = maps(n, 1)
    d = clamp01(m['r'] / (n / 2))
    a = (1 - d * d) ** 2
    a = clamp01(a * 0.94 + (rand(n, n, 11) - 0.5) / 255 * 2)
    a = np.where(d >= 1, 0, a)
    col = mix((252.0, 226.0, 150.0), (226.0, 152.0, 64.0), clamp01(d * 1.15))
    rgba = np.dstack([col, a * 255]).round().astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA')


EDGE_ROWS = {
    # (colour, alpha) for the six rows across the line: soft rim, edge, bright centre line, and back out
    'edge': [((46, 48, 60), 0.26), ((70, 72, 86), 0.72), ((98, 100, 114), 0.95)],
    'edge_open': [((56, 52, 56), 0.30), ((104, 88, 66), 0.80), ((166, 138, 84), 1.0)],
    'edge_lit': [((206, 120, 38), 0.38), ((236, 170, 66), 0.90), ((255, 232, 164), 1.0)],
}


def edge_sprite(kind):
    rows = EDGE_ROWS[kind]
    prof = [rows[0], rows[1], rows[2], rows[2], rows[1], rows[0]]
    arr = np.zeros((6, 16, 4), np.uint8)
    for y, (c, a) in enumerate(prof):
        arr[y, :, :3] = c
        arr[y, :, 3] = round(a * 255)
    return Image.fromarray(arr, 'RGBA')


# ================================================================ plus and minus buttons

def button_sprite(state, sign):
    """A small iron square with a gold plus or minus, 11 x 11 at GUI size, in the style of ui/button."""
    hi, lo, line, fill, fa, mark = {
        'normal': (IRON[3], IRON[1], GOLD[2], (36, 33, 38), 240, GOLD[4]),
        'highlighted': (IRON[4], IRON[2], GOLD[4], (58, 50, 42), 245, GOLD[5]),
        'disabled': (IRON[1], IRON[0], IRON[1], (24, 23, 26), 220, np.array((78, 76, 84), float)),
    }[state]
    layers = [(1, OUT, OUT, 240), (2, hi, lo, 255), (1, IRON[0], IRON[0], 255), (1, line, tone(line, -0.35), 255)]
    cv = ui_hd.frame(11, 11, layers, fill, fa, 30 + len(state))
    xs, ys = grid(22)
    bar = (xs > 7) & (xs < 15) & (ys > 10) & (ys < 12)
    if sign == 'plus':
        bar = bar | ((xs > 10) & (xs < 12) & (ys > 7) & (ys < 15))
    if state == 'highlighted':
        glow = clamp01(1 - np.hypot(xs - 11, ys - 11) / 9.0) ** 1.5 * 0.3
        cv.over(cv.solid, (190, 140, 66), glow)
    if state != 'disabled':
        cv.over(moved(bar, 1, 1) & ~bar, (6, 6, 8), 0.55)
    lit = bar & (~moved(bar, 0, 1) | ~moved(bar, 1, 0))
    dark = bar & ~lit & (~moved(bar, 0, -1) | ~moved(bar, -1, 0))
    mark = np.asarray(mark, float)
    cv.put(bar, mark, 1.0)
    cv.put(lit, tone(mark, 0.4) if state != 'disabled' else mark * 1.12, 1.0)
    cv.put(dark, tone(mark, -0.38), 1.0)
    return cv.image()


# ================================================================ background and nebula

# The sky tile is 512 texels. LedgerScreen draws it at 2 texels to a GUI pixel (SKY_TEXELS = 512, TEXELS = 2), the same
# density as the sprites, so its tile spans 256 GUI pixels. (A 256 tile would also tile, but would be drawn at one texel
# to a GUI pixel: coarser than everything on it.)
SKY = 512


def background(n=SKY, seed=11):
    """The night the tree hangs in, tiling both ways: near-black ink with a faint drift of cold colour, fine grain and
    a dust of stars. Everything wraps at the edges (the noise tiles; the stars are laid modulo the tile). Features are
    sized in GUI pixels for a tile of 256 of them, whatever n is."""
    k = n / 256.0
    ink = np.array((8.0, 9.0, 16.0))
    drift = fbm(n, n, 64 * k, seed, 3) - 0.5
    drift2 = fbm(n, n, 32 * k, seed + 5, 3) - 0.5
    rgb = ink + drift[..., None] * np.array((4.0, 5.0, 11.0)) + drift2[..., None] * np.array((2.0, 2.0, 5.0))
    rgb += (rand(n, n, seed + 9) - 0.5)[..., None] * 3.2
    rng = np.random.default_rng(seed + 31)
    cold, neutral, warm = np.array((150.0, 172.0, 236.0)), np.array((214.0, 218.0, 236.0)), np.array((240.0, 204.0, 150.0))

    def colour(p):
        return [cold, neutral, warm][rng.choice(3, p=p)]

    def star(cx, cy, col, peak, sigma, reach=0.0):
        """Add a star centred at (cx, cy) in texels: a small round glow, and thin spikes along the axes out to `reach`."""
        r = int(math.ceil(max(sigma * 3, reach)))
        xs = np.arange(math.floor(cx) - r, math.floor(cx) + r + 2)
        ys = np.arange(math.floor(cy) - r, math.floor(cy) + r + 2)
        dx, dy = (xs + 0.5 - cx)[None, :], (ys + 0.5 - cy)[:, None]
        w = np.exp(-(dx ** 2 + dy ** 2) / (2 * sigma ** 2))
        if reach:
            thin = 0.42
            w = w + 0.5 * (np.exp(-np.abs(dx) / (reach * 0.33)) * np.exp(-dy ** 2 / (2 * thin ** 2))
                           + np.exp(-np.abs(dy) / (reach * 0.33)) * np.exp(-dx ** 2 / (2 * thin ** 2)))
        yi, xi = np.meshgrid(ys % n, xs % n, indexing='ij')
        np.add.at(rgb, (yi, xi), w[..., None] * (col * peak))

    for _ in range(int(1100 * k * k)):                      # dust: single faint texels
        x, y = int(rng.integers(n)) + 0.5, int(rng.integers(n)) + 0.5
        star(x, y, colour((0.55, 0.3, 0.15)), 0.05 + 0.2 * rng.random() ** 2.5, 0.45)
    for _ in range(int(70 * 1.15)):                         # stars: a round glow, a few with short spikes
        x, y = rng.random() * n, rng.random() * n
        peak = 0.3 + 0.4 * rng.random()
        spiky = rng.random() < 0.3
        star(x, y, colour((0.45, 0.35, 0.2)), peak, 0.62 + 0.3 * rng.random(), 3.0 * k if spiky else 0.0)
    for _ in range(5):                                      # a few glints, brighter, with a halo and long spikes
        x, y = rng.random() * n, rng.random() * n
        c = [cold, neutral, warm][int(rng.integers(3))]
        star(x, y, c, 0.2, 2.2 * k)
        star(x, y, c, 0.8, 0.8, 6.5 * k)
    out = np.clip(rgb, 0, 255).round().astype(np.uint8)
    return Image.fromarray(np.dstack([out, np.full((n, n), 255, np.uint8)]), 'RGBA')


def nebula(n=512, seed=21):
    """Soft cloud light with alpha, warm ember and cold blue, to lie large and faint over the background. It is
    smooth (no pixel art), thins out well before its edges, and keeps its colour where it is clear."""
    xs, ys = grid(n)
    u, v = xs / n, ys / n

    def blobs(spec):
        d = np.zeros((n, n))
        for (bx, by, r, w) in spec:
            d += w * np.exp(-(((u - bx) ** 2 + (v - by) ** 2) / (2 * (r ** 2))))
        return d

    def warp(img, amt, s):
        """Bend an image by smooth noise, so the clouds curl."""
        dx = (fbm(n, n, 150, s, 3) - 0.5) * amt
        dy = (fbm(n, n, 150, s + 7, 3) - 0.5) * amt
        yy = np.clip(np.arange(n)[:, None] + dy, 0, n - 1)
        xx = np.clip(np.arange(n)[None, :] + dx, 0, n - 1)
        y0, x0 = np.floor(yy).astype(int), np.floor(xx).astype(int)
        y1, x1 = np.minimum(y0 + 1, n - 1), np.minimum(x0 + 1, n - 1)
        fy, fx = yy - y0, xx - x0
        return img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x1] * fx * (1 - fy) + img[y1, x0] * (1 - fx) * fy + img[y1, x1] * fx * fy

    def gas(spec, s):
        shape = warp(blobs(spec), 90, s)
        f = fbm(n, n, 90, s + 1, 6, 0.55)
        f = warp(f, 50, s + 2)
        cloud = shape * clamp01((f - 0.24) * 2.2)                        # holes and wisps
        ridge = (1 - np.abs(fbm(n, n, 70, s + 3, 4, 0.5) * 2 - 1)) ** 5   # thin filaments of brighter gas
        lane = (1 - np.abs(fbm(n, n, 110, s + 5, 3, 0.5) * 2 - 1)) ** 7   # dust lanes that cut the cloud
        cloud = cloud * (1 - 0.6 * lane) + 0.35 * ridge * blur(shape, 6)
        return blur(cloud, 1)

    wm = gas([(0.36, 0.62, 0.15, 1.0), (0.50, 0.70, 0.11, 0.8), (0.28, 0.44, 0.10, 0.6), (0.42, 0.52, 0.08, 0.5)], seed)
    cd = gas([(0.64, 0.36, 0.15, 1.0), (0.54, 0.28, 0.10, 0.7), (0.72, 0.52, 0.10, 0.7), (0.60, 0.46, 0.08, 0.5)], seed + 40)
    dens = wm + cd
    ember = ramp([(96, 34, 28), (176, 76, 36), (232, 130, 56), (255, 196, 112)], clamp01(wm * 0.8))
    blue = ramp([(26, 36, 104), (50, 80, 174), (96, 138, 226), (178, 206, 252)], clamp01(cd * 0.8))
    w_warm = clamp01(wm / (dens + 1e-6))
    col = mix(blue, ember, w_warm)
    col = np.where((dens < 0.02)[..., None], np.array((86.0, 70.0, 132.0)), col)
    a = (1 - np.exp(-dens * 1.9)) ** 1.15 * 0.42                           # faint: the screen draws it a further 0.8 strong
    fade = smoothstep(0.0, 0.24, np.minimum(np.minimum(u, 1 - u), np.minimum(v, 1 - v)))
    a = a * fade * fade
    a = clamp01(a + (rand(n, n, seed + 41) - 0.5) / 255 * 1.6)           # a hair of noise so the faint slopes do not band
    col = col + (rand(n, n, seed + 43) - 0.5)[..., None] * 1.6
    rgba = np.dstack([np.clip(col, 0, 255), a * 255]).round().astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA')


# ================================================================ compositing for the previews

class Screen:
    """A float RGB picture that sprites are laid on the way the game draws them (alpha blending, nearest sampling)."""

    def __init__(self, w, h, bg=(8, 9, 15)):
        self.w, self.h = w, h
        self.rgb = np.zeros((h, w, 3)) + np.asarray(bg, float)

    def blit(self, img, x, y, tint=1.0, alpha=1.0):
        """Lay an RGBA sprite at its own pixel size with its top left at (x, y); `tint` multiplies its colour."""
        a = np.asarray(img.convert('RGBA'), float)
        h, w = a.shape[:2]
        x0, y0 = max(0, x), max(0, y)
        x1, y1 = min(self.w, x + w), min(self.h, y + h)
        if x1 <= x0 or y1 <= y0:
            return
        src = a[y0 - y:y1 - y, x0 - x:x1 - x]
        al = (src[..., 3:4] / 255.0) * alpha
        col = src[..., :3] * np.asarray(tint, float)
        self.rgb[y0:y1, x0:x1] = self.rgb[y0:y1, x0:x1] * (1 - al) + col * al

    def blit_centered(self, img, cx, cy, scale=1, **kw):
        if scale != 1:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        self.blit(img, int(round(cx - img.width / 2)), int(round(cy - img.height / 2)), **kw)

    def stretch(self, img, x, y, w, h, alpha=1.0):
        """A sprite stretched to w x h (nearest), as a default 'stretch' sprite is."""
        self.blit(img.resize((w, h), Image.NEAREST), x, y, alpha=alpha)

    def edge(self, img, p0, p1, smooth=False):
        """A connection line: the sprite stretched along its x to the distance between the two points, centred on the
        line and rotated, sampled by nearest texel the way the GPU would."""
        a = np.asarray(img.convert('RGBA'), float)
        th, tw = a.shape[:2]
        (x0, y0), (x1, y1) = p0, p1
        L = math.hypot(x1 - x0, y1 - y0)
        if L < 1:
            return
        ux, uy = (x1 - x0) / L, (y1 - y0) / L
        half = th / 2
        xa, xb = int(math.floor(min(x0, x1) - half - 1)), int(math.ceil(max(x0, x1) + half + 1))
        ya, yb = int(math.floor(min(y0, y1) - half - 1)), int(math.ceil(max(y0, y1) + half + 1))
        xa, ya, xb, yb = max(xa, 0), max(ya, 0), min(xb, self.w), min(yb, self.h)
        ys, xs = np.mgrid[ya:yb, xa:xb]
        px, py = xs + 0.5 - x0, ys + 0.5 - y0
        sx = px * ux + py * uy
        sy = -px * uy + py * ux
        inside = (sx >= 0) & (sx < L) & (sy >= -half) & (sy < half)
        v = sy + half
        if smooth:
            vv = np.clip(v - 0.5, 0, th - 1)
            r0 = np.floor(vv).astype(int)
            r1 = np.minimum(r0 + 1, th - 1)
            f = (vv - r0)[..., None]
            col = a[r0, 0] * (1 - f) + a[r1, 0] * f
        else:
            col = a[np.clip(np.floor(v).astype(int), 0, th - 1), 0]
        al = (col[..., 3:4] / 255.0) * inside[..., None]
        reg = self.rgb[ya:yb, xa:xb]
        self.rgb[ya:yb, xa:xb] = reg * (1 - al) + col[..., :3] * al

    def image(self):
        return Image.fromarray(np.clip(self.rgb, 0, 255).round().astype(np.uint8), 'RGB')


# ================================================================ everything, and the previews

# Icons that would crowd a socket's pit (a notable's is 14 texels from the middle, a minor's 9) are drawn a little smaller
GLYPH_ZOOM = {'damage': 0.85, 'reach': 0.92, 'stealth': 0.84, 'speed': 0.93, 'gather': 0.9, 'craft': 0.9, 'skill': 0.95, 'defence': 0.95}
ZOOM = {'heavy_arms': 0.9, 'guarding': 0.94, 'evasion': 0.86, 'stealth': 0.88, 'smithing': 0.82, 'cooking': 0.82,
        'enchanting': 0.84, 'mining': 0.88, 'woodcutting': 0.8, 'farming': 0.95, 'fishing': 0.9, 'trade': 0.9, 'guild': 0.92}

SKILLS = ['blades', 'heavy_arms', 'archery', 'guarding', 'evasion', 'armor', 'stealth', 'smithing', 'alchemy', 'cooking',
          'enchanting', 'mining', 'woodcutting', 'farming', 'fishing', 'trade', 'sorcery', 'prayer', 'guild']
GLYPHS = ['health', 'stamina', 'focus', 'damage', 'defence', 'speed', 'craft', 'gather', 'coin', 'xp', 'flask', 'stealth',
          'luck', 'reach', 'resist', 'skill']
STATS = ['vigor', 'focus', 'endurance', 'might', 'finesse', 'arcana', 'faith']
STATES = (('locked', ''), ('open', '_open'), ('lit', '_lit'))


def build_sprites():
    """Every sprite, keyed by its path under textures/gui/sprites/."""
    out = {}
    for st, suf in STATES:
        out['tree/minor' + suf] = socket_minor(st, 3)
        out['tree/notable' + suf] = socket_notable(st, 4, 1)
        out['tree/keystone' + suf] = socket_keystone(st, 5)
    out['tree/root'] = socket_root(6)
    out['tree/hover'] = hover_glow()
    for k in EDGE_ROWS:
        out['tree/' + k] = edge_sprite(k)
    for sign in ('plus', 'minus'):
        for st, suf in (('normal', ''), ('highlighted', '_highlighted'), ('disabled', '_disabled')):
            out['tree/' + sign + suf] = button_sprite(st, sign)
    g = globals()
    for nm in SKILLS:
        out['tree/icon/' + nm] = view_icon(g['icon_' + nm], 32, ZOOM.get(nm, 1.0))
    for nm in GLYPHS:
        out['tree/glyph/' + nm] = view_icon(g['icon_glyph_' + nm], 20, GLYPH_ZOOM.get(nm, 1.0))
    for nm in STATS:
        out['tree/stat/' + nm] = view_icon(g['icon_stat_' + nm], 32)
    return out


def build_textures():
    """The two big pictures, keyed by their path under textures/gui/ (they are not sprites)."""
    return {'tree/background': background(), 'tree/nebula': nebula()}


# ---- preview helpers

LABEL = (214, 208, 196)
TITLE = (214, 178, 106)


def font(size):
    try:
        return ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', size)
    except OSError:
        return ImageFont.load_default()


def draw_heading(img, x, y, text, col, scale=2):
    """Heading-font capitals (heading_font.py's glyphs) painted onto an RGBA image, `scale` pixels per glyph pixel."""
    import heading_font
    d = ImageDraw.Draw(img)
    cx = x
    for ch in text.upper():
        if ch == ' ' or ch not in heading_font.G:
            cx += 4 * scale
            continue
        glyph = heading_font.G[ch]
        for gy, row in enumerate(glyph):
            for gx, c in enumerate(row):
                if c == '#':
                    d.rectangle([cx + gx * scale, y + gy * scale, cx + (gx + 1) * scale - 1, y + (gy + 1) * scale - 1], fill=tuple(col) + (255,))
        cx += (max(len(r) for r in glyph) + 1) * scale
    return cx - x


def nine_slice(img, border, w, h):
    """A sprite stretched to w x h by its nine slices: corners kept, edges and middle stretched (nearest)."""
    sw, sh = img.size
    b = border
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    xs = [(0, b, 0, b), (b, sw - b, b, w - b), (sw - b, sw, w - b, w)]
    ys = [(0, b, 0, b), (b, sh - b, b, h - b), (sh - b, sh, h - b, h)]
    for (sx0, sx1, dx0, dx1) in xs:
        for (sy0, sy1, dy0, dy1) in ys:
            piece = img.crop((sx0, sy0, sx1, sy1)).resize((dx1 - dx0, dy1 - dy0), Image.NEAREST)
            out.paste(piece, (dx0, dy0))
    return out


# How the tree screen draws an icon in a socket (LedgerScreen): a share of the socket's size, dimmed by the node's state
ICON_SHARE = 0.62
NODE_SIZE = {'minor': 14, 'notable': 22, 'keystone': 30}
ICON_ALPHA = {'locked': 0.4, 'open': 0.9, 'lit': 1.0}
# The hover glow reaches 1.7 times a node's size
GLOW_SHARE = 1.7
# And the sky: its tile spans 256 GUI pixels, the haze 360 at the default zoom (drawn 0.8 strong)
SKY_GUI, NEBULA_GUI, NEBULA_ALPHA = 256, 360, 0.8


def icon_in_socket(S, spr, kind, art, x, y, state):
    """Lay a glyph or icon in a socket the way the screen does: scaled to its share of the socket (nearest), by state."""
    px = round(NODE_SIZE[kind] * ICON_SHARE) * K
    im = spr['tree/' + art[0] + '/' + art[1]].resize((px, px), Image.NEAREST)
    S.blit_centered(im, x, y, alpha=ICON_ALPHA[state])


def preview_icons(spr, path):
    """Everything on one dark panel at 3x: each socket in each state (bare and with an icon in it), the hover glow and
    the edges, then the skill icons, glyphs, stat icons and buttons, with small labels."""
    Wt, Ht = 680, 524
    S = Screen(Wt, Ht, bg=(10, 10, 14))
    S.blit(ui_hd.panel(w=Wt // 2, h=Ht // 2), 0, 0)
    labels = []

    def label(cx, y, text, col=LABEL, anchor='ma'):
        labels.append((cx, y, text, col, anchor))

    # sockets: a row for each state; bare, then with a glyph or an icon in the pit
    x0, y0 = 46, 28
    label(x0 - 24, y0 - 15, 'SOCKETS   minor 14 / notable 22 / keystone 30 / root 26   (bare, then with an icon)', TITLE, 'la')
    cols = [('minor', None), ('minor', ('glyph', 'damage')), ('notable', None), ('notable', ('icon', 'blades')),
            ('keystone', None), ('keystone', ('icon', 'sorcery')), ('root', None)]
    cw = [36, 36, 52, 52, 68, 68, 60]
    rowh = 68
    for r, (st, suf) in enumerate(STATES):
        label(x0 - 8, y0 + r * rowh + rowh / 2 - 6, st, LABEL, 'ra')
        cx = x0
        for (kind, art), w in zip(cols, cw):
            if kind == 'root' and r > 0:
                cx += w
                continue
            ccx, ccy = cx + w / 2, y0 + r * rowh + rowh / 2
            S.blit_centered(spr['tree/' + kind + ('' if kind == 'root' else suf)], ccx, ccy)
            if art:
                icon_in_socket(S, spr, kind, art, ccx, ccy, st)
            cx += w
    # the hover glow and the three edges (straight, and at a slant), to the right
    hx = x0 + sum(cw) + 50
    S.blit_centered(spr['tree/hover'], hx, y0 + 40)
    S.blit_centered(spr['tree/notable_lit'], hx, y0 + 40)
    label(hx, y0 + 80, 'hover 36', LABEL)
    ex = hx + 46
    for i, k in enumerate(('edge', 'edge_open', 'edge_lit')):
        yy = y0 + 14 + i * 28
        S.edge(spr['tree/' + k], (ex, yy), (ex + 64, yy))
        S.edge(spr['tree/' + k], (ex + 76, yy - 4), (ex + 122, yy + 14))
        label(ex + 32, yy + 6, k + ' 8x3', LABEL)
    # skill icons, and the buttons beside them
    y1 = y0 + 3 * rowh + 24
    label(x0 - 24, y1 - 15, 'SKILL ICONS   16x16', TITLE, 'la')
    for i, nm in enumerate(SKILLS):
        cx = x0 + (i % 10) * 44 + 12
        cy = y1 + (i // 10) * 50 + 16
        S.blit_centered(spr['tree/icon/' + nm], cx, cy)
        label(cx, cy + 18, nm, LABEL)
    bx = x0 + 10 * 44 + 26
    label(bx - 12, y1 - 15, 'BUTTONS   11x11', TITLE, 'la')
    for i, sign in enumerate(('plus', 'minus')):
        for j, suf in enumerate(('', '_highlighted', '_disabled')):
            cx, cy = bx + 6 + j * 44, y1 + 16 + i * 48
            S.blit_centered(spr['tree/' + sign + suf], cx, cy)
            label(cx, cy + 13, (suf[1:] or 'normal'), LABEL)
    # glyphs
    y2 = y1 + 2 * 50 + 26
    label(x0 - 24, y2 - 15, 'GLYPHS   10x10', TITLE, 'la')
    for i, nm in enumerate(GLYPHS):
        cx, cy = x0 + i * 37 - 4, y2 + 12
        S.blit_centered(spr['tree/glyph/' + nm], cx, cy)
        label(cx, cy + 14, nm, LABEL)
    # stat icons, and glyphs and icons in sockets at their real size
    y3 = y2 + 56
    label(x0 - 24, y3 - 15, 'STAT ICONS   16x16', TITLE, 'la')
    for i, nm in enumerate(STATS):
        cx, cy = x0 + i * 44 + 12, y3 + 16
        S.blit_centered(spr['tree/stat/' + nm], cx, cy)
        label(cx, cy + 18, nm, LABEL)
    mx = x0 + 7 * 44 + 34
    label(mx - 14, y3 - 15, 'IN THEIR SOCKETS (lit, open, locked)', TITLE, 'la')
    for i, (nm, st) in enumerate((('health', 'lit'), ('stamina', 'lit'), ('focus', 'open'), ('defence', 'open'), ('coin', 'locked'), ('luck', 'locked'))):
        cx, cy = mx + i * 32, y3 + 14
        S.blit_centered(spr['tree/minor' + dict(STATES)[st]], cx, cy)
        icon_in_socket(S, spr, 'minor', ('glyph', nm), cx, cy, st)
    for i, (nm, st) in enumerate((('mining', 'lit'), ('trade', 'open'), ('prayer', 'locked'))):
        cx, cy = mx + 8 + i * 52, y3 + 54
        S.blit_centered(spr['tree/notable' + dict(STATES)[st]], cx, cy)
        icon_in_socket(S, spr, 'notable', ('icon', nm), cx, cy, st)
    big = S.image().resize((Wt * 3, Ht * 3), Image.NEAREST)
    d = ImageDraw.Draw(big)
    f = font(11)
    for (cx, y, text, col, anchor) in labels:
        d.text((cx * 3, y * 3), text, fill=col, font=f, anchor=anchor)
    big.save(path)


class Node:
    def __init__(self, kind, x, y, state, art=None):
        self.kind, self.x, self.y, self.state, self.art = kind, int(round(x)), int(round(y)), state, art


def mock_layout():
    """A small tree: the root, a ring of six minors round it, and three branches running out to a notable and a keystone."""
    cx, cy = 480, 270
    nodes, edges = {}, []

    def at(ang, r, ox=0, oy=0):
        a = math.radians(ang)
        return cx + r * math.cos(a) + ox, cy + r * math.sin(a) + oy

    def add(key, kind, pos, state, art=None, parent=None):
        nodes[key] = Node(kind, pos[0], pos[1], state, art)
        if parent:
            edges.append((parent, key))

    add('root', 'root', (cx, cy), 'lit')
    ring = {-30: ('lit', 'health'), 30: ('lit', 'stamina'), 90: ('lit', 'defence'), 150: ('open', 'skill'), 210: ('lit', 'focus'), 270: ('open', 'coin')}
    for ang, (st, gl) in ring.items():
        add('ring%d' % ang, 'minor', at(ang, 72), st, ('glyph', gl), 'root')
    angs = sorted(ring)
    for a, b in zip(angs, angs[1:] + angs[:1]):
        edges.append(('ring%d' % a, 'ring%d' % b))
    # right, up and out
    add('a1', 'minor', at(-30, 128), 'lit', ('glyph', 'damage'), 'ring-30')
    add('a2', 'minor', at(-30, 184), 'lit', ('glyph', 'speed'), 'a1')
    add('a3', 'notable', at(-30, 244), 'open', ('icon', 'blades'), 'a2')
    add('a4', 'minor', at(-30, 302), 'locked', ('glyph', 'reach'), 'a3')
    add('a5', 'keystone', at(-30, 366), 'locked', ('icon', 'heavy_arms'), 'a4')
    add('at1', 'minor', at(-30, 184, 0, -54 + 28), 'open', ('glyph', 'craft'), 'a2')
    add('at2', 'minor', at(-30, 184, 0, -108 + 56), 'locked', ('glyph', 'gather'), 'at1')
    # down
    add('b1', 'minor', at(90, 126), 'lit', ('glyph', 'health'), 'ring90')
    add('b2', 'minor', at(90, 180), 'lit', ('glyph', 'resist'), 'b1')
    add('b3', 'notable', at(90, 232), 'open', ('icon', 'guarding'), 'b2')
    # left, up and out
    add('c1', 'minor', at(210, 128), 'lit', ('glyph', 'xp'), 'ring210')
    add('c2', 'minor', at(210, 184), 'open', ('glyph', 'skill'), 'c1')
    add('c3', 'notable', at(210, 244), 'locked', ('icon', 'sorcery'), 'c2')
    add('c4', 'minor', at(210, 302), 'locked', ('glyph', 'luck'), 'c3')
    add('c5', 'keystone', at(210, 366), 'locked', ('icon', 'enchanting'), 'c4')
    p = (nodes['c1'].x - 47, nodes['c1'].y + 27)
    add('ct1', 'minor', p, 'open', ('glyph', 'flask'), 'c1')
    add('ct2', 'minor', (p[0] - 47, p[1] + 27), 'locked', ('glyph', 'stealth'), 'ct1')
    return nodes, edges


def edge_kind(a, b):
    s = (a.state, b.state)
    if s == ('lit', 'lit'):
        return 'edge_lit'
    if 'lit' in s and 'open' in s:
        return 'edge_open'
    return 'edge'


def preview_mock(spr, tex, assets, path):
    """The tree screen as it would look at GUI scale 2 on a 960x540 window, so every sprite is drawn texel for texel."""
    W, H = 960, 540
    S = Screen(W, H, bg=(8, 9, 15))
    sky = tex['tree/background'].resize((SKY_GUI * 2, SKY_GUI * 2), Image.NEAREST)     # a tile spans 256 GUI pixels
    for ty in range(0, H, sky.height):
        for tx in range(0, W, sky.width):
            S.blit(sky, tx, ty)
    haze = NEBULA_GUI * 2                                                           # at the default zoom, centred on the root
    S.blit_centered(tex['tree/nebula'].resize((haze, haze), Image.NEAREST), W // 2, H // 2, alpha=NEBULA_ALPHA)
    nodes, edges = mock_layout()
    for a, b in edges:
        na, nb = nodes[a], nodes[b]
        S.edge(spr['tree/' + edge_kind(na, nb)], (na.x, na.y), (nb.x, nb.y))
    hovered = 'b3'
    h = nodes[hovered]
    glow = round(NODE_SIZE[h.kind] * GLOW_SHARE) * 2
    S.blit_centered(spr['tree/hover'].resize((glow, glow), Image.NEAREST), h.x, h.y)
    suffix = dict(STATES)
    for key, nd in nodes.items():
        sock = 'tree/root' if nd.kind == 'root' else 'tree/' + nd.kind + suffix[nd.state]
        S.blit_centered(spr[sock], nd.x, nd.y)
        if nd.art:
            icon_in_socket(S, spr, nd.kind, nd.art, nd.x, nd.y, nd.state)
    # a tooltip in the panel style: dark panel, gold title, light text
    try:
        panel = Image.open(os.path.join(assets, 'textures/gui/sprites/ui/panel.png')).convert('RGBA')
    except OSError:
        panel = ui_hd.panel()
    title = "WARDEN'S POISE"
    lines = [('Notable  -  Guarding', (150, 146, 140)), ('+6% Defence while standing', (226, 220, 208)),
             ('+2 Vigor', (208, 87, 63)), ('Requires Guarding 4', (170, 120, 110)), ('Cost: 1 point', (214, 178, 106))]
    f = font(12)
    pad = 24
    tw = max(draw_heading(Image.new('RGBA', (1, 1)), 0, 0, title, TITLE, 2), max(f.getlength(t) for t, _ in lines)) + 2 * pad
    tw = int(math.ceil(tw))
    th = pad + 18 + 10 + 16 * len(lines) + pad - 4
    tx, ty = h.x + 36, h.y - th + 20
    S.blit(nine_slice(panel, 16, tw, th), tx, ty)
    img = Image.new('RGBA', (tw, th), (0, 0, 0, 0))
    draw_heading(img, pad, pad - 4, title, TITLE, 2)
    d = ImageDraw.Draw(img)
    d.fontmode = '1'
    for i, (t, c) in enumerate(lines):
        d.text((pad, pad + 24 + i * 16), t, fill=c, font=f)
    S.blit(img, tx, ty)
    S.image().save(path)


def preview_tile(tex, path):
    """The sky tiled three by three, to look for seams."""
    t = tex['tree/background'].convert('RGB')
    big = Image.new('RGB', (t.width * 3, t.height * 3))
    for i in range(3):
        for j in range(3):
            big.paste(t, (i * t.width, j * t.height))
    big.save(path)


def main():
    assets = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    spr, tex = build_sprites(), build_textures()
    for key, img in spr.items():
        path = os.path.join(assets, 'textures/gui/sprites', key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
    for key, img in tex.items():
        path = os.path.join(assets, 'textures/gui', key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
    print(len(spr), 'sprites,', len(tex), 'textures')
    if prev:
        os.makedirs(prev, exist_ok=True)
        preview_icons(spr, os.path.join(prev, 'icons.png'))
        preview_mock(spr, tex, assets, os.path.join(prev, 'mock-tree.png'))
        preview_tile(tex, os.path.join(prev, 'background_3x3.png'))


if __name__ == '__main__':
    main()
