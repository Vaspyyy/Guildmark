"""The older creatures and characters at twice the detail: the Hollow Soldier, the Hollow King, Halvard, the Hill
Troll, the Dusk Rider and his steed, Wren, Tobin, Ilsa, Maren, Pip, the four rival guilds and the Guildmaster.

Each texture ships at twice the size its model declares (the UVs are normalised, so no model changes). It is
remastered from the 1x design its model script paints, one cube face at a time so nothing bleeds between faces:
Scale2x inside the face for clean curves and diagonals, every texel's four pixels shaded by what it's made of
(a bevel on metal plates and rings, a weave in cloth, grain in leather and wood, smooth skin), a lit upper edge
where a lighter piece sits on a darker one, and a little grain. Glow maps follow the same pixels exactly.

    python3 -B art/scripts/creature_hd.py src/main/resources/assets/guildmark [preview-dir]

A texture still at its declared size in the mod is taken as a fresh 1x design (after its model script was rerun)
and copied to art/entity_1x/ first; otherwise the copy there is the source.
"""
import os
import re
import shutil
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from hdkit import tone, rand, sheet

SRC = os.path.normpath(os.path.join(HERE, '..', 'entity_1x'))

# script, declared size, [(texture, [its glow maps])]
CREATURES = [
    ('hollow_soldier', [('entity/hollow_soldier', ['entity/hollow_soldier_glow'])]),
    ('hollow_king', [('entity/hollow_king', ['entity/hollow_king_glow'])]),
    ('fallen_warden', [('entity/fallen_warden', ['entity/fallen_warden_glow', 'entity/fallen_warden_glow2'])]),
    ('hill_troll', [('entity/hill_troll', ['entity/hill_troll_glow'])]),
    ('dusk_rider', [('entity/dusk_rider', ['entity/dusk_rider_glow', 'entity/dusk_rider_glow2'])]),
    ('story_npc', [('entity/character/wren', []), ('entity/character/tobin', []), ('entity/character/ilsa', []),
                   ('entity/character/maren', ['entity/character/maren_glow']), ('entity/character/pip', [])]),
    ('rival', [('entity/rival/iron_hounds', []), ('entity/rival/scarlet_ledger', []), ('entity/rival/gilded_stag', []),
               ('entity/rival/ashen_crows', [])]),
]
# The villager model's profession layer: no model script, so the whole sheet is treated as one face
FLAT = ['entity/villager/profession/guildmaster', 'entity/zombie_villager/profession/guildmaster']


def load_model(script):
    """The script's Model, built and packed, without running the part that writes files."""
    src = open(os.path.join(HERE, script + '.py')).read()
    cut = re.search(r'^(out\b.*=.*sys\.argv|if __name__)', src, re.M)
    ns = {'__name__': 'hd_' + script, '__file__': os.path.join(HERE, script + '.py')}
    exec(compile(src[:cut.start()] if cut else src, script + '.py', 'exec'), ns)
    return ns['model']


def face_rects(model):
    seen = set()
    for c in model.all_cubes():
        for face, (x, y, w, h) in c.rects().items():
            if w > 0 and h > 0 and (x, y, w, h) not in seen:
                seen.add((x, y, w, h))
                yield (x, y, w, h)


# ================================================================ Scale2x as a map of where each pixel comes from

def epx_map(a, wrap=False):
    """For a face's texels, which neighbour each of the 2x pixels copies (dy, dx in -1..1), by Scale2x's rule.
    The face's edge repeats outward, so nothing comes from beyond it; and a see-through texel never spreads over
    a solid one."""
    h, w = a.shape[:2]
    key = (a[..., 0].astype(np.int64) << 24) | (a[..., 1].astype(np.int64) << 16) | (a[..., 2].astype(np.int64) << 8) | a[..., 3]
    pad = np.pad(key, 1, mode='wrap' if wrap else 'edge')
    P = key
    A, B, C, D = pad[:-2, 1:-1], pad[1:-1, 2:], pad[1:-1, :-2], pad[2:, 1:-1]
    solid = a[..., 3] > 0
    dy = np.zeros((h * 2, w * 2), int)
    dx = np.zeros((h * 2, w * 2), int)

    def ok(n):
        return ~solid | ((n & 255) > 0)
    for (oy, ox), cond, (sy, sx) in (
            ((0, 0), (C == A) & (C != D) & (A != B) & ok(A), (-1, 0)),
            ((0, 1), (A == B) & (A != C) & (B != D) & ok(B), (0, 1)),
            ((1, 0), (D == C) & (D != B) & (C != A) & ok(C), (0, -1)),
            ((1, 1), (B == D) & (B != A) & (D != C) & ok(D), (1, 0))):
        dy[oy::2, ox::2] = np.where(cond, sy, 0)
        dx[oy::2, ox::2] = np.where(cond, sx, 0)
    return dy, dx


