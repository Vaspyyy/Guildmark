"""The dragon's fire and its spawn egg.

Dragonfire is a particle (DragonFlameParticle): each flame is a billboard that billows out as it ages and walks through
these eight sprites, from a young bright core of white-gold ringed with orange, down through orange and red, to a dim dark
red wisp breaking up into soot at its edges. The sprites are 32x32 (twice the mod's usual particle size), soft in alpha,
their colours baked: the game tints nothing.

The spawn egg is 32x32 like the Hollows' (region_hollows.py): crimson scales, a band of charcoal ridge plates with a molten
gold slit eye in it, bronze belly plates below, and the first of its horns and spines breaking out of the shell.

    python3 -B art/scripts/dragon_fx.py src/main/resources/assets/guildmark <preview-dir>

writes textures/particle/dragon_flame_0..7.png, particles/dragon_flame.json, textures/item/dragon_spawn_egg.png and
<preview-dir>/dragon_fx_preview.png (the flames scaled up on dark and light grounds, a stream of them as the game throws them,
and the egg beside the Hollows' for scale).
"""
import sys, os, json, math, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image, ImageDraw
from region_hollows import egg_mask, tone, mix, rgba, seed_of, EGG

SIZE = 32
FRAMES = 8


# ================================================================== noise

def _hash(ix, iy, iz, seed):
    h = (ix * 73856093) ^ (iy * 19349663) ^ (iz * 83492791) ^ ((int(seed) * 2654435761) & 0xFFFFFFFF)
    h = h & 0xFFFFFFFF
    h ^= h >> 15
    h = (h * 2246822519) & 0xFFFFFFFF
    h ^= h >> 13
    h = (h * 3266489917) & 0xFFFFFFFF
    h ^= h >> 16
    return h / 4294967296.0


def vnoise(x, y, z, seed=0):
    """Smooth value noise in 0..1, over arrays x, y and a scalar or array z."""
    x0, y0, z0 = np.floor(x), np.floor(y), np.floor(z)
    ease = lambda v: v * v * (3 - 2 * v)
    fx, fy, fz = ease(x - x0), ease(y - y0), ease(z - z0)
    x0, y0, z0 = x0.astype(np.int64), y0.astype(np.int64), np.asarray(z0).astype(np.int64)
    out = 0.0
    for dz in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                w = (fx if dx else 1 - fx) * (fy if dy else 1 - fy) * (fz if dz else 1 - fz)
                out = out + w * _hash(x0 + dx, y0 + dy, z0 + dz, seed)
    return out


def fbm(x, y, z, seed=0, octaves=3):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        tot = tot + amp * vnoise(x * 2 ** o + 13.1 * o, y * 2 ** o + 7.7 * o, z * 2 ** o, seed + 31 * o)
        norm += amp
        amp *= 0.5
    return tot / norm


def sstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def ramp(stops, h):
    """Colours along [(heat, rgb)] stops (ascending heat) at the heats in [h]."""
    hs = np.array([s[0] for s in stops], float)
    cs = np.array([s[1] for s in stops], float)
    out = np.zeros(h.shape + (3,))
    for c in range(3):
        out[..., c] = np.interp(h, hs, cs[:, c])
    return out


# ================================================================== the flame

# Heat to colour: soot, dark red, red, orange, gold, yellow, white-gold
HEAT = [(0.00, (36, 24, 24)), (0.10, (92, 16, 14)), (0.24, (176, 32, 14)), (0.40, (240, 78, 16)), (0.56, (255, 132, 24)),
        (0.72, (255, 190, 52)), (0.86, (255, 232, 120)), (1.00, (255, 252, 222))]


