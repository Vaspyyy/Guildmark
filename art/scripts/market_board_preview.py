"""Previews of the market board, for looking at on a phone: the board's textures, the board in 3D from the front and the
back, a filled-in face as the game would draw it, and a GIF of the crier at work (the title rewritten, three prices
rubbed out and chalked up stroke by stroke, a slip of news flying up and tacked on).

    python3 -B art/scripts/market_board_art.py src/main/resources/assets/guildmark <preview dir> <minecraft client jar>

(market_board_art.py calls previews() here once it has painted everything.) The face is laid out by a port of
client/MarketBoardRenderer.kt's layout and timing, from the same numbers (market_board_art.LAYOUT), so what's here is
where the game puts it. The goods' icons come from the client jar given (any 26.x client or patched jar).
"""
import copy
import json
import math
import os
import shutil
import sys
import unicodedata
import zipfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import market_board_art as MA
import market_board_letters as ML
import island_blockview as BV
from hdkit import sheet

L = MA.LAYOUT
C = MA.COLOURS
T = ML.T
CAP = 7.0
TIP = 0.95
SCRATCH = '/tmp/claude-0/board/pv_assets'
# MarketBoardBlockEntity.LONG_AGO: a slip pinned (or a line chalked) while nobody was by
LONG_AGO = -(2 ** 63) // 4

GOODS = ['bread', 'wheat', 'carrot', 'potato', 'beetroot', 'apple', 'honey', 'cod', 'salmon', 'beef', 'pork', 'mutton',
         'chicken', 'wool', 'string', 'leather', 'feather', 'arrow', 'flint', 'candle', 'bottle', 'iron', 'coal']
ICONS = {'honey': 'item/honey_bottle', 'beef': 'item/cooked_beef', 'pork': 'item/cooked_porkchop',
         'mutton': 'item/cooked_mutton', 'chicken': 'item/cooked_chicken', 'wool': 'block/white_wool',
         'bottle': 'item/glass_bottle', 'iron': 'item/iron_ingot'}
SECTION_KEYS = ['staples', 'produce', 'catch', 'meat', 'wares']


def rgb(c):
    return np.array([(c >> 16) & 255, (c >> 8) & 255, c & 255], float)


def mix(a, b, t):
    return a + (b - a) * min(1.0, max(0.0, t))


# ================================================================ the lettering, as the game reads it

class Cell:
    """An image of the atlas (alpha, or rgb + alpha) with its top left at (left, top) font px of its glyph."""

    def __init__(self, alpha, left, top, rgb_=None):
        self.a = alpha
        self.rgb = rgb_
        self.left, self.top = left, top
        self.width = alpha.shape[1] / T
        self.height = alpha.shape[0] / T


class Hand:
    def __init__(self, built, gap):
        self.g = built
        self.gap = gap

    def glyph(self, c):
        g = self.g.get(c)
        if g is None:
            c2 = {'‘': "'", '’': "'", '‛': "'", '“': '"', '”': '"'}.get(c)
            if c2 is None:
                c2 = (unicodedata.normalize('NFD', c) or '?')[0]
            g = self.g.get(c2)
        return g

    def width(self, text):
        w = 0.0
        for c in text:
            g = self.glyph(c)
            w += g['advance'] if g else 3.0
        return max(0.0, w - self.gap)


CHALK = INK = None
EXTRA = {}


def load_hands():
    global CHALK, INK
    if CHALK is None:
        CHALK = Hand(ML.build(ML.CHALK, True), ML.CHALK.gap)
        INK = Hand(ML.build(ML.INK, False), ML.INK.gap)
        for name, (a, box) in (('underline', ML.chalk_line(40.0, 3, wave=0.18)), ('strike', ML.chalk_line(30.0, 8, wave=0.35, width=0.9)),
                               ('swash', ML.chalk_line(56.0, 11, wave=0.3)), ('smudge', ML.smudge(40.0, 6.0, 21))):
            EXTRA[name] = Cell(a, box[0], box[1])
        srgb, sa = ML.stick()
        EXTRA['stick'] = Cell(sa, 0.0, 0.0, srgb)


# ================================================================ a canvas of the face, in blocks

class Face:
    """RGBA (premultiplied) over the board's face: R pixels to a block, u right and v down from its top left."""

    def __init__(self, R, w=3.0, h=2.0):
        self.R = R
        self.W, self.H = int(round(w * R)), int(round(h * R))
        self.p = np.zeros((self.H, self.W, 3), np.float32)
        self.a = np.zeros((self.H, self.W), np.float32)

    def blit(self, alpha, colour, P0, Px, Py, opacity=1.0, src=None, rgb_img=None):
        """Lays the image (alpha, tinted [colour], or rgb_img) over the parallelogram P0 + s Px + t Py (face blocks),
        s and t running 0..1 over [src] (texels x0, y0, x1, y1; the whole image if not given)."""
        if opacity <= 0.003:
            return
        h, w = alpha.shape
        x0, y0, x1, y1 = src if src is not None else (0.0, 0.0, float(w), float(h))
        if x1 - x0 <= 1e-6 or y1 - y0 <= 1e-6:
            return
        R = self.R
        P0 = np.asarray(P0, float) * R
        Px = np.asarray(Px, float) * R
        Py = np.asarray(Py, float) * R
        cs = np.array([P0, P0 + Px, P0 + Py, P0 + Px + Py])
        bx0, by0 = np.floor(cs.min(axis=0)).astype(int)
        bx1, by1 = np.ceil(cs.max(axis=0)).astype(int)
        bx0, by0 = max(0, bx0), max(0, by0)
        bx1, by1 = min(self.W, bx1), min(self.H, by1)
        if bx0 >= bx1 or by0 >= by1:
            return
        M = np.array([[Px[0], Py[0]], [Px[1], Py[1]]])
        if abs(np.linalg.det(M)) < 1e-9:
            return
        Mi = np.linalg.inv(M)
        ys, xs = np.mgrid[by0:by1, bx0:bx1].astype(np.float32)
        dx, dy = xs + 0.5 - P0[0], ys + 0.5 - P0[1]
        s = Mi[0, 0] * dx + Mi[0, 1] * dy
        t = Mi[1, 0] * dx + Mi[1, 1] * dy
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        if not inside.any():
            return
        sx = x0 + s * (x1 - x0) - 0.5
        sy = y0 + t * (y1 - y0) - 0.5

        def sample(img):
            pad = np.pad(img, ((1, 1), (1, 1)) + ((0, 0),) * (img.ndim - 2))
            fx, fy = np.floor(sx), np.floor(sy)
            ax, ay = sx - fx, sy - fy
            ix = np.clip(fx.astype(int) + 1, 0, pad.shape[1] - 2)
            iy = np.clip(fy.astype(int) + 1, 0, pad.shape[0] - 2)
            if img.ndim == 3:
                ax, ay = ax[..., None], ay[..., None]
            return ((pad[iy, ix] * (1 - ax) + pad[iy, ix + 1] * ax) * (1 - ay) + (pad[iy + 1, ix] * (1 - ax) + pad[iy + 1, ix + 1] * ax) * ay)

        a = sample(alpha.astype(np.float32)) * opacity * inside
        # Stay within the source rectangle (a clipped piece shows nothing past its cut)
        a *= (sx >= x0 - 0.5) & (sx <= x1 - 0.5)
        a *= (sy >= y0 - 0.5) & (sy <= y1 - 0.5)
        if rgb_img is not None:
            c = sample(rgb_img.astype(np.float32) * alpha[..., None]) / np.maximum(sample(alpha.astype(np.float32))[..., None], 1e-4)
        else:
            c = np.broadcast_to(np.asarray(colour, np.float32), a.shape + (3,))
        reg_p = self.p[by0:by1, bx0:bx1]
        reg_a = self.a[by0:by1, bx0:bx1]
        reg_p[:] = c * a[..., None] + reg_p * (1 - a[..., None])
        reg_a[:] = a + reg_a * (1 - a)

    def rect(self, alpha, colour, u0, v0, u1, v1, opacity=1.0, src=None, rgb_img=None):
        self.blit(alpha, colour, (u0, v0), (u1 - u0, 0), (0, v1 - v0), opacity, src, rgb_img)

    def over(self, base):
        """This canvas laid over [base] (an RGB uint8 image of the same size)."""
        b = np.asarray(base, np.float32)[..., :3]
        out = self.p + b * (1 - self.a[..., None])
        return Image.fromarray(np.clip(out, 0, 255).round().astype(np.uint8), 'RGB')

    def image(self):
        a = np.maximum(self.a, 1e-6)
        out = np.dstack([self.p / a[..., None], self.a * 255])
        return Image.fromarray(np.clip(out, 0, 255).round().astype(np.uint8), 'RGBA')