def apply_map(a, dy, dx, wrap=False):
    h, w = a.shape[:2]
    Y = np.repeat(np.arange(h), 2)[:, None] + dy
    X = np.repeat(np.arange(w), 2)[None, :] + dx
    if wrap:
        return a[Y % h, X % w]
    return a[np.clip(Y, 0, h - 1), np.clip(X, 0, w - 1)]


# ================================================================ what each texel is made of

# How strongly each material's own fine surface shows at 2x, in light steps
DETAIL = {'metal': 0.09, 'dark': 0.05, 'cloth': 0.1, 'leather': 0.09, 'skin': 0.06, 'bright': 0.07}


def streaks(w, h, seed, along_y=True, cell=1.5, length=5):
    """Noise stretched one way: folds and threads in cloth (down), grain in leather (across)."""
    if not along_y:
        return streaks(h, w, seed, True, cell, length).T
    rng = np.random.default_rng(seed)
    gw, gh = max(2, int(w / cell) + 2), max(2, int(h / length) + 2)
    g = rng.random((gh, gw))
    fx = np.arange(w) / cell
    fy = np.arange(h) / length
    x0, y0 = fx.astype(int), fy.astype(int)
    tx, ty = fx - x0, fy - y0
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a = g[y0][:, x0] * (1 - tx) + g[y0][:, x0 + 1] * tx
    b = g[y0 + 1][:, x0] * (1 - tx) + g[y0 + 1][:, x0 + 1] * tx
    return a * (1 - ty[:, None]) + b * ty[:, None]


def mottle(w, h, seed):
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w))
    for cell, amp in ((3.0, 0.6), (1.5, 0.4)):
        out += amp * streaks(w, h, int(rng.integers(1 << 30)), True, cell, cell)
    return out


