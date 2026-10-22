"""The Haul Board and the trade's small art: the chip atlas, the ticket art, the dealer's lot's mark, and the Kotlin table the
game draws them from.

    python3 -B art/scripts/haul_board_art.py src/main/resources/assets/guildmark [kotlin-dir] [art/models] [preview-dir]

The pictures themselves are painted in haul_board_icons.py (the goods' chips, the events' badges, the road pennants, the hourglass, the
booker's seals, the notches, the ticket's paper and the rest); this script packs them and writes what the game reads. All at the
HD scale (a screen sprite is drawn at half its texels, as ui_hd.py's are), every picture seeded, so a rerun writes the same files.

Writes:
  textures/block/haul_board/atlas.png           the atlas, for the board's renderer (a block-atlas sprite, drawn as quads)
  textures/gui/trade/atlas.png                  the same pixels as a plain texture, for the screens (drawn with explicit uv)
  textures/gui/sprites/trade/<name>.png         what the market screen's Hauls section draws as sprites: the dealer's lot's
                                                wheel, the ticket row in three kinds (nine-slice, with their .mcmeta), the relief
                                                cross, the festoon, the six seals and the grey one to tint, the two notches
  client/trade/HaulBoardArt.kt                  GENERATED: where every cell of the atlas is, the sprites' names and sizes, and the
                                                numbers the pictures are laid out by (see the file's own comment)
and, with a preview dir, contact sheets of all of it (at 1x and 3x, on dark and light) for a look.

Textures have binary alpha throughout (a pixel is there or it is not), so the atlas draws the same cut out as in a screen.
Sizes: a screen draws a chip, a badge and a seal at half their texels, a sprite likewise; on the board a chip is 0.07 block across.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import haul_board_icons as H
import market_board_art as MB

# ================================================================ the atlas

ATLAS_W = 512
GUTTER = 4                                   # empty texels round every cell, so a mip level does not fetch the one beside

# ================================================================ the screens' sprites (texels; a screen draws them at half)

SPRITE_DIR = os.path.join('textures', 'gui', 'sprites', 'trade')
SPRITES = ['dealer_lot', 'ticket_row', 'ticket_row_relief', 'ticket_row_feast', 'relief_cross', 'festoon',
           'seal_0', 'seal_1', 'seal_2', 'seal_3', 'seal_4', 'seal_5', 'seal_tint', 'notch', 'notch_spent']
ROW_NOMINAL = (32, 20)                       # the ticket row's nine-slice: its size in screen px and its border
ROW_BORDER = 5

# The ticket's paper: where things go on it, in texels of its H.TICKET_W x H.TICKET_H (x, y, w, h). The eyelet, the strip the
# notches are cut in and the notch pitch are the paper's own (H.TICKET_EYELET, H.TICKET_STRIP); the rest are advice that keeps
# clear of the printed rule, the eyelet, the seal and the strip.
TICKET_BOXES = {
    'CHIP': (10, 13, 26, 26),
    'COUNT': (38, 15, 18, 12),
    'PAY': (10, 41, 46, 9),
    'SHOP': (10, 51, 46, 6),
    'SEAL': (35, 31, 22, 22),
    'CROSS': (40, 10, 16, 16),
    'FESTOON': (0, 0, 64, 19),
}
NOTCH_AT = (4, H.TICKET_H - 1 - H.NOTCH_H)   # the first notch's top left on the paper; the next ones follow at NOTCH_PITCH
NOTCH_PITCH = 8
NOTCH_MAX = 7                                # how many fit along the paper's foot


def pieces():
    """Everything the atlas holds, as (key, image) with key 'group/name', in the order the packer is offered them."""
    return [(g + '/' + n, im) for g, n, im in H.all_pieces()]


def pack(items):
    """Shelf-packs [items] (key, image) into a sheet ATLAS_W wide: tallest first, then widest, then by key. Gives the image and
    {key: (x, y, w, h)}. The gutters round a cell carry its edge's colours at alpha 0 (nothing shows, but a mip level averages the
    cell's own colours rather than black)."""
    order = sorted(items, key=lambda it: (-it[1].height, -it[1].width, it[0]))
    pk = MB.Packer(ATLAS_W, GUTTER)
    where = {}
    for key, im in order:
        x, y = pk.add(im.width, im.height)
        where[key] = (x, y, im.width, im.height)
    height = pk.height()
    out = np.zeros((height, ATLAS_W, 4), np.uint8)
    g = GUTTER - 1
    for key, im in order:                                                    # first the edge colours, outside every cell
        x, y, w, h = where[key]
        pad = np.pad(np.asarray(im.convert('RGBA')), ((g, g), (g, g), (0, 0)), mode='edge').copy()
        pad[..., 3] = 0
        region = out[y - g:y + h + g, x - g:x + w + g]
        free = region[..., 3] == 0
        region[free] = pad[free]
    for key, im in order:                                                    # then the cells
        x, y, w, h = where[key]
        out[y:y + h, x:x + w] = np.asarray(im.convert('RGBA'))
    return Image.fromarray(out, 'RGBA'), where


