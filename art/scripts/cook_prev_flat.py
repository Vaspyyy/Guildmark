"""The flat previews (cook_prev.py): the items by tier and stage, the effect icons, the cold vignette, the GUI sprites, the particles, the renderers'
textures, the cookshop's parchment, and the GIFs of everything that moves. Each function takes the folder the mod's assets were written to
(`cook_art.py` has run into it) and the preview folder, and returns the files it wrote. Everything is read back from the written files, so a
preview shows the mod as it is, not as the scripts meant it."""
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw

import cook_prevkit as P
import cook_itemdefs as ID

TIER, STAGE = ID.TIER, ID.STAGE
TIERS = ((1, 'Plain'), (2, 'Sound'), (3, 'Fine'), (4, 'Hallmarked'))
STAGES = ((None, 'fresh'), (1, 'stale'), (2, 'gone off'))
DISHES = [d for d, _ in ID.DISHES]
NICE = {'dish_pottage': 'Pottage', 'dish_stew': 'Stew', 'dish_broth': 'Broth', 'dish_roots': 'Roots', 'dish_chowder': 'Chowder',
        'dish_cakes': 'Cakes', 'dish_loaf': 'Loaf', 'dish_pie': 'Pie', 'dish_tart': 'Tart', 'dish_fish': 'Fish', 'dish_roast': 'Roast',
        'dish_strips': 'Strips', 'gruel': 'Gruel', 'scorched_mess': 'Scorched mess', 'cooks_ladle': "Cook's ladle", 'cooks_knife': "Cook's knife",
        'turner': 'Turner', 'gilded_ladle': 'Gilded ladle', 'cook_book': "Cook's Book", 'herb_sprig': 'Herb sprig'}
TICK = 68                       # a moment when a fresh dish steams, a Hallmarked stamp glints and a tool's glint is crossing


def comps(tier=None, stage=None):
    c = {}
    if tier is not None:
        c[TIER] = tier
    if stage is not None:
        c[STAGE] = stage
    return c


# ================================================================ items

