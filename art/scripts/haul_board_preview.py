"""The Haul Board as it will look, with its writing: previews for haul_board_art.py (given a preview dir).

The renderer that writes the board in the game is not in this script's reach; this draws a sample board by the same numbers, the
ones in haul_board_model.LAYOUT and the Kotlin table (every atlas cell, the rows' places, the hooks', the ticket paper's boxes), with
the market board's own chalk and ink lettering (market_board_letters.py), so what the numbers lay out can be seen, and so can the
board's models and textures standing in 3D beside the market board's. Nothing here is the game: no light, no ambient occlusion, and
the writing is flat planes in front of the slate (as market_board_preview.py's are).

Writes: haul_board_front.png (straight on, filled in), haul_board_bare.png (without writing), haul_board_3d.png (three views of the
filled board), haul_board_beside_market.png (the two boards side by side, from the front and from the side), haul_board_back.png,
haul_board_close_*.png (rows, tickets, shelf, crest), haul_board_textures.png (the paintings and materials), haul_board_crest.gif
(the glint) and haul_board_alive.gif: the board straight on for eight seconds, with the motions the renderer is meant to give the
atlas's pictures, drawn here from the same cells and numbers (a row's sand running and its glass turned end for end, the pennants
rippling in columns, the tickets stirring on their pegs, a chip drawn in three strokes, a ticket peeled off and dropped and a new one
fluttered in, stamped, its seal squashed down; the crest's glint, its long rest cut short). The loop is exact: the last picture is the
one before the first. It shows what the cells allow; how the game's renderer moves them is that renderer's.
"""
import copy
import json
import math
import os
import shutil
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import haul_board_icons as H
import haul_board_art as HA
import haul_board_model as M
import haul_board_paint as P
import market_board_letters as ML
import island_blockview as BV

L = M.LAYOUT
BG = (52, 56, 66)
CHALK = (0xEC, 0xE7, 0xDC)
CHALK_HEAD = (0xE8, 0xD7, 0x9E)
GILT = (0xE2, 0xBC, 0x6C)
INK = (0x2A, 0x1E, 0x1A)
INK_STAMP = (0x9C, 0x2A, 0x22)

# ================================================================ drawing

FONTS = {}


def font(hand):
    if hand not in FONTS:
        FONTS[hand] = ML.build(ML.CHALK if hand == 'chalk' else ML.INK, False)
    return FONTS[hand]


class Face:
    """A transparent picture of a piece of the board, R px to a block, drawn on in blocks (u right, v down)."""

    def __init__(self, R, w=3.0, h=2.0):
        self.R = R
        self.img = Image.new('RGBA', (int(round(w * R)), int(round(h * R))), (0, 0, 0, 0))
        self.cache = {}

    def put(self, im, u, v, w, h, angle=0.0, pivot=(0.5, 0.0), opacity=1.0):
        """The picture im, w x h blocks with its top left at u, v; turned counterclockwise by `angle` degrees about `pivot` (the
        fractions of it across and down)."""
        R = self.R
        pw, ph = max(1, int(round(w * R))), max(1, int(round(h * R)))
        s = im.convert('RGBa').resize((pw, ph), Image.LANCZOS).convert('RGBA')
        if opacity < 1.0:
            a = np.asarray(s).copy()
            a[..., 3] = (a[..., 3] * opacity).astype(np.uint8)
            s = Image.fromarray(a, 'RGBA')
        x, y = int(round(u * R)), int(round(v * R))
        if angle:
            pad = int(max(pw, ph) * 0.6) + 2
            canvas = Image.new('RGBA', (pw + 2 * pad, ph + 2 * pad), (0, 0, 0, 0))
            canvas.paste(s, (pad, pad))
            canvas = canvas.rotate(angle, resample=Image.BICUBIC, center=(pad + pivot[0] * pw, pad + pivot[1] * ph))
            self._clipped(canvas, x - pad, y - pad)
        else:
            self._clipped(s, x, y)

    def _clipped(self, s, x, y):
        W, Hh = self.img.size
        sx, sy = max(0, -x), max(0, -y)
        ex, ey = min(s.width, W - x), min(s.height, Hh - y)
        if ex <= sx or ey <= sy:
            return
        self.img.alpha_composite(s.crop((sx, sy, ex, ey)), (x + sx, y + sy))

    def glyph(self, ch, hand, size):
        key = (ch, hand, round(size, 6))
        if key not in self.cache:
            e = font(hand)[ch]
            if e['full'] is None:
                self.cache[key] = None
            else:
                alpha, box = e['full']
                w = max(1, int(round((box[2] - box[0]) * size * self.R)))
                h = max(1, int(round((box[3] - box[1]) * size * self.R)))
                a = Image.fromarray((np.clip(alpha, 0, 1) * 255).astype(np.uint8), 'L').resize((w, h), Image.LANCZOS)
                self.cache[key] = (np.asarray(a, np.float32) / 255.0, box)
        return self.cache[key]

    def width(self, s, hand, size):
        return sum(font(hand)[c]['advance'] for c in s if c in font(hand)) * size

    def text(self, s, u, base, size, colour, hand='chalk', align='left', opacity=1.0, squeeze=1.0):
        """The string s with its baseline at `base` blocks down, `size` blocks to a font pixel; u is its left, middle or right."""
        R = self.R
        total = self.width(s, hand, size) * squeeze
        if align == 'center':
            u -= total / 2
        elif align == 'right':
            u -= total
        pen = 0.0
        for ch in s:
            if ch not in font(hand):
                continue
            g = self.glyph(ch, hand, size)
            if g is not None:
                a, box = g
                x = int(round(u * R + (pen * squeeze + box[0] * squeeze) * size * R))
                y = int(round(base * R + (box[1] - ML.CAP) * size * R))
                layer = np.zeros(a.shape + (4,), np.uint8)
                layer[..., :3] = colour
                layer[..., 3] = (a * 255 * opacity).astype(np.uint8)
                self._clipped(Image.fromarray(layer, 'RGBA'), x, y)
            pen += font(hand)[ch]['advance']

    def over(self, base):
        out = Image.fromarray(np.asarray(base)).convert('RGBA') if not isinstance(base, Image.Image) else base.convert('RGBA')
        out.alpha_composite(self.img)
        return out


