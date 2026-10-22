"""Small tools the previews share (cook_prev.py, cook_prev_blocks.py): fonts, labelled sheets, an evaluator for the item definitions (so a preview
shows what the game would show for a tier and a stage, not what the script meant to), and GIFs that rerun to the same bytes."""
import json
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

BG = (36, 34, 42)
INK = (226, 224, 218)
DIM = (150, 148, 156)
DARK = (22, 20, 28)
PARCH = (214, 190, 142)
SKY = (120, 150, 186)


def font(size=13):
    return ImageFont.load_default(size=size)


def text(d, xy, s, fill=INK, size=13, anchor='la'):
    d.text(xy, s, fill=fill, font=font(size), anchor=anchor)


def text_size(s, size=13):
    box = font(size).getbbox(s)
    return box[2] - box[0], box[3] - box[1]


def up(img, k):
    """An image enlarged by [k] with nearest-neighbour sampling."""
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def on(img, bg, pad=0):
    """[img] (RGBA) laid on a flat colour (an RGB tuple)."""
    base = Image.new('RGBA', (img.width + 2 * pad, img.height + 2 * pad), tuple(bg) + (255,))
    base.alpha_composite(img.convert('RGBA'), (pad, pad))
    return base


def tiled_bg(w, h, a=(64, 62, 72), b=(52, 50, 60), n=8):
    """A chequered backdrop that shows what is transparent in a sprite."""
    im = Image.new('RGBA', (w, h), a + (255,))
    d = ImageDraw.Draw(im)
    for y in range(0, h, n):
        for x in range(0, w, n):
            if ((x // n) + (y // n)) % 2:
                d.rectangle([x, y, x + n - 1, y + n - 1], fill=b + (255,))
    return im


def sprite_cell(img, k, bg=None, pad=3):
    """A sprite [k] times as big on a chequered ground (or a flat one)."""
    big = up(img.convert('RGBA'), k)
    cell = tiled_bg(big.width + 2 * pad, big.height + 2 * pad) if bg is None else Image.new('RGBA', (big.width + 2 * pad, big.height + 2 * pad), bg + (255,))
    cell.alpha_composite(big, (pad, pad))
    return cell


def contact(cells, cols, labels=None, gap=8, title=None, bg=BG, label_size=12, cell_size=None):
    """[cells] (images, any size) in a grid of [cols] columns, each with its label under it and an optional [title] over all."""
    cw = max(c.width for c in cells) if cell_size is None else cell_size[0]
    ch = max(c.height for c in cells) if cell_size is None else cell_size[1]
    lh = (label_size + 6) if labels else 0
    rows = (len(cells) + cols - 1) // cols
    th = (text_size(title, 16)[1] + 14) if title else 0
    sheet = Image.new('RGBA', (cols * (cw + gap) + gap, th + rows * (ch + lh + gap) + gap), bg + (255,))
    d = ImageDraw.Draw(sheet)
    if title:
        text(d, (gap, 6), title, INK, 16)
    for i, c in enumerate(cells):
        x = gap + (i % cols) * (cw + gap)
        y = th + gap + (i // cols) * (ch + lh + gap)
        sheet.alpha_composite(c.convert('RGBA'), (x + (cw - c.width) // 2, y + (ch - c.height) // 2))
        if labels and labels[i]:
            text(d, (x + cw // 2, y + ch + 2), labels[i], DIM, label_size, 'ma')
    return sheet


def save_png(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.convert('RGB').save(path, optimize=False)
    return path


def save_gif(frames, path, ms=50, sample=12):
    """A looping GIF of [frames] (PIL images), [ms] each (a number, or one per frame). One palette of 255 colours serves the whole clip, chosen by
    median cut (no dithering) from [sample] of its frames laid one over another; a pixel that is the same as in the frame before is left transparent
    over the last frame (index 255, the frames not disposed of), so a still scene with something moving in it costs a fraction of the bytes. Nothing in
    it is random: the same frames give the same bytes."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    rgb = [f.convert('RGB') for f in frames]
    pick = rgb[::max(1, len(rgb) // sample)][:sample]
    w, h = rgb[0].size
    mosaic = Image.new('RGB', (w, h * len(pick)))
    for i, f in enumerate(pick):
        mosaic.paste(f, (0, i * h))
    pal = mosaic.quantize(colors=255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    table = list(pal.getpalette()[:255 * 3])
    table += [0] * (256 * 3 - len(table))
    idx = [np.asarray(f.quantize(palette=pal, dither=Image.Dither.NONE)) for f in rgb]
    out = []
    for k, a in enumerate(idx):
        b = a.copy()
        if k:
            b[a == idx[k - 1]] = 255
        im = Image.fromarray(b, 'P')
        im.putpalette(table)
        out.append(im)
    out[0].save(path, save_all=True, append_images=out[1:], duration=ms, loop=0, optimize=False, disposal=1, transparency=255)
    return path


# ================================================================ the item definitions, read as the game reads them

class Items:
    """Items as the game would draw them from their files: `items/<id>.json` (a composite of models, selects on a component, tints), the models'
    layers, and the textures with their animation timing. [assets] is a mod assets folder."""

    def __init__(self, assets):
        self.assets = assets
        self._json = {}
        self._tex = {}

    def _read(self, rel):
        if rel not in self._json:
            with open(os.path.join(self.assets, rel)) as f:
                self._json[rel] = json.load(f)
        return self._json[rel]

    def strip(self, ref):
        """(frames, timing) of a texture `guildmark:item/...`: its frames as images and the (index, ticks) list the .mcmeta gives (or each frame once)."""
        if ref not in self._tex:
            path = os.path.join(self.assets, 'textures', ref.split(':', 1)[1] + '.png')
            im = Image.open(path).convert('RGBA')
            frames = [im.crop((0, y, im.width, y + im.width)) for y in range(0, im.height, im.width)]
            meta = path + '.mcmeta'
            if os.path.exists(meta):
                with open(meta) as f:
                    fl = json.load(f)['animation'].get('frames')
                timing = [(e['index'], e['time']) for e in fl] if fl else [(i, 1) for i in range(len(frames))]
            else:
                timing = [(i, 1) for i in range(len(frames))]
            self._tex[ref] = (frames, timing)
        return self._tex[ref]

    def frame(self, ref, tick):
        frames, timing = self.strip(ref)
        total = sum(t for _, t in timing)
        k = tick % total
        for i, t in timing:
            if k < t:
                return frames[i]
            k -= t
        return frames[timing[-1][0]]

    def layers(self, model_ref):
        """The texture references of an item model's layers, in order."""
        m = self._read('models/item/%s.json' % model_ref.split('item/', 1)[1])
        tex = m.get('textures', {})
        out = []
        i = 0
        while 'layer%d' % i in tex:
            out.append(tex['layer%d' % i])
            i += 1
        return out

    def _node(self, node, comps, tick, size):
        t = node['type'].split(':', 1)[1]
        if t == 'empty':
            return []
        if t == 'composite':
            out = []
            for m in node['models']:
                out += self._node(m, comps, tick, size)
            return out
        if t == 'select':
            comp = node['component']
            for case in node['cases']:
                if comp in comps and comps[comp] == case['when']:
                    return self._node(case['model'], comps, tick, size)
            return self._node(node['fallback'], comps, tick, size)
        if t == 'model':
            tints = node.get('tints', [])
            out = []
            for i, ref in enumerate(self.layers(node['model'])):
                im = self.frame(ref, tick).copy()
                if i < len(tints):
                    rgb = tints[i]['value']
                    c = np.array([(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255], float) / 255.0
                    a = np.asarray(im, float)
                    a[..., :3] *= c
                    im = Image.fromarray(np.clip(a + 0.5, 0, 255).astype(np.uint8), 'RGBA')
                out.append(im)
            return out
        raise ValueError(t)

    def render(self, item, comps=None, tick=0):
        """The item's sprite (RGBA) with the components [comps] ({'guildmark:dish_tier': 3}) at [tick]."""
        d = self._read('items/%s.json' % item)
        layers = self._node(d['model'], comps or {}, tick, 32)
        base = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
        for l in layers:
            base.alpha_composite(l)
        return base
