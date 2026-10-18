"""The market board's lettering: a hand-printed alphabet drawn as strokes, rendered two ways.

* **Chalk** (the prices, the headings, the "Prices at noon" line): a stick of chalk about 1.2 font pixels wide,
  grainy, its edge a little ragged, pressing hardest where a stroke starts. Every glyph is also cut into its
  *pieces*, each a run of a stroke that goes one way along x or along y, so the board can bring a line up the way it
  is written: piece by piece, each revealed along its own direction while the chalk's tip follows its path.
* **Ink** (the slips' headlines and text): the same letters in a pen hand, slanted further, thick and thin as a
  broad nib at 35 degrees makes them.

Glyphs are designed on the Minecraft font's grid (cap height 7 font pixels, x-height 5, descenders to 9.5), so the
board's text sits at the sizes players know from signs. Coordinates are font pixels, y down, 0 the top of a capital
and 7 the baseline. The chalk and ink are painted white; the game tints them.

Imported by market_board_art.py, which packs the atlas and writes the Kotlin tables.
"""
import math

import numpy as np

# Texels per font pixel in the atlas
T = 3

# The private-use characters the board draws with: the Guild Mark's coin, the trend arrows
COIN = ''
UP = ''
DOWN = ''

CAP = 7.0          # baseline, from the top of a capital
XH = 2.0           # top of the x-height
DESC = 9.4         # bottom of a descender


# ================================================================ stroke helpers

def arc(cx, cy, rx, ry, a0, a1, step=11.0):
    """Points along an elliptic arc from angle a0 to a1 (degrees; y down: 0 right, 90 down, 180 left, 270 up)."""
    n = max(2, int(math.ceil(abs(a1 - a0) / step)))
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def cat(*parts):
    """One stroke from several runs of points, joints not doubled."""
    out = []
    for p in parts:
        for q in p:
            q = (float(q[0]), float(q[1]))
            if not out or math.hypot(q[0] - out[-1][0], q[1] - out[-1][1]) > 1e-6:
                out.append(q)
    return out


def ln(*pts):
    return [(float(x), float(y)) for x, y in pts]


def dot(x, y):
    """A dot: a stroke so short the chalk just touches down."""
    return [(x - 0.06, y - 0.12), (x + 0.06, y + 0.12)]


# ================================================================ the alphabet
# Each glyph: (width of its centre lines, [strokes in the order they're written]). The advance is the width plus
# the chalk's own breadth and a gap (see advance()).

G = {}

# --- capitals
G['A'] = (4.4, [ln((0, 7), (2.2, 0)), ln((2.2, 0), (4.4, 7)), ln((0.9, 4.6), (3.5, 4.6))])
G['B'] = (3.9, [ln((0, 0), (0, 7)), cat(ln((0, 0), (2.3, 0)), arc(2.3, 1.7, 1.45, 1.7, 270, 450), ln((0, 3.4))),
                cat(ln((0, 3.4), (2.4, 3.4)), arc(2.4, 5.2, 1.55, 1.8, 270, 450), ln((0, 7)))])