def cell(atlas, where, key):
    x, y, w, h = where[key]
    return atlas.crop((x, y, x + w, y + h))


# ================================================================ a sample board

ROWS = [
    # name, news age in hours, goods short, goods in glut, road, tickets out, booker's seal
    ('Eastwick', 7, ['bread', 'wheat'], ['wool'], 0, 3, 0),
    ('Saltmarsh', 18, ['cod', 'salmon', 'candle'], ['carrot'], 1, 0, 1),
    ('Ravenhold', 31, ['iron'], ['coal', 'flint'], 2, 2, 2),
    ('Greywater', 40, ['feather', 'arrow'], [], 0, 1, 3),
    ('Thornby', 52, [], ['potato', 'apple', 'honey'], 3, 0, 4),
    ('Caer Dune', 63, ['beef', 'pork', 'mutton'], ['chicken'], 1, 4, 5),
    ('Oakhaven', 70, ['leather'], ['string'], 0, 0, 0),
    ('Stonebridge', 83, ['beetroot'], ['bottle'], 2, 1, 1),
]
STALE_HOURS = 72

TICKETS = [
    # good, count, pay, shop, taken (a seal index or None), days left, kind
    ('wheat', 64, 12, 'Bakery', None, 5, 'plain'),
    ('wool', 32, 9, 'Cloth shop', 1, 3, 'plain'),
    ('carrot', 96, 14, 'Produce', None, 6, 'feast'),
    ('cod', 128, 18, 'Fish & fruit', 4, 2, 'plain'),
    ('iron', 16, 22, 'Smithy', None, 7, 'plain'),
    ('candle', 24, 7, 'Chandler', None, 4, 'relief'),
    ('arrow', 48, 11, 'Fletcher', 0, 1, 'plain'),
    ('bread', 32, 8, 'Bakery', None, 6, 'plain'),
]


def age_text(h):
    d, hh = divmod(int(h), 24)
    return ('%d d %d h' % (d, hh)) if d else '%d h' % hh


def pennant(atlas, where, road, t):
    """The pennant on its pole, the cloth waved by time t (a ripple that grows toward the fly)."""
    cloth = cell(atlas, where, 'pennant/' + H.ROADS[road])
    pole = cell(atlas, where, 'pennant/pole')
    out = Image.new('RGBA', (max(pole.width, 2 + cloth.width), pole.height), (0, 0, 0, 0))
    out.alpha_composite(pole)
    arr = np.asarray(cloth).copy()
    waved = np.zeros_like(arr)
    for x in range(cloth.width):
        k = x / max(1, cloth.width - 1)
        dy = int(round(math.sin(t * 2.4 - x * 0.55) * 1.7 * k))
        col = arr[:, x]
        waved[:, x] = np.roll(col, dy, axis=0)
        if dy > 0:
            waved[:dy, x] = 0
        elif dy < 0:
            waved[dy:, x] = 0
    out.alpha_composite(Image.fromarray(waved, 'RGBA'), HA.PENNANT_HOIST)
    return out


