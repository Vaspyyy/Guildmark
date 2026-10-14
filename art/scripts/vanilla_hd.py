"""Guildmark HD, the optional resource pack inside the mod: the most-seen vanilla blocks at 32x, so the world
matches Guildmark's HD art. Stone and its kin, dirt, sand and gravel, grass, the four common woods (planks, bark
and rings), their leaves, and every ore in stone and deepslate.

Each is vanilla's own design remastered: Scale2x (wrapping, so the texture still tiles) for clean shapes, a lit
upper edge where a lighter shape sits on a darker one, each material's fine surface at the new size (grain in
stone, threads of fibre in wood, specks in sand), then the dark-fantasy grade: a little less colour, deeper and
cooler shadows, warmer light. Textures the game tints (grass, leaves) stay grey, so biome colours still work.

    python3 -B art/scripts/vanilla_hd.py <the Minecraft 26.3 client jar> src/main/resources/resourcepacks/guildmark_hd [preview-dir]

The jar is in Gradle's cache after a build (look for the client jar under net/minecraft or neoforge's
minecraft-patched jar); only the block textures named below are read from it.
"""
import io
import os
import sys
import zipfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from hdkit import tone, mix
from creature_hd import epx_map, apply_map, mottle, streaks

ORES = ['coal', 'iron', 'copper', 'gold', 'diamond', 'redstone', 'lapis', 'emerald']
KINDS = {}
for n in ('stone', 'cobblestone', 'mossy_cobblestone', 'stone_bricks', 'mossy_stone_bricks', 'cracked_stone_bricks',
          'andesite', 'diorite', 'granite', 'deepslate', 'deepslate_top', 'cobbled_deepslate', 'gravel'):
    KINDS[n] = 'stone'
for n in ('dirt', 'coarse_dirt', 'grass_block_side', 'grass_block_snow'):
    KINDS[n] = 'earth'
KINDS['sand'] = 'sand'
for n in ('grass_block_top', 'grass_block_side_overlay', 'short_grass'):
    KINDS[n] = 'tinted'
for w in ('oak', 'spruce', 'birch', 'dark_oak'):
    KINDS[f'{w}_planks'] = 'planks'
    KINDS[f'{w}_log'] = 'bark'
    KINDS[f'{w}_log_top'] = 'rings'
    KINDS[f'{w}_leaves'] = 'tinted'
for o in ORES:
    KINDS[f'{o}_ore'] = 'ore'
    KINDS[f'deepslate_{o}_ore'] = 'ore'

# Per kind: fine-surface strength, how much colour to take out, and how far to darken overall
STYLE = {
    'stone': dict(detail=0.08, desat=0.25, darken=0.06),
    'earth': dict(detail=0.08, desat=0.15, darken=0.07),
    'sand': dict(detail=0.05, desat=0.18, darken=0.06),
    'planks': dict(detail=0.07, desat=0.15, darken=0.06),
    'bark': dict(detail=0.08, desat=0.15, darken=0.06),
    'rings': dict(detail=0.05, desat=0.15, darken=0.06),
    'tinted': dict(detail=0.07, desat=0.0, darken=0.03),
    'ore': dict(detail=0.07, desat=0.25, darken=0.06),
}


def lum(rgb):
    return rgb @ np.array([0.3, 0.59, 0.11])


def remaster(src, kind, seed):
    a = np.asarray(src.convert('RGBA'))
    if a.shape[0] > a.shape[1]:
        a = a[:a.shape[1]]
    h, w = a.shape[:2]
    dy, dx = epx_map(a, wrap=True)
    big = apply_map(a, dy, dx, wrap=True).astype(float)
    rgb = big[..., :3]
    st = STYLE[kind]
    H, W = h * 2, w * 2
    # The lighter-on-darker edges at the 1x design's scale, carried onto the new pixels
    L1 = lum(a[..., :3].astype(float))
    up = np.roll(L1, 1, 0)
    down = np.roll(L1, -1, 0)
    left = np.roll(L1, 1, 1)
    rep = lambda m: np.repeat(np.repeat(m, 2, 0), 2, 1)
    even_y = (np.arange(H) % 2 == 0)[:, None]
    even_x = (np.arange(W) % 2 == 0)[None, :]
    k = np.zeros((H, W))
    k += np.where(even_y, rep(np.where(L1 - up > 10, 0.1, np.where(up - L1 > 10, -0.08, 0.0))), 0)
    k += np.where(~even_y, rep(np.where(L1 - down > 10, -0.08, 0.0)), 0)
    k += np.where(even_x, rep(np.where(L1 - left > 10, 0.06, 0.0)), 0)
    # The material's own fine surface, tiling
    if kind == 'planks':
        field = streaks(W, H, seed, False, 1.3, 6)
    elif kind == 'bark':
        field = streaks(W, H, seed, True, 1.3, 5)
    else:
        field = mottle(W, H, seed)
    field = (field - field.mean()) / max(1e-6, field.std()) * 0.5
    k += np.clip(field, -1, 1) * st['detail']
    if kind == 'tinted':
        g = lum(rgb)
        g = np.clip(g * (1 + k * 1.2), 0, 255)
        mean = g[big[..., 3] > 0].mean()
        g = mean + (g - mean) * 1.12 - mean * st['darken']
        rgb = np.repeat(g[..., None], 3, -1)
    else:
        rgb = tone(rgb, k)
        L = lum(rgb)
        sat = (rgb.max(-1) - rgb.min(-1)) / np.maximum(rgb.max(-1), 1)
        # Ores keep their bright minerals; everything else loses a little colour
        des = st['desat'] * (np.clip(1 - (sat - 0.3) / 0.3, 0, 1) if kind == 'ore' else 1)
        rgb = mix(rgb, np.repeat(L[..., None], 3, -1), des)
        mean = L.mean()
        span = max(1.0, np.percentile(L, 95) - np.percentile(L, 5))
        t = np.clip((L - mean) / span, -1, 1)
        rgb = tone(rgb, np.where(t < 0, t * 0.22, t * 0.1))
        rgb = rgb * (1 - st['darken'])
    out = np.dstack([np.clip(rgb, 0, 255), big[..., 3]]).round().astype(np.uint8)
    return Image.fromarray(out, 'RGBA')


def main():
    jar, out = sys.argv[1], sys.argv[2]
    prev = sys.argv[3] if len(sys.argv) > 3 else None
    tex_dir = os.path.join(out, 'assets/minecraft/textures/block')
    os.makedirs(tex_dir, exist_ok=True)
    made = {}
    with zipfile.ZipFile(jar) as z:
        for i, (name, kind) in enumerate(sorted(KINDS.items())):
            src = Image.open(io.BytesIO(z.read(f'assets/minecraft/textures/block/{name}.png')))
            img = remaster(src, kind, 1000 + i)
            img.save(os.path.join(tex_dir, name + '.png'))
            made[name] = (src, img)
    print(len(made), 'textures')
    if prev:
        os.makedirs(prev, exist_ok=True)
        import pickle
        with open(os.path.join(prev, 'vanilla_hd.pickle'), 'wb') as f:
            pickle.dump({k: (np.asarray(a.convert('RGBA')), np.asarray(b)) for k, (a, b) in made.items()}, f)


if __name__ == '__main__':
    main()