G['C'] = (4.4, [arc(2.4, 3.5, 2.35, 3.5, 318, 42)])
G['D'] = (4.1, [ln((0, 0), (0, 7)), cat(ln((0, 0), (1.5, 0)), arc(1.5, 3.5, 2.6, 3.5, 270, 450), ln((0, 7)))])
G['E'] = (3.7, [ln((0, 0), (0, 7)), ln((0, 0), (3.7, 0)), ln((0, 3.4), (2.9, 3.4)), ln((0, 7), (3.7, 7))])
G['F'] = (3.6, [ln((0, 0), (0, 7)), ln((0, 0), (3.6, 0)), ln((0, 3.4), (2.8, 3.4))])
G['G'] = (4.5, [arc(2.4, 3.5, 2.35, 3.5, 318, 20), ln((4.5, 4.6), (4.5, 7.0)), ln((4.5, 4.1), (2.5, 4.1))])
G['H'] = (4.0, [ln((0, 0), (0, 7)), ln((4, 0), (4, 7)), ln((0, 3.5), (4, 3.5))])
G['I'] = (2.8, [ln((1.4, 0), (1.4, 7)), ln((0, 0), (2.8, 0)), ln((0, 7), (2.8, 7))])
G['J'] = (3.2, [cat(ln((3.2, 0), (3.2, 5.2)), arc(1.6, 5.2, 1.6, 1.8, 0, 165))])
G['K'] = (3.9, [ln((0, 0), (0, 7)), ln((3.8, 0), (0, 4.2)), ln((1.3, 3.0), (3.9, 7))])
G['L'] = (3.5, [cat(ln((0, 0), (0, 7)), ln((3.5, 7)))])
G['M'] = (5.0, [ln((0, 7), (0, 0)), ln((0, 0), (2.5, 4.6)), ln((2.5, 4.6), (5.0, 0)), ln((5.0, 0), (5.0, 7))])
G['N'] = (4.1, [ln((0, 7), (0, 0)), ln((0, 0), (4.1, 7)), ln((4.1, 7), (4.1, 0))])
G['O'] = (4.8, [arc(2.4, 3.5, 2.4, 3.5, 270, 90), arc(2.4, 3.5, 2.4, 3.5, 90, -90)])
G['P'] = (3.8, [ln((0, 0), (0, 7)), cat(ln((0, 0), (2.3, 0)), arc(2.3, 1.85, 1.5, 1.85, 270, 450), ln((0, 3.7)))])
G['Q'] = (4.8, [arc(2.4, 3.5, 2.4, 3.5, 270, 90), arc(2.4, 3.5, 2.4, 3.5, 90, -90), ln((3.0, 5.0), (4.8, 7.4))])
G['R'] = (4.0, [ln((0, 0), (0, 7)), cat(ln((0, 0), (2.3, 0)), arc(2.3, 1.85, 1.5, 1.85, 270, 450), ln((0, 3.7))),
                ln((1.7, 3.7), (4.0, 7))])
G['S'] = (4.1, [cat(arc(2.1, 1.8, 2.0, 1.8, 338, 90), arc(2.05, 5.25, 2.05, 1.75, 270, 518))])
G['T'] = (4.4, [ln((0, 0), (4.4, 0)), ln((2.2, 0), (2.2, 7))])
G['U'] = (4.0, [cat(ln((0, 0), (0, 4.8)), arc(2.0, 4.8, 2.0, 2.2, 180, 0), ln((4.0, 0)))])
G['V'] = (4.4, [ln((0, 0), (2.2, 7)), ln((2.2, 7), (4.4, 0))])
G['W'] = (5.6, [ln((0, 0), (1.4, 7)), ln((1.4, 7), (2.8, 2.2)), ln((2.8, 2.2), (4.2, 7)), ln((4.2, 7), (5.6, 0))])
G['X'] = (4.0, [ln((0, 0), (4.0, 7)), ln((4.0, 0), (0, 7))])
G['Y'] = (4.4, [ln((0, 0), (2.2, 3.6)), ln((4.4, 0), (2.2, 3.6), (2.2, 7))])
G['Z'] = (4.0, [ln((0, 0), (4.0, 0), (0, 7), (4.0, 7))])