# ================================================================ the screens' sprites

def gui_sprites():
    """{name: image} of the screens' sprites, and {name: mcmeta dict} for the ones that need one."""
    imgs = {'dealer_lot': H.dealer_lot(), 'relief_cross': H.relief_cross(), 'festoon': H.festoon(96),
            'notch': H.notch(False), 'notch_spent': H.notch(True)}
    for k in H.ROW_KINDS:
        imgs['ticket_row' if k == 'plain' else 'ticket_row_' + k] = H.ticket_row(k)
    for i in range(len(H.SEAL_COLOURS)):
        imgs['seal_%d' % i] = H.seal(i)
    imgs['seal_tint'] = H.seal(-1)
    meta = {}
    for name in ('ticket_row', 'ticket_row_relief', 'ticket_row_feast'):
        meta[name] = {'gui': {'scaling': {'type': 'nine_slice', 'width': ROW_NOMINAL[0], 'height': ROW_NOMINAL[1], 'border': ROW_BORDER}}}
    return imgs, meta


# ================================================================ the Kotlin table

def ints(cell):
    return 'intArrayOf(%d, %d, %d, %d)' % tuple(cell)


def kotlin(where, atlas_size, sprites):
    lines = []
    w = lines.append
    w('// GENERATED by art/scripts/haul_board_art.py: edit the script, not this file.')
    w('package io.github.vaspyyy.guildmark.client.trade')
    w('')
    w('/**')
    w(' * Where the trade\'s small pictures are, and the numbers they are laid out by. One atlas holds them all: on the board it is a')
    w(' * block-atlas sprite ([ATLAS]) drawn as quads, in the screens the same pixels are a plain texture ([GUI_ATLAS]) drawn with')
    w(' * explicit uv. A cell is {x, y, w, h} in texels of the atlas; the uv of a cell is x / [ATLAS_W], y / [ATLAS_H] to')
    w(' * (x + w) / [ATLAS_W], (y + h) / [ATLAS_H]. Every picture has binary alpha. A screen draws a texel at half a screen pixel')
    w(' * (the pictures are 2x), so a chip, drawn at [CHIP_PX], is the 32 texels it is.')
    w(' *')
    w(' * The sprites ([DEALER_LOT] and the others named below) are drawn with blitSprite from textures/gui/sprites/trade/ at the')
    w(' * sizes given in screen px.')
    w(' */')
    w('internal object HaulBoardArt {')
    w('    const val ATLAS = "block/haul_board/atlas"')
    w('    const val GUI_ATLAS = "textures/gui/trade/atlas.png"')
    w('    const val ATLAS_W = %d' % atlas_size[0])
    w('    const val ATLAS_H = %d' % atlas_size[1])
    w('')
    w('    /** The goods in economy/MarketBoards.ORDER\'s order: [CHIP_RED] and [CHIP_GREEN] hold their chips in it. */')
    w('    val GOODS = arrayOf(' + ', '.join('"%s"' % g for g in H.GOODS) + ')')
    w('    /** A good\'s chip for what the town is short of (a dashed red ring) and for what it has too much of (a solid green one). */')
    w('    val CHIP_RED = arrayOf(' + ', '.join(ints(where['chip/%s_red' % g]) for g in H.GOODS) + ')')
    w('    val CHIP_GREEN = arrayOf(' + ', '.join(ints(where['chip/%s_green' % g]) for g in H.GOODS) + ')')
    w('    /** A chip\'s texels, and the size it is drawn at in a screen (px) and on the board (blocks). */')
    w('    const val CHIP = %d' % H.CHIP)
    w('    const val CHIP_PX = %d' % (H.CHIP // 2))
    w('    const val CHIP_BLOCKS = 0.07f')
    w('')
    w('    /** The events (economy/EventKind ids, in its order) and each one\'s badge, the same size as a chip. The two good ones are [GOOD_EVENTS]. */')
    w('    val EVENTS = arrayOf(' + ', '.join('"%s"' % e for e in H.EVENTS) + ')')
    w('    val EVENT = arrayOf(' + ', '.join(ints(where['event/%s' % e]) for e in H.EVENTS) + ')')
    w('    val GOOD_EVENTS = arrayOf(' + ', '.join('"%s"' % e for e in H.EVENTS if e in H.GOOD_EVENTS) + ')')
    w('')
    w('    /**')
    w('     * The four road pennants in the order of a road\'s state (open, troubled, perilous, closed: green, amber, red, black with a')
    w('     * white cross; a band across for troubled, two for perilous), the cloth alone and the pole it flies from. The cloth\'s hoist')
    w('     * (the edge at its left) is meant to lie over the pole from [PENNANT_HOIST_X] texels across it and [PENNANT_HOIST_Y] down it; the cloth is painted flat, its folds')
    w('     * running up and down, so a renderer can cut it into columns and let each ride on a wave.')
    w('     */')
    w('    val ROADS = arrayOf(' + ', '.join('"%s"' % r for r in H.ROADS) + ')')
    w('    val PENNANT = arrayOf(' + ', '.join(ints(where['pennant/%s' % r]) for r in H.ROADS) + ')')
    w('    val PENNANT_POLE = ' + ints(where['pennant/pole']))
    w('    const val PENNANT_HOIST_Y = 3')
    w('    const val PENNANT_HOIST_X = 2')
    w('')
    w('    /**')
    w('     * The hourglass: its [HOURGLASS_LEVELS] whole pictures from new (all the sand above) to spent (all below), and its parts for')
    w('     * a sand that runs smoothly: the empty glass in its frame, all the sand of the upper bulb and of the lower (each the size of')
    w('     * the glass, to be shown by cutting its rows: from the top down for the upper, from the bottom up for the lower), and the')
    w('     * falling sand\'s [HOURGLASS_STREAM_FRAMES] frames (drawn at [HOURGLASS_STREAM_X], from the neck at [HOURGLASS_NECK_Y] down to')
    w('     * the lower heap). The glass is {[HOURGLASS_W] x [HOURGLASS_H]} texels; its inside, where sand shows, is rows [HOURGLASS_SAND_TOP]')
    w('     * to [HOURGLASS_SAND_BOTTOM] and the neck is at row [HOURGLASS_NECK_Y], the glass turned end for end swaps the two bulbs.')
    w('     */')
    w('    const val HOURGLASS_W = %d' % H.HG_W)
    w('    const val HOURGLASS_H = %d' % H.HG_H)
    w('    const val HOURGLASS_LEVELS = %d' % H.HG_LEVELS)
    w('    const val HOURGLASS_STREAM_FRAMES = %d' % H.HG_STREAM_FRAMES)
    w('    const val HOURGLASS_STREAM_X = %d' % (H.HG_W // 2 - H.HG_STREAM_W // 2))
    w('    const val HOURGLASS_NECK_Y = %d' % H.HG_NECK)
    inner = H.hg_glass(H.Pic(H.HG_W, H.HG_H))[1]
    rows = np.nonzero(inner.any(axis=1))[0]
    w('    const val HOURGLASS_SAND_TOP = %d' % rows.min())
    w('    const val HOURGLASS_SAND_BOTTOM = %d' % rows.max())
    w('    val HOURGLASS = arrayOf(' + ', '.join(ints(where['hourglass/level_%d' % k]) for k in range(H.HG_LEVELS)) + ')')
    w('    val HOURGLASS_FRAME = ' + ints(where['hourglass/frame']))
    w('    val HOURGLASS_SAND_UPPER = ' + ints(where['hourglass/sand_top']))
    w('    val HOURGLASS_SAND_LOWER = ' + ints(where['hourglass/sand_bottom']))
    w('    val HOURGLASS_STREAM = arrayOf(' + ', '.join(ints(where['hourglass/stream_%d' % k]) for k in range(H.HG_STREAM_FRAMES)) + ')')
    w('')
    w('    /**')
    w('     * The booker\'s seal in six colours (red with a wheel, blue a sheaf, green a bell, amber a key, violet scales, teal a star: the')
    w('     * mark says which as well as the colour does) and a grey one to tint. [SEAL] texels square, drawn at [SEAL_PX] in a screen.')
    w('     */')
    w('    val SEAL = arrayOf(' + ', '.join(ints(where['seal/%d' % k]) for k in range(len(H.SEAL_COLOURS))) + ')')
    w('    val SEAL_TINT = ' + ints(where['seal/tint']))
    w('    val SEAL_NAMES = arrayOf(' + ', '.join('"%s"' % n for n, _, _ in H.SEAL_COLOURS) + ')')
    w('    const val SEAL_TEXELS = %d' % H.SEAL)
    w('    const val SEAL_PX = %d' % (H.SEAL // 2))
    w('')
    w('    /**')
    w('     * The notch row of a ticket: one cell for a day still to run (a V bitten up out of the paper\'s foot) and one for a day gone')
    w('     * (filled in and struck across). Cut {[NOTCH_W] x [NOTCH_H]} texels, to lay over the paper with their base on its lower edge.')
    w('     */')
    w('    val NOTCH = ' + ints(where['notch/open']))
    w('    val NOTCH_SPENT = ' + ints(where['notch/spent']))
    w('    const val NOTCH_W = %d' % H.NOTCH_W)
    w('    const val NOTCH_H = %d' % H.NOTCH_H)
    w('')
    w('    /** A stroke of chalk to strike through a stale row (its two ends are 12 texels each; the middle may be stretched), and a small quill. */')
    w('    val STRUCK = ' + ints(where['mark/struck']))
    w('    const val STRUCK_END = 12')
    w('    val QUILL = ' + ints(where['mark/quill']))
    w('')
    w('    /**')
    w('     * The ticket: its paper (hung from its eyelet), its plain back, and the three marks that go on it: the relief cross, the')
    w('     * feast festoon (across its top) and the notches (along its foot). The boxes are where the writing and the rest go on the')
    w('     * paper, in texels of the paper: x, y, w, h.')
    w('     */')
    w('    val TICKET = ' + ints(where['ticket/paper']))
    w('    val TICKET_BACK = ' + ints(where['ticket/back']))
    w('    val RELIEF_CROSS = ' + ints(where['mark/relief_cross']))
    w('    val FESTOON = ' + ints(where['mark/festoon']))
    w('    const val TICKET_W = %d' % H.TICKET_W)
    w('    const val TICKET_H = %d' % H.TICKET_H)
    w('    const val TICKET_EYELET_X = %d' % H.TICKET_EYELET[0])
    w('    const val TICKET_EYELET_Y = %d' % H.TICKET_EYELET[1])
    w('    const val TICKET_STRIP = %d' % H.TICKET_STRIP)
    w('    const val NOTCH_X = %d' % NOTCH_AT[0])
    w('    const val NOTCH_Y = %d' % NOTCH_AT[1])
    w('    const val NOTCH_PITCH = %d' % NOTCH_PITCH)
    w('    const val NOTCH_MAX = %d' % NOTCH_MAX)
    for k, v in TICKET_BOXES.items():
        w('    val TICKET_%s = %s' % (k, ints(v)))
    w('')
    w('    /**')
    w('     * The screens\' sprites, by name under textures/gui/sprites/ and the size to draw each at (screen px): the dealer\'s lot\'s')
    w('     * wagon wheel, the ticket row (a nine-slice of {[ROW_W] x [ROW_H]} with a border of [ROW_BORDER]) in its plain, relief and')
    w('     * feast kinds, the relief cross, the festoon, the six seals by [SEAL_SPRITES] and the grey one, the two notches.')
    w('     */')
    w('    const val DEALER_LOT = "trade/dealer_lot"')
    w('    const val DEALER_LOT_PX = %d' % (H.DEALER_LOT // 2))
    w('    const val TICKET_ROW = "trade/ticket_row"')
    w('    const val TICKET_ROW_RELIEF = "trade/ticket_row_relief"')
    w('    const val TICKET_ROW_FEAST = "trade/ticket_row_feast"')
    w('    const val ROW_W = %d' % ROW_NOMINAL[0])
    w('    const val ROW_H = %d' % ROW_NOMINAL[1])
    w('    const val ROW_BORDER = %d' % ROW_BORDER)
    w('    const val RELIEF_CROSS_SPRITE = "trade/relief_cross"')
    w('    const val RELIEF_CROSS_PX = %d' % (H.CROSS // 2))
    w('    const val FESTOON_SPRITE = "trade/festoon"')
    w('    const val FESTOON_PX_W = %d' % (sprites['festoon'].width // 2))
    w('    const val FESTOON_PX_H = %d' % (sprites['festoon'].height // 2))
    w('    val SEAL_SPRITES = arrayOf(' + ', '.join('"trade/seal_%d"' % k for k in range(len(H.SEAL_COLOURS))) + ')')
    w('    const val SEAL_TINT_SPRITE = "trade/seal_tint"')
    w('    const val NOTCH_SPRITE = "trade/notch"')
    w('    const val NOTCH_SPENT_SPRITE = "trade/notch_spent"')
    w('    const val NOTCH_PX_W = %d' % (H.NOTCH_W // 2))
    w('    const val NOTCH_PX_H = %d' % (H.NOTCH_H // 2))
    w('}')
    return '\n'.join(lines) + '\n'


# ================================================================ writing

def write_png(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def write_json(obj, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')


def verify(atlas, where, sprites, meta):
    """Checks that fail the run rather than ship a wrong file: every cell inside the atlas and clear of the others (gutters
    included), binary alpha in the atlas and in every sprite, every name the table needs, the nine-slices' sizes."""
    problems = []
    arr = np.asarray(atlas)
    boxes = list(where.items())
    for key, (x, y, w, h) in boxes:
        if x < GUTTER or y < GUTTER or x + w > atlas.width - GUTTER or y + h > atlas.height - GUTTER:
            problems.append('%s is too near the atlas edge' % key)
        a = arr[y:y + h, x:x + w, 3]
        if not np.all((a == 0) | (a == 255)):
            problems.append('%s has partial alpha' % key)
        if not (a == 255).any():
            problems.append('%s is empty' % key)
    for i, (k1, (x1, y1, w1, h1)) in enumerate(boxes):
        for k2, (x2, y2, w2, h2) in boxes[i + 1:]:
            if x1 < x2 + w2 + GUTTER and x2 < x1 + w1 + GUTTER and y1 < y2 + h2 + GUTTER and y2 < y1 + h1 + GUTTER:
                problems.append('%s and %s are closer than the gutter' % (k1, k2))
    for g in H.GOODS:
        for k in ('red', 'green'):
            if 'chip/%s_%s' % (g, k) not in where:
                problems.append('no chip for %s %s' % (g, k))
    for name, im in sprites.items():
        a = np.asarray(im)[..., 3]
        if not np.all((a == 0) | (a == 255)):
            problems.append('sprite %s has partial alpha' % name)
    for name in ('ticket_row', 'ticket_row_relief', 'ticket_row_feast'):
        im = sprites[name]
        if im.size != (ROW_NOMINAL[0] * 2, ROW_NOMINAL[1] * 2):
            problems.append('%s is %s, not twice its nine-slice' % (name, im.size))
    if set(sprites) != set(SPRITES):
        problems.append('the sprites are %s, not %s' % (sorted(sprites), sorted(SPRITES)))
    if problems:
        raise SystemExit('haul_board_art: ' + '; '.join(problems))


def build():
    items = pieces()
    atlas, where = pack(items)
    sprites, meta = gui_sprites()
    return atlas, where, sprites, meta


def main(argv):
    assets = argv[1]
    root = os.path.abspath(os.path.join(assets, '..', '..', '..'))
    kt_dir = argv[2] if len(argv) > 2 else os.path.join(root, 'kotlin', 'io', 'github', 'vaspyyy', 'guildmark', 'client', 'trade')
    prev = argv[4] if len(argv) > 4 else None
    atlas, where, sprites, meta = build()
    verify(atlas, where, sprites, meta)
    write_png(atlas, os.path.join(assets, 'textures', 'block', 'haul_board', 'atlas.png'))
    write_png(atlas, os.path.join(assets, 'textures', 'gui', 'trade', 'atlas.png'))
    for name, im in sprites.items():
        write_png(im, os.path.join(assets, SPRITE_DIR, name + '.png'))
        if name in meta:
            write_json(meta[name], os.path.join(assets, SPRITE_DIR, name + '.png.mcmeta'))
    os.makedirs(kt_dir, exist_ok=True)
    with open(os.path.join(kt_dir, 'HaulBoardArt.kt'), 'w') as f:
        f.write(kotlin(where, atlas.size, sprites))
    if prev:
        previews(prev, atlas, where, sprites)


# ================================================================ previews

def previews(out, atlas, where, sprites):
    os.makedirs(out, exist_ok=True)
    dark, light = (46, 50, 62), (214, 208, 190)
    bg = Image.new('RGBA', atlas.size, (*dark, 255))
    bg.alpha_composite(atlas)
    bg.resize((atlas.width * 2, atlas.height * 2), Image.NEAREST).save(os.path.join(out, 'haul_atlas.png'))
    names = SPRITES
    ims = [sprites[n] for n in names]
    H.sheet(ims, names, 3, 6, dark).save(os.path.join(out, 'haul_sprites_dark.png'))
    H.sheet(ims, names, 3, 6, light).save(os.path.join(out, 'haul_sprites_light.png'))


if __name__ == '__main__':
    main(sys.argv)
