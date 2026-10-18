"""Guildmark's screen and HUD sprites at twice the detail. The 1x designs live in art/ui_1x/ (ui_art.py and
hud_art.py draw most of them; the ledger and tracker ones were drawn by hand); this script remasters each at 2x:
Scale2x for clean diagonals, outlines thinned back to one fine line, a bevel of light from the upper left on
every shape, and grain on the iron and gold. The frames, buttons and bars are repainted outright with the HD
art kit, keeping their layout. Sprites keep their .mcmeta: nine-slice sizes and borders stay in screen pixels,
so the game draws the finer textures at the same size.

    python3 -B art/scripts/ui_hd.py src/main/resources/assets/guildmark [preview-dir]
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import PAL, Canvas, grid, vnoise, fbm, rand, tone, mix, ramp, bands, rect, disc, sheet
import ui_art

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'ui_1x')
K = 2


# ================================================================ remastering a 1x sprite

def scale2x(a):
    """EPX: each pixel becomes four, and a corner takes its neighbours' colour where they agree, so steps
    along a diagonal turn into a clean line instead of a staircase of big blocks."""
    h, w = a.shape[:2]
    key = (a[..., 0].astype(np.int64) << 24) | (a[..., 1].astype(np.int64) << 16) | (a[..., 2].astype(np.int64) << 8) | a[..., 3]
    pad = np.pad(key, 1, mode='edge')
    P = key
    A, B, C, D = pad[:-2, 1:-1], pad[1:-1, 2:], pad[1:-1, :-2], pad[2:, 1:-1]      # up, right, left, down
    out_k = np.zeros((h * 2, w * 2), np.int64)
    e0 = np.where((C == A) & (C != D) & (A != B), A, P)
    e1 = np.where((A == B) & (A != C) & (B != D), B, P)
    e2 = np.where((D == C) & (D != B) & (C != A), C, P)
    e3 = np.where((B == D) & (B != A) & (D != C), D, P)
    out_k[0::2, 0::2], out_k[0::2, 1::2], out_k[1::2, 0::2], out_k[1::2, 1::2] = e0, e1, e2, e3
    out = np.zeros((h * 2, w * 2, 4), np.uint8)
    out[..., 0] = (out_k >> 24) & 255
    out[..., 1] = (out_k >> 16) & 255
    out[..., 2] = (out_k >> 8) & 255
    out[..., 3] = out_k & 255
    return out


def _nb(m, dx, dy, fill=False):
    h, w = m.shape
    out = np.full_like(m, fill)
    ys0, ys1 = max(0, -dy), h - max(0, dy)
    xs0, xs1 = max(0, -dx), w - max(0, dx)
    out[ys0:ys1, xs0:xs1] = m[ys0 + dy:ys1 + dy, xs0 + dx:xs1 + dx]
    return out


def remaster(img, seed=0, bevel=True, grain=0.05, thin=True):
    a = np.asarray(img.convert('RGBA')).copy()
    src_solid = a[..., 3] > 0
    lum = a[..., :3].astype(float) @ [0.3, 0.59, 0.11]
    # The 1x outline: dark pixels with see-through beside them
    see = ~src_solid
    near_clear = _nb(see, 1, 0, True) | _nb(see, -1, 0, True) | _nb(see, 0, 1, True) | _nb(see, 0, -1, True)
    big = scale2x(a)
    cv = Canvas.of(Image.fromarray(big, 'RGBA'))
    solid = cv.a > 0.02
    blum = cv.rgb @ [0.3, 0.59, 0.11]
    is_line = solid & (blum < 34)
    clear = ~solid
    outer = _nb(clear, 1, 0, True) | _nb(clear, -1, 0, True) | _nb(clear, 0, 1, True) | _nb(clear, 0, -1, True)
    # Thin each outline back to one texel: an outline texel with no see-through beside it, next to a coloured
    # one, takes that colour
    inner_line = is_line & ~outer if thin else np.zeros_like(is_line)
    body = solid & ~is_line
    rgb = cv.rgb.copy()
    alpha = cv.a.copy()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        take = inner_line & _nb(body, dx, dy)
        src_rgb = np.stack([_nb(cv.rgb[..., c], dx, dy, 0) for c in range(3)], -1)
        src_a = _nb(cv.a, dx, dy, 0)
        rgb[take] = src_rgb[take]
        alpha[take] = src_a[take]
        inner_line &= ~take
    cv.rgb, cv.a = rgb, alpha
    if bevel:
        solid = cv.a > 0.02
        blum = cv.rgb @ [0.3, 0.59, 0.11]
        body = solid & (blum >= 34)
        stop = ~body
        lit = body & (_nb(stop, 0, -1, True) | _nb(stop, -1, 0, True))
        dark = body & (_nb(stop, 0, 1, True) | _nb(stop, 1, 0, True)) & ~lit
        cv.rgb[lit] = tone(cv.rgb[lit], 0.16)
        cv.rgb[dark] = tone(cv.rgb[dark], -0.2)
    if grain:
        n = (rand(cv.w, cv.h, seed) - 0.5) * 2 * grain
        body = (cv.a > 0.02)
        cv.rgb[body] = np.clip(cv.rgb[body] * (1 + n[body, None]), 0, 255)
    return cv.image()


# ================================================================ repainted outright

OUT = (12, 11, 14)
IRON = PAL['iron']
GOLD = PAL['gold']


def frame(w, h, layers, fill, fill_alpha, seed, corner=None):
    """A bordered box at 2x. `layers` run from the outside in, each (thickness in texels, lit colour, dark
    colour, alpha): the lit colour on the top and left sides, the dark on the bottom and right, and a mitred
    corner between them. The fill gets a faint grain and darkens toward its edges."""
    W, H = w * K, h * K
    xs, ys = grid(W, H)
    x, y = xs - 0.5, ys - 0.5
    dt, db, dl, dr = y, H - 1 - y, x, W - 1 - x
    d = np.minimum(np.minimum(dt, db), np.minimum(dl, dr))
    lit_side = (np.minimum(dt, dl) <= np.minimum(db, dr)) & ~((db < dt) & (dl > db)) & ~((dr < dl) & (dt > dr))
    lit_side = np.where(dt == d, (dt <= dr), np.where(dl == d, dl <= db, False))
    cv = Canvas(W, H)
    n = rand(W, H, seed)
    grain = vnoise(W, H, 3, seed + 1)
    start = 0
    for (th, lit, dark, a) in layers:
        m = (d >= start) & (d < start + th)
        col = np.where(lit_side[..., None], np.asarray(lit, float), np.asarray(dark, float))
        col = col * (1 + (grain[..., None] - 0.5) * 0.12 + (n[..., None] - 0.5) * 0.06)
        cv.put(m, col, a / 255)
        start += th
    inner = d >= start
    depth = np.clip((d - start) / 6, 0, 1)
    fc = np.asarray(fill, float) * (0.82 + 0.18 * depth[..., None]) * (1 + (grain[..., None] - 0.5) * 0.08)
    cv.put(inner, fc, fill_alpha / 255)
    # Rounded outer corners
    for cx, cy in ((0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)):
        cv.a[cy, cx] = 0
    if corner:
        corner(cv, W, H)
    return cv


def boss_corners(size, inset):
    """A gilt rivet on each corner of the iron."""
    def paint(cv, W, H):
        for (cx, cy) in ((inset, inset), (W - 1 - inset, inset), (inset, H - 1 - inset), (W - 1 - inset, H - 1 - inset)):
            m, d = disc(cx + 0.5, cy + 0.5, size, W, H)
            xs, ys = grid(W, H)
            t = 0.5 + 0.5 * d - 0.35 * ((xs - cx) + (ys - cy) > 0)
            cv.put(m, bands(GOLD, t, 0), 1.0)
            cv.px(int(cx - size * 0.35), int(cy - size * 0.35), GOLD[5])
    return paint


def ornate_corners(cv, W, H):
    """Gilt corner brackets over the iron, each an L with a bevel, ending in a small spur, and a rivet
    where the arms meet."""
    xs, ys = grid(W, H)
    for sx, sy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        ox = 1 if sx > 0 else W - 2
        oy = 1 if sy > 0 else H - 2
        u = (xs - 0.5 - ox) * sx          # along the corner's own axes, 0 at the corner
        v = (ys - 0.5 - oy) * sy
        arm = ((u >= 0) & (v >= 0)) & (((u < 11) & (v < 3)) | ((v < 11) & (u < 3)))
        spur = ((u >= 11) & (u < 13) & (v >= 1) & (v < 2)) | ((v >= 11) & (v < 13) & (u >= 1) & (u < 2))
        m = arm | spur
        edge_lit = m & ((u < 0.6) | (v < 0.6))
        edge_dark = m & (((u >= 2) & (u < 3) & (v >= 3)) | ((v >= 2) & (v < 3) & (u >= 3)))
        t = np.where(edge_lit, 0.92, np.where(edge_dark, 0.3, 0.62))
        if sx < 0 or sy < 0:
            t = np.where(edge_lit, 0.5, t)
        cv.put(m, bands(GOLD, t, 0), 1.0)
        r, d = disc(ox + sx * 1.5 + 0.5, oy + sy * 1.5 + 0.5, 1.7, W, H)
        cv.put(r, bands(GOLD, 0.45 + 0.55 * d, 0), 1.0)


def iron_frame_layers(gold=True, fat=2):
    L = [(1, OUT, OUT, 235), (fat, IRON[4], IRON[2], 255), (fat, IRON[3], IRON[2], 255), (1, IRON[1], IRON[1], 255),
         (1, OUT, OUT, 240)]
    if gold:
        L += [(1, GOLD[5], GOLD[2], 255), (1, GOLD[3], GOLD[2], 255)]
    return L


def panel(gold=True, corners=True, w=32, h=32, fill=(17, 15, 19), fa=232, seed=1):
    return frame(w, h, iron_frame_layers(gold), fill, fa, seed, ornate_corners if corners else None).image()


def button(fill, line, hi, lo, fa, seed):
    layers = [(1, OUT, OUT, 240), (2, hi, lo, 255), (1, IRON[0], IRON[0], 255), (1, line, tone(line, -0.35), 255)]
    return frame(32, 20, layers, fill, fa, seed).image()


def inset(w, h, fill, fa, seed):
    layers = [(1, OUT, IRON[1], 240), (1, (6, 6, 8), IRON[0], 230)]
    return frame(w, h, layers, fill, fa, seed).image()


def socket(seed):
    layers = [(1, OUT, OUT, 240), (2, IRON[1], IRON[3], 255), (1, (8, 8, 10), (8, 8, 10), 240)]
    cv = frame(18, 18, layers, (12, 11, 14), 226, seed)
    for (x, y) in ((3, 3), (32, 3), (3, 32), (32, 32)):
        cv.put(rect(x, y, x + 1, y + 1, 36), GOLD[3])
    return cv.image()


def bar_frame(w, h, seed, rivets=True):
    """A bar's iron casing with a gold line inside, around a dark see-through slot."""
    layers = [(1, OUT, OUT, 235), (2, IRON[4], IRON[1], 255), (1, IRON[2], IRON[1], 255), (1, GOLD[5], GOLD[2], 255)]
    cv = frame(w, h, layers, (8, 7, 10), 150, seed)
    if rivets:
        W, H = w * K, h * K
        for cx in (2.5, W - 3.5):
            m, d = disc(cx + 0.5, H / 2, 1.6, W, H)
            cv.put(m, bands(GOLD, 0.4 + 0.6 * d, 0), 1.0)
    return cv.image()


