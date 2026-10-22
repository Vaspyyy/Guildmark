"""The Hearthcraft hand tools and the Cook's Book, 32 x 32 (cook_art.py): the Cook's Ladle, the Cook's Knife, the Turner, the Gilded Ladle and the book.

Each tool lies along the sprite's diagonal as the mod's swords do: the grip toward the lower left, the working end toward the upper right.
The axis runs from `GRIP_END` = (3.6, 28.4) up and to the right at 45 degrees; `uv` gives each pixel its distance along it (u) and across
it (v, positive toward the lower right, the side away from the light). Every tool's `LAYOUT` entry gives the point where the hand takes it
and the point of its working end, in sprite pixels (x, y from the top left); cook_art.py holds the same numbers in `TOOL_LAYOUT`.

Every one of them is also a short animation, a bright band that passes over its metal (or its gold) now and then: `glint_frames` gives the
strip's frames, the first the plain sprite, and `GLINT` how long that first rests and how long each frame after it lasts.
"""
import math

import numpy as np
from PIL import Image

from hdkit import PAL, Canvas, grid, fbm, rand, bands, light, disc, path_dist, dist_in, rim, outline
from cook_items import N, paint, put, stroke, IRON, STEEL, GOLD, LEATHER

D = math.sqrt(0.5)
GRIP_END = (3.6, 28.4)

LAYOUT = {
    'cooks_ladle': {'grip': (7, 25), 'tip': (24, 8)},
    'cooks_knife': {'grip': (7, 25), 'tip': (27, 5)},
    'turner': {'grip': (7, 25), 'tip': (25, 7)},
    'gilded_ladle': {'grip': (7, 25), 'tip': (24, 8)},
}

HANDLE = [(30, 20, 16), (58, 40, 28), (92, 66, 44), (128, 96, 62), (166, 130, 86), (204, 168, 116)]
WALNUT = [(22, 14, 12), (44, 28, 22), (70, 46, 34), (98, 68, 48), (128, 94, 66), (160, 124, 90)]
BRASS = PAL['brass']
CRIMSON = PAL['crimson']


def uv():
    """Pixel centres and their (u, v) along and across the tool's axis."""
    xs, ys = grid(N)
    u = ((xs - GRIP_END[0]) - (ys - GRIP_END[1])) * D
    v = ((xs - GRIP_END[0]) + (ys - GRIP_END[1])) * D
    return xs, ys, u, v


def at(u, v=0.0):
    """The sprite point at distance u along the axis and v across it."""
    return (GRIP_END[0] + (u + v) * D, GRIP_END[1] + (v - u) * D)


def rod(cv, u0, u1, r, pal, base=0.55, k=0.95, noise=None, dither=0.3, v=0.0):
    """A round rod along the axis from u0 to u1, radius r, lit from the upper left; returns its mask."""
    return stroke(cv, [at(u0, v), at(u1, v)], 2 * r, pal, base, k, noise, dither)


def handle(cv, u0, u1, r, pal, seed, hole=True):
    """A wooden handle: a rod with the grain running along it and a knob at the grip end."""
    xs, ys, u, v = uv()
    grain = fbm(N, N, 5, seed)
    m = rod(cv, u0, u1, r, pal, 0.52, 0.95, grain, 0.35)
    streak = m & (np.sin((v * 2.3) + grain * 5.0) > 0.86)
    cv.over(streak, np.array(pal[1], float), 0.5)
    # the knob: a round swelling at the end
    km, kh = disc(*at(u0 + 0.6), r + 0.5, N)
    paint(cv, km, pal, kh * 2.2, 0.52, 0.95, None, 0.3)
    if hole:
        hx, hy = at(u0 + 3.6)
        put(cv, int(hx), int(hy), pal[0])
    return m


def band_across(cv, u0, u1, r, pal, base=0.6):
    """A metal band round a handle: a short wide rod across the axis' width, lit like the handle."""
    xs, ys, u, v = uv()
    m = (u >= u0) & (u < u1) & (np.abs(v) <= r)
    h = np.sqrt(np.clip(1 - (v / r) ** 2, 0, 1)) * r
    paint(cv, m, pal, h, base, 0.95, None, 0.3)
    # a bright edge on the side toward the grip, a dark one toward the tip
    cv.over(m & (u < u0 + 0.9), np.array(pal[4], float), 0.5)
    cv.over(m & (u >= u1 - 0.9), np.array(pal[1], float), 0.6)
    return m