# ================================================================ a row of chalk (a port of the renderer's)

class Run:
    def __init__(self, text, x, base, scale, col, squeeze=1.0):
        self.text, self.x, self.base, self.scale, self.col, self.squeeze = text, x, base, scale, col, squeeze


class Bar:
    def __init__(self, cell, x0, x1, y, scale, col):
        self.cell, self.x0, self.x1, self.y, self.scale, self.col = cell, x0, x1, y, scale, col


class Tip:
    def __init__(self, u, v, down):
        self.u, self.v, self.down = u, v, down


class Stroke:
    def __init__(self, cell, axis, d, path, x, base, sx, sy, lift, col):
        self.cell, self.axis, self.dir, self.path = cell, axis, d, path
        self.x, self.base, self.sx, self.sy, self.lift, self.col = x, base, sx, sy, lift, col
        self.length = sum(math.hypot((b[0] - a[0]) * sx, (b[1] - a[1]) * sy) for a, b in zip(path, path[1:])) / L['S_PRICE']

    def point_at(self, f):
        if len(self.path) < 2:
            return self.path[0]
        want = min(1.0, max(0.0, f)) * self.length * L['S_PRICE']
        run = 0.0
        for k, (a, b) in enumerate(zip(self.path, self.path[1:])):
            seg = math.hypot((b[0] - a[0]) * self.sx, (b[1] - a[1]) * self.sy)
            if run + seg >= want or k == len(self.path) - 2:
                g = min(1.0, max(0.0, (want - run) / seg)) if seg > 0 else 1.0
                return (a[0] + (b[0] - a[0]) * g, a[1] + (b[1] - a[1]) * g)
            run += seg
        return self.path[-1]

    def tip_at(self, f, down):
        p = self.point_at(f)
        return Tip(self.x + p[0] * self.sx, self.base + (p[1] - self.lift) * self.sy, down)


class Plan:
    def __init__(self, strokes):
        self.strokes = strokes
        self.starts, self.ends = [], []
        t = 0.0
        last = None
        for s in strokes:
            fr = s.tip_at(0, False)
            if last is not None:
                t += math.hypot(fr.u - last.u, fr.v - last.v) / (L['TRAVEL_SPEED'] * L['S_PRICE'])
            self.starts.append(t)
            t += s.length / L['DRAW_SPEED']
            self.ends.append(t)
            last = s.tip_at(1, False)
        self.total = max(t, 1.0)


class Row:
    def __init__(self):
        self.runs, self.bars = [], []
        self._plan = None

    def run(self, text, x, base, scale, col, squeeze=1.0):
        self.runs.append(Run(text, x, base, scale, col, squeeze))

    def bar(self, cell, x0, x1, y, scale, col):
        self.bars.append(Bar(cell, x0, x1, y, scale, col))

    @property
    def plan(self):
        if self._plan is None:
            strokes = []
            for r in self.runs:
                pen = r.x
                sx = r.scale * r.squeeze
                for c in r.text:
                    g = CHALK.glyph(c)
                    if g is None:
                        pen += 3 * sx
                        continue
                    for (a, box, axis, d, pts) in g['pieces']:
                        cell = Cell(a, box[0], box[1]) if a is not None else None
                        strokes.append(Stroke(cell, axis, d, pts, pen, r.base, sx, r.scale, CAP, r.col))
                    pen += g['advance'] * sx
            for b in self.bars:
                sx = (b.x1 - b.x0) / b.cell.width
                path = [(b.cell.left + 0.8, 0.0), (b.cell.left + b.cell.width - 0.8, 0.0)]
                strokes.append(Stroke(b.cell, 0, 1, path, b.x0 - b.cell.left * sx, b.y, sx, b.scale, 0.0, b.col))
            self._plan = Plan(strokes)
        return self._plan


def fresh(col, k, fade):
    c, a = col
    return (mix(rgb(c), rgb(C['CHALK_FRESH']), 0.6 * k), min(1.0, a + 0.06 * k) * fade)


def draw_cell(face, c, x, base, sx, sy, lift, col, axis=-1, d=0, at=0.0):
    colour, alpha = col
    if alpha < 0.004:
        return
    gx0, gx1, gy0, gy1 = c.left, c.left + c.width, c.top, c.top + c.height
    tx0, tx1, ty0, ty1 = 0.0, float(c.a.shape[1]), 0.0, float(c.a.shape[0])
    if axis == 0:
        if d > 0:
            if at <= gx0:
                return
            if at < gx1:
                tx1 = tx0 + (tx1 - tx0) * (at - gx0) / (gx1 - gx0)
                gx1 = at
        else:
            if at >= gx1:
                return
            if at > gx0:
                tx0 = tx1 - (tx1 - tx0) * (gx1 - at) / (gx1 - gx0)
                gx0 = at
    elif axis == 1:
        if d > 0:
            if at <= gy0:
                return
            if at < gy1:
                ty1 = ty0 + (ty1 - ty0) * (at - gy0) / (gy1 - gy0)
                gy1 = at
        else:
            if at >= gy1:
                return
            if at > gy0:
                ty0 = ty1 - (ty1 - ty0) * (gy1 - at) / (gy1 - gy0)
                gy0 = at
    face.rect(c.a, colour if not isinstance(colour, int) else rgb(colour), x + gx0 * sx, base + (gy0 - lift) * sy, x + gx1 * sx,
              base + (gy1 - lift) * sy, alpha, (tx0, ty0, tx1, ty1), c.rgb)


def whole(face, hand, text, x, base, s, squeeze, col):
    if col[1] < 0.004:
        return
    pen = x
    sx = s * squeeze
    for c in text:
        g = hand.glyph(c)
        if g is None:
            pen += 3 * sx
            continue
        if g['full'] is not None:
            a, box = g['full']
            draw_cell(face, Cell(a, box[0], box[1]), pen, base, sx, s, CAP, col)
        pen += g['advance'] * sx


def bar(face, c, x0, x1, y, s, col, f=1.0):
    colour, alpha = col
    if alpha < 0.004 or f <= 0 or x1 <= x0:
        return
    xe = x0 + (x1 - x0) * f
    face.rect(c.a, rgb(colour) if isinstance(colour, int) else colour, x0, y + c.top * s, xe, y + (c.top + c.height) * s, alpha,
              (0.0, 0.0, c.a.shape[1] * f, float(c.a.shape[0])))


