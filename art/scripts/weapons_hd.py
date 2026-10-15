"""Guildmark's own weapons at 32x, painted with the HD art kit (hdkit.py) in the dark-fantasy look, and their item
models, whose hand transforms weaponkit.py solves so each sits in the fist where a sword (or the mace) does.

Four families, each in iron, diamond and netherite: the greatsword (a two-handed blade with a long ricasso),
the dagger (a leaf blade), the sabre (curved, single-edged, with a knuckle bow) and the war hammer (a long haft,
a blocky two-faced head and a spike).

    python3 -B art/scripts/weapons_hd.py src/main/resources/assets/guildmark [preview-dir]

Writes the textures, the item models and the item definitions. Everything is seeded, so a rerun gives the same files.
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import (PAL, GEMS, Canvas, grid, vnoise, tone, mix, disc, light, rim, outline, glint, sheet, grow)
from hdkit import bands as _bands
import weaponkit

N = 32
ROOT2 = math.sqrt(2.0)
XS, YS = grid(N)
# Every pixel's place along the sprite's diagonal (s, from the bottom-left corner) and across it (w, + is up-left)
S_ = (XS + (N - YS)) / ROOT2
W_ = (-XS - (YS - N)) / ROOT2

# Tier tones: blade metal, fittings, grip wrap, an accent
DIAMOND = [(12, 22, 32), (22, 44, 58), (36, 76, 90), (58, 118, 128), (102, 172, 174), (176, 228, 220)]
NETHERITE = [(12, 9, 13), (25, 20, 24), (40, 33, 37), (57, 48, 51), (78, 67, 67), (104, 92, 88)]
TIERS = {
    'iron': {'blade': PAL['steel'], 'fit': PAL['iron'], 'grip': PAL['leather'], 'accent': PAL['brass'], 'wood': PAL['wood']},
    'diamond': {'blade': DIAMOND, 'fit': PAL['gold'], 'grip': PAL['blackleather'], 'accent': GEMS['frost'], 'wood': PAL['darkwood']},
    'netherite': {'blade': NETHERITE, 'fit': PAL['blacksteel'], 'grip': PAL['crimson'], 'accent': PAL['ember'], 'wood': PAL['darkwood']},
}


def bands(stops, t, dither=0.35):
    """hdkit's bands, taking a plain number for a flat tone too."""
    return _bands(stops, np.broadcast_to(np.asarray(t, float), (N, N)), dither)


def along(s0, s1):
    return (S_ >= s0) & (S_ < s1)


def lerp_w(s, pts):
    """A half-width that runs through [(s, w), ...] along the blade."""
    xs, ws = zip(*pts)
    return np.interp(s, xs, ws)


def ridge_shade(dw, half, k=1.0):
    """A blade's diamond section: the up-left facet lit, the down-right one in shade, a bright ridge between."""
    h = np.clip(1 - np.abs(dw) / np.maximum(half, 0.3), 0, 1)
    return light(h * 2.6 * k, 1.0), h


def wrap(cv, mask, pal, s0, pitch=2.2, seed=1):
    """A leather or cord wrap: diagonal turns across the grip, each turn lit on its upper edge."""
    phase = ((S_ - s0) / pitch) % 1.0
    t = 0.38 + 0.32 * (phase < 0.5) - 0.18 * (phase > 0.85) + 0.18 * (W_ > 0.3) - 0.22 * (W_ < -0.5)
    t = t + 0.06 * (vnoise(N, N, 1.5, seed) - 0.5)
    cv.put(mask, bands(pal, t, 0.2))


def metal(cv, mask, pal, base=0.5, shade=None, k=1.0):
    """A fitting: lit on its up-left side, darker down-right."""
    t = base + 0.22 * np.clip(W_ / 3, -1, 1) * k + (0 if shade is None else shade)
    cv.put(mask, bands(pal, t, 0.25))


def finish(cv, tier, glints=()):
    rim(cv, 0.28)
    outline(cv, 0.8)
    for x, y, size in glints:
        glint(cv, x, y, size, (255, 250, 236) if tier != 'diamond' else (236, 255, 252))
    return cv.image()


# ================================================================ greatsword