def material(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx = np.max(rgb, axis=-1)
    mn = np.min(rgb, axis=-1)
    sat = (mx - mn) / np.maximum(mx, 1)
    lum = rgb @ [0.3, 0.59, 0.11]
    warm = (r >= g) & (g >= b)
    m = np.full(lum.shape, 'bright', object)
    m[(sat < 0.22) & (lum >= 52)] = 'metal'
    m[warm & (sat >= 0.18) & (lum < 120)] = 'leather'
    m[warm & (sat >= 0.12) & (sat < 0.6) & (lum >= 120)] = 'skin'
    m[(sat >= 0.45) & ~(warm & (lum < 120))] = 'cloth'
    m[lum < 46] = 'dark'
    return m


def remaster_face(a, glows, seed):
    """One face's texels (and the same face of each glow map) at 2x."""
    dy, dx = epx_map(a)
    big = apply_map(a, dy, dx).astype(float)
    out_glows = [apply_map(g, dy, dx) for g in glows]
    h, w = a.shape[:2]
    rgb = a[..., :3].astype(float)
    mat = np.repeat(np.repeat(material(rgb), 2, 0), 2, 1)
    lum = rgb @ [0.3, 0.59, 0.11]
    W2, H2 = w * 2, h * 2
    rng = np.random.default_rng(seed)
    fields = {
        'metal': mottle(W2, H2, seed + 1) - 0.5,
        'dark': mottle(W2, H2, seed + 2) - 0.5,
        'cloth': streaks(W2, H2, seed + 3, True, 1.6, 6) - 0.5,
        'leather': streaks(W2, H2, seed + 4, False, 1.4, 5) - 0.5,
        'skin': mottle(W2, H2, seed + 5) - 0.5,
        'bright': mottle(W2, H2, seed + 6) - 0.5,
    }
    k = np.zeros((H2, W2))
    for name, amp in DETAIL.items():
        k = np.where(mat == name, fields[name] * 2 * amp, k)
    # Scratches on metal: short bright nicks running down to the right
    for _ in range(max(1, (W2 * H2) // 90)):
        x, y = int(rng.integers(0, W2)), int(rng.integers(0, H2))
        for i in range(int(rng.integers(2, 4))):
            if 0 <= x + i < W2 and 0 <= y + i < H2 and mat[y + i, x + i] == 'metal':
                k[y + i, x + i] += 0.16
    # Where a lighter texel sits on a darker one, its upper pixels catch the light and the darker one's upper
    # pixels sit in its shadow
    up = np.pad(lum, ((1, 0), (0, 0)), mode='edge')[:-1]
    down = np.pad(lum, ((0, 1), (0, 0)), mode='edge')[1:]
    left = np.pad(lum, ((0, 0), (1, 0)), mode='edge')[:, :-1]
    top_hi = np.repeat(np.repeat(np.where(lum - up > 14, 0.12, np.where(up - lum > 14, -0.12, 0.0)), 2, 0), 2, 1)
    k += np.where((np.arange(h * 2) % 2 == 0)[:, None], top_hi, 0)
    bot = np.repeat(np.repeat(np.where(lum - down > 14, -0.1, 0.0), 2, 0), 2, 1)
    k += np.where((np.arange(h * 2) % 2 == 1)[:, None], bot, 0)
    side = np.repeat(np.repeat(np.where(lum - left > 14, 0.08, 0.0), 2, 0), 2, 1)
    k += np.where((np.arange(w * 2) % 2 == 0)[None, :], side, 0)
    solid = big[..., 3] > 0
    big[..., :3] = np.where(solid[..., None], tone(big[..., :3], k), big[..., :3])
    return np.clip(big, 0, 255).round().astype(np.uint8), out_glows


def remaster(tex, glows, rects, seed=0):
    a = np.asarray(tex.convert('RGBA'))
    gs = [np.asarray(g.convert('RGBA')) for g in glows]
    H, W = a.shape[:2]
    out = np.zeros((H * 2, W * 2, 4), np.uint8)
    gout = [np.zeros((H * 2, W * 2, 4), np.uint8) for _ in gs]
    for i, (x, y, w, h) in enumerate(rects):
        if x >= W or y >= H:
            continue
        w, h = min(w, W - x), min(h, H - y)
        f, fg = remaster_face(a[y:y + h, x:x + w], [g[y:y + h, x:x + w] for g in gs], seed * 1000 + i)
        out[2 * y:2 * (y + h), 2 * x:2 * (x + w)] = f
        for go, g in zip(gout, fg):
            go[2 * y:2 * (y + h), 2 * x:2 * (x + w)] = g
    return Image.fromarray(out, 'RGBA'), [Image.fromarray(g, 'RGBA') for g in gout]


# ================================================================ main

def source(assets, rel, declared):
    """The 1x design: fresh from the mod if it's at its declared size (its model script was just rerun), else the
    kept copy."""
    live = os.path.join(assets, 'textures', rel + '.png')
    kept = os.path.join(SRC, rel + '.png')
    if Image.open(live).size == declared:
        os.makedirs(os.path.dirname(kept), exist_ok=True)
        shutil.copyfile(live, kept)
    return Image.open(kept).convert('RGBA')


def main():
    assets = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    shots = []
    for script, sets in CREATURES:
        model = load_model(script)
        rects = list(face_rects(model))
        declared = (model.tex_w, model.tex_h)
        for n, (rel, glow_rels) in enumerate(sets):
            tex = source(assets, rel, declared)
            glows = [source(assets, g, declared) for g in glow_rels]
            big, big_glows = remaster(tex, glows, rects, seed=n)
            big.save(os.path.join(assets, 'textures', rel + '.png'))
            for g, im in zip(glow_rels, big_glows):
                im.save(os.path.join(assets, 'textures', g + '.png'))
            if prev:
                shots.append((script, rel, model, tex, big, glows[:1], big_glows[:1]))
    for rel in FLAT:
        tex = source(assets, rel, (64, 64))
        big, _ = remaster(tex, [], [(0, 0, tex.width, tex.height)])
        big.save(os.path.join(assets, 'textures', rel + '.png'))
    if prev:
        preview(shots, prev)


def render2(model, tex, glow, yaw, k):
    faces = [(A, B, D, (u * k, v * k, w * k, h * k), face, n) for A, B, D, (u, v, w, h), face, n in model.world_faces()]
    return model.render(tex, yaw=yaw, scale=9, size=(260, 330), glow=glow, faces=faces)


def preview(shots, prev):
    os.makedirs(prev, exist_ok=True)
    for script, rel, model, tex, big, g1, g2 in shots:
        if script in ('story_npc', 'rival'):
            continue
        row = [render2(model, tex, g1[0] if g1 else None, y, 1) for y in (25, 200)] + \
              [render2(model, big, g2[0] if g2 else None, y, 2) for y in (25, 200)]
        out = Image.new('RGBA', (260 * 4, 330))
        for i, im in enumerate(row):
            out.paste(im, (i * 260, 0))
        out.save(os.path.join(prev, f'{script}_hd.png'))


if __name__ == '__main__':
    main()