def stroke(face, st, f, col):
    if st.cell is None:
        return
    if f >= 1:
        draw_cell(face, st.cell, st.x, st.base, st.sx, st.sy, st.lift, col)
        return
    p = st.point_at(f)
    at = (p[0] if st.axis == 0 else p[1]) + st.dir * TIP
    draw_cell(face, st.cell, st.x, st.base, st.sx, st.sy, st.lift, col, st.axis, st.dir, at)


def draw_row(face, row, age, fade):
    """The renderer's draw(): the row whole, or up to the chalk's tip (returned)."""
    plan = row.plan
    duration = min(L['DRAW_MAX'], max(L['DRAW_MIN'], plan.total))
    bright = 0.0 if math.isinf(age) else min(1.0, max(0.0, 1 - (age - duration) / L['FRESH']))
    if age >= duration:
        for r in row.runs:
            whole(face, CHALK, r.text, r.x, r.base, r.scale, r.squeeze, fresh(r.col, bright, fade))
        for b in row.bars:
            bar(face, b.cell, b.x0, b.x1, b.y, b.scale, fresh(b.col, bright, fade))
        return None
    if age <= 0:
        return None
    t = age * plan.total / duration
    tip = None
    for k, st in enumerate(plan.strokes):
        col = fresh(st.col, 1.0, fade)
        if t >= plan.ends[k]:
            stroke(face, st, 1, col)
            continue
        if t >= plan.starts[k]:
            f = (t - plan.starts[k]) / max(1e-4, plan.ends[k] - plan.starts[k])
            stroke(face, st, f, col)
            tip = st.tip_at(f, True)
        else:
            prev = plan.strokes[k - 1] if k > 0 else None
            fr = prev.tip_at(1, False) if prev else st.tip_at(0, False)
            to = st.tip_at(0, False)
            e0 = plan.ends[k - 1] if k > 0 else 0.0
            g = min(1.0, max(0.0, (t - e0) / max(1e-4, plan.starts[k] - e0)))
            tip = Tip(fr.u + (to.u - fr.u) * g, fr.v + (to.v - fr.v) * g, False)
        break
    return tip


def rub(face, row, age, fade):
    gone = min(1.0, max(0.0, age / L['RUB']))
    if gone < 1:
        for r in row.runs:
            whole(face, CHALK, r.text, r.x, r.base, r.scale, r.squeeze, (r.col[0], r.col[1] * (1 - gone) * fade))
        for b in row.bars:
            bar(face, b.cell, b.x0, b.x1, b.y, b.scale, (b.col[0], b.col[1] * (1 - gone) * fade))
    smear = min(1.0, age / L['RUB']) * (1 - min(1.0, max(0.0, (age - L['RUB']) / L['SMEAR'])))
    if smear > 0.01 and row.runs:
        x0 = min(r.x for r in row.runs) - 0.01
        x1 = max(r.x + CHALK.width(r.text) * r.scale * r.squeeze for r in row.runs) + 0.012
        base, s = row.runs[0].base, row.runs[0].scale
        c = EXTRA['smudge']
        face.rect(c.a, rgb(C['CHALK']), x0, base - 7.6 * s, x1, base + 1.6 * s, 0.42 * smear * fade)


# ================================================================ the board's state, and its layout

class Words:
    def __init__(self, lang):
        self.lang = lang
        self.none = lang['board.guildmark.market.none']
        pattern = lang['board.guildmark.market.lot']
        import re
        m = re.match(r'^%(?:1\$)?s(.*?)%(?:2\$)?s$', pattern)
        self.lot_middle = m.group(1).strip() if m and m.group(1).strip() else None

    def text(self, key):
        return self.lang.get(key, key)


def layout(line, cx, mid, words):
    row = Row()
    s = L['S_PRICE']
    base = mid + L['TEXT_DROP'] * s
    colour = {1: C['CHALK_DEAR'], -1: C['CHALK_CHEAP']}.get(line['off'], C['CHALK'])
    col = (colour, 0.94)
    if line['count'] <= 0:
        none = words.none
        x = cx + L['NONE_X'] * s
        row.run(none, x, base, s, (colour, 0.8))
        w = CHALK.width(none) * s
        row.bar(EXTRA['strike'], x - 0.9 * s, x + w + 1.2 * s, base - 2.6 * s, s * 0.85, col)
        return row
    count, marks = str(line['count']), str(line['marks'])
    middle = words.lot_middle
    first = L['ICON_X'] + L['ICON_SIZE'] * 0.5 + 1.0
    natural = L['COUNT_RIGHT'] - CHALK.width(count)
    start = max(first, natural)
    for_x = L['FOR_X'] + start - natural
    marks_x = for_x + CHALK.width(middle) * L['S_FOR'] / s + L['FOR_GAP']
    coin_x = marks_x + CHALK.width(marks) + L['COIN_GAP']
    arrow = {1: ML.UP, -1: ML.DOWN}.get(line['trend'])
    arrow_x = coin_x + CHALK.width(ML.COIN) + L['ARROW_GAP']
    end = arrow_x + CHALK.width(arrow) if arrow else arrow_x - L['ARROW_GAP']
    room = L['COL_W'] / s - 1.5
    k = (room - start) / (end - start) if end > room else 1.0
    at = lambda x: cx + (start + (x - start) * k) * s
    row.run(count, at(start), base, s, col, k)
    row.run(middle, at(for_x), base, L['S_FOR'], (colour, 0.82), k)
    row.run(marks, at(marks_x), base, s, col, k)
    row.run(ML.COIN, at(coin_x), base, s, col, k)
    if arrow:
        row.run(arrow, at(arrow_x), base, s, col, k)
    return row


def title_row(title):
    row = Row()
    left = L['SLATE_U0'] + L['PAD_L']
    base = L['PANEL_V0'] + L['TITLE_BASE']
    row.run(title, left, base, L['S_TITLE'], (C['CHALK'], 0.94))
    w = CHALK.width(title) * L['S_TITLE']
    row.bar(EXTRA['swash'], left - 0.004, left + w + 0.01, base + 2.2 * L['S_TITLE'], L['S_TITLE'] * 0.85, (C['CHALK'], 0.55))
    return row


def row_places():
    """Every good's row: (column's left, the row's middle), and every heading: (key, left, baseline)."""
    left = L['SLATE_U0'] + L['PAD_L']
    col_top = [L['PANEL_V0'] + L['ROWS_TOP']] * 3
    rows, heads = [], []
    for s, key in enumerate(SECTION_KEYS):
        column = MA.SECTION_COLUMN[s]
        cx = left + column * L['COL_W']
        y = col_top[column]
        heads.append(('board.guildmark.market.' + key, cx, y + L['HEAD_BASE']))
        y += L['HEAD_PITCH']
        for _ in range(MA.SECTIONS[s]):
            rows.append((cx, y + L['ITEM_PITCH'] * 0.5))
            y += L['ITEM_PITCH']
        col_top[column] = y + L['SECTION_GAP']
    return rows, heads


# ================================================================ slips (the renderer's slip(), flat)

def java_hash(s):
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h


class Page:
    """A slip's paper (or an order's ticket, [w] by [h] with its tack [pin] down), flat: turned on its tack and moved
    (the game also lifts and ripples it)."""

    def __init__(self, w=None, h=None, pin=None):
        self.slot_u = self.slot_v = self.turn = self.du = self.dv = self.dz = 0.0
        self.w = L['SLIP_W'] if w is None else w
        self.h = L['SLIP_H'] if h is None else h
        self.pin = L['PIN_DOWN'] if pin is None else pin

    def map(self, u, v):
        px, py = u - self.w / 2, v - self.pin
        c, s = math.cos(self.turn), math.sin(self.turn)
        k = 1 + self.dz / 3.0
        return (self.slot_u + self.w / 2 + (px * c - py * s) * k + self.du,
                self.slot_v + self.pin + (px * s + py * c) * k + self.dv)

    def blit(self, face, alpha, colour, u0, v0, u1, v1, opacity=1.0, src=None, rgb_img=None):
        P0 = np.array(self.map(u0, v0))
        face.blit(alpha, colour, P0, np.array(self.map(u1, v0)) - P0, np.array(self.map(u0, v1)) - P0, opacity, src, rgb_img)


