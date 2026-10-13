"""Guildmark's title screen: a painted dusk over the mountains, the capital on its hill, a Wardfire in front.

    python3 art/scripts/title_art.py src/main/resources/assets/guildmark [preview-dir] [--only name,name]

Writes the backdrop's layers, the small effect sprites and the logo to textures/gui/title/ (each PNG with its
.png.mcmeta), and, given a preview dir, title_preview.png (the composite as the game shows it, with button
placeholders), title_preview_clean.png and title_logo_preview.png. --only rebuilds just the named textures and
composes the previews from what's already on disk. All randomness is seeded, so a rerun gives the same files.

Every layer is laid out on a 1920x1080 design canvas (1080p screen pixels); see LAYERS for where each one is
drawn. The sun sits low in the pass at SUN, the Wardfire's ash bed is at FIRE, and the capital crowns the hill on
the right; the game slides the layers for parallax and draws the fire, embers, smoke and stars on top.
"""
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

# ---------------------------------------------------------------- the design canvas
W_DESIGN, H_DESIGN = 1920, 1080
SUN = (960.0, 700.0)
SUN_R = 34.0
FIRE = (430.0, 975.0)

# name: (texture size, drawn rect x, y, w, h in design px, tiles left-right)
LAYERS = {
    "sky": ((1056, 580), (-96, -40, 2112, 1160), False),
    "clouds": ((1024, 200), (0, 40, 2048, 400), True),
    "mountains_far": ((2112, 600), (-96, 420, 2112, 600), False),
    "hills": ((2112, 480), (-96, 640, 2112, 480), False),
    "capital": ((2112, 760), (-96, 360, 2112, 760), False),
    "mist": ((1024, 128), (0, 820, 2048, 256), True),
    "foreground": ((2112, 680), (-96, 440, 2112, 680), False),
    "vignette": ((320, 180), (0, 0, 1920, 1080), False),
}

TEX_DIR = None