def draw_slate(face, atlas, where, t=0.0, rows=ROWS, custom=None):
    """The slate's writing: the title, then each row by LAYOUT: name, chips, hourglass and age, pennant, seal. `custom` (the
    board alive) gives, by row, a glass to draw in place of the one the age says, {row: (picture, degrees turned)}, and, by (row,
    'red' or 'green', place), a chip's {progress drawn, opacity}."""
    custom = custom or {}
    u0 = L['SLATE_U0'] + L['PAD_L']
    v0 = L['PANEL_V0']
    face.text('Word from the capitals', u0, v0 + L['TITLE_BASE'], L['S_TITLE'], CHALK_HEAD)
    for r, (name, age, short, glut, road, tickets, seal) in enumerate(rows):
        stale = age >= STALE_HOURS
        fade = 0.42 if stale else 1.0
        top = v0 + L['ROWS_TOP'] + r * L['ROW_PITCH']
        face.text(name, u0 + L['NAME_X'], top + L['NAME_BASE'], L['S_NAME'], CHALK, opacity=fade)
        csz = 0.07
        for colour, goods, x0 in (('red', short, L['CHIP_RED_X']), ('green', glut, L['CHIP_GREEN_X'])):
            for k, g in enumerate(goods[:3]):
                chip = cell(atlas, where, 'chip/%s_%s' % (g, colour))
                shown = custom.get('chips', {}).get((r, colour, k))
                if shown is not None:
                    progress, opacity = shown
                    if progress <= 0 or opacity <= 0:
                        continue
                    chip = drawn(chip, progress)
                    face.put(chip, u0 + x0 + k * L['CHIP_PITCH'], top + L['CHIP_TOP'], csz, csz, opacity=fade * opacity)
                else:
                    face.put(chip, u0 + x0 + k * L['CHIP_PITCH'], top + L['CHIP_TOP'], csz, csz, opacity=fade)
        lvl = min(H.HG_LEVELS - 1, int(age / STALE_HOURS * H.HG_LEVELS))
        hw, hh = H.HG_W * L['TEXEL'], H.HG_H * L['TEXEL']
        glass, turned = custom.get('glass', {}).get(r, (cell(atlas, where, 'hourglass/level_%d' % lvl), 0.0))
        face.put(glass, u0 + L['HOURGLASS_X'], top + L['HOURGLASS_MID'] - hh / 2, hw, hh, angle=turned, pivot=(0.5, 0.5), opacity=fade)
        face.text(age_text(age), u0 + L['AGE_X'], top + L['AGE_BASE'], L['S_AGE'], CHALK, opacity=fade)
        pm = pennant(atlas, where, road, t + r * 0.9)
        face.put(pm, u0 + L['PENNANT_X'], top + L['PENNANT_TOP'], pm.width * L['TEXEL'], pm.height * L['TEXEL'], opacity=fade)
        if tickets:
            ss = H.SEAL * L['SEAL_TEXEL']
            su = u0 + L['SEAL_X']
            face.put(cell(atlas, where, 'seal/0'), su, top + L['SEAL_MID'] - ss / 2, ss, ss, opacity=fade)
            face.text(str(tickets), u0 + L['COUNT_X'], top + L['COUNT_BASE'], L['S_COUNT'], CHALK, opacity=fade)
        if stale:
            # The strike-through in three pieces at the sprite's own scale: its two ends as they are, the middle stretched
            sw = (L['SLATE_U1'] - L['SLATE_U0']) - L['PAD_L'] - L['PAD_R']
            struck = cell(atlas, where, 'mark/struck')
            te = L['TEXEL']
            e, h = HA.STRUCK_END, struck.height
            y = top + L['STRUCK_Y']
            face.put(struck.crop((0, 0, e, h)), u0, y, e * te, h * te, opacity=0.85)
            face.put(struck.crop((e, 0, struck.width - e, h)), u0 + e * te, y, sw - 2 * e * te, h * te, opacity=0.85)
            face.put(struck.crop((struck.width - e, 0, struck.width, h)), u0 + sw - e * te, y, e * te, h * te, opacity=0.85)