class Turned(Page):
    """Something turned and scaled about a point of a page (a stamp on a ticket), in its own blocks from that point."""

    def __init__(self, page, u, v, turn, scale):
        super().__init__()
        self.page, self.u, self.v, self.t, self.k = page, u, v, turn, scale

    def map(self, u, v):
        c, s = math.cos(self.t), math.sin(self.t)
        x, y = u * self.k, v * self.k
        return self.page.map(self.u + x * c - y * s, self.v + x * s + y * c)


def wrap(hand, text, room, most):
    out, line = [], ''
    for word in text.split(' '):
        tried = word if not line else line + ' ' + word
        if line and hand.width(tried) > room:
            out.append(line)
            if len(out) == most:
                return out
            line = word
        else:
            line = tried
    if line and len(out) < most:
        out.append(line)
    return out


def ink(face, page, text, x, base, s, col, fade):
    pen = x
    colour, alpha = col
    for c in text:
        g = INK.glyph(c)
        if g is None:
            pen += 3 * s
            continue
        if g['full'] is not None:
            a, box = g['full']
            cell = Cell(a, box[0], box[1])
            page.blit(face, a, rgb(colour), pen + cell.left * s, base + (cell.top - CAP) * s, pen + (cell.left + cell.width) * s,
                      base + (cell.top + cell.height - CAP) * s, alpha * fade)
        pen += g['advance'] * s


def ink_on(face, page, slip, kind, head_col, text_col, fade):
    keep = MA.SLIP_KEEP[MA.SLIP_KINDS[kind]]
    W, H = L['SLIP_W'], L['SLIP_H']
    kx0, ky0, kx1, ky1 = keep[0] / 64 * W, keep[1] / 32 * H, keep[2] / 64 * W, keep[3] / 32 * H
    keep_right = kx0 > W / 2

    def room(top, bottom):
        l, r = L['SLIP_MARGIN'], W - L['SLIP_MARGIN']
        if bottom > ky0 and top < ky1:
            if keep_right:
                r = min(r, kx0 - 0.008)
            else:
                l = max(l, kx1 + 0.008)
        return l, r

    head = slip['headline']
    hs = L['S_INK']
    lines = [head]
    top = L['SLIP_HEAD_TOP']
    r = room(top, top + 9 * hs)
    if INK.width(head) * hs > r[1] - r[0]:
        smaller = (r[1] - r[0]) / (INK.width(head) * hs)
        if smaller >= 0.8:
            hs *= smaller
        else:
            lines = wrap(INK, head, (r[1] - r[0]) / hs, 2)
            widest = max(INK.width(x) for x in lines) * hs
            if widest > r[1] - r[0]:
                hs *= (r[1] - r[0]) / widest
    base = top + 7 * hs
    for line in lines:
        r = room(base - 7 * hs, base + 2.5 * hs)
        w = INK.width(line) * hs
        ink(face, page, line, (r[0] + r[1]) / 2 - w / 2, base, hs, head_col, fade)
        base += L['HEAD_LINE'] * hs
    base -= L['HEAD_LINE'] * hs
    ts = L['S_INK_TEXT']
    y = base + 3.4 * hs + 7 * ts
    rest = slip['text'].strip()
    while rest and y + 2.4 * ts <= H - 0.02:
        r = room(y - 7 * ts, y + 2.4 * ts)
        space = (r[1] - r[0]) / ts
        last = y + L['TEXT_LINE'] * ts + 2.4 * ts > H - 0.02
        taken = wrap(INK, rest, space, 1)
        if not taken:
            break
        taken = taken[0]
        rest = rest[min(len(rest), len(taken)):].lstrip()
        shown = taken
        if last and rest:
            while shown and INK.width(shown + '…') > space:
                shown = shown[:-1]
            shown = shown.rstrip() + '…'
        ink(face, page, shown, r[0], y, ts, text_col, fade)
        y += L['TEXT_LINE'] * ts


def draw_slip(face, slips_img, where, slip, slot, age, falling, fade):
    key = '%d|%s|%s' % (slip['pinned'], slip['kind'], slip['headline'])
    h = (java_hash(key) * -7046029254386353131) & 0xFFFFFFFFFFFFFFFF
    rnd = lambda bits: ((h >> bits) & 1023) / 1023.0
    kind = MA.SLIP_KINDS.index(slip['kind']) if slip['kind'] in MA.SLIP_KINDS else 3
    p = Page()
    p.slot_u = L['NEWS_U0'] + (L['NEWS_U1'] - L['NEWS_U0'] - L['SLIP_W']) / 2 + (rnd(3) - 0.5) * 0.012
    p.slot_v = L['PANEL_V0'] + L['SLOT_TOP'] + slot * L['SLOT_PITCH'] + (rnd(13) - 0.5) * 0.008
    p.turn = (rnd(23) - 0.5) * 0.09
    alpha, tack, pressed = 1.0, 1.0, 1.0
    if falling >= 0:
        g = falling
        p.dv = g * g * 0.0011 + g * 0.004
        p.dz = 0.03 + g * 0.006
        p.turn += g * 0.035 * (-1 if rnd(33) < 0.5 else 1)
        alpha = min(1.0, max(0.0, 1 - g / L['FALL']))
        tack = 0
    elif age < L['LAND']:
        e = 1 - (1 - min(1.0, max(0.0, age / L['LAND']))) ** 3
        p.du = (1 - e) * 0.16
        p.dv = (1 - e) * 0.42
        p.dz = (1 - e) * 0.3 + math.sin(e * math.pi) * 0.04
        p.turn += (1 - e) * -0.55
        alpha = min(1.0, age / 2.5)
        tack = 0
    elif age < L['FLUTTER']:
        b = age - L['LAND']
        p.turn += 0.11 * math.exp(-b / 6) * math.sin(b * 0.85)
        pressed = 1 + 0.5 * math.exp(-b / 2.5)
    if alpha <= 0.01:
        return
    W, H = L['SLIP_W'], L['SLIP_H']
    sl = np.asarray(slips_img, np.float32)
    away = min(1.0, p.dz / 0.3)
    sh = 0.014 + 0.12 * away
    x, y, w_, h_ = where['shadow']
    shadow = sl[y:y + h_, x:x + w_]
    face.rect(shadow[..., 3] / 255, rgb(0x0A0606), p.slot_u + p.du * 0.5 + sh * 0.6 - 0.01, p.slot_v + p.dv * 0.5 + sh - 0.008,
              p.slot_u + p.du * 0.5 + sh * 0.6 + W + 0.01, p.slot_v + p.dv * 0.5 + sh + H + 0.012, 0.5 * alpha * (1 - away * 0.8))
    x, y, w_, h_ = where[MA.SLIP_KINDS[kind]]
    paper = sl[y:y + h_, x:x + w_]
    p.blit(face, paper[..., 3] / 255, None, 0, 0, W, H, alpha, rgb_img=paper[..., :3])
    if fade > 0.002:
        head = (C['INK_TROUBLE'] if kind == 0 else C['INK'], 0.93 * alpha)
        ink_on(face, p, slip, kind, head, (C['INK'], 0.9 * alpha), fade)
    if tack > 0:
        size = L['TACK_SIZE'] * pressed
        tu, tv = W / 2, L['PIN_DOWN']
        x, y, w_, h_ = where['tack_shadow']
        ts_ = sl[y:y + h_, x:x + w_]
        p.blit(face, ts_[..., 3] / 255, None, tu - size * 0.35, tv - size * 0.3, tu + size * 0.85, tv + size * 0.8, alpha, rgb_img=ts_[..., :3])
        x, y, w_, h_ = where['tack']
        tk = sl[y:y + h_, x:x + w_]
        p.blit(face, tk[..., 3] / 255, None, tu - size / 2, tv - size / 2, tu + size / 2, tv + size / 2, alpha, rgb_img=tk[..., :3])