def greatsword(tier):
    t = TIERS[tier]
    cv = Canvas(N)
    # Blade: broad at the lugs, a long gentle taper, then the point
    half = lerp_w(S_, [(17, 2.25), (30, 2.0), (39, 1.75), (42.5, 1.15), (44.6, 0.0)])
    blade = along(17, 44.8) & (np.abs(W_) <= half)
    lit, h = ridge_shade(W_, half)
    bt = 0.48 + 0.9 * lit + 0.22 * h + 0.04 * (vnoise(N, N, 3, 11) - 0.5)
    # The fuller: a groove down the first two thirds, dark with a lit lower lip
    fuller = along(18.5, 35) & (np.abs(W_ + 0.15) < 0.62)
    bt = np.where(fuller, 0.22 + 0.12 * (W_ < -0.2), bt)
    # Edges: a pale bevel line on both sides
    edge_line = blade & (np.abs(W_) > half - 0.75)
    bt = np.where(edge_line & (W_ > 0), 0.88, np.where(edge_line, 0.5, bt))
    cv.put(blade, bands(t['blade'], bt))
    if tier == 'netherite':
        # Ember light caught in the fuller
        cv.put(fuller & along(19, 33) & (np.abs(W_ + 0.15) < 0.35), bands(t['accent'], 0.3 + 0.4 * ((S_ - 19) / 14), 0.0))
    # Ricasso and its parrying lugs
    ricasso = along(13.4, 17) & (np.abs(W_) <= 1.25)
    metal(cv, ricasso, t['blade'], 0.42)
    lugs = along(16.2, 17.6) & (np.abs(W_) <= 2.85) & ~blade
    metal(cv, lugs, t['fit'], 0.55)
    # Crossguard: long quillons swept slightly toward the blade, knobbed ends
    sweep = 0.07 * W_ * W_
    guard = (np.abs(S_ - 12.5 - sweep) < 1.15) & (np.abs(W_) <= 6.4)
    metal(cv, guard, t['fit'], 0.52, 0.15 * (S_ - 12.5 - sweep < 0))
    for side in (1, -1):
        cx, cy = at_px(12.5 + 0.07 * 36, 6.4 * side)
        knob, d = disc(cx, cy, 1.25, N)
        cv.put(knob, bands(t['fit'], 0.45 + 0.9 * light(d * 2, 1.0) + 0.15 * d))
    # The long grip for both hands, wrapped
    grip = along(4.0, 11.6) & (np.abs(W_) <= 1.15)
    wrap(cv, grip, t['grip'], 4.0, 2.0, 21)
    collar = along(11.0, 11.8) & (np.abs(W_) <= 1.45)
    metal(cv, collar, t['fit'], 0.5)
    # Pommel: a heavy wheel
    px, py = at_px(2.55, 0)
    pom, d = disc(px, py, 2.05, N)
    cv.put(pom, bands(t['fit'], 0.42 + 1.1 * light(d * 2.2, 1.0) + 0.22 * d))
    if tier == 'diamond':
        gem, gd = disc(px, py, 0.9, N)
        cv.put(gem, bands(t['accent'], 0.45 + 0.5 * gd, 0))
    gx, gy = at_px(33, 1.0)
    return finish(cv, tier, [(int(gx), int(gy), 2)])


def at_px(s, w):
    """Pixel position (x, y down) of the sprite point s along the diagonal, w across it."""
    x = s / ROOT2 - w / ROOT2
    y = N - (s / ROOT2 + w / ROOT2)
    return x, y


# ================================================================ dagger

def dagger(tier):
    t = TIERS[tier]
    cv = Canvas(N)
    # A leaf blade: swelling past the guard, then a long fine point
    half = lerp_w(S_, [(16.6, 1.25), (20, 2.2), (24, 2.6), (30, 1.9), (35.5, 0.9), (38.4, 0.0)])
    blade = along(16.6, 38.6) & (np.abs(W_) <= half)
    lit, h = ridge_shade(W_, half, 1.2)
    bt = 0.5 + 0.95 * lit + 0.25 * h + 0.04 * (vnoise(N, N, 3, 31) - 0.5)
    edge_line = blade & (np.abs(W_) > half - 0.7)
    bt = np.where(edge_line & (W_ > 0), 0.9, np.where(edge_line, 0.52, bt))
    cv.put(blade, bands(t['blade'], bt))
    if tier == 'netherite':
        cv.put(blade & along(18, 30) & (np.abs(W_) < 0.3), bands(t['accent'], 0.35 + 0.35 * ((S_ - 18) / 12), 0.0))
    # A short crossguard with turned-down tips, and a ring on its face
    sweep = 0.12 * W_ * W_
    guard = (np.abs(S_ - 15.8 - sweep) < 0.85) & (np.abs(W_) <= 3.9)
    metal(cv, guard, t['fit'], 0.52)
    rx, ry = at_px(15.8, 0)
    rr = np.hypot(XS - rx, YS - ry)
    ring = (np.abs(rr - 1.55) < 0.55)
    cv.put(ring, bands(t['fit'], 0.45 + 0.3 * (YS < ry) - 0.2 * (XS > rx)))
    grip = along(10.2, 15.2) & (np.abs(W_) <= 1.05)
    wrap(cv, grip, t['grip'], 10.2, 1.7, 33)
    # Pommel: a small faceted knob
    px, py = at_px(9.15, 0)
    pom, d = disc(px, py, 1.6, N)
    cv.put(pom, bands(t['fit'], 0.45 + 1.0 * light(d * 2, 1.0) + 0.2 * d))
    if tier == 'diamond':
        cv.px(int(px), int(py), np.array(t['accent'][3]))
    gx, gy = at_px(25, 1.2)
    return finish(cv, tier, [(int(gx), int(gy), 1)])