def flame_frame(k):
    """Frame [k] of 8. One fire seen end on: a ragged, tongued ball whose heat drains as it ages."""
    t = k / (FRAMES - 1)
    ax = (np.arange(SIZE) + 0.5) / SIZE * 2 - 1
    X, Y = np.meshgrid(ax, ax)
    seed = 4417
    z = t * 1.35                                                         # the same fire, a little further on
    # the fire is stirred: its own space is warped by slow swirls, so its rim and its heat run in curls
    wx = (fbm(X * 1.7 + 2.0, Y * 1.7 + 5.0, z, seed + 3, 2) - 0.5) * (0.34 + 0.2 * t)
    wy = (fbm(X * 1.7 + 8.0, Y * 1.7 + 1.0, z, seed + 5, 2) - 0.5) * (0.34 + 0.2 * t)
    Xw, Yw = X + wx, Y + wy
    r = np.hypot(Xw, Yw)
    th = np.arctan2(Yw, Xw)
    # tongues: heat and reach vary with the angle, slowly with the radius, so they run out from the middle in streaks
    tongue = fbm(np.cos(th) * 1.5 + 3.0, np.sin(th) * 1.5 + 3.0, r * 1.3 + z * 1.6, seed + 9, 3)
    R = (0.74 - 0.14 * t) * (0.80 + (0.50 + 0.5 * t) * (tongue - 0.5) + 0.2)
    d = np.clip(1.0 - r / np.maximum(R, 0.05), 0.0, 1.0)                  # 0 at the rim, 1 in the middle
    # how much fire is left: the core shrinks and cools, the whole thing thins to a wisp
    core = np.exp(-(r / (0.24 * (1 - 0.65 * t) + 0.02)) ** 2) * (1 - sstep(0.30, 0.78, t))
    fine = fbm(X * 4.2 + 9.0, Y * 4.2 + 4.0, z * 1.5, seed + 11, 2) - 0.5
    heat = (d ** 0.85 * (0.80 - 0.66 * t ** 0.85) * (0.70 + 0.75 * tongue) + 0.55 * core * (1 - t)) * (1 + 0.45 * fine)
    heat = np.clip(heat, 0, 1)
    rgb = ramp(HEAT, heat)
    # the body of the flame fades into its edge; the late frames break up into patches of soot
    alpha = sstep(0.0, 0.30, d) * (1.0 - 0.28 * t)
    holes = sstep(0.48, 0.78, fbm(X * 2.8 + 5.0, Y * 2.8 + 11.0, z * 0.9, seed + 77, 3))
    alpha = alpha * (1 - holes * (0.85 * t ** 1.3) * (1 - 0.5 * d))
    alpha = alpha * (1 - 0.35 * sstep(0.25, 1.0, t) * (1 - d) ** 1.2)
    # soot: where the fire has gone out but the cloud still hangs, dark smoky grey-brown
    soot = sstep(0.20, 0.05, heat) * sstep(0.25, 0.85, t)
    rgb = rgb * (1 - soot[..., None] * 0.6) + np.array([46.0, 36.0, 36.0]) * soot[..., None] * 0.6
    # young flames throw a few bright sparks out of the fire
    if k <= 3:
        rng = np.random.default_rng(seed + k)
        for _ in range(5 - k):
            ang = rng.uniform(0, 2 * math.pi)
            rad = rng.uniform(0.55, 0.88)
            px, py = int((math.cos(ang) * rad + 1) / 2 * SIZE), int((math.sin(ang) * rad + 1) / 2 * SIZE)
            if 0 <= px < SIZE and 0 <= py < SIZE:
                rgb[py, px] = (255, 214, 120)
                alpha[py, px] = max(alpha[py, px], 0.9 - 0.15 * k)
    out = np.zeros((SIZE, SIZE, 4), np.uint8)
    out[..., :3] = np.clip(np.round(rgb), 0, 255).astype(np.uint8)
    out[..., 3] = np.clip(np.round(alpha * 255), 0, 255).astype(np.uint8)
    # fully clear texels carry no stray colour
    out[out[..., 3] == 0, :3] = 0
    return Image.fromarray(out, 'RGBA')


# ================================================================== the egg