def draw_ticket(face, slips_img, where, ticket, age, falling, stamped, fade, icons, words):
    """An order's ticket on the rail (a port of the renderer's, without the air's sway): flying up from the crier's
    hand and swinging on its tack ([age] since it was hung), or falling ([falling] since it came down); its head, the
    good, how many and whose inked on it, and the stamp on it once it's taken ([stamped] ticks ago)."""
    h = (java_hash('ticket|%d' % ticket['order']) * -7046029254386353131) & 0xFFFFFFFFFFFFFFFF
    rnd = lambda bits: ((h >> bits) & 1023) / 1023.0
    W, H = L['TICKET_W'], L['TICKET_H']
    p = Page(W, H, L['TICKET_PIN'])
    p.slot_u = L['TICKET_U0'] + ticket['slot'] * L['TICKET_PITCH'] + (rnd(3) - 0.5) * 0.01
    p.slot_v = L['TICKET_TOP'] + (rnd(13) - 0.5) * 0.006
    p.turn = (rnd(23) - 0.5) * 0.07
    corner = -1 if rnd(33) < 0.5 else 1
    alpha, tack, pressed = 1.0, 1.0, 1.0
    if falling >= 0:
        g = falling
        p.dv = min(g * g * 0.0011 + g * 0.003, 2.0 - (p.slot_v + H) - 0.004)
        p.dz = 0.02 + g * 0.01
        p.turn += g * 0.03 * corner
        alpha = min(1.0, max(0.0, 1 - g / L['FALL']))
        tack = 0
    elif age < L['LAND']:
        e = 1 - (1 - min(1.0, max(0.0, age / L['LAND']))) ** 3
        p.du = (1 - e) * (1.5 - (p.slot_u + W / 2)) * 0.5
        p.dv = (1 - e) * -0.55
        p.dz = (1 - e) * 0.35 + math.sin(e * math.pi) * 0.04
        p.turn += (1 - e) * 0.5 * corner
        alpha = min(1.0, age / 2.5)
        tack = 0
    elif age < L['FLUTTER']:
        b = age - L['LAND']
        p.turn += 0.16 * math.exp(-b / 7) * math.sin(b * 0.8)
        pressed = 1 + 0.5 * math.exp(-b / 2.5)
    if alpha <= 0.01:
        return
    sl = np.asarray(slips_img, np.float32)
    x, y, w_, h_ = where['ticket']
    tag = sl[y:y + h_, x:x + w_]
    p.blit(face, tag[..., 3] / 255, None, 0, 0, W, H, alpha, rgb_img=tag[..., :3])
    if fade > 0.002:
        margin = L['TICKET_MARGIN']
        room = W - 2 * margin
        head = words.text('board.guildmark.ticket.wanted').upper()
        s = min(L['S_TICKET_HEAD'], room / max(1.0, INK.width(head)))
        ink(face, p, head, W / 2 - INK.width(head) * s / 2, L['TICKET_HEAD_BASE'], s, (C['INK_WANTED'], 0.93 * alpha), fade)
        icon = icons.get(ticket['good'])
        if icon is not None:
            size = L['TICKET_ICON']
            im = np.asarray(icon, np.float32)
            u, v = L['TICKET_ICON_U'], L['TICKET_ICON_V']
            p.blit(face, im[..., 3] / 255, None, u - size / 2, v - size / 2, u + size / 2, v + size / 2, alpha * fade, rgb_img=im[..., :3] * 0.94)
        if not ticket['taken']:
            count = str(ticket['count'])
            s = min(L['S_TICKET_COUNT'], (W - margin - L['TICKET_COUNT_U']) / max(1.0, INK.width(count)))
            ink(face, p, count, L['TICKET_COUNT_U'], L['TICKET_COUNT_BASE'], s, (C['INK'], 0.92 * alpha), fade)
        shop = ticket['shop']
        s = L['S_TICKET_SHOP']
        lines = [shop] if INK.width(shop) * s <= room else wrap(INK, shop, room / s, 2)
        widest = max(INK.width(t) for t in lines) * s
        if widest > room:
            s *= room / widest
        base = L['TICKET_SHOP_BASE'] - (len(lines) - 1) * L['TEXT_LINE'] * s
        for line in lines:
            ink(face, p, line, W / 2 - INK.width(line) * s / 2, base, s, (C['INK'], 0.88 * alpha), fade)
            base += L['TEXT_LINE'] * s
        if ticket['taken']:
            a = min(1.0, stamped / 2.0)
            k = 1 + 0.6 * max(0.0, 1 - stamped / 6.0) ** 2
            st = Turned(p, L['STAMP_U'], L['STAMP_V'], L['STAMP_TURN'], k)
            sw = L['STAMP_W']
            sh = sw * MA.STAMP_PX[1] / MA.STAMP_PX[0]
            x, y, w_, h_ = where['stamp']
            frame = sl[y:y + h_, x:x + w_]
            st.blit(face, frame[..., 3] / 255, None, -sw / 2, -sh / 2, sw / 2, sh / 2, a * alpha * fade, rgb_img=frame[..., :3])
            word = words.text('board.guildmark.ticket.taken').upper()
            ws = min(L['S_STAMP'], sw * 0.72 / max(1.0, INK.width(word)))
            ink(face, st, word, -INK.width(word) * ws / 2, 3.5 * ws, ws, (C['INK_STAMP'], 0.95 * a * alpha), fade)
    if tack > 0:
        size = L['TACK_SIZE'] * pressed
        tu, tv = W / 2, L['TICKET_PIN']
        x, y, w_, h_ = where['tack_shadow']
        ts_ = sl[y:y + h_, x:x + w_]
        p.blit(face, ts_[..., 3] / 255, None, tu - size * 0.35, tv - size * 0.3, tu + size * 0.85, tv + size * 0.8, alpha, rgb_img=ts_[..., :3])
        x, y, w_, h_ = where['tack']
        tk = sl[y:y + h_, x:x + w_]
        p.blit(face, tk[..., 3] / 255, None, tu - size / 2, tv - size / 2, tu + size / 2, tv + size / 2, alpha, rgb_img=tk[..., :3])


# ================================================================ the whole face

class Board:
    """A board as the server has it: town, when, lines (dicts or None, in MarketBoards.ORDER), slips, writing, the
    orders' tickets."""

    def __init__(self, town, when_key, lines, slips, writing=-1, tickets=None):
        self.town, self.when_key, self.lines, self.slips, self.writing = town, when_key, lines, slips, writing
        self.tickets = tickets or []


class Viewer:
    """What a viewer's client remembers of a board (the renderer's Memory), for animating changes."""

    def __init__(self):
        self.fresh = True
        self.lines = [None] * len(GOODS)
        self.rows = [None] * len(GOODS)
        self.old = [None] * len(GOODS)
        self.old_at = [0.0] * len(GOODS)
        self.title = ''
        self.title_row = None
        self.title_at = -math.inf
        self.old_title = None
        self.old_title_at = 0.0
        self.slots = [None] * 4
        self.gone = []
        self.stick = None
        self.stick_alpha = 0.0
        self.dust = []
        self.last_dust = -1e9
        self.last = None


