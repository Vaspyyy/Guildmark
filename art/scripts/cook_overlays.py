"""The overlays that lie over a dish's sprite (cook_art.py): the steam of a hot dish, the smoke of a smoked or a burnt one, the flies of one that
has gone off, the stamps of the finer tiers, and the paler sprite a Plain dish wears. All are 32 x 32 and every moving one is a strip of frames
that loops (`mcmeta` writes its timing); the steam and the smoke are drawn from each dish's own sprite, so they rise from where that dish's top is.

How the item definitions use them (cook_art.py): a dish is a composite of its sprite (a `select` on `guildmark:dish_stage` tints it for stale and
gone off, and a `select` on `guildmark:dish_tier` swaps the paler sprite in for a Plain dish), an overlay chosen by the stage (steam when fresh,
nothing when stale, flies when gone off) and a stamp chosen by the tier (silver for Fine, gold for Hallmarked).
"""
import math

import numpy as np
from PIL import Image

from hdkit import PAL, Canvas, grid, bands, outline
from cook_items import N

STEAM = (250, 247, 240)
SMOKE = (138, 132, 134)
FRAMES = 8                  # frames of a steam or smoke loop
FLY_FRAMES = 12


def _alpha_mask(img):
    return np.array(img)[..., 3] > 0


def pale(img):
    """The Plain dish's sprite: the same dish with less of its colour and a little more light, its outline kept so it still reads."""
    a = np.array(img).astype(float)
    rgb = a[..., :3]
    lum = rgb @ np.array([0.30, 0.59, 0.11])
    grey = np.repeat(lum[..., None], 3, axis=2)
    wash = np.array([238.0, 232.0, 216.0])
    keep = np.clip((lum - 30.0) / 50.0, 0.35, 1.0)[..., None]          # the dark outline and deep shadows change less
    out = rgb + (grey - rgb) * 0.38 * keep
    out = out + (wash - out) * 0.26 * keep
    a[..., :3] = np.clip(out + 0.5, 0, 255)
    return Image.fromarray(a.astype(np.uint8), 'RGBA')


def _sources(img, count, spread):
    """Where wisps leave a dish: [count] columns spread across the middle of its sprite, each the top of the sprite there."""
    m = _alpha_mask(img)
    cols = np.nonzero(m.any(axis=0))[0]
    cx = (cols.min() + cols.max()) / 2.0 + 0.5
    width = cols.max() - cols.min() + 1
    out = []
    for k in range(count):
        off = (k - (count - 1) / 2.0) * spread * width
        x = int(round(cx + off))
        x = min(max(x, int(cols.min()) + 2), int(cols.max()) - 2)
        ys = np.nonzero(m[:, x])[0]
        out.append((x, int(ys.min()) + 1))
    return out


def wisps(img, count=3, spread=0.2, color=STEAM, alpha=0.8, height=11, amp=1.6, phase0=0.0, frames=FRAMES):
    """[frames] frames of [count] wisps leaving the dish's top: each a slim column whose bend and brightness travel upward, so the loop closes."""
    srcs = _sources(img, count, spread)
    out = []
    for f in range(frames):
        a = np.zeros((N, N))
        for i, (x0, y0) in enumerate(srcs):
            H = int(min(height - (i % 2) * 2, y0 - 1))
            if H < 3:
                continue
            ph = phase0 + i * 1.7
            for yy in range(0, H + 1):
                t = yy / H
                x_off = amp * (0.35 + 0.65 * t) * math.sin(2 * math.pi * (t * 1.15 - f / frames) + ph)
                env = math.sin(math.pi * min(max(t, 0.0), 1.0)) ** 0.8
                pulse = 0.62 + 0.38 * math.sin(2 * math.pi * (t * 1.4 - f / frames) + ph * 2)
                v = alpha * env * pulse
                x, y = int(round(x0 + x_off)), y0 - yy
                if 0 <= x < N and 0 <= y < N:
                    a[y, x] = max(a[y, x], v)
                    if env > 0.4 and 0 <= x + 1 < N:
                        a[y, x + 1] = max(a[y, x + 1], v * 0.62)
        out.append(_overlay(a, color))
    return out