def item_sheets(assets, out):
    it = P.Items(assets)
    written = []
    # ---- every dish, every tier and stage, at 64 px
    rows = DISHES + ['scorched_mess']
    cols = []
    for stage, sname in STAGES:
        for tier, tname in TIERS:
            cols.append((tier, stage, '%s %s' % (tname, sname) if stage is not None else tname))
    k = 2
    w = 32 * k + 6
    head_h = 34
    left = 118
    sheet = Image.new('RGBA', (left + len(cols) * (w + 4) + 6, head_h + len(rows) * (w + 4) + 6), P.BG + (255,))
    d = ImageDraw.Draw(sheet)
    for j, (tier, stage, name) in enumerate(cols):
        x = left + j * (w + 4)
        P.text(d, (x + w // 2, 3), TIERS[tier - 1][1], P.INK, 11, 'ma')
        P.text(d, (x + w // 2, 17), 'fresh' if stage is None else dict(STAGES)[stage], P.DIM, 11, 'ma')
    for i, name in enumerate(rows):
        y = head_h + i * (w + 4)
        P.text(d, (8, y + w // 2), NICE[name], P.INK, 13, 'lm')
        for j, (tier, stage, _) in enumerate(cols):
            if name == 'scorched_mess' and j > 0:
                break
            cell = P.sprite_cell(it.render(name, comps(tier, stage), TICK), k, pad=3)
            sheet.alpha_composite(cell, (left + j * (w + 4), y))
    written.append(P.save_png(sheet, os.path.join(out, 'cook_dish_states.png')))
    # ---- every dish at 16, 32 and 64 px (a sprite is 32 px: a GUI scale of 1, 2 and 4 draws it so; the game samples it by the nearest texel)
    names = DISHES + ['scorched_mess']
    sizes = (16, 32, 64)
    pad = 4
    wid = 64 + 2 * pad
    left = 52
    head = 36
    sheet = Image.new('RGBA', (left + len(names) * (wid + 2) + 6, head + sum(s_ + 2 * pad + 2 for s_ in sizes) + 6), P.BG + (255,))
    d = ImageDraw.Draw(sheet)
    for j, n in enumerate(names):
        P.text(d, (left + j * (wid + 2) + wid // 2, 10), NICE[n].replace('Scorched mess', 'Scorched'), P.INK, 11, 'ma')
    y = head
    for s_ in sizes:
        P.text(d, (6, y + (s_ + 2 * pad) // 2), '%d px' % s_, P.DIM, 12, 'lm')
        for j, n in enumerate(names):
            small = it.render(n, {}, TICK).resize((s_, s_), Image.NEAREST)
            cell = P.tiled_bg(wid, s_ + 2 * pad)
            cell.alpha_composite(small, ((wid - s_) // 2, pad))
            sheet.alpha_composite(cell, (left + j * (wid + 2), y))
        y += s_ + 2 * pad + 2
    written.append(P.save_png(sheet, os.path.join(out, 'cook_dishes_sizes.png')))
    # ---- the four tier marks and the two stage washes on every dish, at the 16 px a GUI scale of 1 gives them (true size, then three times over), and
    # the two finer marks again under the number of a full stack (8 a dish, 16 a ration), written where the game writes it
    variants = ([(tname, 'mark', comps(tier), '') for tier, tname in TIERS] + [('Stale', 'Sound, washed', comps(2, 1), ''), ('Gone off', 'Sound, washed', comps(2, 2), '')]
                + [('Fine', 'stack of 8', comps(3), '8'), ('Hallmarked', 'stack of 16', comps(4), '16')])
    cw = 16 + 6 + 48 + 8
    left = 112
    head = 36
    row_h = 52
    sheet = Image.new('RGBA', (left + len(variants) * cw + 6, head + len(names) * row_h + 6), P.BG + (255,))
    d = ImageDraw.Draw(sheet)
    for j, (vname, vnote, _, _) in enumerate(variants):
        P.text(d, (left + j * cw + cw // 2, 5), vname, P.INK, 11, 'ma')
        P.text(d, (left + j * cw + cw // 2, 19), vnote, P.DIM, 10, 'ma')
    for i, n in enumerate(names):
        y = head + i * row_h
        P.text(d, (6, y + row_h // 2 - 2), NICE[n], P.INK, 12, 'lm')
        for j, (_, _, c, count) in enumerate(variants):
            small = it.render(n, c, TICK).resize((16, 16), Image.NEAREST)
            if count and n != 'scorched_mess':
                small = P.stack_count(small, count)
            x = left + j * cw
            tile = P.tiled_bg(16 + 4, 16 + 4)
            tile.alpha_composite(small, (2, 2))
            sheet.alpha_composite(tile, (x, y + (row_h - 20) // 2 - 2))
            big = P.sprite_cell(small, 3, pad=2)
            sheet.alpha_composite(big, (x + 24, y))
    P.text(d, (6, 5), 'Every dish at 16 px,', P.INK, 11)
    P.text(d, (6, 19), 'then 3 times over', P.DIM, 10)
    written.append(P.save_png(sheet, os.path.join(out, 'cook_dishes_marks_16.png')))
    return written


def tools_sheet(assets, out):
    """The tools, the book and the herb sprig, big and at game size, with the grip and tip each carries."""
    import cook_tools
    it = P.Items(assets)
    names = list(ID.TOOLS) + ['cook_book', 'herb_sprig']
    cells, labels = [], []
    for n in names:
        im = it.render(n, {}, TICK)
        cell = P.sprite_cell(im, 6, pad=4)
        d = ImageDraw.Draw(cell)
        lay = cook_tools.LAYOUT.get(n)
        if lay:
            for key, col in (('grip', (255, 90, 90, 255)), ('tip', (90, 220, 255, 255))):
                if key in lay:
                    x, y = lay[key]
                    X, Y = 4 + int(x * 6) + 3, 4 + int(y * 6) + 3
                    d.ellipse([X - 5, Y - 5, X + 5, Y + 5], outline=col, width=2)
        cells.append(cell)
        labels.append(NICE[n])
    sheet = P.contact(cells, 3, labels, title="Tools: grip (red ring) and working end (blue ring) as the held models use them")
    return [P.save_png(sheet, os.path.join(out, 'cook_tools.png'))]


def items_gifs(assets, out):
    """What moves on the items: the dishes (steam, the sheen on the strips, the smoke of the scorched mess, the flies of a dish gone off, the stamps'
    glints) and the tools' glints, read from the item definitions at each tick. The strips run at their own lengths (24 ticks for steam, 76 for the
    Hallmarked stamp, 94 for the strips' sheen, 114 for the Fine stamp, and a tool's 86 to 164, no two alike so that nothing glints in step), so a
    clip is a stretch of that and its end is not a seam."""
    it = P.Items(assets)
    written = []
    names = DISHES + ['scorched_mess']
    rows = (('Plain, fresh', comps(1)), ('Fine, fresh', comps(3)), ('Hallmarked, fresh', comps(4)), ('Plain, gone off', comps(1, 2)))
    k, pad = 2, 3
    cw = 32 * k + 2 * pad + 4
    left = 124
    top = 34
    base = Image.new('RGBA', (left + len(names) * cw + 6, top + len(rows) * cw + 6), P.BG + (255,))
    d = ImageDraw.Draw(base)
    for j, n in enumerate(names):
        P.text(d, (left + j * cw + cw // 2, 8), NICE[n].replace('Scorched mess', 'Scorched'), P.DIM, 10, 'ma')
    for i, (label, _) in enumerate(rows):
        P.text(d, (6, top + i * cw + cw // 2), label, P.INK, 12, 'lm')
    frames = []
    for t in range(0, 228, 2):
        fr = base.copy()
        for i, (_, c) in enumerate(rows):
            for j, n in enumerate(names):
                fr.alpha_composite(P.sprite_cell(it.render(n, c, t), k, pad=pad), (left + j * cw, top + i * cw))
        frames.append(fr)
    written.append(P.save_gif(frames, os.path.join(out, 'cook_dishes.gif'), 100))
    names = list(ID.TOOLS) + ['cook_book', 'herb_sprig']
    frames = []
    for t in range(0, 330, 2):
        cells = [P.sprite_cell(it.render(n, {}, t), 4, pad=4) for n in names]
        frames.append(P.contact(cells, len(cells), [NICE[n] for n in names], gap=6, label_size=11))
    written.append(P.save_gif(frames, os.path.join(out, 'cook_tools.gif'), 100))
    return written


def block_texture_gif(assets, out):
    """The block textures that move, as the game runs their strips: the flame, the coals (blended from frame to frame) and the oven's glow (the same)."""
    names = ('flame', 'coals', 'oven_glow', 'log_glow')
    strips = {n: Strip(os.path.join(assets, 'textures/block/cook/%s.png' % n)) for n in names}
    frames = []
    for t in range(0, 240, 2):
        cells = [P.sprite_cell(strips[n].at(t), 4, pad=4) for n in names]
        frames.append(P.contact(cells, len(cells), ['%s (%d frames)' % (n, len(strips[n].frames)) for n in names], gap=6, label_size=11))
    return [P.save_gif(frames, os.path.join(out, 'cook_block_textures.gif'), 100)]


# ================================================================ effects

EFFECTS = (('warmth', 'Warmth'), ('hearty', 'Hearty'), ('steady', 'Steady'), ('provisioned', 'Provisioned'), ('chilled', 'Chilled'))


class Strip:
    """An animated texture read from its files: frames (as wide as the strip), and how long each lasts."""

    def __init__(self, path):
        im = Image.open(path).convert('RGBA')
        meta = path + '.mcmeta'
        a = None
        if os.path.exists(meta):
            with open(meta) as f:
                a = json.load(f).get('animation')
        # a texture with no `animation` block is one picture of any shape; with one it is a strip of squares as wide as it is
        w = min(im.width, im.height) if a is not None else None
        self.frames = [im.crop((0, y, w, y + w)) for y in range(0, im.height, w)] if a is not None else [im]
        self.interp = False
        self.timing = [(i, 1) for i in range(len(self.frames))]
        if a is not None:
            ft = a.get('frametime', 1)
            fl = a.get('frames')
            self.timing = [(e['index'], e['time']) if isinstance(e, dict) else (e, ft) for e in fl] if fl else [(i, ft) for i in range(len(self.frames))]
            self.interp = bool(a.get('interpolate'))
        self.total = sum(t for _, t in self.timing)

    def at(self, tick):
        k = tick % self.total
        for n, (i, t) in enumerate(self.timing):
            if k < t:
                a = self.frames[i]
                if self.interp and len(self.frames) > 1:
                    b = self.frames[self.timing[(n + 1) % len(self.timing)][0]]
                    return Image.blend(a, b, k / t)
                return a
            k -= t
        return self.frames[-1]


def effects_sheet(assets, out):
    written = []
    strips = {n: Strip(os.path.join(assets, 'textures/mob_effect/%s.png' % n)) for n, _ in EFFECTS}
    cells, labels = [], []
    for n, nice in EFFECTS:
        s = strips[n]
        big = P.sprite_cell(s.at(0), 5, pad=4)
        game = P.sprite_cell(s.at(0).resize((18, 18), Image.NEAREST), 3, pad=3)
        cell = Image.new('RGBA', (big.width + game.width + 6, big.height), P.BG + (255,))
        cell.alpha_composite(big, (0, 0))
        cell.alpha_composite(game, (big.width + 6, 4))
        cells.append(cell)
        labels.append('%s (%d frames, a loop of %d ticks)' % (nice, len(s.frames), s.total))
    sheet = P.contact(cells, 3, labels, title='Effect icons: 36 px, five times over, and at the 18 px the game draws them', cell_size=(300, 5 * 36 + 8))
    written.append(P.save_png(sheet, os.path.join(out, 'cook_effects.png')))
    # the animation: a row of the five on the game's dark slot, 160 ticks
    frames = []
    for t in range(0, 240, 2):
        tiles = []
        for n, nice in EFFECTS:
            im = strips[n].at(t)
            cell = P.tiled_bg(36 * 4 + 8, 36 * 4 + 8)
            cell.alpha_composite(P.up(im, 4), (4, 4))
            tiles.append(cell)
        row = Image.new('RGBA', (len(tiles) * (36 * 4 + 16) + 8, 36 * 4 + 16), P.BG + (255,))
        for i, c in enumerate(tiles):
            row.alpha_composite(c, (8 + i * (36 * 4 + 16), 8))
        frames.append(row)
    written.append(P.save_gif(frames, os.path.join(out, 'cook_effects.gif'), 100))
    return written


def chill_sheet(assets, out, backdrop=None):
    """The cold vignette over a picture at three strengths."""
    v = Image.open(os.path.join(assets, 'textures/gui/chill.png')).convert('RGBA')
    w, h = 480, 270
    if backdrop is None:
        backdrop = Image.new('RGB', (w, h), (110, 140, 90))
        d = ImageDraw.Draw(backdrop)
        d.rectangle([0, 0, w, h // 2], fill=(130, 170, 214))
        d.rectangle([0, h // 2, w, h], fill=(88, 130, 70))
    backdrop = backdrop.convert('RGBA').resize((w, h), Image.LANCZOS)
    tiles = []
    for k, label in ((0.0, 'none'), (0.5, 'half'), (1.0, 'full')):
        im = backdrop.copy()
        if k > 0:
            vv = v.resize((w, h), Image.BILINEAR)
            a = np.asarray(vv, float)
            a[..., 3] *= k
            im.alpha_composite(Image.fromarray(np.clip(a + 0.5, 0, 255).astype(np.uint8), 'RGBA'))
        tiles.append(im)
    sheet = P.contact(tiles, 3, ['the vignette off', 'at half strength', 'at full strength'], title='Chilled: the cold vignette (256 x 256, stretched over the screen)')
    return [P.save_png(sheet, os.path.join(out, 'cook_chill.png'))]


# ================================================================ GUI sprites

def nine_slice(img, w, h, border):
    """[img] stretched to w x h by a nine-slice of [border] px (texture pixels) at its edges."""
    sw, sh = img.size
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    xs = [0, border, sw - border, sw]
    ys = [0, border, sh - border, sh]
    xd = [0, border, w - border, w]
    yd = [0, border, h - border, h]
    for j in range(3):
        for i in range(3):
            piece = img.crop((xs[i], ys[j], xs[i + 1], ys[j + 1]))
            tw, th = xd[i + 1] - xd[i], yd[j + 1] - yd[j]
            if tw > 0 and th > 0:
                out.alpha_composite(piece.resize((tw, th), Image.NEAREST), (xd[i], yd[j]))
    return out


def gui_sheet(assets, out):
    import cook_gui
    written = []
    d = os.path.join(assets, 'textures/gui/sprites/cook')
    names = sorted(cook_gui.NAMES)
    groups = {False: ([], []), True: ([], [])}        # the squares, and the tall ones (the page and the ribbon) kept apart so they do not stretch the grid
    for n in names:
        s = Strip(os.path.join(d, n + '.png'))
        fr = s.at(0)
        tall = fr.height > 32
        big = P.sprite_cell(fr, 4 if not tall else 2, pad=3)
        half = fr.resize((fr.width // 2, fr.height // 2), Image.BOX)
        shown = P.on(half, (30, 28, 36), 3)
        shown2 = P.on(half, (214, 190, 142), 3)
        sw = shown.width * 2
        cell = Image.new('RGBA', (big.width + 2 * sw + 12, max(big.height, shown.height * 2)), P.BG + (255,))
        cell.alpha_composite(big, (0, 0))
        cell.alpha_composite(P.up(shown, 2), (big.width + 4, 0))
        cell.alpha_composite(P.up(shown2, 2), (big.width + 8 + sw, 0))
        groups[tall][0].append(cell)
        groups[tall][1].append('%s%s' % (n, ' (%d frames)' % len(s.frames) if len(s.frames) > 1 else ''))
    top = P.contact(groups[False][0], 4, groups[False][1], title='GUI sprites: as drawn, then as the game shows them (half size, doubled) on dark and on parchment')
    low = P.contact(groups[True][0], 2, groups[True][1])
    sheet = Image.new('RGBA', (max(top.width, low.width), top.height + low.height), P.BG + (255,))
    sheet.alpha_composite(top, (0, 0))
    sheet.alpha_composite(low, (0, top.height))
    written.append(P.save_png(sheet, os.path.join(out, 'cook_gui.png')))
    # the Book's page, stretched as the nine-slice stretches it
    page = Image.open(os.path.join(d, 'book_page.png')).convert('RGBA')
    with open(os.path.join(d, 'book_page.png.mcmeta')) as f:
        sc = json.load(f)['gui']['scaling']
    shots = []
    for (gw, gh) in ((48, 64), (96, 64), (64, 112), (150, 100)):
        shots.append(P.on(nine_slice(page, gw * 2, gh * 2, sc['border'] * 2), (40, 38, 46), 6))
    sheet = P.contact(shots, 4, ['48 x 64 GUI px', '96 x 64', '64 x 112', '150 x 100'], title="The Cook's Book's page as the nine-slice stretches it (border %d GUI px)" % sc['border'])
    written.append(P.save_png(sheet, os.path.join(out, 'cook_gui_page.png')))
    # the moving sprites
    moving = [n for n in names if len(Strip(os.path.join(d, n + '.png')).frames) > 1]
    strips = {n: Strip(os.path.join(d, n + '.png')) for n in moving}
    frames = []
    for t in range(0, 200, 2):
        tiles = [P.sprite_cell(strips[n].at(t), 4, pad=3) for n in moving]
        frames.append(P.contact(tiles, 3, moving, gap=8, label_size=11))
    written.append(P.save_gif(frames, os.path.join(out, 'cook_gui.gif'), 100))
    return written


# ================================================================ particles

PART_TINT = {'cook_steam': (1.0, 1.0, 1.0), 'stew_bubble': (1.0, 1.0, 1.0), 'cook_smoke_dark': (1.0, 1.0, 1.0), 'cook_sizzle': (1.0, 0.86, 0.5),
             'cook_crumb': (0.94, 0.8, 0.56), 'cook_call': (1.0, 0.9, 0.5), 'cook_foam': (1.0, 1.0, 1.0)}
PART_FRAMES = {'cook_steam': 4, 'stew_bubble': 4, 'cook_smoke_dark': 4, 'cook_sizzle': 4, 'cook_crumb': 3, 'cook_call': 4, 'cook_foam': 4}


def _part_frames(assets, name):
    return [Image.open(os.path.join(assets, 'textures/particle/%s_%d.png' % (name, i))).convert('RGBA') for i in range(PART_FRAMES[name])]


def _tint(im, t):
    a = np.asarray(im, float)
    a[..., :3] *= np.asarray(t, float)
    return Image.fromarray(np.clip(a + 0.5, 0, 255).astype(np.uint8), 'RGBA')


def particle_sheet(assets, out):
    written = []
    night, day = (24, 28, 40), (158, 182, 206)
    cells, labels = [], []
    for name in PART_FRAMES:
        fr = _part_frames(assets, name)
        row = Image.new('RGBA', (len(fr) * (16 * 6 + 6) * 2 + 20, 16 * 6 + 8), P.BG + (255,))
        x = 0
        for bgc in (night, day):
            for f in fr:
                tile = Image.new('RGBA', (16 * 6 + 6, 16 * 6 + 8), bgc + (255,))
                tile.alpha_composite(P.up(_tint(f, PART_TINT[name]), 6), (3, 4))
                row.alpha_composite(tile, (x, 0))
                x += 16 * 6 + 6
            x += 20
        cells.append(row)
        labels.append('%s  (%d frames: newest first; tinted as the game tints it)' % (name, len(fr)))
    sheet = P.contact(cells, 1, labels, title='The seven kitchen particles, on night and day')
    written.append(P.save_png(sheet, os.path.join(out, 'cook_particles.png')))
    # a simulation: each kind in its own box, spawned and aged as the game ages a particle
    written.append(P.save_gif(particle_frames(assets), os.path.join(out, 'cook_particles.gif'), 50))
    return written


def particle_frames(assets, n=72):
    """A GIF's frames: seven boxes, each an emitter that lets its particles rise (or fall) and age through their sprites."""
    rng = np.random.default_rng(5)
    spec = {   # (spawn x range, y, vx, vy, gravity, life ticks, size px, count per second-ish)
        'cook_steam': ((70, 90), 150, 0.0, -1.15, 0.0, 34, 40, 0.5),
        'stew_bubble': ((52, 108), 134, 0.0, -0.1, 0.0, 12, 22, 0.55),
        'cook_smoke_dark': ((70, 90), 150, 0.0, -1.0, 0.0, 40, 46, 0.3),
        'cook_sizzle': ((54, 106), 140, 0.0, -3.2, 0.2, 18, 18, 0.6),
        'cook_crumb': ((50, 110), 90, 0.0, 0.0, 0.18, 22, 22, 0.35),
        'cook_call': ((74, 86), 150, 0.0, -0.9, 0.0, 28, 32, 0.12),
        'cook_foam': ((56, 104), 138, 0.0, -0.4, 0.0, 26, 28, 0.45),
    }
    sprites = {name: [_tint(f, PART_TINT[name]) for f in _part_frames(assets, name)] for name in spec}
    parts = {name: [] for name in spec}
    frames = []
    box = (160, 176)
    for tick in range(n + 40):
        sheet = Image.new('RGBA', (len(spec) * (box[0] + 6) + 6, box[1] + 26), P.BG + (255,))
        d = ImageDraw.Draw(sheet)
        for i, (name, (xr, y0, vx, vy, g, life, size, rate)) in enumerate(spec.items()):
            if tick < n and rng.random() < rate:
                parts[name].append([rng.uniform(*xr), y0 + rng.uniform(-3, 3), vx + rng.uniform(-0.3, 0.3), vy * rng.uniform(0.8, 1.2), 0])
            live = []
            tile = Image.new('RGBA', box, (28, 32, 46, 255) if name != 'cook_smoke_dark' else (150, 168, 186, 255))
            for p in parts[name]:
                p[0] += p[2]
                p[1] += p[3]
                p[3] += g
                p[4] += 1
                if p[4] >= life:
                    continue
                live.append(p)
                fr = sprites[name][min(len(sprites[name]) - 1, int(p[4] / life * len(sprites[name])))]
                sz = int(size * (0.7 + 0.5 * p[4] / life)) if name in ('cook_steam', 'cook_smoke_dark') else size
                s = fr.resize((sz, sz), Image.NEAREST)
                tile.alpha_composite(s, (int(p[0]) - sz // 2, int(p[1]) - sz // 2))
            parts[name] = live
            sheet.alpha_composite(tile, (6 + i * (box[0] + 6), 6))
            P.text(d, (6 + i * (box[0] + 6) + box[0] // 2, box[1] + 10), name, P.DIM, 11, 'ma')
        frames.append(sheet)
    return frames


# ================================================================ the renderers' textures

def renderer_sheet(assets, out):
    written = []
    d = os.path.join(assets, 'textures/entity/cook')
    im = {n: Image.open(os.path.join(d, n + '.png')).convert('RGBA') for n in ('surface', 'film', 'crust', 'ring')}
    tints = (('water', (0.62, 0.78, 1.0)), ('pottage', (0.78, 0.7, 0.4)), ('stew', (0.66, 0.40, 0.22)), ('greens', (0.46, 0.68, 0.30)),
             ('chowder', (0.96, 0.9, 0.78)), ('berry', (0.62, 0.26, 0.4)))
    pot = (62, 58, 66)
    k = 6
    cells, labels = [], []
    for n, t in tints:
        cell = P.on(P.up(_tint(im['surface'], t), k), pot, 4)
        cells.append(cell)
        labels.append('surface, tinted for %s' % n)
    cell = P.on(P.up(_tint(im['surface'], tints[2][1]), k), pot, 4)
    cell.alpha_composite(P.up(im['film'], k), (4, 4))
    cells.append(cell)
    labels.append('a SKIM call: the film over the stew')
    cells.append(P.on(P.up(im['crust'], k), pot, 4))
    labels.append('crust: a burnt pot')
    cell = P.on(P.up(_tint(im['surface'], tints[2][1]), k), (30, 28, 34), 4)
    cell.alpha_composite(P.up(im['ring'], k), (4, 4))
    cells.append(cell)
    labels.append('ring: the ready ring over the pot')
    sheet = P.contact(cells, 3, labels, title='Textures the renderers draw (textures/entity/cook/, 32 px)')
    written.append(P.save_png(sheet, os.path.join(out, 'cook_renderer_textures.png')))
    written.append(P.save_gif(surface_frames(im), os.path.join(out, 'cook_pot_surface.gif'), 50))
    return written


def surface_frames(im, n=120, size=224):
    """The pot's surface from above, as a renderer is meant to use it: the quad turning while it is stirred, its tint following the dish, a film
    spreading and being skimmed away, the ready ring tightening over it, and at the end the crust."""
    out = []
    pot = (62, 58, 66)
    water, stew = np.array((0.62, 0.78, 1.0)), np.array((0.66, 0.40, 0.22))
    for t in range(n):
        base = Image.new('RGBA', (size, size), pot + (255,))
        d = ImageDraw.Draw(base)
        d.ellipse([4, 4, size - 5, size - 5], fill=(40, 38, 44, 255))          # the pot's mouth
        add = min(1.0, max(0.0, (t - 6) / 30.0))
        tint = tuple(water + (stew - water) * add)
        ang = -t * (6.0 if 14 <= t < 70 else 1.2)                              # stirred, then idling
        s = _tint(im['surface'], tint).rotate(ang, resample=Image.NEAREST).resize((size - 16, size - 16), Image.NEAREST)
        base.alpha_composite(s, (8, 8))
        if 40 <= t < 78:                                                       # a film spreads from the middle, then a ladle clears it
            f = min(1.0, (t - 40) / 14.0) if t < 66 else max(0.0, 1.0 - (t - 66) / 12.0)
            if f > 0:
                sz = max(2, int((size - 16) * f))
                fl = im['film'].resize((sz, sz), Image.NEAREST)
                a = np.asarray(fl, float)
                a[..., 3] *= min(1.0, f * 1.4) if t < 66 else f
                fl = Image.fromarray(a.astype(np.uint8), 'RGBA')
                base.alpha_composite(fl, ((size - sz) // 2, (size - sz) // 2))
        if 80 <= t < 112:                                                      # the ready ring gathers at the rim and tightens
            u = (t - 80) / 31.0
            sc = 1.12 - 0.75 * math.sin(min(1.0, u * 1.1) * math.pi / 2)
            sz = int(size * sc)
            r = im['ring'].rotate(-t * 3, resample=Image.NEAREST).resize((sz, sz), Image.NEAREST)
            a = np.asarray(r, float)
            a[..., 3] *= 0.55 + 0.45 * math.sin(u * math.pi)
            r = Image.fromarray(a.astype(np.uint8), 'RGBA')
            base.alpha_composite(r, ((size - sz) // 2, (size - sz) // 2))
        if t >= 112:                                                           # and then the pot is forgotten
            base.alpha_composite(im['crust'].resize((size - 16, size - 16), Image.NEAREST), (8, 8))
        out.append(base)
    return out


# ================================================================ the cookshop's parchment

def parchment_sheet(out):
    import works_art as W
    cells, labels = [], []
    for k in W.WORKS:
        im = W.paper_sheet(k)
        cell = Image.new('RGBA', (im.width * 4 + 8, im.height * 4 + 8), (90, 84, 76, 255))
        cell.alpha_composite(P.up(im, 4), (4, 4))
        if k == 'cookshop':
            ImageDraw.Draw(cell).rectangle([0, 0, cell.width - 1, cell.height - 1], outline=(255, 214, 90, 255), width=3)
        cells.append(cell)
        labels.append('%s%s' % (W.WORKS_TITLE[k], ' (new)' if k == 'cookshop' else ''))
    sheet = P.contact(cells, 3, labels, title="The works post's six parchments (the new one is outlined; it sits in post.png at %d, %d)" % W.PAPER_AT['cookshop'])
    return [P.save_png(sheet, os.path.join(out, 'cook_cookshop_parchment.png'))]