# --- small letters
G['a'] = (3.8, [arc(1.9, 4.5, 1.9, 2.5, 318, 42), ln((3.8, 2.0), (3.8, 7.0))])
G['b'] = (3.8, [ln((0, 0), (0, 7)), arc(1.9, 4.5, 1.9, 2.5, 212, 508)])
G['c'] = (3.7, [arc(2.0, 4.5, 2.0, 2.5, 320, 40)])
G['d'] = (3.8, [arc(1.9, 4.5, 1.9, 2.5, 322, 38), ln((3.8, 0), (3.8, 7.0))])
G['e'] = (3.9, [cat(ln((0.05, 4.4), (3.9, 4.4)), arc(1.95, 4.5, 1.95, 2.5, -2, -318))])
G['f'] = (3.2, [cat(arc(2.3, 1.5, 1.05, 1.5, 325, 180), ln((1.25, 7.0))), ln((0, 2.7), (2.8, 2.7))])
G['g'] = (3.8, [arc(1.9, 4.3, 1.9, 2.3, 318, 42), cat(ln((3.8, 2.0), (3.8, 7.7)), arc(1.9, 7.7, 1.9, 1.6, 0, 165))])
G['h'] = (3.8, [ln((0, 0), (0, 7)), cat(arc(1.9, 4.0, 1.9, 1.9, 200, 360), ln((3.8, 7.0)))])
G['i'] = (0.0, [ln((0, 2.3), (0, 7)), dot(0, 0.6)])
G['j'] = (2.0, [cat(ln((2.0, 2.3), (2.0, 7.6)), arc(1.0, 7.6, 1.0, 1.5, 0, 160)), dot(2.0, 0.6)])
G['k'] = (3.6, [ln((0, 0), (0, 7)), ln((3.4, 2.2), (0, 5.0)), ln((1.2, 4.1), (3.6, 7))])
G['l'] = (1.3, [cat(ln((0.2, 0), (0.2, 5.8)), arc(1.2, 5.8, 1.0, 1.2, 180, 90))])
G['m'] = (5.4, [ln((0, 2.0), (0, 7)), cat(arc(1.35, 3.7, 1.35, 1.6, 192, 360), ln((2.7, 7.0))),
                cat(arc(4.05, 3.7, 1.35, 1.6, 192, 360), ln((5.4, 7.0)))])
G['n'] = (3.8, [ln((0, 2.0), (0, 7)), cat(arc(1.9, 4.0, 1.9, 1.9, 198, 360), ln((3.8, 7.0)))])
G['o'] = (4.0, [arc(2.0, 4.5, 2.0, 2.5, 270, 90), arc(2.0, 4.5, 2.0, 2.5, 90, -90)])
G['p'] = (3.8, [ln((0, 2.0), (0, DESC)), arc(1.9, 4.5, 1.9, 2.5, 212, 508)])
G['q'] = (3.8, [arc(1.9, 4.5, 1.9, 2.5, 322, 38), ln((3.8, 2.0), (3.8, DESC))])
G['r'] = (3.3, [ln((0, 2.0), (0, 7)), arc(2.2, 4.1, 2.2, 2.0, 195, 298)])
G['s'] = (3.5, [cat(arc(1.8, 3.25, 1.7, 1.25, 340, 90), arc(1.75, 5.75, 1.75, 1.25, 270, 515))])
G['t'] = (2.8, [cat(ln((1.2, 0.5), (1.2, 5.8)), arc(2.2, 5.8, 1.0, 1.2, 180, 90)), ln((0, 2.5), (2.8, 2.5))])
G['u'] = (3.8, [cat(ln((0, 2.0), (0, 4.9)), arc(1.9, 4.9, 1.9, 2.1, 180, 0)), ln((3.8, 2.0), (3.8, 7.0))])
G['v'] = (4.0, [ln((0, 2.0), (2.0, 7)), ln((2.0, 7), (4.0, 2.0))])
G['w'] = (5.4, [ln((0, 2.0), (1.35, 7)), ln((1.35, 7), (2.7, 3.4)), ln((2.7, 3.4), (4.05, 7)), ln((4.05, 7), (5.4, 2.0))])
G['x'] = (3.8, [ln((0, 2.0), (3.8, 7)), ln((3.8, 2.0), (0, 7))])
G['y'] = (4.0, [ln((0, 2.0), (2.1, 6.5)), ln((4.0, 2.0), (2.1, 6.5), (0.8, DESC))])
G['z'] = (3.8, [ln((0, 2.0), (3.8, 2.0), (0, 7), (3.8, 7))])