# ================================================================ sabre

SABRE_HILT_W = 1.5
SABRE_CURVE = (11.0, 5.0 / (40.6 - 11.0) ** 2)


def sabre_centre(s):
    """The blade's middle line: straight out of the hilt, bending back toward the spine (-w) more and more."""
    s0, k = SABRE_CURVE
    return SABRE_HILT_W - k * np.clip(s - s0, 0, None) ** 2


def sabre(tier):
    t = TIERS[tier]
    cv = Canvas(N)
    c = sabre_centre(S_)
    dw = W_ - c
    # Widening a little toward the tip, then a clipped point; the edge on the outside of the curve (+w)
    half = lerp_w(S_, [(11.6, 1.4), (28, 1.75), (35, 1.9), (38.6, 1.4), (40.8, 0.0)])
    blade = along(11.6, 41) & (np.abs(dw) <= half)
    # The false edge: the back of the last part is ground away at an angle
    back_cut = (S_ > 35.5) & (dw < -half + (S_ - 35.5) * 0.3)
    blade = blade & ~back_cut
    # Single edge: a broad bevel down to a bright edge on +w, a thick spine on -w
    edge_side = dw > 0.2
    bt = np.where(edge_side, 0.6 + 0.28 * (dw / np.maximum(half, 0.3)), 0.4 - 0.12 * (dw < -half + 0.8))
    bt = bt + 0.04 * (vnoise(N, N, 3, 41) - 0.5)
    edge_line = blade & (dw > half - 0.7)
    bt = np.where(edge_line, 0.92, bt)
    cv.put(blade, bands(t['blade'], bt))
    spine = blade & (dw < -half + 0.65)
    cv.put(spine, bands(t['blade'], 0.26 + 0.1 * (S_ < 25)))
    if tier == 'netherite':
        cv.put(blade & along(13, 28) & (np.abs(dw + 0.25) < 0.3), bands(t['accent'], 0.32 + 0.3 * ((S_ - 13) / 15), 0.0))
    # A small cup guard and the knuckle bow sweeping from it to the pommel on the edge side
    hw = W_ - SABRE_HILT_W
    guard = (np.abs(S_ - 10.6) < 0.9) & (hw <= 3.3) & (hw >= -2.4)
    metal(cv, guard, t['fit'], 0.52)
    bow_w = 3.0 - 2.0 * np.clip((10.4 - S_) / 6.4, 0, 1) ** 2
    bow = along(4.6, 10.4) & (np.abs(hw - bow_w) < 0.62)
    metal(cv, bow, t['fit'], 0.58, 0.12 * (hw > bow_w))
    grip = along(3.6, 10.0) & (np.abs(hw) <= 1.0)
    wrap(cv, grip, t['grip'], 3.6, 1.5, 43)
    cap = along(1.8, 3.8) & (np.abs(hw) <= 1.25)
    metal(cv, cap, t['fit'], 0.5)
    if tier == 'diamond':
        gx_, gy_ = at_px(10.6, SABRE_HILT_W + 0.2)
        cv.px(int(gx_), int(gy_), np.array(t['accent'][4]))
    gx, gy = at_px(29, sabre_centre(29) + 1.0)
    return finish(cv, tier, [(int(gx), int(gy), 1)])


# ================================================================ war hammer