def ticket_image(atlas, where, ticket, R, t=0.0, seal_scale=1.0):
    """A ticket as it hangs: its paper, the good's chip, the count, the pay, the shop, the seal of whoever took it (at seal_scale of
    its size about its middle, for the moment it is pressed), the notches."""
    good, count, pay, shop, taken, left, kind = ticket
    TW = L['TICKET_BLOCKS']
    TH = TW * H.TICKET_H / H.TICKET_W
    tex = TW / H.TICKET_W                                  # blocks to a texel of the paper
    f = Face(R, TW, TH)
    f.put(cell(atlas, where, 'ticket/paper'), 0, 0, TW, TH)

    def box(name):
        x, y, w, h = HA.TICKET_BOXES[name]
        return x * tex, y * tex, w * tex, h * tex

    if kind == 'feast':
        x, y, w, h = box('FESTOON')
        fe = cell(atlas, where, 'mark/festoon')
        f.put(fe, x, y, w, fe.height * (w / fe.width))
    if kind == 'relief':
        x, y, w, h = box('CROSS')
        f.put(cell(atlas, where, 'mark/relief_cross'), x, y, w, h)
    x, y, w, h = box('CHIP')
    f.put(cell(atlas, where, 'chip/%s_red' % good), x, y, w, h)
    x, y, w, h = box('COUNT')
    f.text(str(count), x + w, y + h * 0.92, 1.12 * tex, INK, hand='ink', align='right')
    x, y, w, h = box('PAY')
    f.text('%d' % pay, x, y + h * 0.9, 1.0 * tex, INK_STAMP, hand='ink')
    f.text(ML.COIN, x + f.width('%d' % pay, 'ink', 1.0 * tex) + 0.5 * tex, y + h * 0.9, 1.0 * tex, INK_STAMP, hand='ink')
    x, y, w, h = box('SHOP')
    f.text(shop, x, y + h, 0.72 * tex, INK, hand='ink')
    if taken is not None:
        x, y, w, h = box('SEAL')
        x, y, w, h = x + w * (1 - seal_scale) / 2, y + h * (1 - seal_scale) / 2, w * seal_scale, h * seal_scale
        f.put(cell(atlas, where, 'seal/%d' % taken), x, y, w, h)
    total = min(HA.NOTCH_MAX, 7)
    for k in range(total):
        im = cell(atlas, where, 'notch/open' if k < left else 'notch/spent')
        f.put(im, (HA.NOTCH_AT[0] + k * HA.NOTCH_PITCH) * tex, HA.NOTCH_AT[1] * tex, H.NOTCH_W * tex, H.NOTCH_H * tex)
    return f.img


def draw_tickets(face, atlas, where, t=0.0, tickets=TICKETS, only=None):
    TW = L['TICKET_BLOCKS']
    TH = TW * H.TICKET_H / H.TICKET_W
    for i, tk in enumerate(tickets):
        tier, k = divmod(i, M.HOOKS_PER_TIER)
        img = ticket_image(atlas, where, tk, face.R, t)
        u = L['HOOK_U0'] + k * L['HOOK_PITCH']
        v = L['HOOK_V%d' % tier]
        # The eyelet on the peg: the paper hangs from it and stirs a little on it
        ang = 2.4 * math.sin(t * 1.6 + i * 1.3) * (1.0 if i % 3 else 0.4)
        ex, ey = H.TICKET_EYELET[0] / H.TICKET_W, H.TICKET_EYELET[1] / H.TICKET_H
        face.put(img, u - TW * ex, v - TH * ey, TW, TH, angle=ang, pivot=(ex, ey))


def draw_header(face):
    face.text('HAUL BOARD', 1.5, L['HEADER_V0'] + L['HEADER_BASE'], L['S_HEADER'], GILT, align='center')


# ================================================================ models in 3D

def assemble(assets, name, prefix='', dx=0.0):
    """The six cells of a board as one model: the board's left at model x 32, its right at -16, as market_board_preview's."""
    els, tex = [], {}
    for part, (col, row) in M.PARTS.items():
        with open(os.path.join(assets, 'models', 'block', '%s_%s.json' % (name, part))) as f:
            m = json.load(f)
        tex = {prefix + k: v for k, v in m['textures'].items()}
        for e in m['elements']:
            e = copy.deepcopy(e)
            for k in ('from', 'to'):
                e[k][0] += -col * 16 + dx
                e[k][1] += row * 16
            if 'rotation' in e:
                e['rotation']['origin'][0] += -col * 16 + dx
                e['rotation']['origin'][1] += row * 16
            for f in e['faces'].values():
                if prefix:
                    f['texture'] = '#' + prefix + f['texture'][1:]
            els.append(e)
    return {'textures': tex, 'elements': els}


def plane(z, tex_key, x0=-16, x1=32, y0=0, y1=32):
    return {'from': [x0, y0, z], 'to': [x1, y1, z + 0.01], 'shade': False,
            'faces': {'north': {'uv': [0, 0, 16, 16], 'texture': '#' + tex_key}}}


def with_writing(model, header=True, tickets=True):
    """The model with the writing's planes: the slate's just in front of its face, the header's in front of the plank's (its
    texture's rows 27 to 30 of the 32), the tickets' where they hang."""
    m = copy.deepcopy(model)
    m['textures']['writing'] = 'guildmark:preview/writing'
    m['elements'].append(plane(L['SLATE_DEPTH'] * 16 - 0.06, 'writing'))
    if header:
        m['textures']['header'] = 'guildmark:preview/header'
        z = L['HEADER_DEPTH'] * 16 - 0.06
        m['elements'].append({'from': [-16, 27, z], 'to': [32, 30, z + 0.01], 'shade': False,
                              'faces': {'north': {'uv': [0, 1.0, 16, 2.5], 'texture': '#header'}}})
    if tickets:
        m['textures']['tickets'] = 'guildmark:preview/tickets'
        m['elements'].append(plane(M.TICKET_PLANE + 0.05, 'tickets'))
    return m