def bar_fill(top, mid, bot, w, h):
    """A meter's fill: a bright top edge, the colour, a shadowed underside, and a faint sheen; stretched sideways,
    so it changes only from top to bottom."""
    W, H = w * K, h * K
    cv = Canvas(W, H)
    stops = [top, mid, mid, bot]
    for yy in range(H):
        t = yy / max(1, H - 1)
        c = ramp([tone(top, 0.25), top, mid, mid, bot], t)
        cv.put(rect(0, yy, W, yy + 1, W, H), c)
    return cv.image()


def strip(w, h):
    """The compass band at 2x: a gold rule above and below, a dark glass between, fading out at both ends."""
    W, H = w * K, h * K
    xs, ys = grid(W, H)
    t = np.abs(xs - W / 2) / (W / 2)
    fade = np.clip(1 - t ** 2.6, 0, 1)
    cv = Canvas(W, H)
    yy = ys - 0.5
    rows = np.select([yy < 1, yy < 2, yy < 3, yy > H - 2, yy > H - 3, yy > H - 4],
                     [0, 1, 2, 3, 4, 2], 5)
    cols = np.array([GOLD[5], GOLD[3], OUT, GOLD[2], GOLD[3], OUT, (12, 11, 14)], float)
    al = np.array([225, 225, 185, 225, 225, 185, 150], float)
    cv.rgb = cols[rows]
    cv.a = al[rows] / 255 * fade
    return cv.image()