# --- figures
G['0'] = (4.0, [arc(2.0, 3.5, 2.0, 3.5, 270, 90), arc(2.0, 3.5, 2.0, 3.5, 90, -90)])
G['1'] = (3.0, [ln((0.2, 1.6), (1.6, 0)), ln((1.6, 0), (1.6, 7)), ln((0.2, 7), (3.0, 7))])
G['2'] = (4.0, [cat(arc(2.0, 1.85, 1.85, 1.85, 200, 360), ln((3.85, 2.2), (3.2, 3.5), (0.1, 7.0))), ln((0.1, 7.0), (4.0, 7.0))])
G['3'] = (3.9, [cat(arc(1.9, 1.7, 1.85, 1.7, 210, 450), ln((1.4, 3.4))), cat(arc(1.9, 5.2, 2.0, 1.8, 270, 520))])
G['4'] = (4.2, [ln((2.9, 0), (0, 4.7)), ln((0, 4.7), (4.2, 4.7)), ln((3.1, 2.0), (3.1, 7.0))])
G['5'] = (3.9, [ln((3.7, 0), (0.5, 0)), ln((0.5, 0), (0.3, 3.1)), cat(arc(1.95, 4.95, 1.95, 2.05, 240, 510))])
G['6'] = (4.0, [cat(arc(2.4, 2.0, 1.6, 2.0, 310, 180), arc(2.0, 4.0, 2.0, 3.0, 180, 120)),
                arc(2.0, 5.0, 2.0, 2.0, 115, -180)])
G['7'] = (4.0, [ln((0, 0), (4.0, 0)), ln((4.0, 0), (2.6, 3.4), (1.7, 7.0))])
G['8'] = (4.0, [arc(2.0, 1.7, 1.75, 1.7, 90, -90), arc(2.0, 1.7, 1.75, 1.7, 270, 90),
                arc(2.0, 5.2, 2.0, 1.8, 270, 90), arc(2.0, 5.2, 2.0, 1.8, 90, -90)])
G['9'] = (4.0, [arc(2.0, 2.0, 2.0, 2.0, 15, 375 - 30), cat(ln((4.0, 2.0), (3.9, 4.6)), arc(1.9, 4.6, 2.0, 2.4, 0, 115))])

# --- marks
G[' '] = (1.2, [])
G['.'] = (0.0, [dot(0, 6.7)])
G[','] = (0.6, [ln((0.6, 6.4), (0, 8.2))])
G[':'] = (0.0, [dot(0, 2.9), dot(0, 6.7)])
G[';'] = (0.6, [dot(0.6, 2.9), ln((0.6, 6.4), (0, 8.2))])
G['!'] = (0.0, [ln((0, 0), (0, 4.8)), dot(0, 6.7)])
G['?'] = (3.8, [cat(arc(1.9, 1.85, 1.9, 1.85, 195, 420), ln((1.9, 4.8))), dot(1.9, 6.7)])
G["'"] = (0.4, [ln((0.4, 0), (0, 1.9))])
G['’'] = G["'"]
G['"'] = (1.5, [ln((0.4, 0), (0, 1.9)), ln((1.5, 0), (1.1, 1.9))])
G['-'] = (2.6, [ln((0, 4.0), (2.6, 4.0))])
G['–'] = (3.8, [ln((0, 4.0), (3.8, 4.0))])
G['—'] = (5.6, [ln((0, 4.0), (5.6, 4.0))])
G['+'] = (3.6, [ln((0, 4.0), (3.6, 4.0)), ln((1.8, 2.2), (1.8, 5.8))])
G['='] = (3.6, [ln((0, 3.1), (3.6, 3.1)), ln((0, 5.1), (3.6, 5.1))])
G['('] = (1.8, [arc(2.6, 3.5, 2.4, 4.6, 238, 122)])
G[')'] = (1.8, [arc(-0.8, 3.5, 2.4, 4.6, 302, 418)])
G['['] = (1.8, [ln((1.8, -0.6), (0, -0.6), (0, 7.8), (1.8, 7.8))])
G[']'] = (1.8, [ln((0, -0.6), (1.8, -0.6), (1.8, 7.8), (0, 7.8))])
G['/'] = (3.2, [ln((3.2, -0.2), (0, 7.4))])
G['\\'] = (3.2, [ln((0, -0.2), (3.2, 7.4))])
G['|'] = (0.0, [ln((0, -0.4), (0, 8.4))])
G['_'] = (4.0, [ln((0, 8.4), (4.0, 8.4))])
G['%'] = (4.4, [ln((4.0, 0), (0.4, 7)), arc(0.9, 1.2, 0.9, 1.2, 270, 630), arc(3.5, 5.8, 0.9, 1.2, 270, 630)])
G['&'] = (4.4, [ln((4.4, 7.0), (1.1, 2.9), (1.0, 1.1), (1.9, 0.1), (2.8, 0.9), (2.6, 2.4), (0.6, 4.3), (0.3, 5.9),
                   (1.3, 7.0), (2.7, 6.8), (4.2, 4.6))])