def ladle_bowl(cv, uc, rim_pal, hull_pal, ru=4.5, rv=5.0, seed=0):
    """The ladle's cup seen from above and a little in front: a domed hull, a bright rim ring, and a hollow with the wall turned from the
    light lit. Returns the metal mask. Drawn without dither: a cup this small reads by its clean ring."""
    xs, ys, u, v = uv()
    cx, cy = at(uc)
    du, dv = (u - uc) / ru, v / rv
    rr = np.hypot(du, dv)
    outer = rr <= 1.0
    toward_light = (-(xs - cx) - (ys - cy)) * D / max(ru, rv)                 # +1 at the upper-left, -1 at the lower-right
    hull = 0.52 + 0.34 * toward_light + 0.05 * (fbm(N, N, 4, seed) - 0.5)
    cv.put(outer, bands(hull_pal, np.where(outer, hull, 0), 0.0))
    cv.a[outer] = 1.0
    ring = outer & (rr > 0.76)
    rt = np.where(toward_light > 0.3, 1.0, np.where(toward_light > -0.35, 0.78, 0.5))             # three clean tones round the rim
    cv.put(ring, bands(rim_pal, np.where(ring, rt, 0), 0.0))
    iu, iv = (u - (uc + 0.3)) / (ru * 0.72), (v + 0.7) / (rv * 0.7)
    inner = np.hypot(iu, iv) <= 1.0
    cv.put(inner, np.array(hull_pal[1], float))
    # the hollow's walls: the one turned toward the light (its lower right) is lit, the opposite one in shadow
    lit_wall = inner & ~np.roll(np.roll(inner, -1, 0), -1, 1)
    dark_wall = inner & ~np.roll(np.roll(inner, 1, 0), 1, 1)
    cv.put(dark_wall & ~lit_wall, np.array(hull_pal[0], float))
    cv.put(lit_wall, np.array(hull_pal[3], float))
    return outer


# ---------------------------------------------------------------- the ladle

def _ladle(gilded=False):
    cv = Canvas(N)
    xs, ys, u, v = uv()
    # the handle
    handle(cv, 0.0, 20.5, 1.35, HANDLE, 31)
    metal = np.zeros((N, N), bool)
    if gilded:
        for (a, b) in ((4.6, 5.9), (12.4, 13.7), (19.2, 20.5)):
            metal |= band_across(cv, a, b, 1.9, GOLD, 0.62)
        km, kh = disc(*at(0.7), 1.9, N)
        paint(cv, km, GOLD, kh * 2.4, 0.6, 0.95, None, 0.3)
        metal |= km
    else:
        metal |= band_across(cv, 19.0, 20.8, 1.75, IRON, 0.6)
    # the neck: a flat strap from the collar to the cup
    nk = rod(cv, 20.5, 26.6, 0.95, GOLD if gilded else IRON, 0.55, 0.95)
    metal |= nk
    rivet = at(22.2)
    put(cv, int(rivet[0]), int(rivet[1]), (GOLD if gilded else IRON)[5])
    # the cup
    if gilded:
        cup = ladle_bowl(cv, 28.9, GOLD, IRON, seed=5)
        metal |= cup
    else:
        cup = ladle_bowl(cv, 28.9, IRON, IRON, seed=5)
        metal |= cup
    rim(cv, 0.32)
    outline(cv, 0.8)
    return cv, metal


def _knife():
    cv = Canvas(N)
    xs, ys, u, v = uv()
    # the handle: dark walnut with two brass rivets and a brass cap
    handle(cv, 0.0, 12.0, 1.5, WALNUT, 41, hole=False)
    for ur in (3.4, 7.8):
        rx, ry = at(ur)
        put(cv, int(rx), int(ry), BRASS[5])
        put(cv, int(rx) + 1, int(ry), BRASS[3])
    metal = band_across(cv, 11.8, 13.4, 1.9, BRASS, 0.6)
    # the blade: a straight spine and a belly curving up to the point
    spine = np.where(u < 28.0, -1.8, -1.8 + (u - 28.0) * 0.34)
    belly = 1.8 - (np.clip(u - 25.0, 0, None) ** 1.45) * 0.2
    tipend = 33.2
    blade = (u >= 13.0) & (u < tipend) & (v >= spine) & (v <= belly)
    t = np.where(v < spine + 0.95, 0.80, np.where(v < belly - 1.25, 0.58, np.where(v < belly - 0.45, 0.34, 0.94)))
    t = t + 0.05 * np.sin(u * 0.7)
    cv.put(blade, bands(STEEL, np.where(blade, t, 0), 0.2))
    cv.a[blade] = 1.0
    cv.over(blade & (u < 14.2), np.array(STEEL[2], float), 0.5)                       # the heel's shadow beside the collar
    metal |= blade
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv, metal