def draw_face(face, board, viewer, now, words, icons, slips_img, slips_where, heading, fade=1.0):
    load_hands()
    s_left = L['SLATE_U0'] + L['PAD_L']
    top = L['PANEL_V0']
    frame = 0.0 if viewer.last is None else min(4.0, max(0.0, now - viewer.last))
    viewer.last = now

    # Slips first (they're under nothing of the slate's)
    keys = ['%d|%s|%s' % (s['pinned'], s['kind'], s['headline']) for s in board.slips]
    for i in range(4):
        seen = viewer.slots[i]
        if seen is not None and seen[1] not in keys:
            if not viewer.fresh:
                viewer.gone.append((seen[0], i, now))
            viewer.slots[i] = None
    for slip, k in zip(board.slips, keys):
        if any(sl is not None and sl[1] == k for sl in viewer.slots):
            continue
        free = next((i for i in range(4) if viewer.slots[i] is None), None)
        if free is not None:
            viewer.slots[free] = (slip, k)
    viewer.gone = [g for g in viewer.gone if now - g[2] <= L['FALL']]

    # The slate: title, key, headings, rows
    title = words.text(board.when_key)[:1].upper() + words.text(board.when_key)[1:] if board.when_key else ''
    if title != viewer.title:
        if not viewer.fresh and viewer.title:
            viewer.old_title, viewer.old_title_at = viewer.title_row, now
        viewer.title = title
        viewer.title_at = -math.inf if viewer.fresh else now
        viewer.title_row = title_row(title) if title else None
    tip = None
    if viewer.old_title is not None:
        a = now - viewer.old_title_at
        if a > L['RUB'] + L['SMEAR']:
            viewer.old_title = None
        else:
            rub(face, viewer.old_title, a, fade)
    if viewer.title_row is not None:
        start = viewer.title_at + (L['RUB'] * 0.6 if viewer.old_title is not None else 0)
        tip = draw_row(face, viewer.title_row, now - start, fade) or tip
    legend = ML.COIN + ' ' + words.text('item.guildmark.guild_mark')
    whole(face, CHALK, legend, L['SLATE_U0'] + L['PAD_L'], top + L['LEGEND_BASE'], L['S_LEGEND'], 1.0, (C['CHALK'], 0.62 * fade))

    rows, heads = row_places()
    for key, cx, hb in heads:
        heading_text = words.text(key).rstrip(': ')
        hs = min(L['S_HEAD'], (L['COL_W'] - 0.05) / max(1.0, CHALK.width(heading_text)))
        whole(face, CHALK, heading_text, cx, hb, hs, 1.0, (C['CHALK_HEAD'], 0.95 * fade))
        hw = CHALK.width(heading_text) * hs
        bar(face, EXTRA['underline'], cx - 0.003, cx + hw + 0.006, hb + 2.1 * L['S_HEAD'], L['S_HEAD'] * 0.7, (C['CHALK_HEAD'], 0.5 * fade))
    for i, (cx, mid) in enumerate(rows):
        icon = icons.get(GOODS[i])
        if icon is not None:
            size = L['ICON_SIZE'] * L['S_PRICE'] * (0.8 if GOODS[i] == 'wool' else 1.0)
            u = cx + L['ICON_X'] * L['S_PRICE']
            im = np.asarray(icon, np.float32)
            face.rect(im[..., 3] / 255, None, u - size / 2, mid - size / 2, u + size / 2, mid + size / 2, 1.0, rgb_img=im[..., :3] * 0.94)
        line = board.lines[i]
        if line != viewer.lines[i]:
            before = viewer.rows[i]
            if not viewer.fresh and before is not None and line is not None and line['chalked'] is not None:
                viewer.old[i], viewer.old_at[i] = before, line['chalked']
            else:
                viewer.old[i] = None
            viewer.lines[i] = line
            viewer.rows[i] = layout(line, cx, mid, words) if line else None
        if viewer.old[i] is not None:
            a = now - viewer.old_at[i]
            if a > L['RUB'] + L['SMEAR']:
                viewer.old[i] = None
            else:
                rub(face, viewer.old[i], a, fade)
        row = viewer.rows[i]
        if row is None:
            continue
        if line['chalked'] is None:
            draw_row(face, row, math.inf, fade)
        else:
            start = line['chalked'] + (L['RUB'] * 0.6 if viewer.old[i] is not None else 0)
            tip = draw_row(face, row, now - start, fade) or tip

    # The town's name, gilt on the header plank
    town(face, board.town, heading, now, fade)

    # The slips over their planks
    for i in range(4):
        if viewer.slots[i] is None:
            continue
        slip = viewer.slots[i][0]
        age = math.inf if slip['pinned'] == LONG_AGO else now - slip['pinned']
        draw_slip(face, slips_img, slips_where, slip, i, age, -1, fade)
    for slip, slot, at in viewer.gone:
        draw_slip(face, slips_img, slips_where, slip, slot, math.inf, now - at, fade)

    # The orders' tickets along the rail
    for t in board.tickets:
        age = math.inf if t['pinned'] == LONG_AGO else now - t['pinned']
        draw_ticket(face, slips_img, slips_where, t, age, -1, t.get('stamped', math.inf), fade, icons, words)

    # The chalk stick, and its dust
    stick(face, board, viewer, tip, now, frame, fade)
    viewer.fresh = False


def stick(face, board, viewer, tip, now, frame, fade):
    target = tip
    if target is None and board.writing >= 0 and viewer.rows[board.writing] is not None:
        last = viewer.rows[board.writing].runs[-1]
        target = Tip(last.x + CHALK.width(last.text) * last.scale * last.squeeze + 0.025, last.base - 0.02 + math.sin(now % 1000 * 0.21) * 0.004, False)
    k = 1 - math.exp(-frame * 0.55)
    if target is not None:
        if viewer.stick_alpha <= 0.01 or viewer.stick is None:
            viewer.stick = [target.u + 0.08, L['LEDGE_V']]
        if tip is not None:
            viewer.stick = [target.u, target.v]
        else:
            viewer.stick[0] += (target.u - viewer.stick[0]) * k
            viewer.stick[1] += (target.v - viewer.stick[1]) * k
        viewer.stick_alpha = min(1.0, viewer.stick_alpha + frame / 4)
    else:
        viewer.stick_alpha = max(0.0, viewer.stick_alpha - frame / 8)
        if viewer.stick is not None:
            viewer.stick[1] += (L['LEDGE_V'] - 0.02 - viewer.stick[1]) * k
    # Dust: a mote every couple of ticks from the tip, drifting down and fading
    if tip is not None and tip.down and now - viewer.last_dust > 2.5:
        viewer.last_dust = now
        viewer.dust.append((tip.u, tip.v, now))
    viewer.dust = [d for d in viewer.dust if now - d[2] < 24]
    dot = np.ones((2, 2), np.float32)
    for (u, v, born) in viewer.dust:
        a = now - born
        face.rect(dot, rgb(0xF2EFE6), u - 0.004, v + a * 0.0035, u + 0.004, v + a * 0.0035 + 0.008, 0.8 * (1 - a / 24) * fade)
    if viewer.stick is None or viewer.stick_alpha * fade <= 0.01:
        return
    c = EXTRA['stick']
    u, v = viewer.stick
    length = c.height * L['S_STICK']
    half = c.width * L['S_STICK'] * 0.42
    du, dv = 0.34 * length, -0.86 * length
    wu, wv = 0.93 * half, 0.37 * half
    tl = np.array([u - wu + du, v - wv + dv])
    tr = np.array([u + wu + du, v + wv + dv])
    bl = np.array([u - wu, v - wv])
    face.blit(c.a, None, tl, tr - tl, bl - tl, viewer.stick_alpha * fade, rgb_img=c.rgb)