G['#'] = (4.6, [ln((1.6, 0.4), (1.0, 7.0)), ln((3.6, 0.4), (3.0, 7.0)), ln((0, 2.5), (4.6, 2.5)), ln((0, 4.9), (4.4, 4.9))])
G['*'] = (3.4, [ln((1.7, 0.6), (1.7, 4.0)), ln((0.2, 1.4), (3.2, 3.2)), ln((3.2, 1.4), (0.2, 3.2))])
G['<'] = (3.0, [ln((3.0, 1.6), (0, 4.0), (3.0, 6.4))])
G['>'] = (3.0, [ln((0, 1.6), (3.0, 4.0), (0, 6.4))])
G['^'] = (3.0, [ln((0, 2.4), (1.5, 0), (3.0, 2.4))])
G['~'] = (4.0, [cat(arc(1.0, 4.2, 1.0, 0.8, 180, 360), arc(3.0, 4.0, 1.0, 0.8, 180, 0))])
G['`'] = (0.8, [ln((0, 0), (0.8, 1.6))])
G['{'] = (2.0, [cat(arc(2.0, 1.6, 1.0, 2.2, 270, 180), ln((1.0, 3.0), (0, 3.6), (1.0, 4.2)), arc(2.0, 5.8, 1.0, 2.2, 180, 90))])
G['}'] = (2.0, [cat(arc(0.0, 1.6, 1.0, 2.2, 270, 360), ln((1.0, 3.0), (2.0, 3.6), (1.0, 4.2)), arc(0.0, 5.8, 1.0, 2.2, 0, 90))])
G['@'] = (5.4, [cat(arc(2.9, 4.2, 1.1, 1.3, 0, 360), ln((4.0, 5.4)), arc(2.8, 4.0, 2.6, 3.3, 30, -300))])
G['$'] = (3.8, [cat(arc(1.9, 2.4, 1.8, 1.35, 338, 90), arc(1.9, 5.0, 1.9, 1.3, 270, 515)), ln((1.9, 0), (1.9, 7.4))])
G['…'] = (4.4, [dot(0, 6.7), dot(2.2, 6.7), dot(4.4, 6.7)])
G['·'] = (0.0, [dot(0, 4.0)])

# --- the board's own marks
# The Guild Mark: a coin with an M struck on it, a little larger than a figure
G[COIN] = (5.2, [arc(2.6, 3.6, 2.6, 2.7, 270, 90), arc(2.6, 3.6, 2.6, 2.7, 90, -90),
                 ln((1.45, 4.85), (1.45, 2.35), (2.6, 3.85), (3.75, 2.35), (3.75, 4.85))])
G[UP] = (3.8, [ln((1.9, 7.0), (1.9, 0.6)), ln((0.1, 2.5), (1.9, 0.4), (3.7, 2.5))])
G[DOWN] = (3.8, [ln((1.9, 0.0), (1.9, 6.4)), ln((0.1, 4.5), (1.9, 6.6), (3.7, 4.5))])

# The coin's inner M is struck finer than the chalk's breadth
FINE = {COIN: {2: 0.55}}


def chars():
    return list(G)


# ================================================================ hands

class Hand:
    """How a set of letters is drawn: slant, breadth, the edge and the grain, and the space after each letter."""

    def __init__(self, name, slant, radius, edge, gap, jitter, nib=None):
        self.name, self.slant, self.radius, self.edge, self.gap, self.jitter, self.nib = name, slant, radius, edge, gap, jitter, nib

    def advance(self, ch):
        w, strokes = G[ch]
        if ch == ' ':
            return 3.2 if self.name == 'chalk' else 2.8
        return round(w + 2 * self.radius + self.gap, 2)

    def shape(self, ch):
        """The glyph's strokes as written by this hand: slanted and a little unsteady, offset so the ink's left edge is at 0."""
        w, strokes = G[ch]
        rng = np.random.default_rng(sum(ord(c) * 131 for c in ch) + (7 if self.name == 'chalk' else 19))
        out = []
        for s in strokes:
            wob = rng.normal(0, self.jitter, 2)
            pts = []
            for (x, y) in s:
                # Lean: the top of a capital sits slant * 7 px right of its foot
                xx = x + self.radius + (CAP - y) * self.slant + wob[0] * (0.5 + 0.5 * (y / CAP))
                yy = y + wob[1] * 0.6
                pts.append((xx, yy))
            out.append(pts)
        return out


