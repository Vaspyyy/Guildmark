"""Hearthcraft's check of its files, so that a purple-and-black square, a silent sound or a raw key is found before the game is run:

    python3 -B art/scripts/cook_check.py [assets dir]

Python's standard library only. It does two things.

What `cook_art.py` wrote (`check()`, which `cook_art.py` runs after writing and stops on if anything is found). It starts from the ids of
Appendix C.1 (the nine blocks, the twenty items, the five effects, the seven particles, the twenty-one Book sprites, the four renderer
textures) and the moving pieces (`models/block/cook_*.json` and the names in `CookPieces`), and follows what each file names: a blockstate's
models, an item definition's models (through its composites and selects), a model's parent and textures, a particle's frames. It reports a file
that is missing, a model the game would refuse or draw wrongly (an element outside the range the game takes, a face on the wrong side of a flat
one, a texture the model does not define, a UV outside the picture, a light that is not 0 to 15), a select or composite without what the game
reads in it, an animation whose frames do not fit its strip, and a picture of the wrong size (the sizes of the design's 11.1: blocks', items',
their overlays' and the renderers' textures 32 px, effect icons 36, the cold vignette 256, particles 16, the Book's sprites 24 and the hints 32)
or with nothing in it; every picture in `textures/block/cook/` and `textures/item/cook/` is sized, named by a model or not. The game's own
models and textures are not followed.

What the code names (`code()`, run with the above when this is run as a script), Part M4's (design 12.4):

  - the ids `CookRegistry` and `CookParticles` register are the ones above, and each block, item and effect has its name in `en_us.json`
    and a texture of ours its models reach;
  - every `CookSounds.NAMES` event has its `sounds.json` entry, each of its files is there and is Ogg, its subtitle is a line, every file
    under `sounds/cook/` is named by an entry, and no `cook.` entry is left without an event;
  - every string in `cooking/` and `client/cooking/` that looks like a language key, and the keys three helpers build from a word
    (`say(player, "x")`, `hint("x")`, `"served." + tier`), is in `en_us.json`: a key with a template must match one, or, where the template is
    an enum's name, each of the enum's; and `merge_lang.py --check` finds nothing to merge;
  - every voice family named in `cooking/` and `client/cooking/`, or handed anywhere to the kitchen's line helpers, is in `KitchenWords` or
    `TalkLines.FAMILIES` (or is one of the Door's own, `DoorLines.OWN`, which have their words in its table), and every wording `KitchenWords`
    counts is a line;
  - every JSON file under `data/` parses, with no key twice in an object.

It reads only the files, so the sheets of `cook_prev.py` and this agree on what is there. Exit status 1 if anything is found.
"""
import bisect
import json
import os
import re
import struct
import subprocess
import sys
import zlib

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')
DATA = os.path.join(ROOT, 'src/main/resources/data')
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark')

BLOCKS = ('stewpot', 'hob', 'bake_oven', 'spit', 'prep_board', 'plate', 'larder', 'menu_board', 'herb')
BLOCK_ITEMS = BLOCKS[:-1]                                  # the herb is planted from its sprig, which is an item of its own
ITEMS = ('dish_pottage', 'dish_stew', 'dish_broth', 'dish_roots', 'dish_chowder', 'dish_cakes', 'dish_loaf', 'dish_pie', 'dish_tart', 'dish_fish',
         'dish_roast', 'dish_strips', 'gruel', 'scorched_mess', 'cooks_ladle', 'cooks_knife', 'turner', 'gilded_ladle', 'cook_book', 'herb_sprig')
EFFECTS = ('warmth', 'hearty', 'steady', 'provisioned', 'chilled')
PARTICLES = {'cook_steam': 4, 'stew_bubble': 4, 'cook_smoke_dark': 4, 'cook_sizzle': 4, 'cook_crumb': 3, 'cook_call': 4, 'cook_foam': 4}   # frames each
SPRITES = ('book_page', 'book_ribbon', 'book_lock', 'role_body', 'role_meat', 'role_sea', 'role_green', 'role_sweet', 'role_rich', 'role_savour',
           'station_stewpot', 'station_hob', 'station_oven', 'station_spit', 'tier_plain', 'tier_sound', 'tier_fine', 'tier_hallmarked',
           'hint_pot', 'hint_flame', 'hint_chain')
RENDERER = ('surface', 'film', 'crust', 'ring')
FLAT_FACES = {0: ('west', 'east'), 1: ('down', 'up'), 2: ('north', 'south')}     # the faces an element that is flat along an axis can have
SWEPT = ('textures/block/cook', 'textures/item/cook')                           # every picture in these is 32 px wide, named or not


# -------------------------------------------------------------------------------------------------------------------- pictures

class Picture:
    """A PNG's size and the alpha of each of its pixels (0 to 255, row by row), which is all this check looks at."""

    def __init__(self, width, height, alpha):
        self.width, self.height, self.alpha = width, height, alpha

    @property
    def size(self):
        return self.width, self.height

    def drawn(self, box=None):
        """Whether any pixel in [box] (left, top, right, bottom; the whole picture if none) is not wholly transparent."""
        x0, y0, x1, y1 = box or (0, 0, self.width, self.height)
        a, w = self.alpha, self.width
        return any(any(a[y * w + x0:y * w + x1]) for y in range(y0, y1))