def _turner():
    cv = Canvas(N)
    xs, ys, u, v = uv()
    handle(cv, 0.0, 17.5, 1.4, HANDLE, 51)
    metal = band_across(cv, 16.2, 17.9, 1.7, IRON, 0.6)
    # a neck, then the flat blade with its rounded corners and three slots
    metal |= rod(cv, 17.5, 22.0, 0.9, STEEL, 0.55, 0.95)
    ua, ub, hw = 20.4, 30.4, 4.4
    corner = 1.6
    cu = np.clip(np.maximum(ua + corner - u, u - (ub - corner)), 0, None)
    cvv = np.clip(np.abs(v) - (hw - corner), 0, None)
    blade = (u >= ua) & (u <= ub) & (np.abs(v) <= hw) & (np.hypot(cu, cvv) <= corner + 0.01)
    d = dist_in(blade, 4)
    paint(cv, blade, STEEL, np.clip(d / 2.5, 0, 1) * 1.6, 0.58, 1.0, fbm(N, N, 4, 7), 0.3, 0.1)
    cv.over(blade & (v < -hw + 1.4), np.array(STEEL[4], float), 0.5)
    slots = np.zeros((N, N), bool)
    for vs in (-2.1, 0.0, 2.1):
        slots |= (u >= ua + 3.0) & (u <= ub - 1.4) & (np.abs(v - vs) < 0.5)
    cv.a[slots & blade] = 0.0
    metal |= blade & ~slots
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv, metal


# ---------------------------------------------------------------- the book

def _book():
    cv = Canvas(N)
    xs, ys = grid(N)
    grain = fbm(N, N, 4, 77)
    fine = rand(N, N, 78)
    # the page block, showing at the right and the foot
    pages = (xs > 8) & (xs < 28.0) & (ys > 4.5) & (ys < 29.5)
    lines = (np.mod(ys, 2.0) < 1.0)
    pt = 0.62 + 0.12 * (grain - 0.5) - 0.12 * lines - 0.25 * (xs > 27.0) - 0.2 * (ys > 28.5)
    cv.put(pages, bands(PAL['parchment'], pt))
    back = (xs > 7.5) & (xs < 28.5) & (ys > 28.5) & (ys < 30.5)
    cv.put(back, bands(LEATHER, 0.3 + 0.1 * grain))
    # the front cover
    cover = (xs > 6.5) & (xs < 26.5) & (ys > 2.5) & (ys < 28.0)
    dd = np.minimum.reduce([xs - 6.5, 26.5 - xs, ys - 2.5, 28.0 - ys])
    ct = 0.64 + 0.7 * light(np.clip(dd / 2.0, 0, 1) * 2.0, 1.0) + 0.14 * (grain - 0.5) + 0.06 * (fine - 0.5)
    cv.put(cover, bands(LEATHER, ct))
    # a blind-tooled line round the cover, a shade darker
    cv.over(cover & (dd > 1.5) & (dd < 2.5), np.array(LEATHER[1], float), 0.55)
    # the spine: rounded, ribbed with three raised bands
    spine = (xs > 3.5) & (xs < 8.5) & (ys > 2.0) & (ys < 28.5)
    round_ = np.sin(np.clip((xs - 3.5) / 5.0, 0, 1) * math.pi)
    st = 0.34 + 0.55 * round_ - 0.25 * (xs > 7.0) + 0.08 * (grain - 0.5)
    cv.put(spine, bands(LEATHER, st))
    for by in (7.0, 15.0, 23.0):
        rb = spine & (np.abs(ys - by) < 0.9)
        cv.put(rb, bands(LEATHER, 0.24 + 0.5 * round_ + 0.2 * (ys < by)))
    gold = np.zeros((N, N), bool)

    def gilt(m, base=0.62, lit=0.22, shade=0.3):
        """Fill [m] with gold, a lit edge to its upper left and a shaded one to its lower right, and add it to the glint mask."""
        up_left = m & ~np.roll(np.roll(m, 1, 0), 1, 1)
        low_right = m & ~np.roll(np.roll(m, -1, 0), -1, 1)
        t = base + lit * up_left - shade * low_right + 0.06 * (fine - 0.5)
        cv.put(m, bands(GOLD, np.where(m, t, 0), 0.25))
        cv.a[m] = 1.0
        gold[m] = True

    # brass corners at the right
    for (x0, y0, sx, sy) in ((26.0, 3.0, -1, 1), (26.0, 27.5, -1, -1)):
        uu = (xs - x0) * sx
        vv = (ys - y0) * sy
        gilt(cover & (uu >= 0) & (vv >= 0) & (uu + vv < 4.0), 0.6)
    # the cover's gilt: an open pot with its ears, three wisps of steam rising from it
    cx = 13.4
    belly = np.hypot(np.maximum(np.abs(xs - cx) - 2.0, 0), np.maximum(ys - 16.4, 0)) <= 2.6
    body = (np.abs(xs - cx) <= 4.6) & (ys > 13.4) & (ys < 19.2) & belly
    mouth = ((xs - cx) / 4.6) ** 2 + ((ys - 13.4) / 1.35) ** 2 <= 1.0
    hollow = ((xs - cx) / 3.6) ** 2 + ((ys - 13.5) / 0.8) ** 2 <= 1.0
    ears = (((xs > cx - 6.4) & (xs < cx - 4.3)) | ((xs > cx + 4.3) & (xs < cx + 6.4))) & (ys > 13.8) & (ys < 15.8)
    gilt(body | mouth | ears, 0.6)
    cv.put(hollow, np.array(GOLD[1], float))
    cv.over(hollow & (ys > 13.7), np.array(GOLD[0], float), 0.5)
    for sx_, ph, top in ((cx - 2.3, 0.0, 8.6), (cx, 1.9, 6.4), (cx + 2.3, 3.6, 8.6)):
        pts = [(sx_ + 0.8 * math.sin(ph + s_ * 1.0), 12.6 - s_ * (12.6 - top) / 4.0) for s_ in range(0, 5)]
        dd2, _ = path_dist(pts, N)
        gilt(cover & (dd2 <= 0.6), 0.74, 0.1, 0.1)
    # the clasp: a leather strap across the right edge, held by a brass plate with a hasp
    strap = (xs > 18.6) & (xs < 29.6) & (ys > 19.0) & (ys < 21.8)
    cv.put(strap, bands(LEATHER, 0.66 - 0.4 * np.clip((ys - 19.0) / 2.8, 0, 1) + 0.12 * (grain - 0.5)))
    cv.over(strap & (xs > 27.0), np.array(LEATHER[1], float), 0.4)
    cv.over(strap & ((np.abs(ys - 19.0) < 0.5)), np.array(LEATHER[4], float), 0.4)
    plate = (xs > 21.4) & (xs < 25.8) & (ys > 17.6) & (ys < 23.2)
    gilt(plate, 0.62)
    cv.put((np.abs(xs - 23.6) < 0.6) & (ys > 19.2) & (ys < 21.6), np.array(GOLD[0], float))
    # the ribbon, hanging out at the foot
    rx = 11.0 + (ys - 28.0) * 0.2
    rib = (np.abs(xs - rx) < 1.1) & (ys > 27.5) & (ys < 31.8)
    cv.put(rib, bands(CRIMSON, 0.62 + 0.3 * (xs < rx)))
    notch = (np.abs(xs - (11.0 + 3.0 * 0.2)) < 0.5) & (ys > 30.8)
    cv.a[notch] = 0
    outline(cv, 0.8)
    return cv, gold & (cv.a > 0.5)