def _overlay(a, color):
    rgba = np.zeros((N, N, 4), np.uint8)
    rgba[..., :3] = np.array(color, np.uint8)
    rgba[..., 3] = np.clip(a * 255 + 0.5, 0, 255).astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA')


def steam(img, phase0=0.0):
    return wisps(img, 3, 0.2, STEAM, 0.95, 12, 1.7, phase0)


def smoke(img, phase0=0.0):
    """Smoke is greyer, slower to rise and a little broader than steam: one slim wisp for a smoked dish, two for a burnt one."""
    return wisps(img, 1, 0.0, SMOKE, 0.7, 12, 2.0, phase0)


def burnt_smoke(img, phase0=0.0):
    return wisps(img, 2, 0.24, SMOKE, 0.75, 12, 2.2, phase0)


def sheen(img, run=8, width=2.6):
    """A dish with no room above it (the strips hang to the sprite's top) shines instead: a warm band of light crosses its pixels from the lower
    left to the upper right in [run] frames, then a blank frame that rests. The loop closes."""
    m = _alpha_mask(img)
    xs, ys = grid(N)
    pos = ((xs - 3.0) - (ys - 29.0)) * math.sqrt(0.5)
    out = []
    for f in range(run):
        c = -2.0 + 36.0 * (f + 0.5) / run
        k = np.clip(1 - np.abs(pos - c) / width, 0, 1)
        a = np.where(k > 0.66, 0.78, np.where(k > 0.3, 0.38, 0.0)) * m
        out.append(_overlay(a, (255, 232, 180)))
    out.append(_overlay(np.zeros((N, N)), (255, 232, 180)))
    return out


def flies():
    """Three flies circling over a dish that has gone off: a dark two-pixel body and wings that flick. The paths are figure-eights that close."""
    frames = []
    paths = ((14.0, 9.0, 6.5, 3.4, 0.0, 1), (22.0, 14.0, 5.0, 3.0, 0.4, 1), (9.0, 15.0, 3.8, 2.6, 0.7, 2))
    for f in range(FLY_FRAMES):
        cv = Canvas(N)
        for (cx, cy, rx, ry, ph, k) in paths:
            th = 2 * math.pi * (f / FLY_FRAMES) + ph * 2 * math.pi
            x = cx + rx * math.sin(th * k)
            y = cy + ry * math.sin(th * (k + 1) * 0.5 + 1.1) * math.cos(th * 0.5 * k)
            xi, yi = int(round(x)), int(round(y))
            wing = (222.0, 232.0, 240.0)
            ys_ = (yi - 1,) if f % 2 == 0 else (yi - 1, yi + 1)
            for wy in ys_:
                for wx in ((xi,) if f % 2 == 0 else (xi + 1,)):
                    if 0 <= wx < N and 0 <= wy < N:
                        cv.px(wx, wy, wing, 0.9)
            for dx in (0, 1):
                if 0 <= xi + dx < N and 0 <= yi < N:
                    cv.px(xi + dx, yi, (14.0, 12.0, 16.0), 1.0)
        frames.append(cv.image())
    return frames


# ---------------------------------------------------------------- the stamps

SILVER = [(24, 28, 40), (52, 60, 80), (96, 108, 128), (150, 162, 178), (200, 210, 222), (238, 244, 250)]
INK_GOLD = PAL['gold']


