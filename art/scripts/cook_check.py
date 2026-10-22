"""Hearthcraft's check of what `cook_art.py` wrote, so that a file the game would show as the purple-and-black square is found before the game is run:

    python3 -B art/scripts/cook_check.py [assets dir]

`cook_art.py` runs it after writing and stops with the list if there is anything in it. It starts from the ids of Appendix C.1 (the nine blocks, the
twenty items, the five effects, the seven particles, the twenty-one Book sprites, the four renderer textures) and follows what each file names: a
blockstate's models, an item definition's models (through its composites and selects), a model's parent and textures, a particle's frames. It reports
a file that is missing, a model the game would refuse or draw wrongly (an element outside the range the game takes, a face on the wrong side of a flat
one, a texture the model does not define, a UV outside the picture, a light that is not 0 to 15), a select or composite without what the game reads in
it, an animation whose frames do not fit its strip, and a picture of the wrong size or with nothing in it. The game's own models and textures are not
followed. It reads only the files, so the sheets of `cook_prev.py` and this agree on what is there.
"""
import json
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(HERE, '..', '..', 'src/main/resources/assets/guildmark'))

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


class Check:
    def __init__(self, assets):
        self.assets = assets
        self.problems = []
        self.models = {}               # 'block/stewpot' -> its JSON (None if it could not be read)
        self.textures = set()          # 'item/dish_pottage' and the like

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
            with open(p) as f:
                return json.load(f)
        except ValueError as e:
            self.bad('%s is not JSON: %s' % (rel, e))
            return None

    def picture(self, rel, who=''):
        """The picture at [rel] as RGBA, or None (and a problem) if it is missing."""
        if not os.path.exists(self.path(rel)):
            self.bad('missing %s%s' % (rel, ' (named by %s)' % who if who else ''))
            return None
        return Image.open(self.path(rel)).convert('RGBA')

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
            if not im.crop((0, i * im.width, im.width, (i + 1) * im.width)).getchannel('A').getbbox():
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
        n = self.frames(rel, im, drawn=name.startswith('block/'))
        if name.startswith('item/') and n == 1 and im.size != (32, 32):
            self.bad('%s is %s: the items are drawn at 32 x 32' % (rel, im.size))

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

    def blockstate(self, block):
        s = self.json('blockstates/%s.json' % block)
        if s is None:
            return
        refs = []
        for v in s.get('variants', {}).values():
            refs += [v['model']] if isinstance(v, dict) else [x['model'] for x in v]
        for part in s.get('multipart', []):
            a = part['apply']
            refs += [a['model']] if isinstance(a, dict) else [x['model'] for x in a]
        if not refs:
            self.bad('blockstates/%s.json names no model' % block)
        for r in refs:
            self.model(r, 'blockstates/%s.json' % block)

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
        d = self.json('items/%s.json' % name)
        if d is None:
            return
        refs = []
        self.walk('items/%s.json' % name, d, refs)
        if not refs:
            self.bad('items/%s.json names no model' % name)
        for r in refs:
            self.model(r, 'items/%s.json' % name)

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
            if im is not None and (im.size != (16, 16) or not im.getchannel('A').getbbox()):
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
            if not im.getchannel('A').getbbox():
                self.bad('%s is empty' % rel)
        elif name == 'book_ribbon':
            if not im.getchannel('A').getbbox():
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
        for e in EFFECTS:
            im = self.picture('textures/mob_effect/%s.png' % e)
            if im is not None:
                self.frames('textures/mob_effect/%s.png' % e, im, 36)
        frames = sum(self.particle(p, n) for p, n in PARTICLES.items())
        for s in SPRITES:
            self.sprite(s)
        im = self.picture('textures/gui/chill.png')
        if im is not None and (im.size != (256, 256) or not im.getchannel('A').getbbox()):
            self.bad('textures/gui/chill.png is %s or empty: the vignette is 256 x 256' % (im.size,))
        for r in RENDERER:
            im = self.picture('textures/entity/cook/%s.png' % r)
            if im is not None and (im.size != (32, 32) or not im.getchannel('A').getbbox()):
                self.bad('textures/entity/cook/%s.png is %s or empty: the renderers\' textures are 32 x 32' % (r, im.size))
        return frames


def check(assets=ASSETS):
    """The problems in what Hearthcraft wrote into [assets] (an empty list when there are none), and a line saying what was checked."""
    c = Check(assets)
    frames = c.run()
    line = '%d blocks, %d items, %d effects, %d particles (%d frames), %d Book sprites, %d models and %d textures followed; problems: %d' % (
        len(BLOCKS), len(ITEMS), len(EFFECTS), len(PARTICLES), frames, len(SPRITES), len(c.models), len(c.textures), len(c.problems))
    return c.problems, line


if __name__ == '__main__':
    found, summary = check(sys.argv[1] if len(sys.argv) > 1 else ASSETS)
    print(summary)
    for p in found:
        print(' -', p)
    sys.exit(1 if found else 0)
