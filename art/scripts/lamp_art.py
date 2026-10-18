"""The capitals' street lamps going out and coming on: what is laid over the game's own lantern (and soul lantern) model
when a street lamp isn't simply burning (lamps/StreetLampBlock). The lit lamp is the game's lantern untouched; these are
only its glass, drawn anew:

  out       the glass cold and sooted, the burner dark behind it, a pale glint of the street on one pane
  dying     the same glass just after the snuffer: the wick's ember still glowing at the burner, pulsing, a little smoke
            caught in the top of the glass (about a second, then out)
  catching  the flame just caught: the glass warm but dim, a small low flame flickering on the wick (a moment, then the
            lantern as the game draws it)

Each look is a pane of glass 4 by 5 laid on each of the lantern's four sides just proud of the game's own (where the
game's lantern texture has its glass, inside the frame), and the ember and the flame panes of their own a little prouder
still, glowing (light_emission) so they show at night. All drawn here, nothing of the game's own textures: the models
only name the game's lantern models, which they are laid over.

    python3 -B art/scripts/lamp_art.py [assets dir [preview dir [game assets dir]]]

Writes textures/block/street_lamp_<look>.png and soul_street_lamp_<look>.png (with .mcmeta for the ember and the flame,
which flicker), models/block/<lamp>_<look>.json and blockstates/street_lamp.json and soul_street_lamp.json into the
assets (default src/main/resources/assets/guildmark). Given a preview dir and the game's own assets (an unpacked client
jar's assets/minecraft, for the lantern the looks are laid over: read only for the preview, never written anywhere),
draws lamp_looks.png: each lamp lit, catching, dying and out, by day and by night.
"""
import json
import os
import re
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')

LAMPS = ('street_lamp', 'soul_street_lamp')
GAME_MODEL = {'street_lamp': 'minecraft:block/lantern', 'soul_street_lamp': 'minecraft:block/soul_lantern'}

# How far proud of the lantern's sides the glass and the glowing bits stand, in sixteenths of a block: enough that they
# don't flicker into the game's own faces from across a street, too little to see.
GLASS_OUT = 0.08
GLOW_OUT = 0.16

# The lantern's glass on each side: x (or z) 6 to 10, y 1 to 6 (inside its frame)
GLASS = (6, 1, 10, 6)
# The ember: the wick's tip, the middle two columns one up from the bottom of the glass. The flame: those two, two high.
EMBER = (7, 2, 9, 3)
FLAME = (7, 2, 9, 4)

# How brightly the ember and the flame glow (0 to 15): the ember like a coal, the flame like the lamp it will be
EMBER_LIGHT = 10
FLAME_LIGHT = 14


# ================================================================ palettes
# Colours by kind: the lamp's warm one and the soul lamp's cold one
PAL = {
    'street_lamp': {
        'soot': (44, 40, 37), 'glass': (62, 57, 52), 'low': (54, 49, 45), 'glint': (118, 114, 108), 'glint2': (90, 86, 80),
        'burner': (27, 24, 23), 'wick': (20, 18, 18),
        'ember_glass': (66, 46, 36), 'smoke': (70, 66, 63),
        'ember': [((236, 112, 40), (204, 72, 30)), ((252, 146, 62), (222, 92, 34))],
        'warm_top': (118, 82, 42), 'warm': (148, 104, 50), 'warm_low': (134, 94, 46), 'warm_glint': (182, 146, 92),
        'flame': [((255, 196, 90), (246, 162, 58), (255, 236, 164), (255, 222, 128)),
                  ((250, 170, 66), (255, 204, 100), (255, 226, 140), (255, 240, 176))],
    },
    'soul_street_lamp': {
        'soot': (38, 43, 46), 'glass': (52, 60, 63), 'low': (46, 53, 56), 'glint': (110, 122, 124), 'glint2': (82, 94, 97),
        'burner': (22, 25, 27), 'wick': (16, 18, 20),
        'ember_glass': (36, 56, 62), 'smoke': (62, 68, 70),
        'ember': [((84, 214, 226), (46, 160, 178)), ((126, 236, 240), (64, 186, 200))],
        'warm_top': (36, 86, 92), 'warm': (48, 112, 118), 'warm_low': (42, 100, 106), 'warm_glint': (98, 160, 164),
        'flame': [((96, 222, 232), (64, 186, 204), (186, 250, 250), (140, 240, 244)),
                  ((70, 196, 214), (110, 230, 238), (160, 246, 248), (204, 252, 252))],
    },
}


def jitter(rgb, x, y, seed, amount=4):
    """A colour nudged a little, the same for the same pixel: so a pane isn't flat."""
    h = (x * 73856093) ^ (y * 19349663) ^ (seed * 83492791)
    h = ((h ^ (h >> 13)) * 0x5BD1E995) & 0xFFFFFFFF
    d = (h % (2 * amount + 1)) - amount
    return tuple(max(0, min(255, c + d)) for c in rgb)