# ================================================================ main

HUD_FILLS = {
    'bar_health': ((186, 52, 46), (146, 30, 32), (98, 18, 24), 16, 3),
    'bar_focus': ((78, 110, 206), (48, 74, 168), (30, 44, 114), 16, 3),
    'bar_stamina': ((110, 172, 84), (72, 136, 58), (44, 92, 40), 16, 3),
    'bar_trail': ((244, 226, 160), (216, 192, 118), (172, 146, 84), 16, 3),
    'bar_poison': ((178, 192, 70), (138, 152, 44), (92, 104, 30), 16, 3),
    'bar_wither': ((104, 98, 100), (60, 56, 60), (32, 30, 34), 16, 3),
    'boss_fill': ((176, 48, 44), (140, 28, 30), (118, 22, 26), 16, 5),
    'boss_trail': ((244, 226, 160), (216, 192, 118), (176, 150, 88), 16, 5),
    'enemy_fill': ((178, 50, 44), (138, 30, 32), (92, 18, 24), 8, 3),
}


def repainted():
    out = {
        'ui/panel': panel(),
        'ui/panel_plain': panel(gold=False, corners=False, w=24, h=24, fill=(9, 8, 11), fa=214, seed=2),
        'ui/inset': inset(16, 16, (10, 9, 12), 196, 3),
        'ui/slot': inset(18, 18, (10, 9, 12), 210, 7),
        'ui/socket': socket(8),
        'ui/button': button((36, 33, 38), GOLD[2], IRON[3], IRON[1], 240, 4),
        'ui/button_highlighted': button((58, 50, 42), GOLD[4], IRON[4], IRON[2], 245, 5),
        'ui/button_disabled': button((24, 23, 26), IRON[1], IRON[1], IRON[0], 220, 6),
        'hud/bar_frame': bar_frame(32, 9, 9),
        'hud/boss_frame': bar_frame(64, 11, 10),
        'compass/strip': strip(96, 13),
        'tracker/panel': panel(w=32, h=32, seed=12),
        # Drawn smooth by ui_art at any size: just ask for twice as many pixels
        'ui/glow': ui_art.glow(128, (226, 176, 92), 92),
        'map/rose': ui_art.rose(25),
        'map/frame': ui_art.vignette(64, 24),
        'market/shade': ui_art.gradient_band(64, 8, (0, 0, 0, 255), 175, 0, 1.6),
    }
    for name, (top, mid, bot, w, h) in HUD_FILLS.items():
        out['hud/' + name] = bar_fill(top, mid, bot, w, h)
    return out


def build():
    out = {}
    for d in sorted(os.listdir(SRC)):
        for f in sorted(os.listdir(os.path.join(SRC, d))):
            if f.endswith('.png'):
                key = f'{d}/{f[:-4]}'
                # The compass markers are tiny and sit over the world: they keep their full dark outline
                out[key] = remaster(Image.open(os.path.join(SRC, d, f)), seed=len(out), thin=d != 'compass')
    out.update(repainted())
    return out


def main():
    assets = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    imgs = build()
    for key, img in imgs.items():
        img.save(os.path.join(assets, 'textures/gui/sprites', key + '.png'))
    if prev:
        os.makedirs(prev, exist_ok=True)
        keys = list(imgs)
        sheet([imgs[k] for k in keys], 3, 10, labels=[k.split('/')[-1][:16] for k in keys]).save(os.path.join(prev, 'ui_hd.png'))


if __name__ == '__main__':
    main()