def paint_dragon_egg():
    m, nx, ny = egg_mask()
    rng = random.Random(seed_of('egg', 'dragon'))
    img = np.zeros((EGG, EGG, 4), np.uint8)
    band0, band1, plate0 = 11, 17, 23                      # the band of ridge plates, and where the belly plates start

    CRIMSON = (156, 30, 36)
    CHAR = (48, 42, 46)
    BRONZE = (176, 124, 64)
    IVORY = (236, 226, 196)
    GOLD = (255, 196, 62)

    def put(x, y, c):
        if 0 <= x < EGG and 0 <= y < EGG:
            img[y, x] = rgba(c)

    for y in range(EGG):
        for x in range(EGG):
            if not m[y, x]:
                continue
            # light from the upper left
            k = -nx[y, x] * 0.9 - ny[y, x] * 0.7 + rng.uniform(-0.10, 0.10)
            if band0 <= y <= band1:
                # charcoal ridge plates: two rows of slabs, bright on their upper edge, a dark joint between them
                row = (y - band0) // 3
                cx = (x + (3 if row else 0)) % 6
                cy = (y - band0) % 3
                kk = k * 0.4 + (1.0 if cy == 0 else -0.6 if cy == 2 else 0.1) + (-0.8 if cx == 5 else 0.0)
                img[y, x] = rgba(tone(CHAR, kk))
            elif y >= plate0:
                # dusky bronze belly plates: bands across, a bright edge, a dark seam
                cy = (y - plate0) % 3
                kk = k * 0.7 + (0.8 if cy == 0 else -1.0 if cy == 2 else 0.0)
                img[y, x] = rgba(tone(BRONZE, kk))
            else:
                # overlapping scales in staggered rows, 6 px wide, each lying over the one below: a lit upper face, a dark
                # rounded free edge
                row, v = y // 3, y % 3
                off_prev = 3 * ((row - 1) % 2)
                off = 3 * (row % 2)
                u_prev = (x - off_prev) % 6
                u = (x - off) % 6
                if v == 0 and u_prev in (2, 3) and row > 0:
                    kk, edge = -1.5, True                                    # the tip of the scale above
                    sid = (row - 1, (x - off_prev) // 6)
                elif v == 0:
                    kk, edge = 0.95, False
                    sid = (row, (x - off) // 6)
                elif v == 1:
                    kk, edge = 0.15, False
                    sid = (row, (x - off) // 6)
                else:
                    kk, edge = (-0.15, False) if u in (2, 3) else (-1.5, True)
                    sid = (row, (x - off) // 6)
                jit = (seed_of('scale', sid[0], sid[1]) % 1000) / 1000.0 - 0.5
                kk += k * (0.5 if edge else 1.0) + (0.10 if u == 0 else -0.10 if u == 5 else 0.0) + 0.55 * jit * (0 if edge else 1)
                if rng.random() < 0.05:
                    kk -= 0.5                                                # a chipped, darker scale
                img[y, x] = rgba(tone(CRIMSON, kk))
    # the hot seam between the band and the scales above, and between plates and scales below
    for x in range(EGG):
        for y, c in ((band0 - 1, (222, 78, 26)), (band1 + 1, (156, 46, 20))):
            if m[y, x] and 6 <= x <= 25 and rng.random() < 0.85:
                put(x, y, mix(img[y, x], c, 0.55))
    # the eye: a molten gold slit in the band, with a glow round it
    ex, ey = 13, 12
    eye = [
        '..DDD..',
        '.DGPGD.',
        'DGGPGGD',
        '.DGPGD.',
        '..DDD..',
    ]
    shape = set()
    for dy, row in enumerate(eye):
        for dx, ch in enumerate(row):
            if ch != '.':
                shape.add((ex + dx, ey + dy))
    for dy, row in enumerate(eye):
        for dx, ch in enumerate(row):
            if ch == '.':
                continue
            c = {'D': (30, 10, 8), 'G': GOLD, 'P': (22, 6, 6)}[ch]
            if ch == 'G' and dy == 1:
                c = (255, 226, 120)                                  # the iris is brighter toward the lid
            put(ex + dx, ey + dy, c)
    for (x, y) in list(shape):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in shape and 0 <= q[0] < EGG and 0 <= q[1] < EGG and m[q[1], q[0]] and band0 <= q[1] <= band1:
                put(q[0], q[1], mix(img[q[1], q[0]], (255, 150, 40), 0.32))
    put(ex + 2, ey + 1, (255, 250, 214))                             # the glint in the eye
    # a glint on the shell, upper left
    for (x, y, c) in ((9, 7, (252, 160, 120)), (10, 7, (240, 118, 90)), (9, 8, (240, 118, 90)), (8, 9, (214, 80, 64))):
        if m[y, x]:
            put(x, y, c)
    # the horns coming through the shell at the top: ivory fading to black, curving out and up
    for side in (-1, 1):
        x0 = 8 if side < 0 else 23
        body = [(0, 8, 2), (0, 7, 2), (side, 6, 2), (side, 5, 1), (2 * side, 4, 1), (2 * side, 3, 1), (3 * side, 2, 1)]
        cols = [IVORY, IVORY, (226, 216, 184), (190, 178, 148), (120, 110, 94), (44, 38, 40), (14, 12, 16)]
        for (dx, y, wd), c in zip(body, cols):
            put(x0 + dx, y, c)
            if wd == 2:
                put(x0 + dx - side, y, tone(c, -0.7))
    # a crest of three obsidian spines along the top of the shell, catching the light on their left edge
    for (x, ytop) in ((13, 1), (15, 0), (18, 1)):
        for y in range(ytop, 2):
            put(x, y, (84, 82, 110) if y == ytop else (30, 28, 40))
            put(x + 1, y, (10, 9, 14))
        if x == 15:
            put(16, 0, (10, 9, 14)); put(16, 1, (10, 9, 14))
    # a darker rim round the shell, as the other eggs have
    out = img.copy()
    for y in range(EGG):
        for x in range(EGG):
            if img[y, x, 3] == 0:
                continue
            edge = any(not (0 <= x + dx < EGG and 0 <= y + dy < EGG) or img[y + dy, x + dx, 3] == 0
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                out[y, x] = rgba(tone(img[y, x], -1.6))
    return Image.fromarray(out, 'RGBA')


# ================================================================== previews

def stream_preview(frames, W=1632, H=300, seed=5):
    """A stream of flames as DragonFlameParticle throws them: born small at the left, billowing as they go, each on the sprite its
    age picks, rolled at random, translucent over a dark ground."""
    rng = random.Random(seed)
    bg = Image.new('RGBA', (W, H), (24, 26, 36, 255))
    parts = []
    for i in range(70):
        age = rng.random()
        parts.append(age)
    parts.sort()
    for life in parts:
        x = 40 + life * (W - 200) + rng.uniform(-8, 8)
        y = H / 2 + rng.uniform(-18, 18) * (0.4 + life) - life * 14
        size = (46 + rng.uniform(-8, 8)) * (1 + 3.6 * math.sqrt(life))
        k = min(FRAMES - 1, int(life * FRAMES))
        spr = frames[k].rotate(rng.uniform(0, 360), resample=Image.BICUBIC, expand=False)
        spr = spr.resize((int(size), int(size)), Image.BILINEAR)
        alpha = 1.0 if life < 0.8 else (1 - life) / 0.2
        if alpha < 1:
            a = np.array(spr)
            a[..., 3] = (a[..., 3] * alpha).astype(np.uint8)
            spr = Image.fromarray(a, 'RGBA')
        bg.alpha_composite(spr, (int(x - size / 2), int(y - size / 2)))
    return bg


def preview(frames, egg):
    Z = 6
    cell = SIZE * Z
    W = FRAMES * cell
    sheet = Image.new('RGBA', (W, cell * 2 + 300 + 14 * 3 + cell + 8), (16, 18, 26, 255))
    dark = Image.new('RGBA', (W, cell), (22, 24, 34, 255))
    light = Image.new('RGBA', (W, cell), (136, 168, 206, 255))
    for i, f in enumerate(frames):
        big = f.resize((cell, cell), Image.NEAREST)
        dark.alpha_composite(big, (i * cell, 0))
        light.alpha_composite(big, (i * cell, 0))
    sheet.alpha_composite(dark, (0, 0))
    sheet.alpha_composite(light, (0, cell + 14))
    sheet.alpha_composite(stream_preview(frames, W=W), (0, 2 * cell + 28))
    # the egg at 8x next to the Hollows' for scale, over the same dark
    y0 = 2 * cell + 28 + 300 + 14
    ground = Image.new('RGBA', (W, cell + 8), (58, 62, 74, 255))
    sheet.alpha_composite(ground, (0, y0))
    here = os.path.dirname(os.path.abspath(__file__))
    refs = []
    item_dir = os.path.join(here, '..', '..', 'src', 'main', 'resources', 'assets', 'guildmark', 'textures', 'item')
    for n in ('mire_hollow', 'cinder_hollow', 'rime_hollow', 'glimmer_hollow'):
        p = os.path.join(item_dir, f'{n}_spawn_egg.png')
        if os.path.exists(p):
            refs.append(Image.open(p).convert('RGBA'))
    x = 12
    for im in [egg] + refs:
        sheet.alpha_composite(im.resize((cell, cell), Image.NEAREST), (x, y0 + 4))
        x += cell + 12
    # the egg at icon size (16 px: how the inventory shows it) and at 32 px
    sheet.alpha_composite(egg.resize((16, 16), Image.BOX), (x + 8, y0 + 20))
    sheet.alpha_composite(egg, (x + 40, y0 + 12))
    return sheet.convert('RGB')


# ================================================================== main

def main(out, prev):
    particle = os.path.join(out, 'textures', 'particle')
    items = os.path.join(out, 'textures', 'item')
    particles = os.path.join(out, 'particles')
    for d in (particle, items, particles, prev):
        os.makedirs(d, exist_ok=True)
    frames = [flame_frame(k) for k in range(FRAMES)]
    for k, f in enumerate(frames):
        f.save(os.path.join(particle, f'dragon_flame_{k}.png'))
    with open(os.path.join(particles, 'dragon_flame.json'), 'w') as f:
        json.dump({'textures': [f'guildmark:dragon_flame_{k}' for k in range(FRAMES)]}, f, indent=2)
        f.write('\n')
    egg = paint_dragon_egg()
    egg.save(os.path.join(items, 'dragon_spawn_egg.png'))
    preview(frames, egg).save(os.path.join(prev, 'dragon_fx_preview.png'))


if __name__ == '__main__':
    if len(sys.argv) < 3:
        raise SystemExit('usage: dragon_fx.py <assets dir> <preview dir>')
    main(sys.argv[1], sys.argv[2])