# ---------------------------------------------------------------- registry and the glint animation

_BUILDERS = {
    'cooks_ladle': lambda: _ladle(False),
    'cooks_knife': _knife,
    'turner': _turner,
    'gilded_ladle': lambda: _ladle(True),
    'cook_book': _book,
}
_CACHE = {}


def _get(name):
    if name not in _CACHE:
        cv, metal = _BUILDERS[name]()
        _CACHE[name] = (cv.image(), metal)
    return _CACHE[name]


# frames of the glint (the first is the plain sprite), the ticks the plain frame rests, and the ticks of each frame in the band's pass
GLINT = {
    'cooks_ladle': dict(rest=110, frames=7, tick=2),
    'cooks_knife': dict(rest=130, frames=8, tick=2),
    'turner': dict(rest=95, frames=7, tick=2),
    'gilded_ladle': dict(rest=70, frames=9, tick=2),
    'cook_book': dict(rest=150, frames=8, tick=2),
}


def glint_frames(name):
    """The sprite and the frames of a bright band passing over its metal: from the grip to the working end, or for the book from its lower
    left to its upper right."""
    img, metal = _get(name)
    spec = GLINT[name]
    base = np.array(img).astype(float)
    xs, ys = grid(N)
    if name == 'cook_book':
        pos = (xs - ys)                                  # lower left to upper right
        lo, hi = -26.0, 28.0
    else:
        pos = ((xs - GRIP_END[0]) - (ys - GRIP_END[1])) * D
        lo, hi = 0.0, 34.0
    out = [img]
    n = spec['frames']
    for i in range(1, n):
        c = lo + (hi - lo) * (i - 0.5) / (n - 1)
        w = 2.6
        k = np.clip(1 - np.abs(pos - c) / w, 0, 1)
        strength = np.where(k > 0.66, 0.88, np.where(k > 0.3, 0.45, 0.0)) * metal
        fr = base.copy()
        white = np.array([255, 250, 232], float)
        fr[..., :3] = base[..., :3] + (white - base[..., :3]) * strength[..., None]
        out.append(Image.fromarray(np.clip(fr + 0.5, 0, 255).astype(np.uint8), 'RGBA'))
    return out


PAINTERS = {name: (lambda n=name: _get(n)[0].copy()) for name in _BUILDERS}