def _stamp(pal, field_pal, centre, seed):
    """A small round stamp at the sprite's lower left: a lit ring, a dark field, a bright mark in its middle, a dark outline. It sits on the left
    because the game writes a stack's number at the slot's lower right (white, from x 17 minus its width, y 9 of the 16), which would hide it there."""
    cv = Canvas(N)
    xs, ys = grid(N)
    cx, cy, R = 6.0, 26.0, 4.2
    d = np.hypot(xs - cx, ys - cy)
    m = d <= R
    toward = (-(xs - cx) - (ys - cy)) * math.sqrt(0.5) / R
    ring = m & (d > R - 1.6)
    field = m & ~ring
    cv.put(ring, bands(pal, np.where(ring, 0.62 + 0.34 * toward, 0), 0.0))
    cv.put(field, bands(field_pal, np.where(field, 0.45 + 0.22 * toward, 0), 0.0))
    cv.a[m] = 1.0
    # the mark: a small four-point star in the field
    for (dx, dy, a) in ((0, 0, 1.0), (1, 0, 0.7), (-1, 0, 0.7), (0, 1, 0.7), (0, -1, 0.7)):
        cv.px(int(cx + dx - 0.0), int(cy + dy - 0.0), tuple(float(c) for c in centre), a)
    outline(cv, 0.82)
    return cv


def stamp_fine():
    cv = _stamp(SILVER, [(20, 26, 44), (36, 46, 76), (58, 72, 110), (84, 102, 146)], (236, 244, 255), 1)
    return cv.image()


def stamp_hallmarked():
    cv = _stamp(INK_GOLD, [(48, 22, 14), (92, 44, 22), (140, 72, 30), (178, 104, 44)], (255, 236, 160), 2)
    return cv.image()


def sweep(img, lo=0.0, hi=34.0, frames=9, width=2.2, star=None):
    """[frames] frames of [img] with a bright band crossing its opaque pixels from the lower left to the upper right, and (if [star] is a
    point) a four-point sparkle growing and fading there in the middle frames. The first frame is the plain image."""
    base = np.array(img).astype(float)
    n = img.width
    xs, ys = grid(n)
    pos = ((xs - 3.0 * n / N) - (ys - 29.0 * n / N)) * math.sqrt(0.5)       # the band's zero runs through the lower left, scaled to the sprite
    solid = base[..., 3] > 0
    out = [img]
    for i in range(1, frames):
        c = lo + (hi - lo) * (i - 0.5) / (frames - 1)
        k = np.clip(1 - np.abs(pos - c) / width, 0, 1)
        strength = np.where(k > 0.66, 0.9, np.where(k > 0.3, 0.45, 0.0)) * solid
        fr = base.copy()
        white = np.array([255.0, 250.0, 232.0])
        fr[..., :3] = base[..., :3] + (white - base[..., :3]) * strength[..., None]
        if star is not None:
            mid = (frames - 1) / 2.0
            size = int(round(2.4 - abs(i - mid) * 0.9))
            if size >= 1:
                sx, sy = star
                for r in range(0, size + 1):
                    for (ddx, ddy) in ((r, 0), (-r, 0), (0, r), (0, -r)):
                        x, y = sx + ddx, sy + ddy
                        if 0 <= x < n and 0 <= y < n:
                            fa = 1.0 - 0.28 * r
                            if fr[y, x, 3] == 0:
                                fr[y, x, :3] = (255, 250, 232)
                                fr[y, x, 3] = 255 * fa * 0.9
                            else:
                                fr[y, x, :3] = fr[y, x, :3] + (white - fr[y, x, :3]) * fa
        out.append(Image.fromarray(np.clip(fr + 0.5, 0, 255).astype(np.uint8), 'RGBA'))
    return out


def stamp_frames_fine():
    return sweep(stamp_fine(), 0.0, 20.0, 8)


def stamp_frames_hallmarked():
    return sweep(stamp_hallmarked(), 0.0, 20.0, 9, star=(9, 23))


# ---------------------------------------------------------------- timing

def mcmeta(times):
    """The .mcmeta of a strip of frames, [times] their ticks one by one: a loop's frames are all alike, a rest is one long frame."""
    return {'animation': {'frames': [{'index': i, 'time': t} for i, t in enumerate(times)]}}


def loop_times(frames, tick, rest=0):
    """[frames] frames of [tick] ticks; if [rest], the first rests that long (the plain sprite between two passes of a glint)."""
    t = [tick] * frames
    if rest:
        t[0] = rest
    return t


def strip(frames):
    """The frames one above the other in a single image: how an animated texture is stored."""
    out = Image.new('RGBA', (N, N * len(frames)), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        out.paste(f, (0, N * i))
    return out