def war_hammer(tier):
    t = TIERS[tier]
    cv = Canvas(N)
    # The haft: dark wood, a leather grip at the butt end for both hands, iron langets below the head
    haft = along(1.2, 30.0) & (np.abs(W_) <= 1.05)
    grain = vnoise(N, N, 2.5, 51)
    cv.put(haft, bands(t['wood'], 0.42 + 0.3 * (W_ > 0.2) - 0.2 * (W_ < -0.5) + 0.12 * (grain - 0.5)))
    grip = along(2.6, 11.6) & (np.abs(W_) <= 1.2)
    wrap(cv, grip, t['grip'], 2.6, 1.9, 53)
    langets = along(22.0, 30.0) & (np.abs(W_) <= 1.25) & (np.abs(W_) > 0.45)
    metal(cv, langets, t['fit'], 0.48)
    rivets = langets & (np.abs(((S_ - 22.0) % 2.7) - 1.35) < 0.45) & (np.abs(np.abs(W_) - 0.85) < 0.4)
    cv.put(rivets, bands(t['fit'], 0.8))
    butt = along(0.6, 2.7) & (np.abs(W_) <= 1.35)
    metal(cv, butt, t['fit'], 0.45)
    # The head: a long two-faced block across the haft, the faces flared a little like a sledge's, banded
    flare = 0.55 * np.clip(np.abs(W_) - 5.4, 0, None)
    block = (S_ >= 30.0 - flare) & (S_ < 36.2 + flare) & (np.abs(W_) <= 7.6)
    lit = light(np.clip(1 - np.abs(S_ - 33.1) / 4.0, 0, 1) * 1.5, 1.0)
    cv.put(block, bands(t['blade'], 0.46 + 0.75 * lit + 0.1 * (W_ > 0) + 0.04 * (vnoise(N, N, 2, 57) - 0.5)))
    for side in (1, -1):
        face = block & (W_ * side > 6.7)
        cv.put(face, bands(t['blade'], (0.82 if side > 0 else 0.5) - 0.15 * (S_ < 30.4) - 0.12 * (S_ > 35.8)))
        neck = block & (np.abs(W_ * side - 5.0) < 0.5)
        cv.put(neck, bands(t['blade'], 0.24))
    head = block
    band = block & (np.abs(np.abs(W_) - 2.9) < 0.7)
    cv.put(band, bands(t['fit'], 0.55 + 0.2 * (S_ > 33) + 0.1 * (W_ > 0)))
    socket = along(28.4, 37.2) & (np.abs(W_) <= 1.6)
    metal(cv, socket, t['fit'], 0.45, 0.1 * (S_ > 33))
    # The top spike
    spike_half = lerp_w(S_, [(36.6, 1.4), (40.6, 0.0)])
    spike = along(36.6, 40.8) & (np.abs(W_) <= spike_half)
    sl, sh = ridge_shade(W_, spike_half)
    cv.put(spike, bands(t['blade'], 0.5 + 0.9 * sl + 0.2 * sh))
    if tier == 'diamond':
        gx_, gy_ = at_px(33.1, 0)
        stone, sd = disc(gx_, gy_, 1.05, N)
        cv.put(stone, bands(t['accent'], 0.4 + 0.55 * sd, 0))
    if tier == 'netherite':
        cv.put(block & (np.abs(S_ - 33.1) < 0.5) & (np.abs(W_) > 3.8) & (np.abs(W_) < 4.6), bands(t['accent'], 0.45, 0.0))
    gx, gy = at_px(31.0, 6.0)
    return finish(cv, tier, [(int(gx), int(gy), 1)])


# ================================================================ main

FAMILIES = {'greatsword': (greatsword, weaponkit.GREATSWORD), 'dagger': (dagger, weaponkit.DAGGER),
            'sabre': (sabre, weaponkit.SABRE), 'war_hammer': (war_hammer, weaponkit.WAR_HAMMER)}


def build():
    out = {}
    for family, (paint, geo) in FAMILIES.items():
        for tier in TIERS:
            out[f'{tier}_{family}'] = (paint(tier), geo)
    return out


def main():
    assets = sys.argv[1]
    previews = sys.argv[2] if len(sys.argv) > 2 else None
    made = build()
    for name, (img, geo) in made.items():
        tex = os.path.join(assets, 'textures', 'item', name + '.png')
        os.makedirs(os.path.dirname(tex), exist_ok=True)
        img.save(tex)
        mod = os.path.join(assets, 'models', 'item', name + '.json')
        os.makedirs(os.path.dirname(mod), exist_ok=True)
        with open(mod, 'w') as f:
            json.dump(weaponkit.model('guildmark:item/' + name, geo), f, indent=2)
            f.write('\n')
        item = os.path.join(assets, 'items', name + '.json')
        os.makedirs(os.path.dirname(item), exist_ok=True)
        with open(item, 'w') as f:
            json.dump({'model': {'type': 'minecraft:model', 'model': 'guildmark:item/' + name}}, f, indent=2)
            f.write('\n')
        print(tex)
    if previews:
        os.makedirs(previews, exist_ok=True)
        names = list(made)
        sheet([made[n][0] for n in names], scale=8, cols=3, labels=names).save(os.path.join(previews, 'weapons.png'))


if __name__ == '__main__':
    main()