def heading_font(assets):
    """The heading capitals: {char: (alpha 12 tall, advance)} as the game's bitmap provider has them."""
    with open(os.path.join(assets, 'font', 'heading.json')) as f:
        spec = json.load(f)
    out = {' ': (np.zeros((12, 1), np.float32), 4.0)}
    for prov in spec['providers']:
        if prov['type'] != 'bitmap':
            continue
        im = np.asarray(Image.open(os.path.join(assets, 'textures', prov['file'].split(':')[1])).convert('RGBA'), np.float32)
        rows = prov['chars']
        ch_h = im.shape[0] // len(rows)
        ch_w = im.shape[1] // len(rows[0])
        for r, chars in enumerate(rows):
            for k, ch in enumerate(chars):
                a = im[r * ch_h:(r + 1) * ch_h, k * ch_w:(k + 1) * ch_w, 3] / 255
                cols = np.nonzero(a.any(axis=0))[0]
                w = int(cols[-1]) + 1 if len(cols) else 0
                out[ch] = (a[:, :max(1, w)], w + 1.0)
    return out


def town(face, name, heading, now, fade):
    name = name.upper()
    if not name.strip() or fade <= 0.002:
        return
    width = sum(heading.get(c, (None, 6.0))[1] for c in name)
    scale = min(L['S_TOWN'], L['TOWN_MAX_W'] / max(1.0, width))
    u0 = L['TOWN_U'] - width * scale / 2
    vc = (L['HEADER_V0'] + L['HEADER_V1']) / 2
    for colour, off in ((rgb(C['GILT_SHADOW']), 0.6), (rgb(C['GILT']), 0.0)):
        pen = 0.0
        for c in name:
            g = heading.get(c)
            if g is None:
                pen += 6.0
                continue
            a, adv = g
            if a.shape[1] > 1 or a.any():
                face.rect(a, colour, u0 + (pen + off) * scale, vc + (-4.5 + off) * scale, u0 + (pen + off + a.shape[1]) * scale,
                          vc + (-4.5 + off + a.shape[0]) * scale, fade)
            pen += adv


# ================================================================ renders of the board itself

def board_model(assets, writing=None):
    """The six cells' models as one (north-facing, the board's left cell at model x 16..32), plus the writing as
    planes just in front of the slate and the header plank if [writing] names their textures."""
    els = []
    tex = None
    for part, (col, row) in MA.PARTS.items():
        with open(os.path.join(assets, 'models', 'block', 'market_board_%s.json' % part)) as f:
            m = json.load(f)
        tex = dict(m['textures'])
        for e in m['elements']:
            e = copy.deepcopy(e)
            e['from'][0] -= col * 16
            e['to'][0] -= col * 16
            e['from'][1] += row * 16
            e['to'][1] += row * 16
            if 'rotation' in e:
                e['rotation']['origin'][0] -= col * 16
                e['rotation']['origin'][1] += row * 16
            els.append(e)
    if writing:
        tex['writing'] = writing[0]
        tex['header_writing'] = writing[1]
        z = L['SLATE_DEPTH'] * 16 - 0.06
        els.append({'from': [-16, 0, z], 'to': [32, 32, z + 0.01], 'shade': False,
                    'faces': {'north': {'uv': [0, 0, 16, 16], 'texture': '#writing'}}})
        z = L['HEADER_DEPTH'] * 16 - 0.06
        els.append({'from': [-16, 27, z], 'to': [32, 30, z + 0.01], 'shade': False,
                    'faces': {'north': {'uv': [0, 1.0, 16, 2.5], 'texture': '#header_writing'}}})
    return {'textures': tex, 'elements': els}


def scratch_assets(assets):
    """A scratch copy of the board's textures, where the previews' writing planes can be put beside them."""
    os.makedirs(os.path.join(SCRATCH, 'textures', 'block'), exist_ok=True)
    os.makedirs(os.path.join(SCRATCH, 'textures', 'preview'), exist_ok=True)
    for k in ('front', 'back', 'wood', 'post', 'cap', 'trim'):
        shutil.copy(os.path.join(assets, 'textures', 'block', 'market_board_%s.png' % k), os.path.join(SCRATCH, 'textures', 'block'))
    return SCRATCH


def front_view(assets, R, bg):
    """The board straight on from the front, R pixels to a block: the face's own pixels (u * R, v * R)."""
    return BV.render_model(board_model(assets), assets, yaw=180.0, elev=0.0, scale=R / 16.0, size=(3 * R, 2 * R),
                           center=(8.0, 16.0, 12.0), bg=bg, ss=2)


# ================================================================ a board to show

def sample_board(lang, now):
    """A capital's board at noon: prices from a market having a middling day, two slips of news."""
    data = {
        'bread': (6, 1, 0, 0), 'wheat': (12, 1, 1, 1), 'carrot': (8, 1, -1, -1), 'potato': (10, 1, 0, 0), 'beetroot': (0, 0, 0, 0),
        'apple': (5, 1, 1, 0), 'honey': (1, 2, 0, 0), 'cod': (3, 1, -1, -1), 'salmon': (2, 1, 0, 1), 'beef': (2, 1, 1, 1),
        'pork': (3, 1, 0, 0), 'mutton': (3, 1, 0, -1), 'chicken': (4, 1, -1, -1), 'wool': (4, 1, 0, 0), 'string': (8, 1, 0, 0),
        'leather': (1, 1, 1, 1), 'feather': (16, 1, -1, 0), 'arrow': (16, 1, 0, 0), 'flint': (12, 1, 0, 0), 'candle': (2, 1, 0, -1),
        'bottle': (8, 1, 0, 0), 'iron': (1, 3, 1, 1), 'coal': (6, 1, 0, 0),
    }
    lines = []
    for g in GOODS:
        count, marks, trend, off = data[g]
        lines.append({'count': count, 'marks': marks, 'trend': trend, 'off': off, 'chalked': None})
    slips = [
        {'kind': 'trouble', 'headline': lang['board.guildmark.slip.blight'] % lang.get('good.guildmark.potato', 'Potato'),
         'text': lang.get('say.guildmark.crier_blight_potato', 'Blight in the fields! The potatoes are failing!'), 'pinned': LONG_AGO},
        {'kind': 'good', 'headline': lang['board.guildmark.slip.good_catch'],
         'text': lang.get('say.guildmark.crier_good_catch', 'The boats are coming in full! Fish is cheap at the quay!'), 'pinned': LONG_AGO},
        {'kind': 'word', 'headline': lang['board.guildmark.slip.word.slain'],
         'text': 'The guild says the troll on the north road is slain. Travellers may pass in safety.', 'pinned': LONG_AGO},
    ]
    tickets = [
        {'order': 11, 'good': 'wheat', 'count': 64, 'shop': lang['shop.guildmark.bakery'], 'taken': False, 'slot': 0, 'pinned': LONG_AGO},
        {'order': 12, 'good': 'wool', 'count': 32, 'shop': lang['shop.guildmark.cloth'], 'taken': True, 'slot': 1, 'pinned': LONG_AGO},
        {'order': 14, 'good': 'carrot', 'count': 96, 'shop': lang['shop.guildmark.produce'], 'taken': False, 'slot': 3, 'pinned': LONG_AGO},
        {'order': 15, 'good': 'cod', 'count': 128, 'shop': lang['shop.guildmark.fish_and_fruit'], 'taken': False, 'slot': 4, 'pinned': LONG_AGO},
    ]
    return Board('Hollowmere', 'board.guildmark.market.noon', lines, slips, tickets=tickets)