CHALK = Hand('chalk', slant=0.06, radius=0.62, edge=0.26, gap=1.0, jitter=0.07)
INK = Hand('ink', slant=0.17, radius=0.36, edge=0.16, gap=0.85, jitter=0.05, nib=35.0)


# ================================================================ rasterizing

def _seg_dist(px, py, pts):
    """Distance from each point to a polyline, the running length along it at the nearest point, and the nearest
    segment's direction (radians)."""
    best = np.full(px.shape, 1e9)
    along = np.zeros(px.shape)
    ang = np.zeros(px.shape)
    run = 0.0
    if len(pts) == 1:
        pts = [pts[0], pts[0]]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        dx, dy = x1 - x0, y1 - y0
        L2 = max(dx * dx + dy * dy, 1e-9)
        t = np.clip(((px - x0) * dx + (py - y0) * dy) / L2, 0, 1)
        d = np.hypot(px - (x0 + t * dx), py - (y0 + t * dy))
        closer = d < best
        best = np.where(closer, d, best)
        along = np.where(closer, run + t * math.sqrt(L2), along)
        ang = np.where(closer, math.atan2(dy, dx), ang)
        run += math.sqrt(L2)
    return best, along, ang, run


def _noise(w, h, seed, cell):
    rng = np.random.default_rng(seed)
    gw, gh = max(2, int(w / cell) + 2), max(2, int(h / cell) + 2)
    g = rng.random((gh, gw))
    fx = np.arange(w) / cell
    fy = np.arange(h) / cell
    x0, y0 = np.floor(fx).astype(int), np.floor(fy).astype(int)
    tx, ty = fx - x0, fy - y0
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a = g[y0][:, x0] * (1 - tx) + g[y0][:, x0 + 1] * tx
    b = g[y0 + 1][:, x0] * (1 - tx) + g[y0 + 1][:, x0 + 1] * tx
    return a * (1 - ty[:, None]) + b * ty[:, None]


def raster(hand, ch, strokes, box, seed):
    """The glyph's alpha over `box` (x0, y0, x1, y1 in font px, on the texel grid), and for each texel the stroke that
    owns it (-1 where there's nothing)."""
    x0, y0, x1, y1 = box
    w, h = int(round((x1 - x0) * T)), int(round((y1 - y0) * T))
    ys, xs = np.mgrid[0:h, 0:w].astype(float)
    px = x0 + (xs + 0.5) / T
    py = y0 + (ys + 0.5) / T
    alpha = np.zeros((h, w))
    owner = np.full((h, w), -1)
    owner_d = np.full((h, w), 1e9)
    rough = (_noise(w, h, seed, 1.6) - 0.5) * (0.34 if hand.name == 'chalk' else 0.12)
    grain = _noise(w, h, seed + 5, 0.8) * 0.6 + np.random.default_rng(seed + 9).random((h, w)) * 0.4
    fine = FINE.get(ch, {})
    for k, s in enumerate(strokes):
        if not s:
            continue
        d, along, ang, length = _seg_dist(px, py, s)
        r = hand.radius * fine.get(k, 1.0)
        if hand.nib is not None:
            # A broad nib: thickest across its own angle
            r = r * (0.62 + 0.62 * np.abs(np.sin(ang - math.radians(hand.nib))))
        dd = d + rough
        cov = np.clip((r + hand.edge - dd) / (2 * hand.edge), 0, 1)
        if hand.name == 'chalk':
            # Hardest where the stroke starts; the grain of the slate shows through, more toward the edges
            press = 1.0 - 0.16 * np.clip(along / max(length, 0.5), 0, 1)
            core = np.clip((r - dd) / r, 0, 1)
            a = cov * press * (0.5 + 0.5 * np.clip(grain * 1.25 + core * 0.35, 0, 1))
        else:
            # Ink pools a little at the start of a stroke
            pool = 1.0 + 0.12 * np.clip(1 - along / 1.2, 0, 1)
            a = cov * np.clip(0.86 + 0.14 * grain, 0, 1) * pool
        a = np.clip(a, 0, 1)
        alpha = 1 - (1 - alpha) * (1 - a)
        mine = (d < owner_d) & (a > 0.01)
        owner = np.where(mine, k, owner)
        owner_d = np.where(mine, d, owner_d)
    alpha = np.clip(alpha, 0, 1)
    if hand.name == 'chalk':
        # Below the cut-off the game shows nothing anyway; lift the faint edge so strokes don't look thin
        alpha = np.where(alpha > 0.05, np.clip(alpha * 1.08, 0, 1), 0)
    owner = np.where(alpha > 0.02, owner, -1)
    return alpha, owner