# ---------------------------------------------------------------- small helpers
def smoothstep(e0, e1, x):
    t = np.clip((np.asarray(x, dtype=np.float64) - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def rgb(c):
    """a colour as floats 0..1 from 0..255 ints"""
    return np.array(c[:3], dtype=np.float64) / 255.0


def gradient(stops, t):
    """piecewise-linear colour ramp; stops are (position, (r,g,b) 0..255), t any array"""
    t = np.asarray(t, dtype=np.float64)
    pos = np.array([s[0] for s in stops])
    out = np.empty(t.shape + (3,))
    for ch in range(3):
        out[..., ch] = np.interp(t, pos, [s[1][ch] / 255.0 for s in stops])
    return out


def screen(base, add):
    return 1 - (1 - base) * (1 - np.clip(add, 0, 1))


def texel_grid(tex, rect):
    """design-space coordinates of every texel centre of a texture drawn at rect"""
    (tw, th), (x0, y0, w, h) = tex, rect
    xs = x0 + (np.arange(tw) + 0.5) * w / tw
    ys = y0 + (np.arange(th) + 0.5) * h / th
    return np.meshgrid(xs, ys)


def smooth_noise(h, w, cy, cx, rng, wrap_x=False):
    """smooth value noise in about 0..1 with features cy x cx texels; wrap_x tiles left-right (w % cx == 0)"""
    if wrap_x:
        gw = int(round(w / cx))
        assert abs(gw * cx - w) < 1e-6, (w, cx)
    else:
        gw = int(math.ceil(w / cx)) + 4
    gh = int(math.ceil(h / cy)) + 4
    g = rng.random((gh, gw))
    Y, X = np.meshgrid(np.arange(h) / cy + 2, np.arange(w) / cx + (0 if wrap_x else 2), indexing="ij")
    return ndimage.map_coordinates(g, [Y, X], order=3, mode="grid-wrap" if wrap_x else "nearest")


def fbm(h, w, cy, cx, rng, octaves=4, gain=0.5, wrap_x=False):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        f = 2 ** o
        tot = tot + amp * smooth_noise(h, w, cy / f, cx / f, rng, wrap_x)
        norm += amp
        amp *= gain
    return tot / norm


def noise_at(Y, X, rng, period=256):
    """smooth noise sampled at arbitrary (fractional) coordinates, one unit per feature; repeats every period"""
    g = rng.random((period, period))
    return ndimage.map_coordinates(g, [np.asarray(Y) % period, np.asarray(X) % period], order=3, mode="grid-wrap")


def fbm_at(Y, X, rng, octaves=4, gain=0.5):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        f = 2 ** o
        tot = tot + amp * noise_at(Y * f, X * f, rng)
        norm += amp
        amp *= gain
    return tot / norm


def noise1d(n, cell, rng, wrap=False):
    if wrap:
        # a whole number of cells round the loop, so the last texel runs into the first
        g = rng.random(max(1, int(round(n / cell))))
        x = np.arange(n) * len(g) / n
        return ndimage.map_coordinates(g, [x], order=3, mode="grid-wrap")
    g = rng.random(int(math.ceil(n / cell)) + 4)
    return ndimage.map_coordinates(g, [np.arange(n) / cell + 2], order=3, mode="nearest")


def fbm1d(n, cell, rng, octaves=4, gain=0.5, wrap=False):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        tot = tot + amp * noise1d(n, cell / 2 ** o, rng, wrap)
        norm += amp
        amp *= gain
    return tot / norm


BAYER8 = (np.array([[0, 32, 8, 40, 2, 34, 10, 42], [48, 16, 56, 24, 50, 18, 58, 26],
                    [12, 44, 4, 36, 14, 46, 6, 38], [60, 28, 52, 20, 62, 30, 54, 22],
                    [3, 35, 11, 43, 1, 33, 9, 41], [51, 19, 59, 27, 49, 17, 57, 25],
                    [15, 47, 7, 39, 13, 45, 5, 37], [63, 31, 55, 23, 61, 29, 53, 21]]) + 0.5) / 64.0


def quantize(arr, dither=False):
    v = np.clip(arr, 0, 1) * 255.0
    if dither:
        h, w = v.shape[:2]
        b = np.tile(BAYER8, (h // 8 + 1, w // 8 + 1))[:h, :w]
        if v.ndim == 3:
            b = b[..., None]
        v = np.floor(v + b)
    else:
        v = np.round(v)
    return np.clip(v, 0, 255).astype(np.uint8)


def bleed(rgb_arr, alpha):
    """fills the colour of fully transparent texels from the nearest visible one, so linear filtering
    doesn't pull dark fringes into the edges"""
    empty = alpha <= 0.5 / 255
    if not empty.any() or empty.all():
        return rgb_arr
    _, (iy, ix) = ndimage.distance_transform_edt(empty, return_indices=True)
    return rgb_arr[iy, ix]


def to_image(rgb_arr, alpha=None, dither=False, dither_alpha=False):
    if alpha is None:
        return Image.fromarray(quantize(rgb_arr, dither), "RGB").convert("RGBA")
    rgb_arr = bleed(rgb_arr, alpha)
    out = np.dstack([quantize(rgb_arr, dither), quantize(alpha, dither_alpha)])
    return Image.fromarray(out, "RGBA")


def save(img, name, tile=False):
    path = os.path.join(TEX_DIR, name + ".png")
    img.save(path, optimize=True)
    meta = {"texture": {"blur": True}} if tile else {"texture": {"blur": True, "clamp": True}}
    with open(path + ".mcmeta", "w") as f:
        json.dump(meta, f, indent=2)
        f.write("\n")
    print("  %-18s %4dx%-4d %8d bytes" % (name, img.width, img.height, os.path.getsize(path)))


# ---------------------------------------------------------------- the sky
SKY_SUN = [(-40, (12, 13, 38)), (120, (17, 17, 48)), (260, (28, 23, 66)), (380, (52, 32, 90)),
           (470, (90, 44, 106)), (550, (140, 58, 110)), (615, (192, 80, 100)), (665, (232, 118, 84)),
           (700, (248, 158, 88)), (740, (252, 190, 110)), (1200, (252, 196, 120))]
SKY_SIDE = [(-40, (12, 13, 38)), (120, (16, 16, 46)), (260, (25, 21, 62)), (380, (42, 29, 80)),
            (470, (64, 37, 94)), (550, (96, 48, 104)), (615, (132, 62, 108)), (665, (166, 82, 108)),
            (700, (186, 98, 108)), (760, (198, 112, 110)), (1200, (200, 116, 112))]


def sun_glow(X, Y):
    """the warm light round the sun, in 0..1 screen-blend amounts per channel"""
    dx, dy = X - SUN[0], Y - SUN[1]
    up = np.where(dy < 0, dy, dy * 2.2)  # the glow hugs the horizon: it falls off fast below it
    wide = 0.50 * np.exp(-(dx / 560) ** 2 - (up / 250) ** 2)
    mid = 0.62 * np.exp(-(dx / 190) ** 2 - (up / 120) ** 2)
    r = np.hypot(dx, up * 1.15)
    core = 0.70 * np.exp(-(r / 70) ** 2) + 0.30 * np.exp(-(r / 130) ** 2)
    g = np.zeros(X.shape + (3,))
    g = screen(g, wide[..., None] * rgb((255, 128, 72)))
    g = screen(g, mid[..., None] * rgb((255, 176, 96)))
    g = screen(g, core[..., None] * rgb((255, 214, 150)))
    return g


def god_rays(X, Y, seed):
    """soft shafts fanning up from the sun: a few broad light wedges with darker gaps between"""
    rng = np.random.default_rng(seed)
    dx, dy = X - SUN[0], Y - SUN[1]
    ang = np.arctan2(dy, dx)  # -pi..0 is up
    rays = np.zeros_like(X)
    for _ in range(11):
        a = rng.uniform(-math.pi * 0.92, -math.pi * 0.08)
        width = rng.uniform(0.035, 0.09)
        rays += rng.uniform(0.5, 1.0) * np.exp(-((ang - a) / width) ** 2)
    rays = np.clip(rays, 0, 1.2) / 1.2
    r = np.hypot(dx, dy)
    fall = np.exp(-r / 360) * smoothstep(40, 160, r) * (dy < 40)
    return rays, fall


def sky_base(X, Y):
    """the dusk gradient without the sun's glow: warm under the sun, rose and violet away from it"""
    near = np.exp(-((X - SUN[0]) / 820) ** 2)
    col = gradient(SKY_SIDE, Y) * (1 - near[..., None]) + gradient(SKY_SUN, Y) * near[..., None]
    # a faint cool band high up so the zenith isn't one flat colour
    return col * (1 - 0.10 * np.exp(-((Y - 40) / 160) ** 2))[..., None]


def sky():
    tex, rect, _ = LAYERS["sky"]
    X, Y = texel_grid(tex, rect)
    col = sky_base(X, Y)

    rays, fall = god_rays(X, Y, 7)
    glow = sun_glow(X, Y)
    glow = glow * (0.86 + 0.30 * rays * fall + 0.14 * (1 - fall))[..., None]
    col = screen(col, glow)
    col = screen(col, (0.07 * rays * fall)[..., None] * rgb((255, 196, 128)))

    # the sun: a pale disc with a soft limb, half of it already behind the pass
    r = np.hypot(X - SUN[0], Y - SUN[1])
    disc = smoothstep(SUN_R + 3.0, SUN_R - 3.0, r)
    limb = np.clip(r / SUN_R, 0, 1) ** 3
    sun_c = rgb((255, 252, 242)) + (rgb((255, 238, 200)) - rgb((255, 252, 242))) * limb[..., None]
    col = col + (sun_c - col) * disc[..., None]

    # stars, faint, only high up (the game twinkles brighter ones on top)
    rng = np.random.default_rng(11)
    h, w = Y.shape
    stars = np.zeros((h, w))
    tint = np.zeros((h, w, 3))
    n = 0
    while n < 360:
        u, v = rng.uniform(0, w), rng.uniform(0, h)
        yd = rect[1] + v * rect[3] / h
        if rng.random() > (1 - yd / 420) ** 1.6 if yd < 420 else True:
            continue
        n += 1
        mag = rng.choice([rng.uniform(0.06, 0.18), rng.uniform(0.18, 0.36), rng.uniform(0.36, 0.6)], p=[0.62, 0.3, 0.08])
        sig = 0.45 + 0.25 * mag
        x0, y0 = int(u), int(v)
        for yy in range(max(0, y0 - 2), min(h, y0 + 3)):
            for xx in range(max(0, x0 - 2), min(w, x0 + 3)):
                k = mag * math.exp(-((xx + 0.5 - u) ** 2 + (yy + 0.5 - v) ** 2) / (2 * sig * sig))
                stars[yy, xx] += k
                tint[yy, xx] = (0.86, 0.9, 1.0) if rng.random() < 0.8 else (1.0, 0.9, 0.78)
    col = screen(col, stars[..., None] * np.where(tint.sum(-1, keepdims=True) > 0, tint, 1.0))
    save(to_image(col, dither=True), "sky")


# ---------------------------------------------------------------- the clouds
def clouds():
    """long thin dusk streaks that tile left-right: violet on top, lit warm from underneath by the sun below
    them; faint and dark high up (behind the logo), denser and warmer toward the horizon"""
    (tw, th), rect, _ = LAYERS["clouds"]
    rng = np.random.default_rng(23)
    prng = random.Random(23)
    _, Y = texel_grid((tw, th), rect)
    yd = Y[:, 0]
    X = np.arange(tw) + 0.5
    dens = np.zeros((th, tw))
    strands = fbm(th, tw, 1.4, 64, rng, 3, 0.6, wrap_x=True)
    # (design y, how many, length in texels, thickness in design px, strength)
    tiers = [(130, 3, (160, 320), 5.0, 0.40), (196, 3, (200, 380), 6.5, 0.48), (262, 4, (220, 440), 9.0, 0.55),
             (322, 5, (240, 520), 11.0, 0.62), (372, 5, (260, 560), 12.0, 0.70), (408, 4, (220, 480), 9.0, 0.70)]
    for (yc0, count, (l0, l1), thick0, strength) in tiers:
        for _ in range(count):
            x0 = prng.uniform(0, tw)
            length = prng.uniform(l0, l1)
            t = ((X - x0) % tw) / length
            on = t < 1
            tt = np.clip(t, 0, 1)
            taper = smoothstep(0.0, 0.18, tt) * smoothstep(1.0, 0.45, tt) * on
            wav = (noise1d(tw, 90, rng, wrap=True) - 0.5) * 18 + (noise1d(tw, 30, rng, wrap=True) - 0.5) * 5
            yc = yc0 + prng.uniform(-16, 16) + wav - tt * prng.uniform(-10, 10)
            thick = thick0 * prng.uniform(0.7, 1.3) * (0.55 + 0.9 * noise1d(tw, 64, rng, wrap=True)) * (0.4 + 0.6 * taper)
            dy = yd[:, None] - yc[None, :]
            prof = np.where(dy < 0, np.exp(-(dy / (thick * 1.5)) ** 2), np.exp(-(dy / (thick * 0.75)) ** 2))
            dens += strength * prng.uniform(0.6, 1.2) * prof * taper[None, :] * (0.45 + 0.9 * strands)
    env = smoothstep(56, 96, yd) * smoothstep(438, 412, yd)
    dens = np.clip(dens * env[:, None], 0, 2.0)
    # light reaching each texel from below, through the cloud under it
    cs = np.cumsum(dens[::-1], axis=0)[::-1]
    od = np.vstack([cs[1:], np.zeros((1, tw))])
    lit = np.exp(-od * 1.7)
    shade_c = gradient([(40, (26, 23, 58)), (200, (40, 30, 76)), (320, (76, 46, 98)), (440, (118, 62, 104))], yd)[:, None]
    lit_c = gradient([(40, (96, 64, 118)), (200, (156, 86, 124)), (300, (226, 118, 104)), (370, (248, 148, 96)),
                      (440, (255, 188, 120))], yd)[:, None]
    col = shade_c + (lit_c - shade_c) * lit[..., None]
    amax = gradient([(40, (95, 0, 0)), (230, (135, 0, 0)), (330, (205, 0, 0)), (440, (235, 0, 0))], yd)[:, 0]
    alpha = (1 - np.exp(-dens * 2.4)) * amax[:, None]
    save(to_image(col, alpha), "clouds", tile=True)


# ---------------------------------------------------------------- the far mountains
def render_terrain(H, xs, yb, sc, ZD, SS, rows):
    """voxel-space projection of a height field H[depth, column] (front row first): returns, for every
    supersampled pixel, the index of the depth row it shows (len(H) where it shows sky)"""
    ND, cols = H.shape
    Yp = (yb[:, None] - H * sc[:, None]) * SS
    M = np.minimum.accumulate(Yp, axis=0)
    rowc = np.arange(rows) + 0.5
    idx = np.empty((rows, cols), np.int32)
    for c in range(cols):
        idx[:, c] = np.searchsorted(-M[:, c], -rowc, side="left")
    return idx


def ridged_mf(Y, X, rng, octaves=3, gain=2.0, offset=1.0):
    """Musgrave's ridged multifractal: sharp branching crests, rougher where the last octave was high"""
    sig = (offset - np.abs(2 * noise_at(Y, X, rng) - 1)) ** 2
    out, norm, w = sig, 1.0, sig
    for o in range(1, octaves):
        f = 2.0 ** o
        w = np.clip(sig * gain, 0, 1)
        sig = (offset - np.abs(2 * noise_at(Y * f, X * f, rng) - 1)) ** 2 * w
        out = out + sig / f
        norm += 1 / f
    return out / norm


def massif_heights(peaks, xs, d, yb, sc, ZD, y0, rng, T=14.0, warp=90.0):
    """a range of pyramid peaks (3-4 faces each), smooth-unioned; the faces are bent by a domain warp so
    ridges and aretes wander like real ones. Also returns, for every point, the downhill direction of the
    face it lies on, so couloirs can run straight down each face."""
    ND, cols = len(d), len(xs)
    Xg = np.broadcast_to(xs[None, :], (ND, cols))
    Zg = np.broadcast_to((d * ZD)[:, None], (ND, cols))
    wx = (fbm_at(Zg / 260.0, Xg / 260.0, rng, 3) - 0.5) * 2 * warp
    wz = (fbm_at(Zg / 260.0 + 37, Xg / 260.0 + 91, rng, 3) - 0.5) * 2 * warp
    Xw, Zw = Xg + wx, Zg + wz * 0.6
    tops = []
    for (px, pd, ptop, nf, steep) in peaks:
        k = int(round(pd * (ND - 1)))
        tops.append((yb[k] - (ptop - y0)) / sc[k])
    hmax = max(tops)
    acc = np.zeros((ND, cols))
    best = np.full((ND, cols), -1e9)
    fall_x = np.zeros((ND, cols))
    fall_z = np.zeros((ND, cols))
    for (px, pd, ptop, nf, steep), h in zip(peaks, tops):
        base = rng.uniform(0, math.tau)
        cut = np.full((ND, cols), -1e9)
        ux = np.zeros((ND, cols))
        uz = np.zeros((ND, cols))
        for j in range(nf):
            a = base + j * math.tau / nf + rng.uniform(-0.3, 0.3)
            s = steep * rng.uniform(0.8, 1.2)
            v = s * (math.cos(a) * (Xw - px) + math.sin(a) * (Zw - pd * ZD) * 0.9)
            win = v > cut
            cut = np.where(win, v, cut)
            ux = np.where(win, math.cos(a), ux)
            uz = np.where(win, math.sin(a), uz)
        P = h - cut
        acc += np.exp(np.clip(P - hmax, -3000, 50) / T)
        top = P > best
        best = np.where(top, P, best)
        fall_x = np.where(top, ux, fall_x)
        fall_z = np.where(top, uz, fall_z)
    Hs = T * np.log(acc + 1e-300) + hmax
    return np.maximum(Hs, 0), hmax, Xg, Zg, Xw, Zw, fall_x, fall_z


def mountains_far():
    (tw, th), (x0, y0, _, _), _ = LAYERS["mountains_far"]
    SS = 2
    cols, rows = tw * SS, th * SS
    xs = x0 + (np.arange(cols) + 0.5) / SS
    ND = 2000
    d = np.linspace(0.0, 1.0, ND)
    ZD = 1400.0
    yb = th - 290 * d ** 0.7          # flat ground, in layer px from its top
    sc = 1 / (1 + 1.3 * d)            # far things are smaller
    rng = np.random.default_rng(31)

    # peaks: design x, depth, design y of the summit as seen, faces, steepness
    peaks = [(-80, 0.62, 596, 3, 1.6), (30, 0.82, 530, 4, 1.8), (150, 0.70, 560, 3, 1.7), (205, 0.86, 520, 3, 1.9),
             (330, 0.80, 476, 4, 2.0), (420, 0.72, 540, 3, 1.8), (520, 0.78, 572, 3, 1.8), (610, 0.70, 616, 3, 1.6),
             (700, 0.84, 630, 4, 1.7), (790, 0.90, 672, 3, 1.5), (870, 0.97, 708, 3, 1.2), (1050, 0.97, 710, 3, 1.2),
             (1130, 0.92, 670, 3, 1.5), (1220, 0.86, 626, 4, 1.7), (1330, 0.80, 600, 3, 1.8), (1450, 0.86, 572, 3, 1.8),
             (1560, 0.76, 560, 3, 1.8), (1655, 0.84, 502, 4, 2.0), (1730, 0.74, 540, 3, 1.9), (1800, 0.80, 482, 4, 2.1),
             (1905, 0.70, 530, 3, 1.9), (2010, 0.62, 584, 3, 1.7),
             # low shoulders in front, mostly lost in the haze
             (250, 0.50, 640, 3, 1.3), (600, 0.55, 676, 3, 1.2), (1300, 0.55, 676, 3, 1.2), (1700, 0.50, 640, 3, 1.3)]
    Hs, hmax, Xg, Zg, Xw, Zw, fall_x, fall_z = massif_heights(peaks, xs, d, yb, sc, ZD, y0, rng)
    Hs = Hs * smoothstep(0.25, 0.5, d)[:, None]

    # couloirs run straight down each face (V-shaped, with sharp ribs between); ridged noise adds spurs and
    # broken crests, and a finer ridged layer roughens the rock; all of it more so higher up
    across = Xw * (-fall_z) + Zw * fall_x
    along = Xw * fall_x + Zw * fall_z
    coul = fbm_at(along / 140.0, across / 20.0, rng, 2, 0.45)
    groove = np.clip(1 - np.abs(2 * coul - 1) * 2.2, 0, 1)
    gz, gx = np.gradient(Hs, ZD / (ND - 1), 1.0 / SS)
    steep = np.clip(np.hypot(gx, gz) / 1.3, 0, 1)
    rid = ridged_mf(Zw / 210.0, Xw / 210.0, rng, 3)
    rough = ridged_mf(Zw / 46.0, Xw / 46.0, rng, 2)
    hn = np.clip(Hs / (hmax * 0.5), 0, 1)
    H = Hs - hn * steep * groove * 24 + hn * (rid - 0.45) * 42 + hn * (rough - 0.5) * 9
    H = ndimage.gaussian_filter(H, (0.8, 0.7))

    # carve the pass: the skyline round the sun dips to just above its middle, so it sits half set
    Yp = yb[:, None] - H * sc[:, None]
    ksky = np.argmin(Yp, axis=0)
    ysky = Yp[ksky, np.arange(cols)] + y0
    target = 704 - 0.0016 * (xs - SUN[0]) ** 2
    need = ysky < target
    f = np.where(need, (yb[ksky] - (target - y0)) / np.maximum(H[ksky, np.arange(cols)] * sc[ksky], 1e-3), 1.0)
    f = np.clip(f, 0, 1)
    f = np.where(np.abs(xs - SUN[0]) < 260, f, 1.0)
    f = ndimage.minimum_filter1d(f, 9)
    f = ndimage.gaussian_filter1d(f, 6)
    H = H * f[None, :]
    Yp = yb[:, None] - H * sc[:, None]
    sk = Yp.min(axis=0) + y0
    print("    skyline at", ", ".join("x%d: y%.0f" % (x, sk[np.argmin(np.abs(xs - x))]) for x in (330, 760, 900, 960, 1020, 1160, 1650, 1800)))

    idx = render_terrain(H, xs, yb, sc, ZD, SS, rows)
    sky_px = idx >= ND
    idxc = np.minimum(idx, ND - 1)
    cc = np.broadcast_to(np.arange(cols)[None, :], (rows, cols))

    gz, gx = np.gradient(H, ZD / (ND - 1), 1.0 / SS)
    nrm = np.stack([-gx, np.ones_like(H), -gz], -1)
    nrm /= np.linalg.norm(nrm, axis=-1, keepdims=True)
    lx = (np.clip((SUN[0] - xs) / 600, -1, 1) * 1.15)[None, :]
    L = np.stack(np.broadcast_arrays(lx, np.full_like(lx, 0.22), np.full_like(lx, 0.50)), -1)
    L = L / np.linalg.norm(L, axis=-1, keepdims=True)
    diff = np.clip(((nrm * L).sum(-1) + 0.18) / 1.18, 0, 1) ** 1.3
    amb = 0.45 + 0.55 * np.clip(nrm[..., 1], 0, 1)
    # snow lies where it's high and gentle enough, and fills the couloirs; its edge against the rock is crisp
    snow_n = fbm_at(Zw / 90.0, Xw / 90.0, rng, 3)
    snowline = hmax * 0.34
    high = smoothstep(snowline - 20, snowline + 40, H + (snow_n - 0.5) * 230)
    hold = nrm[..., 1] + groove * steep * 0.34 + (snow_n - 0.5) * 0.25
    snow = high * smoothstep(0.50, 0.56, hold)

    rock_alb = rgb((70, 62, 72))
    snow_alb = rgb((232, 232, 248))
    alb = rock_alb + (snow_alb - rock_alb) * snow[..., None]
    sky_light = rgb((76, 78, 146))
    sun_light = rgb((255, 170, 104))
    col = alb * (sky_light * amb[..., None] + sun_light * (diff[..., None] * 1.35))

    # atmosphere: farther and lower is hazier; the haze glows warm toward the sun
    near_sun = np.exp(-((xs - SUN[0]) / 480) ** 2)[None, :]
    haze_c = rgb((138, 96, 152)) + (rgb((232, 146, 112)) - rgb((138, 96, 152))) * near_sun[..., None]
    low = np.exp(-np.maximum(H, 0) / 130)
    fog = 1 - np.exp(-(0.30 + 0.30 * d[:, None] ** 1.5 + 1.4 * low))
    fog = np.clip(fog, 0, 0.97)
    col = col + (haze_c - col) * fog[..., None]

    img = col[idxc, cc]
    # rim light: the first rows under every crest that stands against something farther away
    above = np.vstack([np.full((1, cols), ND), idx[:-1]])
    jump = np.where(above >= ND, 1.0, np.clip(((above - idx) - 40) / 400.0, 0, 0.45))
    rim = np.zeros((rows, cols))
    for i in range(7):
        rim[i:] = np.maximum(rim[i:], jump[:rows - i] * math.exp(-i / 2.0))
    face_sun = np.clip(nrm[..., 0][idxc, cc] * np.sign(SUN[0] - xs)[None, :] * 2.5 + 0.35, 0, 1)
    ypx = y0 + (np.arange(rows)[:, None] + 0.5) / SS
    glow = np.exp(-((xs[None, :] - SUN[0]) / 560) ** 2 - ((ypx - SUN[1]) / 300) ** 2)
    rim_k = rim * face_sun * (0.30 + 1.0 * glow)
    img = screen(img, rim_k[..., None] * rgb((255, 190, 130)))
    # glare: the pass swims in the sun's light
    img = screen(img, (0.62 * np.exp(-((xs[None, :] - SUN[0]) / 240) ** 2 - ((ypx - SUN[1]) / 70) ** 2))[..., None] * rgb((255, 176, 112)))

    # aerial perspective: the whole range takes on some of the sky's own colour behind it
    Xs, Ys = np.meshgrid(xs, ypx[:, 0])
    behind = screen(sky_base(Xs, Ys), sun_glow(Xs, Ys))
    img = img + (behind * 0.92 + rgb((150, 120, 170)) * 0.08 - img) * (0.14 + 0.26 * smoothstep(470, 760, ypx))[..., None]

    alpha = (~sky_px).astype(np.float64)
    pre = (img * alpha[..., None]).reshape(th, SS, tw, SS, 3).mean(axis=(1, 3))
    a = alpha.reshape(th, SS, tw, SS).mean(axis=(1, 3))
    out = pre / np.maximum(a, 1e-6)[..., None]
    save(to_image(out, a), "mountains_far")


# ---------------------------------------------------------------- the hills
def ridge_line(xs, points, rng, wobble=14.0, cell=140.0, fine=4.0):
    """a ridge's top (design y per column) through control points, roughened with smooth noise"""
    px, py = zip(*points)
    base = np.interp(xs, px, py)
    n = len(xs)
    step = xs[1] - xs[0]
    return (base + (fbm1d(n, cell / step, rng, 3) - 0.5) * 2 * wobble
            + (fbm1d(n, cell / 5 / step, rng, 2) - 0.5) * 2 * fine)


def body_mask(top, h, y0, ss=1):
    """coverage of everything below a ridge line, antialiased vertically (rows of the layer at y0)"""
    ys = y0 + (np.arange(h * ss) + 0.5) / ss
    return np.clip(ys[:, None] - top[None, :] + 0.5, 0, 1)


def conifer(x, y, h, w, rng):
    """a spruce's silhouette: a narrow spire with a few drooping tiers"""
    tiers = max(2, int(h / 7))
    left, right = [], []
    for i in range(tiers + 1):
        t = i / tiers
        yy = y - h + t * h * 0.92
        ww = w * (0.18 + 0.82 * t) * rng.uniform(0.85, 1.15)
        if i == 0:
            continue
        left.append((x - ww, yy))
        left.append((x - ww * 0.45, yy - h * 0.06))
        right.append((x + ww, yy))
        right.append((x + ww * 0.45, yy - h * 0.06))
    trunk = [(x - w * 0.12, y - h * 0.08), (x - w * 0.12, y + 2), (x + w * 0.12, y + 2), (x + w * 0.12, y - h * 0.08)]
    return [(x, y - h)] + right + trunk[::-1][:2] + trunk[:2][::-1] + left[::-1]


def sun_rim(mask, xs, strength, reach=3):
    """warm edge light on the sides of a silhouette that face the sun (and its top edge)"""
    h, w = mask.shape
    toward = np.sign(SUN[0] - xs)[None, :]
    rim = np.zeros_like(mask)
    for k in range(1, reach + 1):
        right = np.zeros_like(mask)
        right[:, :-k] = mask[:, k:]
        left = np.zeros_like(mask)
        left[:, k:] = mask[:, :-k]
        up = np.zeros_like(mask)
        up[k:, :] = mask[:-k, :]
        side = np.where(toward > 0, right, left)
        edge = mask * np.clip(1 - np.minimum(side, up * 0.6 + side * 0.4), 0, 1)
        rim = np.maximum(rim, edge * math.exp(-(k - 1) / 1.3))
    return rim * strength


def hills():
    """three forested ridges between the far range and the valley, each sinking into the mist at its foot"""
    (tw, th), (x0, y0, _, _), _ = LAYERS["hills"]
    rng = np.random.default_rng(41)
    prng = random.Random(41)
    xs = x0 + np.arange(tw) + 0.5
    ys = y0 + np.arange(th) + 0.5
    near = np.exp(-((xs - SUN[0]) / 420) ** 2)[None, :]
    glow = np.exp(-((xs - SUN[0]) / 280) ** 2)[None, :]
    L = Layer(x0, y0, tw, th)
    SSF = 2

    # (control points, crest colour away from / near the sun, mist colour away / near, mist depth, tree size, wobble)
    ridges = [
        ([(-96, 704), (120, 692), (330, 718), (560, 758), (760, 794), (900, 808), (1020, 810), (1160, 792), (1320, 766),
          (1520, 744), (1720, 724), (1900, 710), (2016, 714)],
         (62, 50, 92), (112, 76, 100), (146, 110, 150), (226, 156, 132), 64, 11, 9),
        ([(-96, 772), (180, 778), (420, 806), (640, 838), (820, 862), (960, 878), (1100, 872), (1300, 848), (1560, 824),
          (1800, 806), (2016, 798)],
         (40, 33, 64), (80, 54, 80), (114, 88, 128), (196, 132, 120), 72, 15, 12),
        ([(-96, 846), (260, 868), (520, 902), (720, 930), (840, 944), (960, 950), (1080, 946), (1260, 926), (1500, 906),
          (1760, 896), (2016, 890)],
         (27, 22, 44), (56, 40, 62), (86, 68, 104), (150, 102, 106), 80, 20, 14),
    ]
    for ri, (pts, crest_far, crest_near, mist_far, mist_near, depth, size, wob) in enumerate(ridges):
        top = ridge_line(xs, pts, rng, wobble=wob, cell=170)
        crest = rgb(crest_far) + (rgb(crest_near) - rgb(crest_far)) * near[0][:, None]
        mist_c = rgb(mist_far) + (rgb(mist_near) - rgb(mist_far)) * near[0][:, None]
        # paint the ridge and its forest into a supersampled colour image: the body first, then rows of trees
        # from the crest down, each tree a touch lighter or darker than its neighbours
        img = Image.new("RGBA", (tw * SSF, th * SSF), (0, 0, 0, 0))
        dr = ImageDraw.Draw(img)
        poly = [((x - x0) * SSF, (y - y0) * SSF) for x, y in zip(xs[::2], top[::2])]
        poly += [((xs[-1] - x0 + 1) * SSF, th * SSF), ((xs[0] - x0 - 1) * SSF, th * SSF)]
        dr.polygon(poly, fill=(128, 128, 128, 255))
        rows = 7
        for r in range(rows):
            off = r * size * 0.42
            sz = size * (1 - 0.06 * r)
            x = xs[0] - prng.uniform(0, 4)
            while x < xs[-1]:
                i = int(np.clip(x - x0, 0, tw - 1))
                hh = sz * prng.uniform(0.6, 1.25)
                ww = hh * prng.uniform(0.24, 0.34)
                by = top[i] + off + prng.uniform(-1.0, 2.0) + (hh * 0.25 if r == 0 else hh * 0.6)
                v = int(np.clip(128 + prng.gauss(0, 14) - (4 if r == 0 else 0), 70, 190))
                pts_ = conifer(x, by, hh, ww, prng)
                dr.polygon([((px - x0) * SSF, (py - y0) * SSF) for px, py in pts_], fill=(v, v, v, 255))
                x += hh * prng.uniform(0.16, 0.34)
        big = np.asarray(img, dtype=np.float64) / 255.0
        a = big[..., 3].reshape(th, SSF, tw, SSF).mean(axis=(1, 3))
        val = (big[..., 0] * big[..., 3]).reshape(th, SSF, tw, SSF).mean(axis=(1, 3)) / np.maximum(a, 1e-6)
        shade_k = 0.72 + 0.56 * (val - 0.5) / 0.5 * 0.5  # per-tree value variation around 1.0
        below = np.clip((ys[:, None] - top[None, :]) / depth, 0, 1.6)
        t = smoothstep(0.25, 1.15, below) ** 1.1
        c = crest[None, :, :] * (shade_k * (1 - 0.35 * t))[..., None]
        c = c + (mist_c[None, :, :] - c) * t[..., None]
        # wisps of mist drifting along the ridge's foot
        wisp = fbm(th, tw, 4, 120, rng, 3, 0.5)
        wisp = smoothstep(0.5, 0.75, wisp) * smoothstep(0.35, 0.9, below) * smoothstep(1.6, 1.0, below)
        c = c + (mist_c[None, :, :] * 1.08 - c) * (wisp * 0.45)[..., None]
        rim = sun_rim(np.clip(a, 0, 1), xs, 1.0) * (0.15 + 1.0 * glow) * (1.0 - 0.25 * ri)
        c = screen(c, rim[..., None] * rgb((255, 178, 116)))
        L.over(x0, y0, c, a)

    # the valley floor, and a river bringing the sunset down toward us
    floor_top = ridge_line(xs, [(-96, 960), (600, 963), (960, 966), (1400, 960), (2016, 956)], rng, wobble=2, cell=200)
    fa = body_mask(floor_top, th, y0)
    fc = gradient([(950, (66, 52, 90)), (1000, (42, 34, 64)), (1120, (22, 19, 36))], ys)[:, None, :] * np.ones((1, tw, 1))
    fc = screen(fc, (0.28 * near * smoothstep(1060, 955, ys)[:, None])[..., None] * rgb((200, 124, 110)))
    L.over(x0, y0, fc, fa)
    river = river_mask(tw, th, x0, y0)
    X, Y = np.meshgrid(xs, ys)
    sky_refl = gradient([(950, (176, 104, 118)), (1010, (140, 80, 112)), (1100, (100, 62, 100))], Y)
    glint = np.exp(-((X - SUN[0]) / 46) ** 2) * smoothstep(1110, 950, Y)
    ripple = fbm(th, tw, 2.0, 30, rng, 2, 0.5)
    sky_refl = sky_refl + (rgb((255, 206, 140)) - sky_refl) * np.clip(glint * (0.55 + 0.7 * ripple), 0, 1)[..., None]
    edge = ndimage.gaussian_filter(river, 1.2)
    rc = sky_refl * (0.75 + 0.25 * smoothstep(0.3, 0.9, edge))[..., None]
    L.over(x0, y0, rc, river * 0.92)
    save(to_image(L.col, L.a), "hills")


RIVER = [(985, 952), (962, 960), (930, 970), (918, 982), (944, 996), (996, 1010), (1004, 1028), (950, 1048),
         (880, 1070), (836, 1100), (820, 1130)]


def river_mask(w, h, x0, y0, ss=4):
    """the river as a ribbon widening toward us"""
    img = Image.new("L", (w * ss, h * ss), 0)
    dr = ImageDraw.Draw(img)
    pts = np.array(RIVER, dtype=np.float64)
    # resample along a smooth curve
    t = np.linspace(0, len(pts) - 1, 400)
    sx = ndimage.map_coordinates(pts[:, 0], [t], order=3, mode="nearest")
    sy = ndimage.map_coordinates(pts[:, 1], [t], order=3, mode="nearest")
    for i in range(len(t) - 1):
        wdt = 1.0 + (sy[i] - 950) * 0.13
        nx, ny = sy[i + 1] - sy[i], -(sx[i + 1] - sx[i])
        ln = math.hypot(nx, ny) + 1e-9
        nx, ny = nx / ln * wdt, ny / ln * wdt
        quad = [(sx[i] + nx, sy[i] + ny), (sx[i + 1] + nx, sy[i + 1] + ny), (sx[i + 1] - nx, sy[i + 1] - ny), (sx[i] - nx, sy[i] - ny)]
        dr.polygon([((qx - x0) * ss, (qy - y0) * ss) for qx, qy in quad], fill=255)
    return np.asarray(img.resize((w, h), Image.BOX), dtype=np.float64) / 255.0


# ---------------------------------------------------------------- the capital
STONE = (46, 44, 68)         # faces turned to us, in the shade
STONE_DK = (32, 30, 50)      # far sides and recesses
STONE_LIT = (164, 102, 78)   # faces turned to the sun
STONE_HI = (236, 164, 108)   # the brightest edges
SLATE = (40, 41, 68)
SLATE_LIT = (118, 80, 84)
TILE = (68, 40, 50)
TILE_LIT = (146, 82, 66)
WINDOW = (255, 196, 112)
WINDOW_HOT = (255, 232, 170)
DARK_WINDOW = (30, 28, 46)
CRIMSON = (132, 28, 42)
CRIMSON_LIT = (196, 60, 54)
GILT = (226, 180, 90)

HILL = [(980, 990), (1040, 978), (1080, 966), (1120, 948), (1170, 926), (1230, 898), (1300, 866), (1370, 836),
        (1430, 810), (1480, 792), (1520, 786), (1560, 788), (1610, 800), (1660, 822), (1710, 850), (1770, 884),
        (1840, 914), (1920, 938), (2020, 952)]
ROAD = [(992, 1124), (1010, 1096), (1044, 1068), (1086, 1046), (1112, 1024), (1108, 1004), (1086, 990), (1084, 976),
        (1104, 963), (1132, 954), (1150, 951)]
LANTERNS = [(1028, 1074), (1100, 1032), (1094, 994), (1118, 958)]


def tone(c, k):
    return tuple(max(0.0, min(255.0, v * k)) for v in c[:3])


def lerp_c(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def hill_y(x):
    px, py = zip(*HILL)
    return float(np.interp(x, px, py))


class Painter:
    """draws flat polygons in design px onto a supersampled canvas. An emission mask follows along, so windows and
    lamps hidden by anything drawn later stay hidden; lit ones are remembered as light sources."""

    def __init__(self, x0, y0, x1, y1, ss, rng):
        self.x0, self.y0, self.ss = x0, y0, ss
        self.w, self.h = x1 - x0, y1 - y0
        self.img = Image.new("RGBA", (self.w * ss, self.h * ss), (0, 0, 0, 0))
        self.dr = ImageDraw.Draw(self.img)
        self.em = Image.new("L", (self.w * ss, self.h * ss), 0)
        self.de = ImageDraw.Draw(self.em)
        self.lights = []
        self.chimneys = []
        self.rng = rng

    def _p(self, pts):
        return [((x - self.x0) * self.ss, (y - self.y0) * self.ss) for x, y in pts]

    def poly(self, pts, col):
        p = self._p(pts)
        self.dr.polygon(p, fill=tuple(int(round(c)) for c in col[:3]) + (255,))
        self.de.polygon(p, fill=0)

    def rect(self, x0, y0, x1, y1, col):
        self.poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], col)

    def line(self, pts, col, width):
        p = self._p(pts)
        self.dr.line(p, fill=tuple(int(round(c)) for c in col[:3]) + (255,), width=max(1, int(round(width * self.ss))))
        self.de.line(p, fill=0, width=max(1, int(round(width * self.ss))))

    def lamp(self, pts, col, strength=1.0, size=None):
        """an emissive shape (a lit window, a lantern): painted, marked in the emission mask, remembered"""
        p = self._p(pts)
        self.dr.polygon(p, fill=tuple(int(round(c)) for c in col[:3]) + (255,))
        self.de.polygon(p, fill=255)
        xs_ = [q[0] for q in pts]
        ys_ = [q[1] for q in pts]
        cx, cy = (min(xs_) + max(xs_)) / 2, (min(ys_) + max(ys_)) / 2
        self.lights.append((cx, cy, size or max(max(xs_) - min(xs_), max(ys_) - min(ys_)), strength))

    def window(self, x0, y0, x1, y1, lit, strength=1.0, arch=False):
        pts = [(x0, y1), (x0, y0 + (x1 - x0) * 0.5 if arch else y0), ((x0 + x1) / 2, y0) if arch else (x0, y0),
               (x1, y0 + (x1 - x0) * 0.5 if arch else y0), (x1, y1)]
        if lit:
            self.lamp(pts, lerp_c(WINDOW, WINDOW_HOT, self.rng.uniform(0, 0.5)) if strength > 0.7 else tone(WINDOW, 0.8), strength)
        else:
            self.poly(pts, DARK_WINDOW)

    # ---- buildings
    def house(self, x, base, w, wall_h, roof_h, gable, roof, lit_p=0.45, side=3.2):
        rng = self.rng
        k = rng.uniform(0.92, 1.07)
        front = tone(STONE, k)
        lit = tone(STONE_LIT, k * rng.uniform(0.8, 1.05))
        rsh, rlit = (SLATE, SLATE_LIT) if roof == "slate" else (TILE, TILE_LIT)
        rsh, rlit = tone(rsh, rng.uniform(0.9, 1.08)), tone(rlit, rng.uniform(0.55, 1.0))
        top = base - wall_h
        rise = side * 0.4
        self.poly([(x, base), (x, top), (x - side, top - rise), (x - side, base - rise)], lit)
        self.rect(x, top, x + w, base, front)
        if gable:
            apex = (x + w / 2, top - roof_h)
            self.poly([(x - side - 0.6, top - rise + 0.5), (x - 0.6, top + 0.5), apex, (apex[0] - side, apex[1] - rise)], rlit)
            self.poly([(x - 0.8, top + 0.5), apex, (x + w + 0.8, top + 0.5)], tone(front, 0.93))
            self.line([(x - 0.8, top + 0.6), apex, (x + w + 0.8, top + 0.6)], tone(rsh, 0.75), 0.7)
            ridge_y, rx = apex[1], apex[0]
            if w > 9 and rng.random() < 0.5:
                self.window(apex[0] - 0.9, top - roof_h * 0.55, apex[0] + 0.9, top - roof_h * 0.55 + 2.2, rng.random() < lit_p * 0.8, 0.7)
        else:
            ridge_l = (x - side * 0.5, top - roof_h - rise * 0.5)
            ridge_r = (x + w - side * 0.5, top - roof_h - rise * 0.5)
            self.poly([(x - side, top - rise), (x, top), ridge_l], lit)
            self.poly([(x - 0.9, top + 0.6), (x + w + 0.9, top + 0.6), ridge_r, ridge_l], rsh)
            self.poly([(x - side - 0.7, top - rise + 0.5), (x - 0.9, top + 0.6), ridge_l], rlit)
            self.line([ridge_l, ridge_r], tone(rlit, 0.8), 0.6)
            ridge_y, rx = ridge_l[1], x + w * rng.uniform(0.25, 0.75)
        # windows on the front
        n = max(1, int(w // 6.5))
        for i in range(n):
            wx = x + (i + 0.5) * w / n
            wy = top + wall_h * rng.uniform(0.28, 0.45)
            self.window(wx - 1.0, wy, wx + 1.0, wy + 2.8, rng.random() < lit_p)
        if wall_h > 13 and w > 12:
            for i in range(n):
                wx = x + (i + 0.5) * w / n
                wy = top + wall_h * 0.68
                if wy + 3 < base - 1:
                    self.window(wx - 1.0, wy, wx + 1.0, wy + 2.8, rng.random() < lit_p * 0.7)
        if rng.random() < 0.45:
            cx = rx + rng.uniform(-w * 0.2, w * 0.2) if gable else rx
            cy = ridge_y + (roof_h * 0.35 if gable else roof_h * 0.15)
            self.rect(cx - 1.6, cy - 5.5, cx + 1.6, cy + 1, tone(STONE, 0.9))
            self.rect(cx - 1.6, cy - 5.5, cx - 0.7, cy + 1, STONE_LIT)
            self.rect(cx - 2.0, cy - 6.4, cx + 2.0, cy - 5.2, STONE_DK)
            self.chimneys.append((cx, cy - 6.4))

    def round_tower(self, cx, base, r, top, roof="cone", roof_h=0.0, slits=2, banner=None, cren=True, roof_col="slate"):
        n = 14
        for i in range(n):
            u0, u1 = i / n, (i + 1) / n
            um = (u0 + u1) / 2
            litk = float(smoothstep(0.45, 0.02, um))
            dk = float(smoothstep(0.55, 1.0, um))
            c = lerp_c(lerp_c(STONE, STONE_LIT, litk * 0.95), STONE_DK, dk * 0.7)
            if um < 0.07:
                c = lerp_c(c, STONE_HI, 0.6)
            self.rect(cx - r + u0 * 2 * r - 0.05, top, cx - r + u1 * 2 * r + 0.05, base, c)
        # arrow slits
        for j in range(slits):
            sy = top + (base - top) * (0.25 + 0.5 * j / max(1, slits - 1)) if slits > 1 else top + (base - top) * 0.4
            sx = cx + r * 0.15
            lit = self.rng.random() < 0.35
            self.window(sx - 0.7, sy, sx + 0.7, sy + 4.0, lit, 0.8)
        if cren:
            # a corbelled parapet, a touch wider, with merlons
            pr = r + 1.6
            self.rect(cx - pr, top - 5, cx + pr, top + 1, STONE)
            self.rect(cx - pr, top - 5, cx - pr + 1.4, top + 1, STONE_LIT)
            self.rect(cx - pr, top + 1, cx + pr, top + 2.2, STONE_DK)
            if roof == "flat":
                m = max(3, int(pr * 2 / 4.2))
                for j in range(m):
                    if j % 2 == 0:
                        mx0 = cx - pr + j * (2 * pr / m)
                        self.rect(mx0, top - 9, mx0 + 2 * pr / m, top - 4.5, STONE if j else STONE_LIT)
                        self.rect(mx0, top - 9, mx0 + 0.9, top - 4.5, STONE_LIT)
            top = top - 5
        if roof == "cone":
            rs, rl = (SLATE, SLATE_LIT) if roof_col == "slate" else (TILE, TILE_LIT)
            pr = r + 2.4
            apex = (cx - r * 0.12, top - roof_h)
            self.poly([(cx - pr, top + 0.5), apex, (cx - r * 0.35, top + 0.5)], rl)
            self.poly([(cx - r * 0.35, top + 0.5), apex, (cx + pr, top + 0.5)], rs)
            self.line([(cx - pr, top + 0.6), (cx + pr, top + 0.6)], STONE_DK, 0.8)
            self.line([apex, (apex[0], apex[1] - 5)], GILT, 0.8)
            top = apex[1] - 5
        if banner:
            self.pennant(cx - r * 0.12 if roof == "cone" else cx, top, banner)

    def square_tower(self, x0, x1, base, top, side=5.0, roof="spire", roof_h=0.0, windows=(), banner=None):
        rise = side * 0.4
        self.poly([(x0, base), (x0, top), (x0 - side, top - rise), (x0 - side, base - rise)], STONE_LIT)
        self.poly([(x0 - side, top - rise), (x0 - side + 1.0, top - rise), (x0 - side + 1.0, base - rise), (x0 - side, base - rise)], STONE_HI)
        self.rect(x0, top, x1, base, STONE)
        self.rect(x1 - (x1 - x0) * 0.12, top, x1, base, tone(STONE, 0.86))
        for (wx, wy, ww, wh, lit) in windows:
            self.window(wx - ww / 2, wy, wx + ww / 2, wy + wh, lit, 1.0, arch=True)
        if roof == "spire":
            apex = ((x0 + x1) / 2 - side * 0.5, top - roof_h)
            self.poly([(x0 - side - 0.8, top - rise + 0.4), (x0 - 0.5, top + 0.8), apex], SLATE_LIT)
            self.poly([(x0 - 0.5, top + 0.8), apex, (x1 + 0.9, top + 0.8)], SLATE)
            self.poly([((x0 + x1) / 2 - 0.5, top + 0.8), apex, (x1 + 0.9, top + 0.8)], tone(SLATE, 0.85))
            self.line([apex, (apex[0], apex[1] - 7)], GILT, 0.9)
            self.rect(apex[0] - 1.0, apex[1] - 4.2, apex[0] + 1.0, apex[1] - 2.8, GILT)
            if banner:
                self.pennant(apex[0], apex[1] - 7, banner)
        elif roof == "flat":
            self.rect(x0 - 1.2, top - 4, x1 + 1.2, top + 1, STONE)
            self.poly([(x0 - side - 1.2, top - rise - 4), (x0 - 1.2, top - 4), (x0 - 1.2, top + 1), (x0 - side - 1.2, top - rise + 1)], STONE_LIT)
            w = x1 - x0 + 2.4
            m = max(3, int(w / 4.0)) | 1
            for j in range(0, m, 2):
                mx0 = x0 - 1.2 + j * w / m
                self.rect(mx0, top - 8.5, mx0 + w / m, top - 3.5, STONE)
                self.rect(mx0, top - 8.5, mx0 + 0.9, top - 3.5, STONE_LIT)
            self.poly([(x0 - side - 1.2, top - rise - 8.5), (x0 - side + 1.0, top - rise - 8.5), (x0 - side + 1.0, top - rise - 3.5), (x0 - side - 1.2, top - rise - 3.5)], STONE_LIT)
            if banner:
                self.pennant((x0 + x1) / 2, top - 8.5, banner)

    def pennant(self, x, y, size):
        """a pole and a long crimson swallowtail streaming to the right"""
        L, Hh = size * 1.0, size * 0.32
        top = y - size * 0.9
        self.line([(x, y + 0.5), (x, top)], (40, 34, 44), 0.9)
        wave = lambda t: math.sin(t * 5.2 + 0.6) * Hh * 0.35
        pts_top = [(x + t * L, top + 0.6 + wave(t)) for t in np.linspace(0, 1, 9)]
        pts_bot = [(x + t * L * 0.97, top + 0.6 + Hh * (1 - 0.35 * t) + wave(t) + 0.4 * t) for t in np.linspace(1, 0, 9)]
        tail = (x + L * 0.78, top + 0.6 + Hh * 0.55 + wave(0.78))
        self.poly(pts_top + [tail] + pts_bot, CRIMSON)
        self.poly(pts_top[:6] + [(p[0], p[1] + Hh * 0.28) for p in pts_top[:6][::-1]], CRIMSON_LIT)
        self.rect(x - 1.0, top - 1.6, x + 1.0, top + 0.2, GILT)

    def hanging_banner(self, x, y, w, h):
        self.rect(x - w / 2 - 1.2, y - 1.2, x + w / 2 + 1.2, y + 0.4, GILT)
        self.poly([(x - w / 2, y), (x + w / 2, y), (x + w / 2, y + h), (x, y + h - w * 0.55), (x - w / 2, y + h)], CRIMSON)
        self.poly([(x - w / 2, y), (x - w / 2 + 1.6, y), (x - w / 2 + 1.6, y + h - 1.5), (x - w / 2, y + h)], CRIMSON_LIT)
        cy = y + h * 0.4
        r = w * 0.32
        self.poly([(x, cy - r * 1.3), (x + r, cy), (x, cy + r * 1.3), (x - r, cy)], GILT)
        self.poly([(x, cy - r * 0.55), (x + r * 0.45, cy), (x, cy + r * 0.55), (x - r * 0.45, cy)], CRIMSON)

    def wall(self, pts, h, merlon=4.6, gap=3.0, lit_end=True):
        """a curtain wall along a base line (left to right): shaded face, merlons with lit left edges"""
        pts = list(pts)
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            self.poly([(ax, ay), (bx, by), (bx, by - h), (ax, ay - h)], STONE)
            self.poly([(ax, ay - h), (bx, by - h), (bx, by - h + 1.6), (ax, ay - h + 1.6)], tone(STONE, 1.12))
            self.poly([(ax, ay), (bx, by), (bx, by - 3.0), (ax, ay - 3.0)], tone(STONE, 0.86))
        xs_ = np.array([p[0] for p in pts])
        ys_ = np.array([p[1] for p in pts])
        x = pts[0][0]
        while x < pts[-1][0] - 1:
            x1 = min(x + merlon, pts[-1][0])
            yb = float(np.interp((x + x1) / 2, xs_, ys_)) - h
            self.rect(x, yb - 4.4, x1, yb + 0.3, STONE)
            self.rect(x, yb - 4.4, x + 0.9, yb + 0.3, STONE_LIT)
            self.rect(x, yb - 4.4, x1, yb - 3.6, tone(STONE, 1.25))
            x += merlon + gap
        if lit_end:
            ax, ay = pts[0]
            self.poly([(ax, ay), (ax, ay - h - 4.4), (ax - 3.5, ay - h - 5.8), (ax - 3.5, ay - 1.4)], STONE_LIT)

    def conifer(self, x, y, h, col, lit):
        w = h * 0.3
        tiers = max(2, int(h / 6))
        pts_l, pts_r = [], []
        for i in range(1, tiers + 1):
            t = i / tiers
            yy = y - h + t * h * 0.95
            ww = w * (0.2 + 0.8 * t)
            pts_l += [(x - ww * 0.45, yy - h * 0.07), (x - ww, yy)]
            pts_r += [(x + ww * 0.45, yy - h * 0.07), (x + ww, yy)]
        pts = [(x, y - h)] + pts_r + [(x + w * 0.1, y), (x - w * 0.1, y)] + pts_l[::-1]
        self.poly(pts, col)
        self.poly([(x, y - h)] + [(x - 0.2, p[1]) for p in pts_l[:1]] + pts_l[1:2] + [(x - w * 0.3, y - h * 0.4)], lit)


def capital_scene():
    """paints the hill, the plain and road, and the city; returns the painter (image, emission, lights, chimneys)"""
    rng = random.Random(77)
    P = Painter(900, 440, 2016, 1120, 4, rng)

    # the plain in front of the hill and the hill itself
    plain_top = [(900, 984), (1000, 982), (1200, 978), (1500, 972), (1800, 966), (2016, 962)]
    P.poly(plain_top + [(2016, 1120), (900, 1120)], (34, 29, 50))
    P.poly(HILL + [(2020, 1120), (980, 1120)], (38, 35, 58))
    # the hill's sunward flank catches the light
    lit_edge = [(x - 7, y + 2) for x, y in HILL[:10]]
    P.poly(HILL[:10] + lit_edge[::-1], (116, 80, 76))
    P.poly([(x - 3, y + 1) for x, y in HILL[:10]] + [(x - 5, y + 6) for x, y in HILL[:10]][::-1], (70, 56, 74))

    # the road up to the gate: one smooth ribbon, widening toward us, with a darker verge
    road = np.array(ROAD)
    t = np.linspace(0, len(road) - 1, 240)
    rx = ndimage.map_coordinates(road[:, 0], [t], order=3, mode="nearest")
    ry = ndimage.map_coordinates(road[:, 1], [t], order=3, mode="nearest")
    tx, ty = np.gradient(rx), np.gradient(ry)
    tl = np.hypot(tx, ty) + 1e-9
    nx, ny = ty / tl, -tx / tl
    wdt = 2.2 + np.maximum(0.0, ry - 950) * 0.075
    for extra, c in ((1.4, (28, 24, 40)), (0.0, None)):
        left = list(zip(rx + nx * (wdt + extra), ry + ny * (wdt + extra)))
        right = list(zip(rx - nx * (wdt + extra), ry - ny * (wdt + extra)))
        if c is not None:
            P.poly(left + right[::-1], c)
        else:
            n_ = len(rx)
            for i in range(0, n_ - 1, 6):
                j = min(n_ - 1, i + 7)
                k = min(1.0, (ry[i] - 950) / 160)
                P.poly(left[i:j + 1] + right[i:j + 1][::-1], lerp_c((104, 82, 92), (74, 60, 76), k))

    # a hamlet at the foot of the hill, by the road
    for (hx, hb, hw, hh, rh, g) in [(1046, 982, 16, 10, 9, True), (1066, 986, 13, 9, 8, False), (1128, 968, 12, 9, 8, True)]:
        P.house(hx, hb, hw, hh, rh, g, "tile", lit_p=0.8)
    for (tx, ty, th_) in [(1012, 984, 22), (1024, 990, 16), (1180, 972, 18), (1196, 976, 14), (1720, 868, 20), (1748, 884, 24),
                          (1780, 896, 18), (1812, 912, 22), (1850, 924, 16), (1888, 934, 20), (1930, 944, 14), (1700, 880, 14)]:
        P.conifer(tx, ty, th_, (36, 34, 54), (110, 76, 76))

    # ---- the citadel on the crest: the great hall, the chapel and the keep, back to front
    # the great hall to the right of the keep
    P.house(1596, 812, 64, 30, 26, False, "slate", lit_p=0.0, side=6)
    for i in range(5):
        wx = 1602 + i * 12
        P.window(wx, 790, wx + 3.2, 802, True, 1.0, arch=True)
    P.round_tower(1668, 818, 8, 760, "cone", 26, slits=1, banner=None)
    # the chapel and its bell tower, left of the keep
    P.house(1338, 846, 40, 26, 30, True, "slate", lit_p=0.0, side=5)
    P.lamp([(1355, 806), (1361, 806), (1361, 812), (1355, 812)], WINDOW, 0.8)
    P.square_tower(1318, 1334, 852, 726, side=5, roof="spire", roof_h=40,
                   windows=[(1326, 740, 3.2, 9, True), (1326, 772, 2.6, 6, False)], banner=None)
    # back towers of the keep
    P.round_tower(1452, 806, 13, 610, "cone", 46, slits=3, banner=13)
    P.round_tower(1594, 808, 12, 626, "cone", 42, slits=3, banner=12)
    # the donjon
    P.square_tower(1470, 1576, 806, 650, side=13, roof="flat", windows=[
        (1486, 676, 4, 12, True), (1508, 676, 4, 12, False), (1540, 676, 4, 12, True), (1562, 676, 4, 12, True),
        (1486, 726, 4, 11, False), (1562, 726, 4, 11, True), (1500, 760, 3, 8, True), (1548, 760, 3, 8, False)])
    # the high tower and its spire
    P.square_tower(1504, 1538, 660, 560, side=7, roof="spire", roof_h=82,
                   windows=[(1521, 576, 4, 13, True), (1521, 612, 3.4, 9, True)], banner=16)
    # corner turrets on the donjon
    P.round_tower(1468, 660, 6, 626, "cone", 22, slits=0, cren=False)
    P.round_tower(1578, 660, 6, 628, "cone", 22, slits=0, cren=False)
    P.hanging_banner(1497, 690, 9, 40)
    P.hanging_banner(1551, 690, 9, 40)

    # ---- the citadel wall
    P.wall([(1392, 820), (1450, 816), (1520, 814), (1600, 816), (1648, 820)], 22)
    P.round_tower(1392, 826, 11, 784, "cone", 30, slits=1, banner=10)
    P.round_tower(1648, 826, 10, 786, "flat", slits=1)
    # the citadel gate, glowing
    P.rect(1508, 818, 1532, 790, STONE)
    P.lamp([(1514, 818), (1514, 806), (1520, 800), (1526, 806), (1526, 818)], (255, 176, 92), 0.9, size=10)

    # ---- the middle district
    def row(base_fn, x0, x1, wall=(10, 16), roofh=(8, 13), width=(12, 22), lit_p=0.45, tile_p=0.45):
        x = x0
        out = []
        while x < x1:
            w = rng.uniform(*width)
            b = base_fn(x + w / 2)
            if b > hill_y(x + w / 2) + 4 or b > hill_y(x) + 4:
                out.append((x, b + rng.uniform(-1.5, 1.5), w, rng.uniform(*wall), rng.uniform(*roofh),
                            rng.random() < 0.55, "tile" if rng.random() < tile_p else "slate"))
            x += w + rng.uniform(-1.0, 2.5)
        for (hx, hb, hw, hh, rh, g, rf) in out:
            P.house(hx, hb, hw, hh, rh, g, rf, lit_p=lit_p)

    row(lambda x: 834, 1290, 1468, (12, 18), lit_p=0.4)
    row(lambda x: 834, 1612, 1676, (12, 18), lit_p=0.4)
    row(lambda x: 850, 1250, 1690, (12, 17), lit_p=0.45)
    P.square_tower(1400, 1418, 862, 790, side=4, roof="spire", roof_h=30, windows=[(1409, 800, 2.6, 7, True)])
    row(lambda x: 866, 1238, 1694, (10, 15), lit_p=0.5)

    # ---- the inner wall
    P.wall([(1228, 890), (1320, 884), (1420, 880), (1520, 880), (1620, 876), (1690, 870)], 24)
    for tx, tb in [(1228, 896), (1340, 888), (1452, 886), (1560, 886), (1690, 876)]:
        P.round_tower(tx, tb, 10, tb - 46, "cone" if tx in (1228, 1452, 1690) else "flat", 22, slits=1,
                      banner=9 if tx == 1452 else None, roof_col="tile" if tx == 1340 else "slate")
    # the stepped wall climbing the hill's sunward flank to the inner ring
    P.wall([(1104, 958), (1166, 924), (1228, 892)], 22)

    # ---- the lower district
    row(lambda x: 902, 1180, 1700, (12, 18), lit_p=0.5)
    P.square_tower(1236, 1256, 920, 846, side=4, roof="spire", roof_h=26, windows=[(1246, 858, 2.6, 7, True)])
    row(lambda x: 920, 1128, 1704, (10, 16), lit_p=0.5)
    row(lambda x: 940, 1108, 1708, (9, 13), (7, 11), lit_p=0.5)

    # ---- the outer wall, its towers and the gatehouse
    P.wall([(1096, 962), (1180, 956), (1290, 957), (1400, 958), (1500, 955), (1600, 947), (1668, 934), (1712, 916)], 28)
    for tx, tb in [(1096, 966), (1290, 962), (1400, 963), (1500, 960), (1600, 952), (1712, 920)]:
        P.round_tower(tx, tb, 12, tb - 52, "flat" if tx in (1290, 1500) else "cone", 24, slits=2,
                      roof_col="slate", banner=10 if tx == 1712 else None)
    # the gatehouse: two drum towers, the gate passage glowing between them
    P.rect(1138, 954, 1164, 900, STONE)
    P.rect(1138, 900, 1164, 904, tone(STONE, 1.15))
    for j in range(3):
        P.rect(1140 + j * 9, 893, 1145 + j * 9, 900, STONE)
        P.rect(1140 + j * 9, 893, 1141 + j * 9, 900, STONE_LIT)
    P.lamp([(1144, 954), (1144, 935), (1151, 928), (1158, 935), (1158, 954)], (255, 178, 96), 1.0, size=14)
    for j in range(4):
        P.line([(1145.5 + j * 3.6, 929.5 + (2.5 if j in (0, 3) else 0)), (1145.5 + j * 3.6, 942)], (60, 40, 40), 0.8)
    P.line([(1144.5, 936), (1157.5, 936)], (60, 40, 40), 0.7)
    P.round_tower(1132, 958, 13, 892, "cone", 30, slits=2, banner=11)
    P.round_tower(1170, 958, 13, 894, "cone", 30, slits=2)
    P.hanging_banner(1151, 906, 7, 18)

    # lanterns along the road
    for lx, ly in LANTERNS:
        P.line([(lx, ly), (lx, ly - 9)], (34, 30, 40), 1.0)
        P.lamp([(lx - 1.3, ly - 12), (lx + 1.3, ly - 12), (lx + 1.3, ly - 9), (lx - 1.3, ly - 9)], WINDOW_HOT, 1.2, size=8)
    return P


def capital():
    (tw, th), (lx0, ly0, _, _), _ = LAYERS["capital"]
    P = capital_scene()
    ss = P.ss
    big = np.asarray(P.img, dtype=np.float64) / 255.0
    a_big = big[..., 3]
    pre = (big[..., :3] * a_big[..., None]).reshape(P.h, ss, P.w, ss, 3).mean(axis=(1, 3))
    a = a_big.reshape(P.h, ss, P.w, ss).mean(axis=(1, 3))
    col = pre / np.maximum(a, 1e-6)[..., None]
    em = (np.asarray(P.em, dtype=np.float64) / 255.0).reshape(P.h, ss, P.w, ss).mean(axis=(1, 3))

    xs = P.x0 + np.arange(P.w) + 0.5
    ys = P.y0 + np.arange(P.h) + 0.5
    X, Y = np.meshgrid(xs, ys)
    lit_pre = col.copy()
    # air between us and the city, mist pooling at the foot of the hill, warmth on the sunward side
    col = col + (rgb((116, 92, 140)) - col) * 0.06
    mist = 0.30 * np.exp(-((Y - 968) / 22) ** 2)
    col = col + (rgb((120, 94, 132)) - col) * mist[..., None]
    col = screen(col, (0.10 * np.exp(-((X - 1080) / 260) ** 2) * smoothstep(1000, 900, Y))[..., None] * rgb((255, 150, 100)))
    col = col * (1 - 0.18 * smoothstep(1000, 1120, Y))[..., None]
    # the skyline's sunward edges catch the last light
    rim = sun_rim(a, xs, 1.0, reach=2) * smoothstep(960, 760, Y)
    col = screen(col, (rim * (0.45 + 0.4 * smoothstep(800, 560, Y)))[..., None] * rgb((255, 184, 120)))
    col = col * (1 - em[..., None]) + lit_pre * em[..., None]

    out = np.zeros((th, tw, 3))
    alpha = np.zeros((th, tw))
    ox, oy = P.x0 - lx0, P.y0 - ly0
    out[oy:oy + P.h, ox:ox + P.w] = col
    alpha[oy:oy + P.h, ox:ox + P.w] = a
    # the plain fades out to the left, where the hills' own valley floor and river carry on
    fade = smoothstep(1000, 1090, lx0 + np.arange(tw) + 0.5)[None, :]
    flat = (ly0 + np.arange(th)[:, None] + 0.5) > 978
    keep = np.where(flat & (lx0 + np.arange(tw)[None, :] < 1100), fade, 1.0)
    alpha = alpha * keep
    save(to_image(out, alpha), "capital")

    # the lights: soft warm glows over the windows and lamps that can still be seen
    vis = []
    for (cx, cy, size, strength) in P.lights:
        ix, iy = int((cx - P.x0)), int((cy - P.y0))
        if 0 <= ix < P.w and 0 <= iy < P.h and em[max(0, iy - 1):iy + 2, max(0, ix - 1):ix + 2].max() > 0.25:
            vis.append((cx, cy, size, strength))
    lrng = random.Random(91)
    sets = ([], [])
    for v in vis:
        sets[lrng.random() < 0.5].append(v)
    (lw, lh) = (1056, 380)
    for name, group in (("capital_lights", sets[0]), ("capital_lights2", sets[1])):
        X2, Y2 = texel_grid((lw, lh), (lx0, ly0, 2112, 760))
        acc = np.zeros((lh, lw, 3))
        for (cx, cy, size, strength) in group:
            sig = 2.0 + size * 0.55
            r = int(sig * 3.5 / 2) + 2
            u, v = int((cx - lx0) / 2), int((cy - ly0) / 2)
            y0_, y1_ = max(0, v - r), min(lh, v + r + 1)
            x0_, x1_ = max(0, u - r), min(lw, u + r + 1)
            g = np.exp(-((X2[y0_:y1_, x0_:x1_] - cx) ** 2 + (Y2[y0_:y1_, x0_:x1_] - cy) ** 2) / (2 * sig * sig))
            amp = 0.20 * strength * min(1.6, 0.7 + size / 10)
            acc[y0_:y1_, x0_:x1_] += g[..., None] * amp * rgb((255, 150, 66))
        save(additive_image(1 - np.exp(-acc * 1.2)), name)
    return P


# ---------------------------------------------------------------- the foreground: the rise, the Wardfire, the old tree
FIRE_LIGHT = (430.0, 948.0)   # where the fire's light comes from (its middle, above the ash)
FIRE_COL = np.array([1.0, 0.56, 0.26])


def cover(polys, x0, y0, w, h, ss=3):
    """antialiased coverage of polygons (design coords) on a w x h canvas whose top-left is (x0, y0)"""
    img = Image.new("L", (w * ss, h * ss), 0)
    dr = ImageDraw.Draw(img)
    for p in polys:
        dr.polygon([((x - x0) * ss, (y - y0) * ss) for x, y in p], fill=255)
    return np.asarray(img, dtype=np.float64).reshape(h, ss, w, ss).mean(axis=(1, 3)) / 255.0


class Layer:
    """a float RGBA layer in design coords, composited back to front"""

    def __init__(self, x0, y0, w, h):
        self.x0, self.y0, self.w, self.h = x0, y0, w, h
        self.col = np.zeros((h, w, 3))
        self.a = np.zeros((h, w))

    def grid(self, bx, by, bw, bh):
        return np.meshgrid(bx + np.arange(bw) + 0.5, by + np.arange(bh) + 0.5)

    def over(self, bx, by, c, a):
        """composite colour c with coverage a, whose canvas starts at design (bx, by)"""
        h, w = a.shape
        ox, oy = int(bx - self.x0), int(by - self.y0)
        x0, y0 = max(0, ox), max(0, oy)
        x1, y1 = min(self.w, ox + w), min(self.h, oy + h)
        if x0 >= x1 or y0 >= y1:
            return
        cc = c[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
        aa = a[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
        dst, da = self.col[y0:y1, x0:x1], self.a[y0:y1, x0:x1]
        out_a = aa + da * (1 - aa)
        dst[:] = (cc * aa[..., None] + dst * (da * (1 - aa))[..., None]) / np.maximum(out_a, 1e-9)[..., None]
        da[:] = out_a


def fire_lit(X, Y, Z, n=None, reach=95.0, gain=1.0):
    """light from the Wardfire on surface points (Z toward us, fire at Z=0); n = unit normals or None (facing)"""
    lx, ly, lz = FIRE_LIGHT[0] - X, FIRE_LIGHT[1] - Y, -Z
    dist = np.sqrt(lx * lx + ly * ly + lz * lz) + 1e-6
    fall = gain / (1 + (dist / reach) ** 2)
    if n is None:
        return fall
    return np.clip((n[..., 0] * lx + n[..., 1] * ly + n[..., 2] * lz) / dist, 0, 1) * fall


def pillow_normals(a, R):
    """normals of a soft dome filling a mask, for shading stones as solid things"""
    d = ndimage.distance_transform_edt(a > 0.5)
    hgt = np.sqrt(np.clip(d / R, 0, 1))
    hgt = ndimage.gaussian_filter(hgt, 0.9)
    gy, gx = np.gradient(hgt)
    n = np.stack([-gx * R * 0.9, -gy * R * 0.9, np.ones_like(gx)], -1)
    return n / np.linalg.norm(n, axis=-1, keepdims=True), hgt


def stone(layer, pts, z0, R=9.0, base=(28, 24, 36), albedo=(255, 168, 112), sky=0.10, gain=1.5, reach=95.0, grain=0.0, seed=0):
    """a stone lit by the fire: a soft dome over the polygon, optionally weathered (mottled, darker at the foot)"""
    xs_ = [p[0] for p in pts]
    ys_ = [p[1] for p in pts]
    bx, by = int(min(xs_)) - 3, int(min(ys_)) - 3
    bw, bh = int(max(xs_)) - bx + 4, int(max(ys_)) - by + 4
    a = cover([pts], bx, by, bw, bh)
    n, hgt = pillow_normals(a, R)
    X, Y = layer.grid(bx, by, bw, bh)
    Z = z0 + hgt * R * 0.7
    li = fire_lit(X, Y, Z, n, reach, gain)
    alb = rgb(albedo) * FIRE_COL * 1.1
    if grain:
        rng = np.random.default_rng(seed)
        mott = fbm_at(Y / 4.0, X / 5.0 + seed * 37.0, rng, 3, 0.55)
        streak = fbm_at(Y / 9.0, X / 1.6 + seed * 11.0, rng, 2, 0.5)
        k = 1 + grain * ((mott - 0.5) * 1.3 + (streak - 0.5) * 0.6)
        k = k * (1 - grain * 0.55 * smoothstep(max(ys_) - 10, max(ys_), Y))
        alb = alb * k[..., None]
    c = rgb(base) + alb * li[..., None]
    c = c + rgb((80, 76, 120)) * (np.clip(-n[..., 1], 0, 1) * sky)[..., None]
    layer.over(bx, by, c, a)
    return bx, by, a


TREE_TOP = 470.0


def gnarled_tree(rng):
    """a dead tree as tapered segments (x0, y0, x1, y1, w0, w1): a leaning trunk, big limbs, then forks to twigs"""
    segs = []

    def limb(x, y, ang, length, w0, depth, gnarl):
        n = max(2, int(length / 8))
        step = length / n
        w = w0
        sides = []
        if depth > 0 and n > 3:
            sides = rng.sample(range(1, n - 1), k=min(n - 2, rng.choice((0, 1, 1, 2))))
        flip = rng.choice((-1, 1))
        for i in range(n):
            ang += rng.gauss(0, gnarl)
            if rng.random() < 0.08:
                ang += rng.choice((-1, 1)) * rng.uniform(0.2, 0.45)
            ang += (-math.pi / 2 - ang) * 0.03
            nx, ny = x + math.cos(ang) * step, y + math.sin(ang) * step
            w1 = max(0.7, w0 * (1 - 0.55 * (i + 1) / n))
            segs.append((x, y, nx, ny, w, w1))
            if i in sides:
                flip = -flip
                limb(nx, ny, ang + flip * rng.uniform(0.45, 1.0), length * rng.uniform(0.4, 0.62), w1 * rng.uniform(0.45, 0.7),
                     depth - 1, gnarl * 1.08)
            x, y, w = nx, ny, w1
        if depth > 0:
            spread = rng.uniform(0.3, 0.6)
            for side in (-1, 1):
                limb(x, y, ang + side * spread * rng.uniform(0.7, 1.2), length * rng.uniform(0.58, 0.78),
                     w * rng.uniform(0.62, 0.78), depth - 1, gnarl * 1.1)
        else:
            for _ in range(rng.choice((1, 2))):
                a2 = ang + rng.uniform(-0.6, 0.6)
                segs.append((x, y, x + math.cos(a2) * rng.uniform(5, 10), y + math.sin(a2) * rng.uniform(5, 10), w, 0.4))

    # the trunk: thick at the root, leaning toward the fire, then twisting back and up
    x, y, ang, w = 26.0, 934.0, -math.pi / 2 + 0.30, 50.0
    for i in range(14):
        ang += rng.gauss(0, 0.05) - (0.035 if i > 6 else -0.01)
        nx, ny = x + math.cos(ang) * 11, y + math.sin(ang) * 11
        w1 = w * 0.962
        segs.append((x, y, nx, ny, w, w1))
        x, y, w = nx, ny, w1
    fork = (x, y, w)
    limb(x, y, -math.pi / 2 - 0.25, 120, w * 0.74, 4, 0.12)
    limb(x, y, -math.pi / 2 + 0.62, 150, w * 0.66, 4, 0.11)
    # a long low limb reaching out over the rise toward the fire
    mx, my = segs[8][2], segs[8][3]
    limb(mx, my, -0.50, 150, 17, 4, 0.13)
    # and one hanging back to the left, out of the frame
    limb(segs[10][2], segs[10][3], -math.pi + 0.55, 95, 15, 3, 0.12)
    # keep it all inside the layer: scale about the root so the highest twig stays below TREE_TOP
    top = min(min(sg[1], sg[3]) for sg in segs)
    k = min(1.0, (934.0 - TREE_TOP) / (934.0 - top))
    return [(26 + (x0 - 26) * k, 934 + (y0 - 934) * k, 26 + (x1 - 26) * k, 934 + (y1 - 934) * k, w0 * k ** 0.5, w1 * k ** 0.5)
            for (x0, y0, x1, y1, w0, w1) in segs]


def foreground():
    (tw, th), (lx0, ly0, _, _), _ = LAYERS["foreground"]
    L = Layer(lx0, ly0, tw, th)
    rng = random.Random(53)
    fx, fy = FIRE

    # ---- the ground: a rocky rise on the left carrying the fire, low grass running right, bushes at the edge
    ground = [(-96, 876), (-40, 884), (20, 894), (90, 908), (160, 924), (230, 940), (300, 952), (360, 957), (430, 958),
              (500, 960), (550, 966), (600, 980), (660, 1000), (720, 1018), (790, 1034), (870, 1046), (960, 1054),
              (1080, 1060), (1240, 1063), (1400, 1062), (1560, 1058), (1680, 1050), (1760, 1036), (1840, 1018),
              (1920, 1004), (2016, 996)]
    gx_ = np.array([p[0] for p in ground], dtype=np.float64)
    gy_ = np.array([p[1] for p in ground], dtype=np.float64)
    xsx = np.arange(-96, 2017, 3.0)
    top = np.interp(xsx, gx_, gy_) + (fbm1d(len(xsx), 14, np.random.default_rng(3), 3) - 0.5) * 6
    a = cover([list(zip(xsx, top)) + [(2016, 1120), (-96, 1120)]], lx0, ly0, tw, th)
    X, Y = L.grid(lx0, ly0, tw, th)
    below = Y - np.interp(X, xsx, top)
    up = np.exp(-np.clip(below, 0, None) / 28)  # the top of the ground faces the sky and the fire
    ground_c = rgb((17, 14, 24)) + rgb((26, 22, 38)) * (fbm(th, tw, 40, 90, np.random.default_rng(4), 3) - 0.5)[..., None] * 0.6
    # the pool of firelight on the ledge: wide and flat, as on the ground
    pool = 1.0 / (1 + (np.hypot(X - fx, (Y - (fy + 8)) * 2.4) / 120) ** 2)
    ground_c = ground_c + rgb((236, 132, 70)) * (pool * (0.25 + 0.75 * up) * 0.85)[..., None]
    ground_c = ground_c + rgb((70, 62, 104)) * (up * 0.35 * (1 - pool))[..., None]
    L.over(lx0, ly0, ground_c, a)

    # boulders on the rise and along the bottom
    for (bx, bb, bw, bh, z) in [(178, 950, 70, 26, 30), (250, 1002, 110, 30, 70), (606, 1024, 120, 24, 80),
                                (300, 1074, 190, 40, 130), (-30, 1030, 150, 56, 110)]:
        stone(L, blob(bx, bb, bw, bh, rng, 20, 0.45), z, R=14, base=(19, 16, 27), albedo=(220, 150, 110), gain=1.3, reach=110)

    # ---- the Wardfire: back stones, the ash bed, the sword, front stones
    ring = [(0, False), (45, False), (90, False), (140, True), (185, False), (230, True), (275, False), (320, True)]
    RX, RY, RCY = 74.0, 19.0, 981.0
    stones = []
    srng = random.Random(7)
    for k, (ang, tall) in enumerate(ring):
        a_ = math.radians(ang + srng.uniform(-6, 6))
        sx, sy = fx + RX * math.cos(a_), RCY + RY * math.sin(a_)
        w, h = (srng.uniform(17, 21), srng.uniform(44, 52)) if tall else (srng.uniform(20, 28), srng.uniform(13, 19))
        stones.append((math.sin(a_), sx, sy, w, h, tall, k, RX * math.sin(a_)))
    runes = []

    def standing(sx, sy, w, h, k):
        """a menhir: wide at the foot, tapering to a worn, off-centre point"""
        r = random.Random(200 + k)
        lean = r.uniform(-0.06, 0.06) * h
        apx = r.uniform(-0.18, 0.18)
        sl, sr = r.uniform(0.80, 0.90), r.uniform(0.78, 0.88)
        return [(sx - w / 2, sy + 3), (sx - w / 2 - 0.8, sy - h * 0.35), (sx - w * 0.43 + lean * 0.6, sy - h * 0.66),
                (sx - w * 0.30 + lean * 0.85, sy - h * sl), (sx + w * (apx - 0.08) + lean, sy - h * 0.98),
                (sx + w * (apx + 0.06) + lean, sy - h), (sx + w * 0.34 + lean * 0.85, sy - h * sr),
                (sx + w * 0.46 + lean * 0.6, sy - h * 0.6), (sx + w / 2 + 0.8, sy - h * 0.25), (sx + w / 2, sy + 3)]

    def draw_ring_stone(st):
        _, sx, sy, w, h, tall, k, z = st
        pts = standing(sx, sy, w, h, k) if tall else blob(sx, sy + 2, w, h, random.Random(300 + k), 14, 0.2)
        stone(L, pts, z, R=5 if tall else 6, base=(30, 26, 38), albedo=(232, 178, 132), gain=1.7, reach=85,
              grain=0.5 if tall else 0.3, seed=k)
        if tall:
            runes.append((sx + (0 if z > 0 else 0.8), sy - h * 0.78, h * 0.46, len(runes)))

    for st in sorted([s for s in stones if s[0] <= 0.1], key=lambda s: s[2]):
        draw_ring_stone(st)

    # the bed of ash: a low mound, darker at its ragged rim, smouldering toward the middle
    bx, by, bw, bh = int(fx - 64), int(fy - 16), 128, 38
    X, Y = L.grid(bx, by, bw, bh)
    ang = np.arctan2((Y - fy - 4) / 13, (X - fx) / 54)
    ragged = 1 + 0.10 * np.sin(ang * 5 + 1.3) + 0.06 * np.sin(ang * 11 + 0.4)
    r = np.hypot((X - fx) / 54, (Y - fy - 4) / 13) / ragged
    a = smoothstep(1.05, 0.80, r)
    ash = rgb((30, 27, 33)) + (rgb((82, 70, 70)) - rgb((30, 27, 33))) * smoothstep(1.0, 0.5, r)[..., None]
    hot = smoothstep(0.78, 0.18, r)
    speck = fbm(bh, bw, 2.2, 3.5, np.random.default_rng(13), 2, 0.6)
    ash = ash + (rgb((255, 128, 44)) - ash) * (hot * (0.40 + 0.6 * smoothstep(0.45, 0.7, speck)))[..., None]
    ash = ash + (rgb((255, 214, 140)) - ash) * smoothstep(0.34, 0.0, r)[..., None]
    L.over(bx, by, ash, a)

    # the Warden's sword, point down in the ash, lit from all round by the fire
    sw = Layer(fx - 30, 856, 60, 132)
    def part(pts, c):
        a = cover([pts], sw.x0, sw.y0, sw.w, sw.h, ss=4)
        sw.over(sw.x0, sw.y0, np.broadcast_to(rgb(c), a.shape + (3,)), a)
    bx_ = fx
    part([(bx_ - 3.6, 900), (bx_ + 3.6, 900), (bx_ + 2.6, 977), (bx_, 982), (bx_ - 2.6, 977)], (120, 108, 112))
    part([(bx_ - 3.6, 900), (bx_ - 1.5, 900), (bx_ - 1.1, 977), (bx_ - 2.6, 977)], (248, 212, 170))
    part([(bx_ + 1.5, 900), (bx_ + 3.6, 900), (bx_ + 2.6, 977), (bx_ + 1.1, 977)], (190, 120, 84))
    part([(bx_ - 0.45, 905), (bx_ + 0.45, 905), (bx_ + 0.35, 962), (bx_ - 0.35, 962)], (86, 64, 66))
    part([(bx_ - 19, 895.5), (bx_ + 19, 895.5), (bx_ + 21.5, 898.5), (bx_ + 17, 902.5), (bx_ - 17, 902.5), (bx_ - 21.5, 898.5)], (176, 124, 62))
    part([(bx_ - 19, 895.5), (bx_ + 19, 895.5), (bx_ + 18, 897.4), (bx_ - 18, 897.4)], (255, 220, 140))
    part([(bx_ - 17, 901), (bx_ + 17, 901), (bx_ + 17, 902.5), (bx_ - 17, 902.5)], (110, 70, 34))
    part([(bx_ + 3.4 * math.cos(t), 899 + 3.0 * math.sin(t)) for t in np.linspace(0, math.tau, 16, endpoint=False)], (240, 196, 110))
    for i in range(8):
        y = 872 + i * 2.95
        part([(bx_ - 2.7, y), (bx_ + 2.7, y + 1.1), (bx_ + 2.7, y + 3.1), (bx_ - 2.7, y + 2.0)], (108, 66, 40) if i % 2 else (78, 46, 30))
    part([(bx_ - 1.2, 872), (bx_ - 0.4, 872), (bx_ - 0.4, 895.5), (bx_ - 1.2, 895.5)], (160, 100, 60))
    part([(bx_ + 3.9 * math.cos(t), 868.2 + 3.7 * math.sin(t)) for t in np.linspace(0, math.tau, 20, endpoint=False)], (186, 140, 66))
    part([(bx_ - 1.1 + 1.6 * math.cos(t), 867 + 1.4 * math.sin(t)) for t in np.linspace(0, math.tau, 12, endpoint=False)], (255, 236, 180))
    # a strip of faded crimson cloth tied under the guard, hanging down the blade's west side
    part([(bx_ - 3.8, 902), (bx_ - 1.2, 902), (bx_ - 2.2, 914), (bx_ - 4.0, 926), (bx_ - 2.8, 935), (bx_ - 6.0, 932),
          (bx_ - 7.4, 921), (bx_ - 5.6, 910)], (150, 46, 42))
    part([(bx_ - 3.8, 902), (bx_ - 2.6, 902), (bx_ - 4.6, 916), (bx_ - 5.8, 926), (bx_ - 6.0, 932), (bx_ - 7.4, 921), (bx_ - 5.6, 910)], (206, 84, 62))
    L.over(sw.x0, sw.y0, sw.col, sw.a)

    for st in sorted([s for s in stones if s[0] > 0.1], key=lambda s: s[2]):
        draw_ring_stone(st)

    # the runes on the three standing stones, faintly aglow
    rl = Layer(fx - 110, 900, 220, 110)
    rmask = Image.new("L", (rl.w * 4, rl.h * 4), 0)
    rd = ImageDraw.Draw(rmask)
    for (cx, top_, hh, i) in runes:
        for stroke in rune_strokes(40 + i, cx, top_, hh):
            rd.line([((p[0] - rl.x0) * 4, (p[1] - rl.y0) * 4) for p in stroke], fill=255, width=5, joint="curve")
    rm = np.asarray(rmask, dtype=np.float64).reshape(rl.h, 4, rl.w, 4).mean(axis=(1, 3)) / 255.0
    halo = ndimage.gaussian_filter(rm, 2.2) * 1.6
    rc = rgb((255, 214, 140)) * rm[..., None] + rgb((255, 150, 60)) * halo[..., None] * 0.55
    ra = np.clip(rm * 0.95 + halo * 0.35, 0, 1)
    L.over(rl.x0, rl.y0, rc, ra)

    # ---- grass along the edges, lit warm where the fire reaches
    gimg = Image.new("L", (tw * 3, th * 3), 0)
    gd = ImageDraw.Draw(gimg)
    grng = random.Random(31)
    for _ in range(1500):
        x = grng.uniform(-96, 2016)
        if 330 < x < 530 and grng.random() < 0.85:
            continue
        y = float(np.interp(x, xsx, top)) + grng.uniform(-1, 6)
        hgt = grng.uniform(5, 15) * (1.5 if x > 1640 or x < 260 else 1.0)
        for _ in range(grng.randint(2, 6)):
            bxx = x + grng.uniform(-4, 4)
            lean = grng.uniform(-0.5, 0.5)
            hh = hgt * grng.uniform(0.55, 1.1)
            tipx, tipy = bxx + lean * hh, y - hh
            midx, midy = bxx + lean * hh * 0.35, y - hh * 0.5
            gd.polygon([((bxx - 0.9 - lx0) * 3, (y + 1 - ly0) * 3), ((midx - 0.5 - lx0) * 3, (midy - ly0) * 3), ((tipx - lx0) * 3, (tipy - ly0) * 3),
                        ((midx + 0.5 - lx0) * 3, (midy - ly0) * 3), ((bxx + 0.9 - lx0) * 3, (y + 1 - ly0) * 3)], fill=255)
    ga = np.asarray(gimg, dtype=np.float64).reshape(th, 3, tw, 3).mean(axis=(1, 3)) / 255.0
    X, Y = L.grid(lx0, ly0, tw, th)
    gl = 1.0 / (1 + (np.hypot(X - fx, (Y - fy) * 1.6) / 110) ** 2)
    gc = rgb((18, 15, 25)) + rgb((240, 140, 72)) * (gl * 0.8)[..., None]
    L.over(lx0, ly0, gc, ga)

    # ---- bushes at the right edge, low so the city stays in view
    bimg = Image.new("L", (tw * 3, th * 3), 0)
    bd = ImageDraw.Draw(bimg)
    for (cx, cy, r) in [(1736, 1052, 26), (1790, 1040, 36), (1850, 1026, 42), (1910, 1016, 40), (1968, 1008, 46), (2016, 1002, 44),
                        (1690, 1062, 20), (1640, 1066, 16)]:
        brng = random.Random(int(cx))
        for _ in range(int(r * 2.2)):
            ang = brng.uniform(math.pi * 1.02, math.pi * 1.98)
            d = brng.uniform(0.15, 1.0) * r
            ex, ey = cx + math.cos(ang) * d, cy + math.sin(ang) * d * 0.6
            rx, ry = brng.uniform(2.5, 6), brng.uniform(2, 4.5)
            bd.ellipse([((ex - rx - lx0) * 3, (ey - ry - ly0) * 3), ((ex + rx - lx0) * 3, (ey + ry - ly0) * 3)], fill=255)
        bd.ellipse([((cx - r - lx0) * 3, (cy - r * 0.2 - ly0) * 3), ((cx + r - lx0) * 3, (cy + r * 0.8 - ly0) * 3)], fill=255)
    ba = np.asarray(bimg, dtype=np.float64).reshape(th, 3, tw, 3).mean(axis=(1, 3)) / 255.0
    rim = sun_rim(ba, X[0], 0.5, reach=2) * smoothstep(1060, 990, Y)
    bc = rgb((15, 13, 22)) + rgb((90, 70, 110)) * rim[..., None]
    L.over(lx0, ly0, bc, ba)

    # ---- the old tree, black against the dusk, the fire picking out the sides turned to it
    segs = gnarled_tree(random.Random(12))
    timg = Image.new("L", (tw * 3, th * 3), 0)
    wimg = Image.new("L", (tw * 3, th * 3), 0)
    limg = Image.new("L", (tw * 3, th * 3), 0)
    td, twd, tld = ImageDraw.Draw(timg), ImageDraw.Draw(wimg), ImageDraw.Draw(limg)
    T = lambda x, y: ((x - lx0) * 3, (y - ly0) * 3)
    for (x0, y0, x1, y1, w0, w1) in segs:
        nx, ny = y1 - y0, -(x1 - x0)
        ln = math.hypot(nx, ny) + 1e-9
        nx, ny = nx / ln, ny / ln
        # the finest twigs go on their own sheet, drawn fainter, so the crown reads as a haze of twigs, not wire
        d = twd if w0 < 1.5 else td
        d.polygon([T(x0 + nx * w0 / 2, y0 + ny * w0 / 2), T(x1 + nx * w1 / 2, y1 + ny * w1 / 2),
                   T(x1 - nx * w1 / 2, y1 - ny * w1 / 2), T(x0 - nx * w0 / 2, y0 - ny * w0 / 2)], fill=255)
        if w1 > 1.2:
            td.ellipse([T(x1 - w1 / 2, y1 - w1 / 2), T(x1 + w1 / 2, y1 + w1 / 2)], fill=255)
        dxf, dyf = FIRE_LIGHT[0] - (x0 + x1) / 2, FIRE_LIGHT[1] - (y0 + y1) / 2
        s = 1 if dxf * nx + dyf * ny > 0 else -1
        if w0 > 1.5:
            tld.polygon([T(x0 + s * nx * w0 * 0.5, y0 + s * ny * w0 * 0.5), T(x1 + s * nx * w1 * 0.5, y1 + s * ny * w1 * 0.5),
                         T(x1 + s * nx * w1 * 0.05, y1 + s * ny * w1 * 0.05), T(x0 + s * nx * w0 * 0.05, y0 + s * ny * w0 * 0.05)], fill=255)
    # roots gripping the rise
    for (rx0, ry0, rx1, ry1, rw) in [(26, 930, -50, 950, 16), (34, 932, 92, 948, 13), (16, 934, -6, 962, 12), (40, 934, 60, 960, 10)]:
        td.polygon([T(rx0 - rw / 2, ry0), T(rx0 + rw / 2, ry0 - 2), T(rx1 + 1.5, ry1), T(rx1 - 1.5, ry1 + 1)], fill=255)
    ta = np.asarray(timg, dtype=np.float64).reshape(th, 3, tw, 3).mean(axis=(1, 3)) / 255.0
    ta = np.maximum(ta, np.asarray(wimg, dtype=np.float64).reshape(th, 3, tw, 3).mean(axis=(1, 3)) / 255.0 * 0.6)
    tl = np.asarray(limg, dtype=np.float64).reshape(th, 3, tw, 3).mean(axis=(1, 3)) / 255.0
    tlit = fire_lit(X, Y, np.zeros_like(X), None, reach=200, gain=0.42)
    tc = rgb((14, 12, 20)) + rgb((255, 140, 76)) * (tl * tlit)[..., None]
    L.over(lx0, ly0, tc, ta)

    save(to_image(L.col, L.a), "foreground")


def blob(cx, base, w, h, rng, n=14, flat=0.25, lean=0.0):
    """an irregular rounded stone sitting on base: a superellipse with a jittered rim"""
    pts = []
    for a in np.linspace(math.pi, 2 * math.pi, n):
        r = rng.uniform(0.88, 1.06)
        x = cx + math.cos(a) * w / 2 * r + lean * (math.sin(a) + 1) * -h
        y = base + math.sin(a) * h * r * (1 - flat * abs(math.cos(a)) ** 3)
        pts.append((x, y))
    pts.append((cx + w / 2 * 0.95, base + 3))
    pts.append((cx - w / 2 * 0.95, base + 3))
    return pts


def rune_strokes(seed, cx, top, h):
    """the Wardens' rune as line strokes (the same family as the stones in the world: a spine, a pair of arms,
    a foot that differs on each stone)"""
    rng = random.Random(seed)
    s = h / 12.0
    arm = rng.randrange(6, 9)
    strokes = [[(cx, top + 2 * s), (cx, top + 11 * s)],
               [(cx - 3 * s, top + (arm + 2) * s), (cx, top + (arm - 1) * s), (cx + 3 * s, top + (arm + 2) * s)],
               [(cx - 1.2 * s, top + 0.6 * s), (cx, top + 1.6 * s), (cx + 1.2 * s, top + 0.6 * s)]]
    foot = rng.randrange(3)
    if foot == 0:
        strokes.append([(cx - 2.2 * s, top + 11 * s), (cx + 2.2 * s, top + 11 * s)])
    elif foot == 1:
        strokes.append([(cx - 2.2 * s, top + 9 * s), (cx, top + 11 * s), (cx + 2.2 * s, top + 9 * s)])
    else:
        strokes.append([(cx - 3 * s, top + 7.5 * s), (cx - 3 * s, top + 10.5 * s)])
        strokes.append([(cx + 3 * s, top + 7.5 * s), (cx + 3 * s, top + 10.5 * s)])
    return strokes


# ---------------------------------------------------------------- mist and vignette
def mist():
    """a soft band of valley mist, lilac and rose-grey, that tiles left-right"""
    (tw, th), _, _ = LAYERS["mist"]
    rng = np.random.default_rng(61)
    v = (np.arange(th) + 0.5) / th
    env = smoothstep(0.02, 0.38, v) * smoothstep(0.98, 0.62, v)
    wisps = fbm(th, tw, 10, 128, rng, 4, 0.5, wrap_x=True)
    fine = fbm(th, tw, 3, 64, rng, 3, 0.55, wrap_x=True)
    cover = fbm(th, tw, 80, 256, rng, 2, 0.5, wrap_x=True)
    tint = fbm(th, tw, 40, 128, rng, 2, 0.5, wrap_x=True)
    d = np.clip((wisps - 0.36) * 2.4 + (fine - 0.5) * 0.45, 0, 1) * (0.45 + 0.75 * cover) * env[:, None]
    alpha = np.clip(d, 0, 1) * 0.35
    col = rgb((170, 148, 196)) + (rgb((214, 164, 176)) - rgb((170, 148, 196))) * smoothstep(0.35, 0.65, tint)[..., None]
    col = col * (1 + 0.08 * (1 - v))[:, None, None]
    save(to_image(col, alpha), "mist", tile=True)


def vignette():
    """black, clear in the middle, darker in the corners and along the bottom"""
    (tw, th), _, _ = LAYERS["vignette"]
    U, V = np.meshgrid((np.arange(tw) + 0.5) / tw, (np.arange(th) + 0.5) / th)
    r = np.hypot((U - 0.5) / 0.5, (V - 0.42) / 0.58)
    corner = smoothstep(0.78, 1.42, r) * 0.42
    bottom = smoothstep(0.70, 1.0, V) ** 1.4 * 0.26
    a = 1 - (1 - corner) * (1 - bottom)
    save(to_image(np.zeros((th, tw, 3)), a, dither_alpha=True), "vignette")


# ---------------------------------------------------------------- effect sprites (additive ones on black)
def luma(c):
    return c[..., 0] * 0.2126 + c[..., 1] * 0.7152 + c[..., 2] * 0.0722


def additive_image(col):
    col = np.clip(col, 0, 1)
    return Image.fromarray(np.dstack([quantize(col), quantize(luma(col))]), "RGBA")


FLAME_RAMP = [(0.00, (0, 0, 0)), (0.10, (132, 34, 8)), (0.26, (206, 80, 20)), (0.44, (238, 126, 34)),
              (0.62, (252, 172, 58)), (0.78, (255, 208, 100)), (0.91, (255, 234, 164)), (1.00, (255, 248, 222))]


def flame():
    """eight looping frames of golden fire round the planted blade, each 96x160, base at the bottom middle.
    Thin tongues lick up on both sides of the blade, amber at their edges and pale gold at the heart; each
    rises, sways and falls back a whole number of times over the loop and now and then sheds a lick that
    floats up and fades, so the last frame runs into the first. Painted at twice the size and shrunk."""
    FW, FH, NF, K = 96, 160, 8, 2
    rng = np.random.default_rng(71)
    prng = random.Random(71)
    Y, X = np.meshgrid((np.arange(FH * K) + 0.5) / K, (np.arange(FW * K) + 0.5) / K, indexing="ij")
    u = (X - FW / 2) / (FW / 2)
    v = (FH - 3 - Y) / FH  # 0 at the base
    # (centre, height, width): a tall pair flanking the blade, shorter ones outside them
    layout = [(-0.11, 0.72, 0.15), (0.12, 0.76, 0.15), (-0.32, 0.54, 0.15), (0.33, 0.50, 0.15),
              (-0.52, 0.34, 0.13), (0.52, 0.37, 0.13), (-0.66, 0.18, 0.10), (0.67, 0.16, 0.10),
              (-0.02, 0.40, 0.12), (0.22, 0.33, 0.10), (-0.22, 0.31, 0.10)]
    tongues = []
    for (cx, tall, w0) in layout:
        tongues.append((cx + prng.uniform(-0.03, 0.03), tall * prng.uniform(0.94, 1.06), w0 * prng.uniform(0.9, 1.1),
                        prng.uniform(0, math.tau), prng.choice((1, 1, 2)), prng.choice((-1, 1))))
    licks = [(i, prng.uniform(0, 1), prng.choice((1, 2))) for i in (0, 1, 2, 3, 5)]
    grids = [rng.random((8, 8)) for _ in range(3)]

    def sample(g, yy, xx):
        return ndimage.map_coordinates(g, [yy % g.shape[0], xx % g.shape[1]], order=3, mode="grid-wrap")

    sheet = np.zeros((FH, FW * NF, 3))
    for f in range(NF):
        t = f / NF
        turb = (sample(grids[0], Y / 20.0 + t * 8, X / 14.0) - 0.5) + 0.5 * (sample(grids[1], Y / 9.0 + t * 16, X / 7.0) - 0.5)
        vc = np.clip(v, 0, 1)
        uu = u + turb * (0.05 + 0.24 * vc)
        vv = v + turb * 0.05
        D = np.zeros_like(u)      # how much flame
        Hm = np.zeros_like(u)     # how hot
        tips = []
        for (cx, tall, w0, ph, k, lean) in tongues:
            hgt = tall * (0.80 + 0.20 * math.sin(math.tau * k * t + ph))
            q = vv / hgt
            qc = np.clip(q, 0, 1.2)
            sway = (0.07 + 0.05 * tall) * qc ** 1.5 * np.sin(math.tau * k * t + ph + qc * 3.0) + lean * 0.03 * qc ** 2
            centre = cx * (1 - 0.45 * np.clip(q, 0, 1)) + sway
            width = w0 * np.clip(1 - q, 0, 1) ** 0.75 * (1 + 0.35 * smoothstep(0.3, 0.0, q))
            d = np.abs(uu - centre) / np.maximum(width, 1e-4)
            Di = smoothstep(1.0, 0.62, d) * (q < 1) * smoothstep(-0.12, 0.02, q)
            Hi = Di * (0.62 + 0.38 * smoothstep(0.9, 0.0, d)) * (1 - 0.62 * np.clip(q, 0, 1) ** 1.2) * (0.75 + 0.25 * tall / 0.7)
            D = 1 - (1 - D) * (1 - Di)
            Hm = np.maximum(Hm, Hi)
            tips.append((cx * 0.55 + (0.07 + 0.05 * tall) * math.sin(math.tau * k * t + ph + 3.0) + lean * 0.03, hgt, w0, k, ph))
        # licks shed from the tips of some tongues, rising and thinning as they go
        for (i, ph, k) in licks:
            tx, hgt, w0, _, _ = tips[i]
            p = (t * k + ph) % 1.0
            cy = hgt * (0.92 + 0.42 * p)
            sz = w0 * (0.85 - 0.5 * p)
            hh = 0.10 * (1 - 0.4 * p)
            qy = (vv - cy) / hh
            dx = np.abs(uu - tx - 0.04 * math.sin(math.tau * p)) / np.maximum(sz * np.clip(1 - np.abs(qy) ** 1.5, 0, 1) * (1 - 0.4 * qy), 1e-4)
            Dl = smoothstep(1.0, 0.5, dx) * (np.abs(qy) < 1) * (1 - p) ** 1.3 * smoothstep(0.95, 0.82, vv)
            D = 1 - (1 - D) * (1 - Dl)
            Hm = np.maximum(Hm, Dl * 0.5)
        # the bed of the fire where the tongues meet, hottest in the middle
        bed = smoothstep(0.74, 0.30, np.abs(u)) * smoothstep(0.14, 0.0, v) * smoothstep(-0.03, 0.0, v)
        D = 1 - (1 - D) * (1 - bed)
        Hm = np.maximum(Hm, bed * (0.85 + 0.15 * smoothstep(0.5, 0.0, np.abs(u))))
        Hm = Hm + 0.20 * smoothstep(0.36, 0.0, np.abs(u)) * smoothstep(0.18, 0.0, v) * D
        # the blade stands in the middle: the flames part round it low down
        gap = 1 - 0.42 * np.exp(-(u / 0.06) ** 2) * smoothstep(0.52, 0.10, v) * smoothstep(0.0, 0.06, v)
        heat = np.clip(Hm * gap, 0, 1)
        col = gradient(FLAME_RAMP, heat) * (D * gap)[..., None]
        col = col.reshape(FH, K, FW, K, 3).mean(axis=(1, 3))
        yy, xx = np.arange(FH) + 0.5, np.arange(FW) + 0.5
        edge = (smoothstep(0, 4, xx) * smoothstep(FW, FW - 4, xx))[None, :] * (smoothstep(0, 3, yy) * smoothstep(FH, FH - 1.5, yy))[:, None]
        sheet[:, f * FW:(f + 1) * FW] = col * edge[..., None]
    save(additive_image(sheet), "flame")


def glow_sprite():
    S = 128
    Y, X = np.meshgrid(np.arange(S) + 0.5, np.arange(S) + 0.5, indexing="ij")
    r2 = ((X - S / 2) ** 2 + (Y - S / 2) ** 2) / (S / 2) ** 2
    f = np.clip(1 - r2, 0, 1) ** 3 * (0.42 + 0.58 * np.exp(-r2 / 0.07))
    rng = np.random.default_rng(81)
    f = np.clip(f + (rng.random(f.shape) - 0.5) / 255.0 * (f > 0), 0, 1)
    col = np.dstack([f, f, f])
    save(Image.fromarray(np.dstack([quantize(col), quantize(f)]), "RGBA"), "glow")


def ember_sprite():
    S = 16
    Y, X = np.meshgrid(np.arange(S) + 0.5, np.arange(S) + 0.5, indexing="ij")
    r2 = (X - S / 2) ** 2 + (Y - S / 2) ** 2
    win = smoothstep(7.6, 4.5, np.sqrt(r2))
    core = np.exp(-r2 / (2 * 0.9 ** 2))
    halo = np.exp(-r2 / (2 * 2.6 ** 2)) * 0.45
    col = (core[..., None] * rgb((255, 250, 232)) + halo[..., None] * rgb((255, 196, 120))) * win[..., None]
    save(additive_image(col), "ember")


def star_sprite():
    S = 16
    Y, X = np.meshgrid(np.arange(S) + 0.5, np.arange(S) + 0.5, indexing="ij")
    dx, dy = X - S / 2, Y - S / 2
    r = np.hypot(dx, dy)
    win = smoothstep(7.8, 5.0, r)
    arms = np.exp(-dy ** 2 / (2 * 0.45 ** 2)) * np.exp(-np.abs(dx) / 2.0) + np.exp(-dx ** 2 / (2 * 0.45 ** 2)) * np.exp(-np.abs(dy) / 2.0)
    core = np.exp(-r ** 2 / (2 * 0.8 ** 2))
    f = np.clip(arms * 0.75 + core, 0, 1) * win
    col = f[..., None] * rgb((236, 242, 255))
    save(additive_image(col), "star")


def smoke_sprite():
    """a soft irregular puff: white, all its shape in the alpha (alpha-blended, the game tints it)"""
    S = 64
    rng = np.random.default_rng(91)
    Y, X = np.meshgrid(np.arange(S) + 0.5, np.arange(S) + 0.5, indexing="ij")
    a = np.zeros((S, S))
    for _ in range(7):
        cx, cy = S / 2 + rng.normal(0, 6), S / 2 + rng.normal(0, 5)
        sg = rng.uniform(6.5, 10.5)
        a += rng.uniform(0.5, 1.0) * np.exp(-((X - cx) ** 2 + (Y - cy) ** 2) / (2 * sg * sg))
    n = fbm(S, S, 10, 10, rng, 3, 0.55)
    a = a * (0.7 + 0.6 * n)
    a = a / a.max()
    win = smoothstep(31.5, 22, np.hypot(X - S / 2, Y - S / 2))
    a = np.clip(a * win, 0, 1) ** 1.2 * 0.85
    save(to_image(np.ones((S, S, 3)), a), "smoke")


# ---------------------------------------------------------------- the logo
LOGO_W, LOGO_H = 1280, 272
FACE = [(0.00, (255, 246, 200)), (0.16, (252, 218, 128)), (0.40, (222, 158, 58)), (0.50, (150, 92, 30)),
        (0.555, (255, 226, 140)), (0.70, (236, 182, 82)), (1.00, (150, 96, 36))]


def logo_masks(k):
    """the word in the heading font, blown up k times past the logo's own 15 texels per font pixel"""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from heading_font import G as GLYPHS
    word = "GUILDMARK"
    P = 15 * k
    widths = [max(len(r) for r in GLYPHS[c]) for c in word]
    total = sum(widths) + len(word) - 1
    W, H = LOGO_W * k, LOGO_H * k
    x = (W - total * P) // 2
    top = 22 * k
    face = np.zeros((H, W), bool)
    for c, w in zip(word, widths):
        for gy, row in enumerate(GLYPHS[c]):
            for gx, ch in enumerate(row):
                if ch == "#":
                    face[top + gy * P:top + (gy + 1) * P, x + gx * P:x + (gx + 1) * P] = True
        x += (w + 1) * P
    return face, top, P


def logo():
    """GUILDMARK in the heading font as a cast-gold title: bevelled metallic faces, a bronze extrusion going
    down, a dark outline and a soft drop shadow, over a gold rule with diamonds. Painted at twice the size and
    shrunk, so the diamonds and bevels are antialiased."""
    k = 2
    face, top, P = logo_masks(k)
    H, W = face.shape
    Y, X = np.meshgrid(np.arange(H) + 0.5, np.arange(W) + 0.5, indexing="ij")
    depth = 26 * k
    ext = np.zeros_like(face)
    below = np.zeros((H, W))
    for d in range(1, depth + 1):
        sh = np.zeros_like(face)
        sh[d:] = face[:-d]
        new = sh & ~face & ~ext
        below[new] = d
        ext |= sh
    ext &= ~face

    # the rule under the word: a gold line fading out at both ends, a central diamond, smaller ones beside it
    cy, half = 214 * k, 470 * k
    t = np.abs(X - W / 2) / half
    fade = np.clip(1 - t ** 2.2, 0, 1)
    line = (np.abs(Y - cy) <= 4.5 * k) & (t < 1)
    line2 = (np.abs(Y - (cy + 11 * k)) <= 1.2 * k) & (np.abs(X - W / 2) < 300 * k)
    dia = []
    for (dx, r) in [(0, 23), (-70, 10), (70, 10), (-330, 7), (330, 7)]:
        dia.append((np.abs(X - (W / 2 + dx * k)) + np.abs(Y - cy), r * k))
    rule = line | line2
    for dd, r in dia:
        rule |= dd <= r

    def bevel_light(mask, B):
        """lighting of a chamfer B texels wide round a shape, lit from the upper left"""
        din = ndimage.distance_transform_edt(mask)
        hgt = np.clip(din / B, 0, 1)
        hs = ndimage.gaussian_filter(hgt, 0.9 * k)
        gy, gx = np.gradient(hs)
        kk = 3.2 * B / 5.0
        n = np.stack([-gx * kk, -gy * kk, np.ones_like(gx)], -1)
        n /= np.linalg.norm(n, axis=-1, keepdims=True)
        L = np.array([-0.42, -0.72, 0.55])
        L /= np.linalg.norm(L)
        return (n * L).sum(-1), din

    # the faces: a polished gold gradient with a dark band and a bright one, brighter and darker on the chamfer
    lit, din = bevel_light(face, 5.5 * k)
    base = gradient(FACE, np.clip((Y - top) / (9 * P), 0, 1))
    kf = np.clip(1 + 1.5 * (lit - 0.55), 0.32, 1.6)
    col_face = np.clip(base * kf[..., None], 0, 1)
    spec = smoothstep(0.80, 0.95, lit) * (din < 6 * k)
    col_face = screen(col_face, spec[..., None] * 0.8)
    seam = np.exp(-((din - 5.5 * k) / (0.8 * k)) ** 2) * 0.18
    col_face = col_face * (1 - seam[..., None])

    # extrusion: bronze, lit along its top lip, darkening downward
    tt = np.clip(below / depth, 0, 1)
    col_ext = gradient([(0.0, (118, 76, 32)), (0.14, (88, 56, 25)), (0.5, (60, 38, 19)), (1.0, (38, 24, 13))], tt)

    # the rule's own gold with a narrower chamfer; dark cores in the big diamond with a hot point
    rlit, _ = bevel_light(rule, 2.5 * k)
    rbase = gradient([(0.0, (255, 236, 170)), (0.5, (226, 172, 74)), (1.0, (150, 98, 38))],
                     np.clip((Y - (cy - 18 * k)) / (36 * k), 0, 1))
    col_rule = np.clip(rbase * np.clip(1 + 1.3 * (rlit - 0.55), 0.4, 1.5)[..., None], 0, 1)
    dd0, r0 = dia[0]
    col_rule = np.where((dd0 <= r0 * 0.42)[..., None], rgb((70, 36, 22)), col_rule)
    col_rule = np.where((dd0 <= r0 * 0.16)[..., None], rgb((255, 214, 120)), col_rule)

    solid = face | ext | rule
    outline = ndimage.binary_dilation(solid, structure=disk(4 * k)) & ~solid
    # the rule's line fades out toward its ends, outline and shadow too
    near_line = ndimage.binary_dilation(line | line2, structure=disk(5 * k))
    fade_zone = near_line & ~ndimage.binary_dilation(face | ext, structure=disk(5 * k))
    for dd, r in dia:
        fade_zone &= ~(dd <= r + 5 * k)
    body = (solid | outline).astype(np.float64) * np.where(fade_zone, fade, 1.0)
    shadow = ndimage.gaussian_filter(body, 7 * k)
    sh = np.zeros_like(shadow)
    sh[10 * k:] = shadow[:-10 * k]

    col = np.zeros((H, W, 3)) + rgb((22, 14, 10))
    col = np.where(ext[..., None], col_ext, col)
    col = np.where(rule[..., None], col_rule, col)
    col = np.where(face[..., None], col_face, col)
    alpha = np.maximum(sh * 0.62, body)
    # shadow-only texels are black; everything else keeps its colour
    col = np.where((body > 0)[..., None], col, 0.0)
    pre = (col * alpha[..., None]).reshape(LOGO_H, k, LOGO_W, k, 3).mean(axis=(1, 3))
    a = alpha.reshape(LOGO_H, k, LOGO_W, k).mean(axis=(1, 3))
    save(to_image(pre / np.maximum(a, 1e-6)[..., None], np.clip(a, 0, 1)), "logo")

    # the glow behind it: the logo's shape, blurred, warm gold, on black
    m = body.reshape(LOGO_H // 2, 2 * k, LOGO_W // 2, 2 * k).mean(axis=(1, 3))
    g = np.clip(ndimage.gaussian_filter(m, 9) * 1.1 + ndimage.gaussian_filter(m, 3) * 0.25, 0, 1)
    gcol = g[..., None] * rgb((255, 176, 84)) * 0.62
    hh, ww = gcol.shape[:2]
    ey = smoothstep(0, 6, np.arange(hh))[:, None] * smoothstep(hh, hh - 6, np.arange(hh))[:, None]
    ex = smoothstep(0, 6, np.arange(ww))[None, :] * smoothstep(ww, ww - 6, np.arange(ww))[None, :]
    save(additive_image(gcol * (ey * ex)[..., None]), "logo_glow")


def disk(r):
    y, x = np.mgrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r + 0.5


# ---------------------------------------------------------------- previews
def gl_draw(tex, w, h, wrap=False):
    """samples a texture at w x h like GL_LINEAR does (clamped or repeating), as float RGBA"""
    a = np.asarray(tex.convert("RGBA"), dtype=np.float64) / 255.0
    th, tw = a.shape[:2]
    ys = (np.arange(h) + 0.5) * th / h - 0.5
    xs = (np.arange(w) + 0.5) * tw / w - 0.5
    Y, X = np.meshgrid(ys, xs, indexing="ij")
    mode = "grid-wrap" if wrap else "nearest"
    return np.dstack([ndimage.map_coordinates(a[..., c], [Y, X], order=1, mode=mode) for c in range(4)])


def blit(canvas, src, x, y, mode="alpha", tint=(1, 1, 1), strength=1.0):
    """draws float RGBA src onto the float RGB canvas at x, y (design px), clipped"""
    H, W = canvas.shape[:2]
    h, w = src.shape[:2]
    cx0, cy0 = max(0, x), max(0, y)
    cx1, cy1 = min(W, x + w), min(H, y + h)
    if cx0 >= cx1 or cy0 >= cy1:
        return
    s = src[cy0 - y:cy1 - y, cx0 - x:cx1 - x]
    dst = canvas[cy0:cy1, cx0:cx1]
    rgbs = s[..., :3] * np.array(tint)
    if mode == "alpha":
        a = s[..., 3:4] * strength
        dst[:] = dst * (1 - a) + rgbs * a
    else:
        dst[:] = dst + rgbs * strength


def compose(buttons, with_effects=True):
    canvas = np.zeros((H_DESIGN, W_DESIGN, 3))

    def layer(name, mode="alpha"):
        path = os.path.join(TEX_DIR, name + ".png")
        if not os.path.exists(path):
            return
        tex = Image.open(path)
        (tw, th), (x, y, w, h), tile = LAYERS[name]
        src = gl_draw(tex, w, h, wrap=tile)
        if tile:
            for ox in range(x - w, W_DESIGN, w):
                blit(canvas, src, ox, y, mode)
        else:
            blit(canvas, src, x, y, mode)

    def additive(name, x, y, w, h, tint=(1, 1, 1), strength=1.0, crop=None):
        path = os.path.join(TEX_DIR, name + ".png")
        if not os.path.exists(path):
            return
        tex = Image.open(path).convert("RGBA")
        if crop:
            tex = tex.crop(crop)
        src = gl_draw(tex, w, h)
        blit(canvas, src, int(round(x)), int(round(y)), "add", tint, strength)

    layer("sky")
    layer("clouds")
    layer("mountains_far")
    layer("hills")
    layer("capital")
    additive("capital_lights", -96, 360, 2112, 760)
    additive("capital_lights2", -96, 360, 2112, 760)
    layer("mist")
    layer("foreground")
    if with_effects:
        rng = random.Random(5)
        additive("glow", FIRE[0] - 260, FIRE[1] - 330, 520, 520, (1.0, 0.55, 0.22), 0.55)
        additive("glow", FIRE[0] - 90, FIRE[1] - 150, 180, 180, (1.0, 0.75, 0.4), 0.5)
        additive("flame", FIRE[0] - 42, FIRE[1] - 140, 84, 140, crop=(0, 0, 96, 160))
        for _ in range(30):
            t = rng.random()
            ex = FIRE[0] + rng.gauss(0, 18) + (t ** 1.5) * rng.uniform(-40, 70)
            ey = FIRE[1] - 70 - t * 380
            s = rng.uniform(5, 11) * (1 - 0.5 * t)
            additive("ember", ex - s / 2, ey - s / 2, s, s, (1.0, 0.7, 0.35), 1.0 - 0.6 * t)
        for _ in range(14):
            sx, sy = rng.uniform(80, 1840), rng.uniform(20, 330)
            if 560 < sx < 1360 and sy > 60:
                continue
            s = rng.uniform(9, 16)
            additive("star", sx - s / 2, sy - s / 2, s, s, (0.85, 0.9, 1.0), rng.uniform(0.4, 0.9))
    layer("vignette")
    logo = os.path.join(TEX_DIR, "logo.png")
    if os.path.exists(logo):
        additive("logo_glow", 480, 90, 960, 204, (1, 1, 1), 0.9)
        blit(canvas, gl_draw(Image.open(logo), 960, 204), 480, 90)
    if buttons:
        for i in range(5):
            y = 370 + i * 72
            box = np.zeros((60, 600, 4))
            box[..., :3] = (18 / 255, 16 / 255, 22 / 255)
            box[..., 3] = 0.6
            box[0, :, :3] = box[-1, :, :3] = box[:, 0, :3] = box[:, -1, :3] = (152 / 255, 120 / 255, 58 / 255)
            box[0, :, 3] = box[-1, :, 3] = box[:, 0, 3] = box[:, -1, 3] = 0.9
            blit(canvas, box, 660, y)
    return Image.fromarray(quantize(canvas), "RGB")


def logo_preview():
    path = os.path.join(TEX_DIR, "logo.png")
    if not os.path.exists(path):
        return None
    logo = Image.open(path)
    glowp = os.path.join(TEX_DIR, "logo_glow.png")
    W, H = 1400, 70 + 136 + 204 + 272 + 80
    canvas = np.zeros((H, W, 3))
    canvas[:] = rgb((30, 22, 58))
    y = 20
    for scale in (2, 3, 4):
        w, h = 320 * scale, 68 * scale
        x = (W - w) // 2
        if os.path.exists(glowp):
            blit(canvas, gl_draw(Image.open(glowp), w, h), x, y, "add", (1, 1, 1), 0.9)
        blit(canvas, gl_draw(logo, w, h), x, y)
        y += h + 20
    return Image.fromarray(quantize(canvas), "RGB")


# ---------------------------------------------------------------- main
BUILDERS = {}


def main():
    global TEX_DIR
    argv = sys.argv[1:]
    only = None
    if "--only" in argv:
        i = argv.index("--only")
        only = set(argv[i + 1].split(","))
        argv = argv[:i] + argv[i + 2:]
    args = argv
    assets = args[0]
    preview = args[1] if len(args) > 1 else None
    TEX_DIR = os.path.join(assets, "textures/gui/title")
    os.makedirs(TEX_DIR, exist_ok=True)
    builders = [("sky", sky), ("clouds", clouds), ("mountains_far", mountains_far), ("hills", hills), ("capital", capital),
                ("mist", mist), ("foreground", foreground), ("vignette", vignette), ("flame", flame), ("glow", glow_sprite),
                ("ember", ember_sprite), ("star", star_sprite), ("smoke", smoke_sprite), ("logo", logo)]
    for name, fn in builders:
        if only is None or name in only:
            fn()
    if preview:
        os.makedirs(preview, exist_ok=True)
        compose(True).save(os.path.join(preview, "title_preview.png"), optimize=True)
        compose(False).save(os.path.join(preview, "title_preview_clean.png"), optimize=True)
        lp = logo_preview()
        if lp is not None:
            lp.save(os.path.join(preview, "title_logo_preview.png"), optimize=True)
    total = sum(os.path.getsize(os.path.join(TEX_DIR, f)) for f in os.listdir(TEX_DIR) if f.endswith(".png"))
    print("title textures: %d bytes" % total)


if __name__ == "__main__":
    main()