def pane(rows, fill, seed):
    """A 16 x 16 texture: the 4 x 5 pane [rows] (top row first) at its top left, the rest [fill] (so its mips stay its own)."""
    img = Image.new('RGBA', (16, 16), fill + (255,))
    px = img.load()
    for y, row in enumerate(rows):
        for x, rgb in enumerate(row):
            px[x, y] = jitter(rgb, x, y, seed) + (255,)
    return img


def frames(tiles, fill):
    """A 16-wide strip of 16 x 16 frames, each [tile] (rows of pixels) at its top left, the rest [fill]: an animated texture."""
    img = Image.new('RGBA', (16, 16 * len(tiles)), fill + (255,))
    px = img.load()
    for k, rows in enumerate(tiles):
        for y, row in enumerate(rows):
            for x, rgb in enumerate(row):
                px[x, 16 * k + y] = rgb + (255,)
    return img


def out_glass(p, seed):
    """Cold glass: soot under the cap, a glint on the left pane, the dark burner and the wick's stub behind it."""
    s, g, lo, gl, gl2, b, w = p['soot'], p['glass'], p['low'], p['glint'], p['glint2'], p['burner'], p['wick']
    rows = [
        [s, s, s, s],
        [gl, g, g, s],
        [gl2, w, g, g],
        [g, b, b, g],
        [lo, b, b, lo],
    ]
    return pane(rows, g, seed)


def dying_glass(p, seed):
    """The glass a breath after the snuffer: smoke caught under the cap, the burner lit red from below by the ember."""
    s, g, lo, gl, sm, eg, b = p['soot'], p['glass'], p['low'], p['glint'], p['smoke'], p['ember_glass'], p['burner']
    rows = [
        [s, sm, sm, s],
        [gl, sm, g, s],
        [g, eg, eg, g],
        [g, eg, eg, g],
        [lo, b, b, lo],
    ]
    return pane(rows, g, seed)


def catching_glass(p, seed):
    """Warm glass, but dim: the flame has only just caught (its own pane is laid on the middle)."""
    t, m, lo, gl = p['warm_top'], p['warm'], p['warm_low'], p['warm_glint']
    rows = [
        [t, t, t, t],
        [gl, m, m, t],
        [m, m, m, m],
        [m, m, m, m],
        [lo, lo, lo, lo],
    ]
    return pane(rows, m, seed)


def ember(p):
    """The wick's ember, two pixels, pulsing between two frames."""
    return frames([[list(f)] for f in p['ember']], p['ember'][0][1])


def flame(p):
    """The new flame, two by two: its tongue on top, its brighter root on the wick, flickering between two frames."""
    tiles = []
    for a, b, c, d in p['flame']:
        tiles.append([[a, b], [c, d]])
    return frames(tiles, p['flame'][0][0])


# ================================================================ models
def plane(face, rect, out, uv, texture, light=0):
    """A pane on the lantern's [face] side over [rect] (x0, y0, x1, y1 along that side), [out] proud of it."""
    a0, y0, a1, y1 = rect
    lo, hi = 5 - out, 11 + out
    if face == 'north':
        f, t = [a0, y0, lo], [a1, y1, lo]
    elif face == 'south':
        f, t = [a0, y0, hi], [a1, y1, hi]
    elif face == 'west':
        f, t = [lo, y0, a0], [lo, y1, a1]
    else:
        f, t = [hi, y0, a0], [hi, y1, a1]
    e = {'from': [round(v, 3) for v in f], 'to': [round(v, 3) for v in t], 'faces': {face: {'uv': uv, 'texture': texture}}}
    if light:
        e['light_emission'] = light
    return e


def model(lamp, look):
    # The particle texture is the game's own lantern's, named (the texture has the model's name): broken, a street lamp
    # crumbles as a lantern does
    textures = {'particle': GAME_MODEL[lamp], 'glass': f'guildmark:block/{lamp}_{look}'}
    elements = [plane(face, GLASS, GLASS_OUT, [0, 0, 4, 5], '#glass') for face in ('north', 'south', 'west', 'east')]
    if look == 'dying':
        textures['glow'] = f'guildmark:block/{lamp}_ember'
        elements += [plane(face, EMBER, GLOW_OUT, [0, 0, 2, 1], '#glow', EMBER_LIGHT) for face in ('north', 'south', 'west', 'east')]
    if look == 'catching':
        textures['glow'] = f'guildmark:block/{lamp}_flame'
        elements += [plane(face, FLAME, GLOW_OUT, [0, 0, 2, 2], '#glow', FLAME_LIGHT) for face in ('north', 'south', 'west', 'east')]
    return {'textures': textures, 'elements': elements}


def blockstate(lamp):
    """The game's lantern always, and over it the look for a lamp out, dying or catching (none for one simply burning)."""
    return {'multipart': [
        {'apply': {'model': GAME_MODEL[lamp]}},
        {'when': {'lit': 'false', 'settling': 'false'}, 'apply': {'model': f'guildmark:block/{lamp}_out'}},
        {'when': {'lit': 'false', 'settling': 'true'}, 'apply': {'model': f'guildmark:block/{lamp}_dying'}},
        {'when': {'lit': 'true', 'settling': 'true'}, 'apply': {'model': f'guildmark:block/{lamp}_catching'}},
    ]}