# ================================================================ pieces

def _one_way(vals, tol=0.05):
    return all(b >= a - tol for a, b in zip(vals, vals[1:])) or all(b <= a + tol for a, b in zip(vals, vals[1:]))


def pieces(stroke):
    """A stroke cut into the longest runs that each go one way along x or along y, so each can be revealed by sweeping
    a line across it. Gives (points, axis, direction) with axis 0 for x and 1 for y: the sweep goes along the axis the
    run keeps to (the longer one, if it keeps to both)."""
    if len(stroke) < 2:
        return []
    runs = []
    cur = [stroke[0]]
    for p in stroke[1:]:
        cand = cur + [p]
        if len(cand) <= 2 or _one_way([q[0] for q in cand]) or _one_way([q[1] for q in cand]):
            cur = cand
        else:
            runs.append(cur)
            cur = [cur[-1], p]
    runs.append(cur)
    out = []
    for run in runs:
        xs = [p[0] for p in run]
        ys = [p[1] for p in run]
        mx, my = _one_way(xs), _one_way(ys)
        ex, ey = abs(xs[-1] - xs[0]), abs(ys[-1] - ys[0])
        if my and (not mx or ey >= ex):
            out.append((run, 1, 1 if ys[-1] >= ys[0] else -1))
        else:
            out.append((run, 0, 1 if xs[-1] >= xs[0] else -1))
    return out


def glyph_box(hand, strokes, ch):
    """The texel-aligned box (font px) round everything the glyph could touch."""
    pad = hand.radius + hand.edge + 0.35
    pts = [p for s in strokes for p in s]
    if not pts:
        return (0, 0, 1.0 / T, 1.0 / T)
    x0 = min(p[0] for p in pts) - pad
    x1 = max(p[0] for p in pts) + pad
    y0 = min(p[1] for p in pts) - pad
    y1 = max(p[1] for p in pts) + pad
    snap = lambda v, f: f(v * T) / T
    return (snap(x0, math.floor), snap(y0, math.floor), snap(x1, math.ceil), snap(y1, math.ceil))


def build(hand, with_pieces):
    """Every glyph in `hand`: {ch: dict(advance, full=(alpha, box), pieces=[(alpha, box, axis, dir, points)])}."""
    out = {}
    for ch in chars():
        strokes = hand.shape(ch)
        seed = 1000 + sum(ord(c) for c in ch) * 17 + (0 if hand.name == 'chalk' else 5000)
        entry = {'advance': hand.advance(ch), 'pieces': []}
        if not any(strokes):
            entry['full'] = None
            out[ch] = entry
            continue
        box = glyph_box(hand, strokes, ch)
        if with_pieces:
            # Cut the strokes into pieces first, and raster against the pieces so each texel knows its piece
            runs = []
            for s in strokes:
                runs.extend(pieces(s))
            alpha, owner = raster(hand, ch, [r[0] for r in runs], box, seed)
            entry['full'] = (alpha, box)
            for k, (pts, axis, d) in enumerate(runs):
                a = np.where(owner == k, alpha, 0.0)
                ys, xs = np.nonzero(a > 0.02)
                if len(xs) == 0:
                    # Wholly covered by another piece: still give the tip its path
                    entry['pieces'].append((None, None, axis, d, pts))
                    continue
                bx0, bx1, by0, by1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
                sub = a[by0:by1, bx0:bx1]
                pbox = (box[0] + bx0 / T, box[1] + by0 / T, box[0] + bx1 / T, box[1] + by1 / T)
                entry['pieces'].append((sub, pbox, axis, d, pts))
        else:
            alpha, owner = raster(hand, ch, strokes, box, seed)
            entry['full'] = (alpha, box)
        out[ch] = entry
    return out