def item_icons(jar):
    out = {}
    with zipfile.ZipFile(jar) as z:
        for g in GOODS:
            path = 'assets/minecraft/textures/%s.png' % ICONS.get(g, 'item/' + g)
            with z.open(path) as f:
                im = Image.open(f).convert('RGBA')
                out[g] = im.crop((0, 0, im.width, im.width))
    return out


# ================================================================ the previews

def previews(assets, out, jar, imgs, where, chalk, ink_, slips_where):
    os.makedirs(out, exist_ok=True)
    load_hands()
    with open(os.path.join(assets, 'lang', 'en_us.json')) as f:
        lang = json.load(f)
    words = Words(lang)
    icons = item_icons(jar)
    heading = heading_font(assets)
    slips_img = imgs['block/market_board/slips']
    bg = (52, 56, 66)

    # 1. The textures
    fb = [imgs['block/market_board_front'], imgs['block/market_board_back']]
    sheet(fb, scale=6, cols=2, labels=['front (96x64 over the whole 3x2 board)', 'back'], bg=(40, 38, 46)).save(os.path.join(out, 'textures_front_back.png'))
    small = [imgs['block/market_board_%s' % k] for k in ('wood', 'post', 'cap', 'trim')] + [imgs['item/market_board']]
    sheet(small, scale=8, cols=5, labels=['wood', 'post', 'cap (slate tiles)', 'trim', 'item icon'], bg=(40, 38, 46)).save(os.path.join(out, 'textures_materials.png'))
    sheet([slips_img], scale=5, cols=1, bg=(40, 38, 46)).save(os.path.join(out, 'textures_slips.png'))

    # 2. The face, filled in, straight on (R pixels to a block)
    R = 360
    base = front_view(assets, R, bg)
    board = sample_board(lang, 0)
    face = Face(R)
    draw_face(face, board, Viewer(), 1000.0, words, icons, slips_img, slips_where, heading)
    filled = face.over(base)
    filled.save(os.path.join(out, 'face_filled.png'))
    Image.fromarray(base).save(os.path.join(out, 'face_bare.png'))
    crop = filled.crop((int(2.05 * R), int(0.28 * R), int(2.86 * R), int(1.62 * R)))
    crop.resize((crop.width * 2, crop.height * 2), Image.LANCZOS).save(os.path.join(out, 'face_slips_closeup.png'))
    crop = filled.crop((int(0.2 * R), int(0.3 * R), int(1.05 * R), int(0.95 * R)))
    crop.resize((crop.width * 2, crop.height * 2), Image.LANCZOS).save(os.path.join(out, 'face_prices_closeup.png'))
    crop = filled.crop((int(0.5 * R), int(1.55 * R), int(2.5 * R), int(2.0 * R)))
    crop.resize((crop.width * 2, crop.height * 2), Image.LANCZOS).save(os.path.join(out, 'face_tickets_closeup.png'))

    # 3. In 3D: from the front and a little to the side, with the writing; from behind
    sa = scratch_assets(assets)
    face.image().save(os.path.join(sa, 'textures', 'preview', 'writing.png'))
    face.image().save(os.path.join(sa, 'textures', 'preview', 'header.png'))
    model = board_model(assets, ('guildmark:preview/writing', 'guildmark:preview/header'))
    views = []
    for yaw, elev in ((205.0, 12.0), (180.0, 4.0), (25.0, 14.0)):
        m = model if yaw > 90 else board_model(assets)
        views.append(Image.fromarray(BV.render_model(m, sa, yaw=yaw, elev=elev, scale=17.0, size=(900, 640), center=(8.0, 16.0, 12.0), bg=bg, ss=2)))
    w = sum(v.width for v in views[:2])
    top = Image.new('RGB', (w, views[0].height), bg)
    top.paste(views[0], (0, 0))
    top.paste(views[1], (views[0].width, 0))
    top.save(os.path.join(out, 'board_3d_front.png'))
    views[2].save(os.path.join(out, 'board_3d_back.png'))

    # 4. The crier at work: the title rewritten, three prices chalked, a slip flying up
    gif(out, assets, lang, words, icons, slips_img, slips_where, heading, bg)


def gif(out, assets, lang, words, icons, slips_img, slips_where, heading, bg):
    R = 240
    base = front_view(assets, R, bg)
    board = sample_board(lang, 0)
    board.when_key = 'board.guildmark.market.morning'
    board.slips = board.slips[:2]
    viewer = Viewer()
    frames = []
    box = (int(0.12 * R), int(0.06 * R), int(2.88 * R), int(1.66 * R))
    # What happens when (ticks): the morning's prices up; at noon the crier begins (the title is rewritten), pins a
    # slip, then chalks three changed lines one after another (14 ticks apart) and puts the chalk down
    changes = {
        6: ('title', 'board.guildmark.market.noon'),
        20: ('pin', {'kind': 'word', 'headline': lang['board.guildmark.slip.word.slain'],
                     'text': 'The guild says the troll on the north road is slain. Travellers may pass in safety.', 'pinned': 20}),
        58: ('chalk', 0, {'count': 5, 'marks': 1, 'trend': 1, 'off': 1}),
        72: ('chalk', 2, {'count': 12, 'marks': 1, 'trend': -1, 'off': -1}),
        86: ('chalk', 4, {'count': 6, 'marks': 1, 'trend': 1, 'off': 0}),
        100: ('done',),
    }
    for t2 in range(0, 128, 2):
        for tick in (t2, t2 + 1):
            ch = changes.get(tick)
            if ch is None:
                continue
            if ch[0] == 'title':
                board.when_key = ch[1]
            elif ch[0] == 'pin':
                board.slips = board.slips + [ch[1]]
            elif ch[0] == 'chalk':
                line = dict(ch[2])
                line['chalked'] = tick
                board.lines = list(board.lines)
                board.lines[ch[1]] = line
                board.writing = ch[1]
            elif ch[0] == 'done':
                board.writing = -1
        face = Face(R)
        draw_face(face, board, viewer, float(t2), words, icons, slips_img, slips_where, heading)
        frames.append(face.over(base).crop(box))
    pal = frames[70 // 2].quantize(colors=255, method=Image.Quantize.MEDIANCUT)
    q = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    durations = [100] * len(q)
    durations[-1] = 1600
    q[0].save(os.path.join(out, 'crier_at_work.gif'), save_all=True, append_images=q[1:], duration=durations, loop=0, optimize=False)
    # A still from the middle of a line coming up, bigger, to see the strokes and the stick
    face = Face(R * 2)
    viewer2 = Viewer()
    board2 = sample_board(lang, 0)
    draw_face(face, board2, viewer2, 0.0, words, icons, slips_img, slips_where, heading)
    line = dict(board2.lines[0])
    line.update({'count': 5, 'marks': 1, 'trend': 1, 'off': 1, 'chalked': 10})
    board2.lines = list(board2.lines)
    board2.lines[0] = line
    board2.writing = 0
    face = Face(R * 2)
    draw_face(face, board2, viewer2, 10.0 + L['RUB'] * 0.6 + 7.0, words, icons, slips_img, slips_where, heading)
    big = face.over(front_view(assets, R * 2, bg))
    big.crop((int(0.2 * 2 * R), int(0.38 * 2 * R), int(0.95 * 2 * R), int(0.72 * 2 * R))).save(os.path.join(out, 'chalking_closeup.png'))