def dump(path, data):
    """Writes [data] as JSON, two-space indented, with each list of numbers on one line (the way the models read best)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    text = json.dumps(data, indent=2)
    text = re.sub(r'\[\s*([-0-9.,\s]+?)\s*\]', lambda m: '[' + ', '.join(v.strip() for v in m.group(1).split(',')) + ']', text)
    with open(path, 'w') as f:
        f.write(text + '\n')


def write(assets):
    for k, lamp in enumerate(LAMPS):
        p = PAL[lamp]
        tex = os.path.join(assets, 'textures/block')
        os.makedirs(tex, exist_ok=True)
        out_glass(p, 11 + k).save(os.path.join(tex, f'{lamp}_out.png'))
        dying_glass(p, 23 + k).save(os.path.join(tex, f'{lamp}_dying.png'))
        catching_glass(p, 37 + k).save(os.path.join(tex, f'{lamp}_catching.png'))
        ember(p).save(os.path.join(tex, f'{lamp}_ember.png'))
        dump(os.path.join(tex, f'{lamp}_ember.png.mcmeta'), {'animation': {'frametime': 4, 'interpolate': True}})
        flame(p).save(os.path.join(tex, f'{lamp}_flame.png'))
        dump(os.path.join(tex, f'{lamp}_flame.png.mcmeta'), {'animation': {'frametime': 2}})
        for look in ('out', 'dying', 'catching'):
            dump(os.path.join(assets, f'models/block/{lamp}_{look}.json'), model(lamp, look))
        dump(os.path.join(assets, f'blockstates/{lamp}.json'), blockstate(lamp))
    print('wrote the street lamps\' looks into', assets)


# ================================================================ preview
def preview(assets, out_dir, game):
    """Each lamp lit, catching, dying and out, seen from a corner, by day and by night (drawn flat, big pixels)."""
    S = 12
    cell = 16 * S + 24
    looks = ('lit', 'catching', 'dying', 'out')
    img = Image.new('RGBA', (cell * len(looks) * 2, cell * len(LAMPS) + 28), (32, 34, 40, 255))
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    for row, lamp in enumerate(LAMPS):
        base = Image.open(os.path.join(game, 'textures/block', ('soul_lantern' if 'soul' in lamp else 'lantern') + '.png')).convert('RGBA')
        side = base.crop((0, 2, 6, 9))  # the side of the lantern's body, frame and glass (first frame)
        for col, look in enumerate(looks):
            face = side.copy()
            if look != 'lit':
                over = Image.open(os.path.join(assets, f'textures/block/{lamp}_{look}.png')).convert('RGBA').crop((0, 0, 4, 5))
                face.paste(over, (1, 1))
            if look in ('dying', 'catching'):
                g = Image.open(os.path.join(assets, f'textures/block/{lamp}_{"ember" if look == "dying" else "flame"}.png')).convert('RGBA')
                g = g.crop((0, 0, 2, 1 if look == 'dying' else 2))
                face.paste(g, (2, 3 if look == 'dying' else 2))
            for night in (False, True):
                lit = {'lit': 1.0, 'catching': 0.62, 'dying': 0.25, 'out': 0.0}[look]
                shade = (0.95 if not night else 0.18 + 0.82 * lit)
                f = face.copy()
                px = f.load()
                for y in range(f.size[1]):
                    for x in range(f.size[0]):
                        r, g_, b, a = px[x, y]
                        glow = 0
                        # The glowing panes keep their light at night
                        if night and look in ('dying', 'catching') and 1 <= x <= 4 and 1 <= y <= 5:
                            if (look == 'dying' and y == 4 and 2 <= x <= 3) or (look == 'catching' and 3 <= y <= 4 and 2 <= x <= 3):
                                glow = 1
                        k = 1.0 if glow else shade
                        px[x, y] = (int(r * k), int(g_ * k), int(b * k), a)
                big = f.resize((6 * S, 7 * S), Image.NEAREST)
                x0 = (col * 2 + (1 if night else 0)) * cell + (cell - 6 * S) // 2
                y0 = row * cell + 28 + (cell - 7 * S) // 2
                bg = (150, 176, 206, 255) if not night else (14, 16, 26, 255)
                d.rectangle([x0 - 10, y0 - 10, x0 + 6 * S + 10, y0 + 7 * S + 10], fill=bg)
                img.alpha_composite(big, (x0, y0))
    for col, look in enumerate(looks):
        d.text((col * 2 * cell + 8, 8), f'{look}: day, night', fill=(230, 230, 230, 255))
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, 'lamp_looks.png')
    img.save(path)
    print('preview:', path)


def main(argv):
    assets = argv[1] if len(argv) > 1 else ASSETS
    write(assets)
    if len(argv) > 3:
        preview(assets, argv[2], argv[3])


if __name__ == '__main__':
    main(sys.argv)