# ================================================================ chalk marks that aren't letters

def chalk_line(length, seed, wave=0.25, width=1.0):
    """A long chalk stroke, laid down left to right: underlines, the stroke through "none", the swash under the head.
    Gives (alpha, box) with the line's centre at y = 0."""
    rng = np.random.default_rng(seed)
    n = int(length / 2) + 2
    pts = [(i * length / (n - 1), wave * math.sin(i * 1.7 + seed) + rng.normal(0, 0.06)) for i in range(n)]
    hand = Hand('chalk', 0.0, CHALK.radius * width, CHALK.edge, 0, 0)
    box = glyph_box(hand, [pts], '-')
    alpha, _ = raster(hand, '-', [pts], box, seed)
    return alpha, box


def smudge(w, h, seed):
    """A rubbed-out line: chalk smeared sideways by a rag, the grain dragged along. (alpha, box) with its top-left at 0."""
    W, H = int(w * T), int(h * T)
    ys, xs = np.mgrid[0:H, 0:W].astype(float)
    u, v = (xs + 0.5) / W, (ys + 0.5) / H
    streak = _noise(W, H, seed, 1.0)
    streak = np.repeat(_noise(W, 1, seed + 1, 3.0), H, axis=0) * 0.6 + _noise(W, H, seed + 2, 2.5) * 0.4
    body = np.clip(1 - np.abs(v - 0.5) * 2.2, 0, 1) ** 0.8 * np.clip(np.minimum(u, 1 - u) * 7, 0, 1)
    a = body * (0.25 + 0.75 * streak) * 0.62
    return np.clip(a, 0, 1), (0, 0, W / T, H / T)


def specks(seed):
    """Four tiny motes of chalk dust, 2x2 font px each, side by side. (alpha, box)."""
    S = 2 * T
    out = np.zeros((S, S * 4))
    rng = np.random.default_rng(seed)
    for k in range(4):
        ys, xs = np.mgrid[0:S, 0:S].astype(float)
        cx, cy = S / 2 - 0.5 + rng.normal(0, 0.4), S / 2 - 0.5 + rng.normal(0, 0.4)
        r = 1.2 + 0.5 * k / 3
        d = np.hypot(xs - cx, ys - cy)
        out[:, k * S:(k + 1) * S] = np.clip((r - d) / 1.1, 0, 1) * (0.7 + 0.3 * rng.random((S, S)))
    return out, (0, 0, 8, 2)


def puff(seed):
    """A soft little cloud of chalk dust, 6x6 font px. (alpha, box)."""
    S = 6 * T
    ys, xs = np.mgrid[0:S, 0:S].astype(float)
    d = np.hypot(xs - S / 2 + 0.5, ys - S / 2 + 0.5) / (S / 2)
    n = _noise(S, S, seed, 2.0)
    a = np.clip(1 - d, 0, 1) ** 1.6 * (0.45 + 0.55 * n)
    return np.clip(a * 0.85, 0, 1), (0, 0, 6, 6)


def stick():
    """The stick of chalk's skin: 4x12 font px, white, its writing end worn round and grey with slate dust; the
    other end clean. Packed as RGBA (white with grey toward the tip)."""
    W, H = 4 * T, 12 * T
    ys, xs = np.mgrid[0:H, 0:W].astype(float)
    rng = np.random.default_rng(77)
    v = (ys + 0.5) / H
    base = 236 - 10 * rng.random((H, W)) - 14 * (np.abs((xs + 0.5) / W - 0.5) * 2) ** 2
    dusty = np.clip((v - 0.78) / 0.22, 0, 1)
    rgb = np.stack([base * (1 - 0.32 * dusty), base * (1 - 0.3 * dusty), base * (1 - 0.26 * dusty)], -1)
    a = np.ones((H, W))
    return rgb, a