class Scene:
    """The scratch assets the renderer reads (the real ones plus the writing planes), and the crest's frame shown."""

    def __init__(self, assets, work):
        self.assets = assets
        self.root = work
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(os.path.join(work, 'textures', 'preview'), exist_ok=True)
        for sub in ('textures/block', 'models/block'):
            shutil.copytree(os.path.join(assets, sub), os.path.join(work, sub))
        self.frame = 0
        self._orig = BV.load_texture
        BV.load_texture = self.load_texture

    def load_texture(self, assets, ref, frame=0, tick=None):
        """An animated strip whose .mcmeta gives the frame's size (the crest's is not square), at the frame asked for."""
        ns, _, path = ref.partition(':')
        file = os.path.join(assets, 'textures', (path or ns) + '.png')
        if os.path.exists(file + '.mcmeta'):
            meta = json.load(open(file + '.mcmeta')).get('animation', {})
            if 'height' in meta:
                im = np.asarray(Image.open(file).convert('RGBA'), np.float32)
                h = meta['height']
                k = self.frame % (im.shape[0] // h)
                return im[k * h:(k + 1) * h]
        return self._orig(assets, ref, frame, tick)

    def writing(self, slate, header, tickets):
        slate.img.save(os.path.join(self.root, 'textures', 'preview', 'writing.png'))
        header.img.save(os.path.join(self.root, 'textures', 'preview', 'header.png'))
        tickets.img.save(os.path.join(self.root, 'textures', 'preview', 'tickets.png'))

    def render(self, model, yaw, elev, scale, size, center):
        return Image.fromarray(BV.render_model(model, self.root, yaw=yaw, elev=elev, scale=scale, size=size, center=center, bg=BG, ss=2))

    def close(self):
        BV.load_texture = self._orig


def front(scene, model, R, size_blocks=(3.0, 2.6), top_blocks=2.5):
    """The board straight on, R px to a block (the top of the crest in the picture); gives it and the face's top left in it."""
    W, Hh = int(size_blocks[0] * R), int(size_blocks[1] * R)
    cy = top_blocks * 16 - Hh / (R / 16.0) / 2
    img = scene.render(model, 180.0, 0.0, R / 16.0, (W, Hh), (8.0, cy, 12.0))
    top = int(round(Hh / 2 - (32 - cy) * R / 16.0))
    return img, top


def filled_front(scene, R, t=0.0, frame=0):
    scene.frame = frame
    base = assemble(scene.root, 'haul_board')
    atlas = Image.open(os.path.join(scene.root, 'textures', 'block', 'haul_board', 'atlas.png')).convert('RGBA')
    where = HA.build()[1]
    slate, header, tickets = Face(R), Face(R), Face(R)
    draw_slate(slate, atlas, where, t)
    draw_header(header)
    draw_tickets(tickets, atlas, where, t)
    scene.writing(slate, header, tickets)
    return base, slate, tickets


def previews(assets, out, work):
    """All the previews, into `out`; `work` is a scratch dir."""
    os.makedirs(out, exist_ok=True)
    sc = Scene(assets, work)
    try:
        R = 360
        base, slate, tickets = filled_front(sc, R)
        bare, top = front(sc, base, R)
        bare.save(os.path.join(out, 'haul_board_bare.png'))
        full_model = with_writing(base)
        # The writing planes are the board's 3 x 2 blocks; in a front view they sit at the picture's `top`
        img = front(sc, full_model, R)[0]
        img.save(os.path.join(out, 'haul_board_front.png'))
        # Closer looks, from the same front render
        W, Hh = img.size
        crop = lambda im, a, b, c, d: im.crop((int(a * R), int(b * R + top), int(c * R), int(d * R + top)))
        big = lambda im: im.resize((im.width * 2, im.height * 2), Image.LANCZOS)
        big(crop(img, 0.2, 0.3, 1.45, 1.62)).save(os.path.join(out, 'haul_board_close_rows.png'))
        big(crop(img, 1.5, 0.3, 2.85, 1.62)).save(os.path.join(out, 'haul_board_close_tickets.png'))
        views = []
        for yaw, elev in ((205.0, 12.0), (160.0, 10.0)):
            views.append(sc.render(full_model, yaw, elev, 14.0, (900, 700), (8.0, 18.0, 12.0)))
        three = Image.new('RGB', (sum(v.width for v in views), views[0].height), BG)
        x = 0
        for v in views:
            three.paste(v, (x, 0))
            x += v.width
        three.save(os.path.join(out, 'haul_board_3d.png'))
        sc.render(base, 25.0, 14.0, 14.0, (900, 700), (8.0, 18.0, 12.0)).save(os.path.join(out, 'haul_board_back.png'))
        sc.render(full_model, 195.0, 10.0, 44.0, (900, 520), (-2.0, 10.5, 11.0)).save(os.path.join(out, 'haul_board_close_shelf.png'))
        sc.render(full_model, 190.0, 10.0, 40.0, (900, 520), (8.0, 34.0, 12.0)).save(os.path.join(out, 'haul_board_close_crest.png'))
        beside(sc, out, full_model)
        textures(assets, out)
        crest_gif(sc, out, base)
        alive_gif(sc, out, base)
    finally:
        sc.close()


def beside(sc, out, haul):
    """The two boards on one ground: the market board to the viewer's left, three blocks apart."""
    mb = assemble(sc.root, 'market_board', 'mb_', dx=48 + 8)
    both = {'textures': {**mb['textures'], **haul['textures']}, 'elements': mb['elements'] + haul['elements']}
    for yaw, elev, name in ((190.0, 8.0, 'haul_board_beside_market.png'), (140.0, 12.0, 'haul_board_beside_market_side.png')):
        img = sc.render(both, yaw, elev, 9.0, (1500, 640), (8.0 + 28, 17.0, 12.0))
        img.save(os.path.join(out, name))


def textures(assets, out):
    tex = os.path.join(assets, 'textures', 'block', 'haul_board')
    L_ = lambda n: Image.open(os.path.join(tex, n + '.png')).convert('RGBA')
    crest = L_('crest')
    ims = [L_('front'), L_('back'), crest.crop((0, 0, 128, 56)), L_('wood'), L_('post'), L_('cap'), L_('trim'), L_('props')]
    H.sheet(ims, ['front (96x64 over the whole 3x2 board)', 'back', 'crest (frame 0 of 12)', 'wood', 'post', 'cap', 'trim', 'props'], 6, 3, (40, 38, 46)).save(os.path.join(out, 'haul_board_textures.png'))


def crest_gif(sc, out, base):
    meta = json.load(open(os.path.join(sc.root, 'textures', 'block', 'haul_board', 'crest.png.mcmeta')))['animation']
    strip = Image.open(os.path.join(sc.root, 'textures', 'block', 'haul_board', 'crest.png')).convert('RGB')
    n = strip.height // meta['height']
    frames = [strip.crop((0, k * meta['height'], strip.width, (k + 1) * meta['height'])).resize((strip.width * 4, meta['height'] * 4), Image.NEAREST) for k in range(n)]
    pal = Image.new('RGB', (frames[0].width, frames[0].height * n))
    for k, f in enumerate(frames):
        pal.paste(f, (0, k * f.height))
    q = pal.quantize(colors=200, method=Image.Quantize.MEDIANCUT)
    gifs = [f.quantize(palette=q, dither=Image.Dither.NONE) for f in frames]
    order = [0] * 8 + list(range(1, n))
    gifs = [gifs[i] for i in order]
    dur = [100] * 8 + [meta['frametime'] * 50] * (n - 1)
    gifs[0].save(os.path.join(out, 'haul_board_crest.gif'), save_all=True, append_images=gifs[1:], duration=dur, loop=0, optimize=False, disposal=1)


# ================================================================ the board alive

ALIVE_TICKS = 160            # the loop, in game ticks (eight seconds)
ALIVE_STEP = 2               # game ticks to a picture
ALIVE_R = 260                # px to a block
ALIVE_ROW = 0                # the row whose glass runs, then is turned: Eastwick
ALIVE_CHIP = (1, 'red', 2)   # the chip that is drawn in three strokes, then rubbed out: Saltmarsh's candle
ALIVE_TICKET = 3             # the ticket that is peeled off, and the new one that is hung in its place
RUN_FROM, RUN_TO = 4, 116    # the sand runs from a fresh glass to a spent one (a time-lapse: really a level takes nine hours)
TURN_FROM, TURN_TO = 124, 144    # and the glass is turned end for end
CHIP_FROM, CHIP_RUB = 30, 146    # the chip's three strokes begin; the chip is rubbed out
PEEL, FALL, HANG, STAMP = 24, 32, 80, 100    # the ticket's corner lifts; it drops; a new one flutters in; it is stamped
GLINT_AT = 116               # the crest's glint begins (the long rest before it is cut to fit the loop)
STIRS = 2                    # times each ticket sways to and fro in a loop
RIPPLES = 8                  # times each pennant's ripple passes in a loop


def ease(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


def drawn(chip, progress):
    """A chip drawn so far: the ring and what is in it from the top, clockwise, as far as `progress` (0 to 1) of the way round."""
    a = np.asarray(chip).copy()
    h, w = a.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    turn = (np.arctan2(xs + 0.5 - w / 2.0, -(ys + 0.5 - h / 2.0)) % (2 * np.pi)) / (2 * np.pi)
    a[..., 3] = np.where(turn <= progress, a[..., 3], 0)
    return Image.fromarray(a, 'RGBA')


def strokes(t, start=CHIP_FROM, stroke=8, gap=2):
    """How much of a chip is drawn at tick t: three strokes of `stroke` ticks, `gap` apart, a third of the way round each."""
    d = t - start
    if d <= 0:
        return 0.0
    for k in range(3):
        a = k * (stroke + gap)
        if d < a + stroke:
            return (k + ease((d - a) / stroke)) / 3.0 if d >= a else k / 3.0
    return 1.0


def glass_image(atlas, where, frac, phase):
    """The hourglass with its sand run `frac` of the way, from the atlas's parts: the empty glass, then the upper sand with its
    rows cut from the top down, the lower with its rows cut from the bottom up, and the falling sand's frame `phase`."""
    out = cell(atlas, where, 'hourglass/frame').copy()
    up = np.asarray(cell(atlas, where, 'hourglass/sand_top')).copy()
    lo = np.asarray(cell(atlas, where, 'hourglass/sand_bottom')).copy()
    rows = lambda a: [y for y in range(a.shape[0]) if a[y, :, 3].any()]
    ru, rl = rows(up), rows(lo)
    cut_u = int(round(ru[0] + (ru[-1] + 1 - ru[0]) * frac))
    cut_l = int(round(rl[-1] + 1 - (rl[-1] + 1 - rl[0]) * frac))
    up[:cut_u] = 0
    lo[:cut_l] = 0
    out.alpha_composite(Image.fromarray(up, 'RGBA'))
    out.alpha_composite(Image.fromarray(lo, 'RGBA'))
    if 0.0 < frac < 1.0:
        y0 = max(H.HG_NECK - 3, cut_u)
        n = max(1, min(H.HG_STREAM_H, cut_l - y0))
        out.alpha_composite(cell(atlas, where, 'hourglass/stream_%d' % (phase % H.HG_STREAM_FRAMES)).crop((0, 0, H.HG_STREAM_W, n)),
                            (H.HG_W // 2 - 1, y0))
    return out


def alive_rows(T):
    """The sample rows at tick T: the first one's news ages as its sand runs (and goes stale, faded and struck through, when it
    has all run), then the glass is turned and the news is fresh."""
    rows = list(ROWS)
    if T < RUN_FROM or T >= TURN_TO:
        age = 0.0
    elif T <= RUN_TO:
        age = STALE_HOURS * (T - RUN_FROM) / float(RUN_TO - RUN_FROM)
    else:
        age = float(STALE_HOURS)
    name, _, short, glut, road, tickets, seal = rows[ALIVE_ROW]
    rows[ALIVE_ROW] = (name, age, short, glut, road, tickets, seal)
    return rows


def alive_custom(atlas, where, T):
    """What the slate draws in place of its still pictures at tick T: the running, turning glass and the chip being drawn."""
    if T < RUN_FROM:
        frac, turned = 0.0, 0.0
    elif T <= RUN_TO:
        frac, turned = (T - RUN_FROM) / float(RUN_TO - RUN_FROM), 0.0
    elif T < TURN_FROM:
        frac, turned = 1.0, 0.0
    elif T < TURN_TO:
        frac, turned = 1.0, 180.0 * ease((T - TURN_FROM) / float(TURN_TO - TURN_FROM))
    else:
        frac, turned = 0.0, 0.0
    if T >= CHIP_RUB:
        chip = (1.0, max(0.0, 1.0 - ease((T - CHIP_RUB) / 12.0)))
    else:
        chip = (strokes(T), 1.0)
    return {'glass': {ALIVE_ROW: (glass_image(atlas, where, frac, T // ALIVE_STEP), turned)}, 'chips': {ALIVE_CHIP: chip}}


def draw_tickets_alive(face, atlas, where, T):
    """The rail's tickets at tick T: each stirring on its peg; one peeled off and dropped, a new one fluttering in on its peg and
    stamped (a 3-tick thump, the seal squashed down and settling)."""
    TW = L['TICKET_BLOCKS']
    TH = TW * H.TICKET_H / H.TICKET_W
    ts = T / float(ALIVE_TICKS) * STIRS * 2 * math.pi / 1.6
    ex, ey = H.TICKET_EYELET[0] / H.TICKET_W, H.TICKET_EYELET[1] / H.TICKET_H
    order = [i for i in range(len(TICKETS)) if i != ALIVE_TICKET] + [ALIVE_TICKET]
    for i in order:
        tk = TICKETS[i]
        tier, k = divmod(i, M.HOOKS_PER_TIER)
        u = L['HOOK_U0'] + k * L['HOOK_PITCH']
        v = L['HOOK_V%d' % tier]
        ang = 2.4 * math.sin(ts * 1.6 + i * 1.3) * (1.0 if i % 3 else 0.4)
        du, dv, op, squash = 0.0, 0.0, 1.0, 1.0
        if i == ALIVE_TICKET:
            bare = tk[:4] + (None,) + tk[5:]
            if PEEL <= T < FALL:                                   # its corner comes off the peg
                x = ease((T - PEEL) / float(FALL - PEEL))
                ang, dv = ang + 30 * x, -0.012 * x
            elif FALL <= T < HANG:                                 # and it drops, turning, and fades
                d = (T - FALL) / 20.0
                if d > 0.8:
                    continue
                ang, dv, op = ang + 30 + 150 * d, -0.012 + 2.0 * d * d, 1.0 - ease((d - 0.35) / 0.45)
                du = 0.05 * d
            elif HANG <= T < STAMP:                                # a new one flutters in on the peg, unsealed
                d = T - HANG
                dv, op = -0.30 * (1 - ease(d / 8.0)), ease(d / 3.0)
                ang += 22 * math.exp(-d / 6.0) * math.cos(2 * math.pi * d / 9.0)
                tk = bare
            elif T >= STAMP:
                d = T - STAMP
                dv = 0.012 * max(0.0, 1 - d / 3.0)                 # the thump
                squash = 1 + 0.55 * math.exp(-d / 2.5) * math.cos(math.pi * d / 2.5) if d < 12 else 1.0
                ang += 22 * math.exp(-(T - HANG) / 6.0) * math.cos(2 * math.pi * (T - HANG) / 9.0)
        img = ticket_image(atlas, where, tk, face.R, ts, seal_scale=squash)
        face.put(img, u + du - TW * ex, v + dv - TH * ey, TW, TH, angle=ang, pivot=(ex, ey), opacity=op)


def alive_gif(sc, out, base):
    """haul_board_alive.gif: see the head of this file. The board's models are drawn once for each frame of the crest's strip; the
    slate's and the rail's writing, which the game's planes show unshaded, is laid over them for each picture."""
    R = ALIVE_R
    atlas = Image.open(os.path.join(sc.root, 'textures', 'block', 'haul_board', 'atlas.png')).convert('RGBA')
    where = HA.build()[1]
    meta = json.load(open(os.path.join(sc.root, 'textures', 'block', 'haul_board', 'crest.png.mcmeta')))['animation']
    n = Image.open(os.path.join(sc.root, 'textures', 'block', 'haul_board', 'crest.png')).height // meta['height']
    bare, top = [], 0
    for k in range(n):
        sc.frame = k
        img, top = front(sc, base, R)
        bare.append(img.convert('RGBA'))
    sc.frame = 0
    shared = {}
    pics = []
    for step in range(ALIVE_TICKS // ALIVE_STEP):
        T = step * ALIVE_STEP
        slate, header, tickets = Face(R), Face(R), Face(R)
        for f in (slate, header, tickets):
            f.cache = shared
        t_ripple = T / float(ALIVE_TICKS) * RIPPLES * 2 * math.pi / 2.4
        draw_slate(slate, atlas, where, t_ripple, alive_rows(T), alive_custom(atlas, where, T))
        draw_header(header)
        draw_tickets_alive(tickets, atlas, where, T)
        k = 1 + (T - GLINT_AT) // ALIVE_STEP if GLINT_AT <= T < GLINT_AT + ALIVE_STEP * (n - 1) else 0
        pic = bare[k].copy()
        for f in (slate, header, tickets):
            pic.alpha_composite(f.img, (0, top))
        pics.append(pic.convert('RGB'))
    # One palette for the whole loop, made from a sheet of some of its pictures and all of the glint's
    sample = pics[::3] + [pics[(GLINT_AT // ALIVE_STEP) + k] for k in range(n - 1)]
    sheet = Image.new('RGB', (pics[0].width, pics[0].height * len(sample)))
    for j, p in enumerate(sample):
        sheet.paste(p, (0, j * p.height))
    q = sheet.quantize(colors=160, method=Image.Quantize.MEDIANCUT)
    gifs = [p.quantize(palette=q, dither=Image.Dither.NONE) for p in pics]
    gifs[0].save(os.path.join(out, 'haul_board_alive.gif'), save_all=True, append_images=gifs[1:], duration=ALIVE_STEP * 50, loop=0,
                 optimize=False, disposal=1)