PNG = b'\x89PNG\r\n\x1a\n'
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}                                   # by colour type: grey, RGB, palette, grey and alpha, RGBA
DEPTHS = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
ADAM7 = ((0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2))   # each pass's x, y, dx, dy


def read_png(path):
    """The Picture in the PNG at [path]; ValueError if it is not one this (and so, near enough, the game) can read."""
    with open(path, 'rb') as f:
        b = f.read()
    if not b.startswith(PNG):
        raise ValueError('not a PNG')
    at, head, palette, trns, idat = 8, None, None, None, []
    while True:
        if at + 8 > len(b):
            raise ValueError('it ends before its IEND')
        n, kind = struct.unpack('>I4s', b[at:at + 8])
        body = b[at + 8:at + 8 + n]
        if len(body) < n or at + 12 + n > len(b):
            raise ValueError('its %s chunk is cut short' % kind.decode('latin-1'))
        if zlib.crc32(kind + body) & 0xFFFFFFFF != struct.unpack('>I', b[at + 8 + n:at + 12 + n])[0]:
            raise ValueError('its %s chunk fails its CRC' % kind.decode('latin-1'))
        at += 12 + n
        if head is None and kind != b'IHDR':
            raise ValueError('its first chunk is not IHDR')
        if kind == b'IHDR':
            head = struct.unpack('>IIBBBBB', body)
        elif kind == b'PLTE':
            palette = len(body) // 3
        elif kind == b'tRNS':
            trns = body
        elif kind == b'IDAT':
            idat.append(body)
        elif kind == b'IEND':
            break
    w, h, depth, ctype, comp, filt, lace = head
    if ctype not in CHANNELS or depth not in DEPTHS[ctype] or comp or filt or lace > 1 or not w or not h:
        raise ValueError('a header this does not read: %s' % (head,))
    if ctype == 3 and not palette:
        raise ValueError('a palette picture with no palette')
    try:
        data = zlib.decompress(b''.join(idat))
    except zlib.error as e:
        raise ValueError('its image data does not inflate: %s' % e)
    ch = CHANNELS[ctype]
    bpp = max(1, ch * depth // 8)
    if trns is not None and ctype in (0, 2):
        trns = struct.unpack('>%dH' % ch, trns[:2 * ch])
    alpha = bytearray(w * h)
    at = 0
    for x0, y0, dx, dy in (ADAM7 if lace else ((0, 0, 1, 1),)):
        pw, ph = (w - x0 + dx - 1) // dx, (h - y0 + dy - 1) // dy
        if pw <= 0 or ph <= 0:
            continue
        rows, at = unfilter(data, at, ph, (pw * ch * depth + 7) // 8, bpp)
        for r, line in enumerate(rows):
            y = y0 + r * dy
            for i, a in enumerate(pixel_alpha(samples(line, pw * ch, depth), pw, ctype, depth, trns, palette)):
                alpha[y * w + x0 + i * dx] = a
    return Picture(w, h, alpha)


def unfilter(data, at, rows, stride, bpp):
    """The [rows] scanlines of [stride] bytes from data[at], each after its filter byte, unfiltered; and where they end."""
    out, prev = [], bytearray(stride)
    for _ in range(rows):
        if at + 1 + stride > len(data):
            raise ValueError('its image data ends early')
        ft, line = data[at], bytearray(data[at + 1:at + 1 + stride])
        at += 1 + stride
        if ft == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif ft == 3:
            for i in range(stride):
                line[i] = (line[i] + ((line[i - bpp] if i >= bpp else 0) + prev[i]) // 2) & 255
        elif ft == 4:
            for i in range(stride):
                a, up, c = (line[i - bpp], prev[i], prev[i - bpp]) if i >= bpp else (0, prev[i], 0)
                p = a + up - c
                pa, pb, pc = abs(p - a), abs(p - up), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else up if pb <= pc else c)) & 255
        elif ft:
            raise ValueError('a scanline with filter %d' % ft)
        out.append(line)
        prev = line
    return out, at


def samples(line, count, depth):
    """The first [count] samples of an unfiltered scanline at [depth] bits each."""
    if depth == 8:
        return line[:count]
    if depth == 16:
        return [line[2 * j] << 8 | line[2 * j + 1] for j in range(count)]
    per, mask = 8 // depth, (1 << depth) - 1
    return [(line[j // per] >> (8 - depth * (j % per + 1))) & mask for j in range(count)]


def pixel_alpha(s, n, ctype, depth, trns, palette):
    """The alpha (0 to 255) of each of the [n] pixels whose samples are [s]."""
    if ctype in (4, 6):
        a = s[CHANNELS[ctype] - 1::CHANNELS[ctype]]
        return [v >> 8 for v in a] if depth == 16 else a
    if ctype == 3:
        if any(v >= palette for v in s):
            raise ValueError('a pixel past the end of its palette')
        return [trns[v] if trns is not None and v < len(trns) else 255 for v in s]
    if trns is None:
        return [255] * n
    if ctype == 0:
        return [0 if v == trns[0] else 255 for v in s]
    return [0 if (s[3 * i], s[3 * i + 1], s[3 * i + 2]) == trns else 255 for i in range(n)]


# -------------------------------------------------------------------------------------------------------------------- the art

class Check:
    def __init__(self, assets):
        self.assets = assets
        self.problems = []
        self.models = {}               # 'block/stewpot' -> its JSON (None if it could not be read)
        self.textures = set()          # 'item/dish_pottage' and the like
        self.named = {}                # 'blockstates/stewpot.json' -> the models it names ('guildmark:block/stewpot', ...)

    def bad(self, msg):
        self.problems.append(msg)

    def path(self, rel):
        return os.path.join(self.assets, rel)

    def json(self, rel, who=''):
        p = self.path(rel)
        if not os.path.exists(p):
            self.bad('missing %s%s' % (rel, ' (named by %s)' % who if who else ''))
            return None
        try:
            with open(p, encoding='utf-8') as f:
                return json.load(f)
        except ValueError as e:
            self.bad('%s is not JSON: %s' % (rel, e))
            return None

    def picture(self, rel, who=''):
        """The picture at [rel], or None (and a problem) if it is missing or can't be read."""
        if not os.path.exists(self.path(rel)):
            self.bad('missing %s%s' % (rel, ' (named by %s)' % who if who else ''))
            return None
        try:
            return read_png(self.path(rel))
        except (OSError, ValueError, struct.error) as e:
            self.bad('%s can\'t be read as a PNG: %s' % (rel, e))
            return None

    # ---------------------------------------------------------------- pictures

    def frames(self, rel, im, width=None, drawn=True):
        """Checks [im], the picture at [rel], against its `.mcmeta`: a strip of square frames of its width has as many frames as it names, in range,
        each with a tick or more; a picture with one frame has no animation; and (if [drawn]) no frame is empty (the glints over a dish rest on a
        blank one, so they are not). Returns the number of frames."""
        if width is not None and im.width != width:
            self.bad('%s is %d px wide, wanted %d' % (rel, im.width, width))
            return 0
        if im.height % im.width:
            self.bad('%s is %s: not a strip of square frames' % (rel, im.size))
            return 0
        n = im.height // im.width
        mp = self.path(rel + '.mcmeta')
        meta = self.json(rel + '.mcmeta') if os.path.exists(mp) else None
        anim = meta.get('animation') if meta else None
        if n > 1 and anim is None:
            self.bad('%s has %d frames and no animation' % (rel, n))
        if n == 1 and anim is not None:
            self.bad('%s has one frame and an animation' % rel)
        if anim is not None:
            listed = anim.get('frames')
            if listed is not None:
                idx = [f['index'] if isinstance(f, dict) else f for f in listed]
                if any(i < 0 or i >= n for i in idx):
                    self.bad('%s: an animation frame outside its %d frames: %s' % (rel, n, idx))
                if any(isinstance(f, dict) and f.get('time', 1) < 1 for f in listed):
                    self.bad('%s: an animation frame shorter than a tick' % rel)
            elif anim.get('frametime', 1) < 1:
                self.bad('%s: an animation with a frametime under a tick' % rel)
        for i in range(n if drawn else 0):
            if not im.drawn((0, i * im.width, im.width, (i + 1) * im.width)):
                self.bad('%s: frame %d is empty' % (rel, i))
        return n

    def texture(self, ref, who):
        """A texture a model names, [ref] ('guildmark:item/dish_stew'); the game's own are not ours."""
        ns, _, name = ref.rpartition(':')
        if ns != 'guildmark' or name in self.textures:
            return
        self.textures.add(name)
        rel = 'textures/%s.png' % name
        im = self.picture(rel, who)
        if im is None:
            return
        n = self.frames(rel, im, 32 if name.startswith(('item/', 'block/cook/')) else None, drawn=name.startswith('block/'))
        if name.startswith('item/') and n == 1 and im.size != (32, 32):
            self.bad('%s is %s: the items are drawn at 32 x 32' % (rel, im.size))

    def sweep(self):
        """Every picture in SWEPT, named by a model or not, is a strip of 32 px square frames that fits its `.mcmeta`; no `.mcmeta` is alone."""
        for d in SWEPT:
            if not os.path.isdir(self.path(d)):
                self.bad('missing %s/' % d)
                continue
            for name in sorted(os.listdir(self.path(d))):
                rel = '%s/%s' % (d, name)
                if name.endswith('.png.mcmeta'):
                    if not os.path.exists(self.path(rel[:-len('.mcmeta')])):
                        self.bad('%s is the animation of no picture' % rel)
                elif name.endswith('.png'):
                    ref = 'guildmark:' + rel[len('textures/'):-len('.png')]
                    if ref.partition(':')[2] not in self.textures:
                        self.texture(ref, '')
                else:
                    self.bad('%s is neither a picture nor its animation' % rel)

    # ---------------------------------------------------------------- models

    def element(self, name, i, e, textures):
        fr, to = e['from'], e['to']
        for a in range(3):
            if fr[a] > to[a] + 1e-9:
                self.bad('models/%s.json element %d: from is past to on axis %d' % (name, i, a))
            if fr[a] < -16 or to[a] > 32:
                self.bad('models/%s.json element %d: %s to %s is outside the -16 to 32 the game takes' % (name, i, fr, to))
        flat = [a for a in range(3) if abs(fr[a] - to[a]) < 1e-9]
        if len(flat) >= 2:
            self.bad('models/%s.json element %d: flat along two axes' % (name, i))
        for d, face in e['faces'].items():
            t = face['texture']
            if not t.startswith('#') or t[1:] not in textures:
                self.bad('models/%s.json element %d: the face %s names a texture the model does not define (%s)' % (name, i, d, t))
            uv = face.get('uv')
            if uv and (min(uv) < 0 or max(uv) > 16):
                self.bad('models/%s.json element %d: the uv of %s is outside the picture: %s' % (name, i, d, uv))
            if len(flat) == 1 and d not in FLAT_FACES[flat[0]]:
                self.bad('models/%s.json element %d: a %s face on an element that is flat along axis %d' % (name, i, d, flat[0]))
        rot = e.get('rotation')
        if rot and not ('x' in rot and 'y' in rot and 'z' in rot or 'axis' in rot and 'angle' in rot):
            self.bad('models/%s.json element %d: a rotation of neither form the game reads: %s' % (name, i, rot))
        light = e.get('light_emission')
        if light is not None and not (isinstance(light, int) and 0 <= light <= 15):
            self.bad('models/%s.json element %d: light_emission %s is not 0 to 15' % (name, i, light))

    def model(self, ref, who):
        """Follows the model [ref] ('guildmark:block/stewpot') that [who] names: its parent, its textures and its elements."""
        ns, _, name = ref.rpartition(':')
        if ns != 'guildmark' or name in self.models:
            return
        self.models[name] = None
        m = self.json('models/%s.json' % name, who)
        if m is None:
            return
        self.models[name] = m
        if 'parent' in m:
            self.model(m['parent'], 'models/%s.json' % name)
        textures = m.get('textures', {})
        for t in textures.values():
            if not t.startswith('#'):
                self.texture(t, 'models/%s.json' % name)
        for i, e in enumerate(m.get('elements', [])):
            self.element(name, i, e, textures)

    def ours(self, refs):
        """The textures of ours that the models [refs] and their parents name."""
        out, seen, todo = set(), set(), list(refs)
        while todo:
            ns, _, name = todo.pop().rpartition(':')
            if ns != 'guildmark' or name in seen:
                continue
            seen.add(name)
            m = self.models.get(name) or {}
            out |= {t.rpartition(':')[2] for t in m.get('textures', {}).values() if t.startswith('guildmark:')}
            if 'parent' in m:
                todo.append(m['parent'])
        return out

    def blockstate(self, block):
        rel = 'blockstates/%s.json' % block
        s = self.json(rel)
        if s is None:
            return
        refs = []
        for v in s.get('variants', {}).values():
            refs += [v['model']] if isinstance(v, dict) else [x['model'] for x in v]
        for part in s.get('multipart', []):
            a = part['apply']
            refs += [a['model']] if isinstance(a, dict) else [x['model'] for x in a]
        if not refs:
            self.bad('%s names no model' % rel)
        self.named[rel] = refs
        for r in refs:
            self.model(r, rel)

    def walk(self, who, node, refs):
        """The models an item definition names, and a check that its selects and composites hold what the game reads."""
        if isinstance(node, dict):
            t = node.get('type')
            if t == 'minecraft:model':
                if 'model' in node:
                    refs.append(node['model'])
                else:
                    self.bad('%s: a model node without a model' % who)
            elif t == 'minecraft:select':
                for k in ('property', 'cases'):
                    if k not in node:
                        self.bad('%s: a select without %s' % (who, k))
                if node.get('property') == 'minecraft:component' and 'component' not in node:
                    self.bad('%s: a component select without its component' % who)
                for c in node.get('cases', []):
                    if 'when' not in c or 'model' not in c:
                        self.bad('%s: a select case without when or model' % who)
            elif t == 'minecraft:composite' and not node.get('models'):
                self.bad('%s: a composite without models' % who)
            for v in node.values():
                self.walk(who, v, refs)
        elif isinstance(node, list):
            for v in node:
                self.walk(who, v, refs)

    def item(self, name):
        rel = 'items/%s.json' % name
        d = self.json(rel)
        if d is None:
            return
        refs = []
        self.walk(rel, d, refs)
        if not refs:
            self.bad('%s names no model' % rel)
        self.named[rel] = refs
        for r in refs:
            self.model(r, rel)

    def pieces(self):
        """The moving pieces: every `models/block/cook_*.json`, and every model `CookPieces` names (`Piece("cook_spit_rod")`)."""
        names = set()
        d = self.path('models/block')
        if os.path.isdir(d):
            names |= {n[:-5] for n in os.listdir(d) if n.startswith('cook_') and n.endswith('.json')}
        src = read_kotlin('client/cooking/CookPieces.kt')
        named = set(re.findall(r'\bPiece\(\s*"([a-z0-9_]+)"\s*\)', src or ''))
        if src is not None and not named:
            self.bad('client/cooking/CookPieces.kt names no Piece this can read')
        for n in sorted(names | named):
            self.model('guildmark:block/' + n, 'CookPieces.kt' if n in named else 'models/block/')
        return len(names | named)

    # ---------------------------------------------------------------- the rest

    def particle(self, name, frames):
        j = self.json('particles/%s.json' % name)
        if j is None:
            return 0
        listed = j.get('textures', [])
        if len(listed) != frames:
            self.bad('particles/%s.json lists %d frames, wanted %d' % (name, len(listed), frames))
        for t in listed:
            im = self.picture('textures/particle/%s.png' % t.rpartition(':')[2])
            if im is not None and (im.size != (16, 16) or not im.drawn()):
                self.bad('textures/particle/%s.png is %s or empty: particles are 16 x 16 and drawn' % (t, im.size))
        return len(listed)

    def sprite(self, name):
        rel = 'textures/gui/sprites/cook/%s.png' % name
        im = self.picture(rel)
        if im is None:
            return
        if name == 'book_page':
            gui = (self.json(rel + '.mcmeta') or {}).get('gui', {}).get('scaling', {})
            w, h, b = gui.get('width', 0), gui.get('height', 0), gui.get('border', 0)
            if gui.get('type') != 'nine_slice' or min(w, h, b) <= 0 or 2 * b >= min(w, h):
                self.bad('%s.mcmeta: the page is not a nine-slice the game can stretch: %s' % (rel, gui))
            if not im.drawn():
                self.bad('%s is empty' % rel)
        elif name == 'book_ribbon':
            if not im.drawn():
                self.bad('%s is empty' % rel)
        else:
            self.frames(rel, im, 32 if name.startswith('hint_') else 24)

    def run(self):
        for b in BLOCKS:
            self.blockstate(b)
        for b in BLOCK_ITEMS:
            self.item(b)
        for i in ITEMS:
            self.item(i)
        self.pieces()
        for e in EFFECTS:
            im = self.picture('textures/mob_effect/%s.png' % e)
            if im is not None:
                self.frames('textures/mob_effect/%s.png' % e, im, 36)
        frames = sum(self.particle(p, n) for p, n in PARTICLES.items())
        for s in SPRITES:
            self.sprite(s)
        im = self.picture('textures/gui/chill.png')
        if im is not None and (im.size != (256, 256) or not im.drawn()):
            self.bad('textures/gui/chill.png is %s or empty: the vignette is 256 x 256' % (im.size,))
        for r in RENDERER:
            im = self.picture('textures/entity/cook/%s.png' % r)
            if im is not None and (im.size != (32, 32) or not im.drawn()):
                self.bad('textures/entity/cook/%s.png is %s or empty: the renderers\' textures are 32 x 32' % (r, im.size))
        self.sweep()
        return frames


def check(assets=ASSETS):
    """The problems in what Hearthcraft wrote into [assets] (an empty list when there are none), and a line saying what was checked."""
    c = Check(assets)
    frames = c.run()
    line = '%d blocks, %d items, %d effects, %d particles (%d frames), %d Book sprites, %d models and %d textures followed; problems: %d' % (
        len(BLOCKS), len(ITEMS), len(EFFECTS), len(PARTICLES), frames, len(SPRITES), len(c.models), len(c.textures), len(c.problems))
    return c.problems, line


# -------------------------------------------------------------------------------------------------------------------- the code

sys.path.insert(0, os.path.join(ROOT, 'art', 'lang'))
import check_lang  # noqa: E402  (its reading of Kotlin strings: comments left out, a template as WILD)

WILD = check_lang.WILD
LANG_LIKE = re.compile(r'^(?:[a-z_]+\.)?guildmark\.[A-Za-z0-9_.\x00-]+$')
FAMILY_LIKE = re.compile(r'^(?:kitchen|works)\.[a-z0-9_]+(?:\.[a-z0-9_]+)*$')
SCANNED = ('cooking', 'client/cooking')

# Keys a helper builds from a word ([file or folder, the call, the key]); a call ending in "+" puts more after the word.
HELPERS = (
    ('cooking', re.compile(r'\bsay\(\s*player\s*,\s*"([a-z0-9_.]+)"\s*(\+?)'), 'message.guildmark.cook.%s'),
    ('client/cooking/CookHints.kt', re.compile(r'\bhint\(\s*"([a-z0-9_.]+)"\s*(\+?)'), 'gui.guildmark.cook.hint.%s'),
    ('cooking/vessel/Serving.kt', re.compile(r'"(served\.[a-z_.]*)"\s*(\+?)'), 'message.guildmark.cook.%s'),
)

# A key that is these and a template is a key for each of the enum's names (in CookData.kt), lower case.
EXPAND = {
    'message.guildmark.cook.tier.': 'Tier', 'message.guildmark.cook.served.': 'Tier', 'gui.guildmark.cook.dish.stage.': 'Stage',
    'message.guildmark.cook.role.': 'Role', 'gui.guildmark.cook.hint.heat_up_': 'Station', 'gui.guildmark.cook.hint.heat_down_': 'Station',
    'gui.guildmark.cook.hint.add_': 'Station',
}

# Calls anywhere in the Kotlin that hand the kitchen's line helpers a family.
FAMILY_CALLS = re.compile(r'\b(?:KitchenLines\.(?:say|line|part)|DoorLines\.say|HarvestTable\.voice|SceneLines\.say)\(\s*[\w.]+\s*,\s*"([a-z0-9_.]+)"')


def read_kotlin(rel):
    """The Kotlin at [rel] under the mod's package, or None if there is none."""
    try:
        with open(os.path.join(KOTLIN, rel), encoding='utf-8') as f:
            return f.read()
    except OSError:
        return None


def kotlin_files(*folders):
    """(rel, text) of every Kotlin file in [folders] (rel to the mod's package), in order."""
    for d in folders:
        for top, dirs, names in os.walk(os.path.join(KOTLIN, d)):
            dirs.sort()
            for n in sorted(names):
                if n.endswith('.kt'):
                    p = os.path.join(top, n)
                    with open(p, encoding='utf-8') as f:
                        yield os.path.relpath(p, KOTLIN).replace(os.sep, '/'), f.read()


def strings(src):
    """(line, text) of each string in [src], comments left out, a template as WILD."""
    out = []
    check_lang.scan_code(src, 0, out, False)
    nl = [m.start() for m in re.finditer('\n', src)]
    return [(bisect.bisect_left(nl, at) + 1, text) for at, text in out]


def enum_body(src, name):
    """The entries of `enum class [name]` in [src] as text (up to its first `;` or its end), comments left out; '' if there is none."""
    m = re.search(r'\benum class %s\b[^{]*\{' % name, src or '')
    if m is None:
        return ''
    body = re.sub(r'/\*.*?\*/|//[^\n]*', '', src[m.end():], flags=re.S)
    depth = 0
    for i, c in enumerate(body):
        if c in '([{':
            depth += 1
        elif c in ')]}':
            if depth == 0:
                return body[:i]
            depth -= 1
        elif c == ';' and depth == 0:
            return body[:i]
    return body


def enum_names(src, name):
    """The names of the entries of `enum class [name]` in [src], in order."""
    out, depth, piece = [], 0, ''
    for c in enum_body(src, name) + ',':
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        if c == ',' and depth == 0:
            m = re.match(r'\s*([A-Z_][A-Z0-9_]*)', piece)
            if m:
                out.append(m.group(1))
            piece = ''
        else:
            piece += c
    return out


def no_twice(pairs):
    seen = set()
    for k, _ in pairs:
        if k in seen:
            raise ValueError('the key "%s" twice in one object' % k)
        seen.add(k)
    return dict(pairs)


class Code:
    """Part M4's checks of what the code names (the module's docstring); its problems go to [c]'s list."""

    def __init__(self, c):
        self.c = c
        self.bad = c.bad
        self.lang = c.json('lang/en_us.json') or {}
        self.counts = {}

    def has(self, key):
        """Whether [key] is a line; a key with a template in it, whether it matches one; a key ending in . or _, whether one starts so."""
        if WILD in key:
            rx = re.compile('.+'.join(re.escape(p) for p in key.split(WILD)))
            return any(rx.fullmatch(k) for k in self.lang)
        if key.endswith(('.', '_')):
            return any(k.startswith(key) for k in self.lang)
        return key in self.lang

    # ---------------------------------------------------------------- the registry

    def registry(self):
        reg = read_kotlin('cooking/CookRegistry.kt')
        if reg is None:
            self.bad('missing cooking/CookRegistry.kt')
            return
        data = read_kotlin('cooking/CookData.kt')
        found = {
            'blocks': re.findall(r'\bBLOCKS\.registerBlock\(\s*"([a-z0-9_]+)"', reg),
            'block items': re.findall(r'\bITEMS\.registerSimpleBlockItem\(\s*"([a-z0-9_]+)"', reg),
            'items': [],
            'effects': re.findall(r'\bEFFECTS\.register\(\s*"([a-z0-9_]+)"', reg),
            'particles': re.findall(r'\bparticle\(\s*"([a-z0-9_]+)"\s*\)', read_kotlin('cooking/CookParticles.kt') or ''),
        }
        for arg in re.findall(r'\bITEMS\.registerItem\(\s*([^,)]+)', reg):
            arg = arg.strip()
            if re.fullmatch(r'"[a-z0-9_]+"', arg):
                found['items'].append(arg[1:-1])
            elif arg == 'tool.id':
                found['items'] += re.findall(r'\b[A-Z_]+\(\s*"([a-z0-9_]+)"\s*\)', enum_body(read_kotlin('cooking/vessel/CookTools.kt'), 'CookTool'))
            elif arg == 'kind.itemId' and re.search(r'val itemId: String get\(\) = "dish_\$id"', data or ''):
                found['items'] += ['dish_' + i for i in re.findall(r'^\s*[A-Z_]+\(\s*"([a-z0-9_]+)"', enum_body(data, 'DishKind'), re.M)]
            else:
                self.bad('cooking/CookRegistry.kt registers an item by a name this can\'t read: %s' % arg)
        listed = {'blocks': BLOCKS, 'block items': BLOCK_ITEMS, 'items': ITEMS, 'effects': EFFECTS, 'particles': tuple(PARTICLES)}
        for what, ids in found.items():
            for i in sorted(set(ids) - set(listed[what])):
                self.bad('the Kotlin registers the %s %s, which this check\'s list (Appendix C.1) lacks, so its files are not followed' % (what[:-1], i))
            for i in sorted(set(listed[what]) - set(ids)):
                self.bad('this check lists the %s %s, which the Kotlin does not register' % (what[:-1], i))
            if len(ids) != len(set(ids)):
                self.bad('the Kotlin registers a %s twice: %s' % (what[:-1], sorted(i for i in set(ids) if ids.count(i) > 1)))
        names = [('blocks', 'block.guildmark.%s', 'blockstates/%s.json'), ('block items', 'block.guildmark.%s', 'items/%s.json'),
                 ('items', 'item.guildmark.%s', 'items/%s.json'), ('effects', 'effect.guildmark.%s', None)]
        for what, key, rel in names:
            for i in found[what]:
                if key % i not in self.lang:
                    self.bad('the %s %s has no name: %s is not in en_us.json' % (what[:-1], i, key % i))
                if rel is not None and (rel % i) in self.c.named and not self.c.ours(self.c.named[rel % i]):
                    self.bad('the %s %s: the models %s names reach no texture of ours' % (what[:-1], i, rel % i))
        self.counts['ids'] = sum(len(v) for v in found.values())

    # ---------------------------------------------------------------- the sounds

    def sounds(self):
        src = read_kotlin('cooking/CookSounds.kt') or ''
        m = re.search(r'\bval NAMES\b[^=]*=\s*listOf\((.*?)\)', src, re.S)
        names = re.findall(r'"([a-z0-9_.]+)"', m.group(1)) if m else []
        if not names:
            self.bad('cooking/CookSounds.kt: no NAMES this can read')
        sj = self.c.json('sounds.json') or {}
        listed = set()
        for key, entry in sj.items():
            for s in (entry.get('sounds') or []) if isinstance(entry, dict) else []:
                if isinstance(s, dict) and s.get('type', 'file') == 'file':
                    listed.add(s.get('name'))
                elif isinstance(s, str):
                    listed.add(s)
        files = 0
        for n in names:
            entry = sj.get(n)
            if not isinstance(entry, dict) or not entry.get('sounds'):
                self.bad('sounds.json: the event %s has no sounds' % n)
                continue
            for s in entry['sounds']:
                name, kind = (s, 'file') if isinstance(s, str) else (s.get('name', ''), s.get('type', 'file'))
                ns, _, path = name.rpartition(':')
                if kind == 'event':
                    if (ns or 'minecraft') == 'guildmark' and path not in sj:
                        self.bad('sounds.json: %s plays the event %s, which has no entry' % (n, name))
                    continue
                if (ns or 'minecraft') != 'guildmark':
                    continue
                rel = 'sounds/%s.ogg' % path
                files += 1
                if not os.path.exists(self.c.path(rel)):
                    self.bad('sounds.json: %s names %s, which is missing' % (n, rel))
                else:
                    with open(self.c.path(rel), 'rb') as f:
                        if f.read(4) != b'OggS':
                            self.bad('%s is not Ogg' % rel)
            sub = entry.get('subtitle')
            if not sub:
                self.bad('sounds.json: %s has no subtitle' % n)
            elif sub not in self.lang:
                self.bad('sounds.json: the subtitle of %s, %s, is not in en_us.json' % (n, sub))
        for k in sorted(k for k in sj if k.startswith('cook.') and k not in names):
            self.bad('sounds.json: %s is not in CookSounds.NAMES, so no event plays it' % k)
        top = self.c.path('sounds/cook')
        for d, dirs, fs in os.walk(top):
            dirs.sort()
            for f in sorted(fs):
                rel = os.path.relpath(os.path.join(d, f), self.c.assets).replace(os.sep, '/')
                if not f.endswith('.ogg'):
                    self.bad('%s is not a sound' % rel)
                elif 'guildmark:' + rel[len('sounds/'):-len('.ogg')] not in listed:
                    self.bad('%s is named by no entry of sounds.json' % rel)
        self.counts['events'], self.counts['sound files'] = len(names), files

    # ---------------------------------------------------------------- the language

    def language(self):
        enums = read_kotlin('cooking/CookData.kt')
        keys = {}
        for rel, src in kotlin_files(*SCANNED):
            for line, text in strings(src):
                if LANG_LIKE.match(text) and check_lang.KEY.match(text):
                    keys.setdefault(text, '%s:%d' % (rel, line))
            for where, rx, key in HELPERS:
                if rel == where or rel.startswith(where + '/'):
                    for m in rx.finditer(src):
                        k = key % m.group(1) + (WILD if m.group(2) else '')
                        keys.setdefault(k, '%s:%d' % (rel, src.count('\n', 0, m.start()) + 1))
        for k, where in sorted(keys.items()):
            if not self.has(k):
                self.bad('%s names %s, which is not in en_us.json' % (where, k.replace(WILD, '*')))
            head = k[:-1] if k.endswith(WILD) and k.count(WILD) == 1 else None
            if head in EXPAND:
                for v in enum_names(enums, EXPAND[head]):
                    if head + v.lower() not in self.lang:
                        self.bad('%s names %s* for each %s: %s is not in en_us.json' % (where, head, EXPAND[head], head + v.lower()))
        merge = subprocess.run([sys.executable, '-B', os.path.join(ROOT, 'art', 'lang', 'merge_lang.py'), '--check'],
                               capture_output=True, text=True)
        if merge.returncode != 0:
            said = (merge.stdout + merge.stderr).strip().splitlines()
            self.bad('merge_lang.py --check: a merge would change en_us.json or sounds.json: %s' % ' | '.join(said[-4:]))
        self.counts['keys'] = len(keys)

    # ---------------------------------------------------------------- the voices

    def families(self):
        words = read_kotlin('townsfolk/talk/sources/KitchenWords.kt') or ''
        count = {k: int(n) for k, n in re.findall(r'^\s+"([a-z0-9_.]+)" to (\d+)', words, re.M)}
        talk = read_kotlin('townsfolk/talk/TalkLines.kt') or ''
        fam = set(re.findall(r'^\s+"([a-z0-9_.]+)" to Family\(', talk, re.M))
        door = read_kotlin('cooking/market/DoorLines.kt') or ''
        own = re.search(r'private val OWN\b[^=]*=\s*setOf\((.*?)\)', door, re.S)
        own = set(re.findall(r'"([a-z0-9_.]+)"', own.group(1))) if own else set()
        table = set(re.findall(r'^\s+"([a-z0-9_.]+)" to listOf\(', door, re.M))
        if not count or not fam:
            self.bad('no families read from KitchenWords.kt (%d) or TalkLines.kt (%d)' % (len(count), len(fam)))
        for f in sorted(own - table):
            self.bad('cooking/market/DoorLines.kt: %s is one of the Door\'s own and has no words in its table' % f)
        named = {}
        for rel, src in kotlin_files(*SCANNED):
            for line, text in strings(src):
                if FAMILY_LIKE.match(text):
                    named.setdefault(text, '%s:%d' % (rel, line))
        for rel, src in kotlin_files(''):
            for m in FAMILY_CALLS.finditer(src):
                named.setdefault(m.group(1), '%s:%d' % (rel, src.count('\n', 0, m.start()) + 1))
        for f, where in sorted(named.items()):
            if f not in count and f not in fam and f not in own:
                self.bad('%s names the voice family %s, which is in neither KitchenWords nor TalkLines.FAMILIES' % (where, f))
        for f, n in sorted(count.items()):
            for i in range(n):
                if 'say.guildmark.talk.%s_%d' % (f, i) not in self.lang:
                    self.bad('KitchenWords counts %d wordings of %s: say.guildmark.talk.%s_%d is not in en_us.json' % (n, f, f, i))
        self.counts['families'] = len(named)

    # ---------------------------------------------------------------- data

    def data(self):
        n = 0
        for d, dirs, fs in os.walk(DATA):
            dirs.sort()
            for f in sorted(fs):
                if not f.endswith('.json'):
                    continue
                n += 1
                p = os.path.join(d, f)
                try:
                    with open(p, encoding='utf-8') as fh:
                        json.load(fh, object_pairs_hook=no_twice)
                except (ValueError, UnicodeDecodeError) as e:
                    self.bad('%s: %s' % (os.path.relpath(p, os.path.dirname(DATA)).replace(os.sep, '/'), e))
        self.counts['data files'] = n

    def run(self):
        self.registry()
        self.sounds()
        self.language()
        self.families()
        self.data()


def code(c):
    """Part M4's checks of the code, on [c], a Check that has run; the problems go to its list. A line saying what was checked."""
    before = len(c.problems)
    k = Code(c)
    k.run()
    return '%d registry ids, %d sound events (%d files), %d language keys, %d voice families, %d data files read; problems: %d' % (
        k.counts.get('ids', 0), k.counts.get('events', 0), k.counts.get('sound files', 0), k.counts.get('keys', 0),
        k.counts.get('families', 0), k.counts.get('data files', 0), len(c.problems) - before)


if __name__ == '__main__':
    c = Check(sys.argv[1] if len(sys.argv) > 1 else ASSETS)
    frames = c.run()
    print('art: %d blocks, %d items, %d effects, %d particles (%d frames), %d Book sprites, %d models and %d textures followed; problems: %d' % (
        len(BLOCKS), len(ITEMS), len(EFFECTS), len(PARTICLES), frames, len(SPRITES), len(c.models), len(c.textures), len(c.problems)))
    print('code: ' + code(c))
    for p in c.problems:
        print(' -', p)
    sys.exit(1 if c.problems else 0)
