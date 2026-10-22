"""Tales' four sprites, drawn the way the screen kit draws its markers (orders_art.py, ui_art.py): a 1x design in row strings, then
remastered at 2x by ui_hd.py (Scale2x, a bevel of light from the upper left, a little grain). Part G of the Tales design
(living-world/stories/design.md, 13.2.4).

    python3 -B art/scripts/tale_ui.py src/main/resources/assets/guildmark [preview-dir]

Writes textures/gui/sprites/

  * compass/tale.png (14 px, 7 rows at 1x): a cream quill with a gold nib, for a tale's waypoint on the compass strip, in the compass's
    full dark outline;
  * map/tale.png (22 px, 11 rows at 1x): an ink quill on the parchment, the nib red, for a tale's waypoint on the map;
  * compass/memorial.png and map/memorial.png (14 and 22 px): a small standing stone with a point of light over its top, for the atlas's
    MEMORIAL mark (the compass strip's is grey on the dark, the map's grey in an ink outline).

Every one moves a little: the memorial's light twinkles (a dot, a small cross, a flare), a gold spark runs up the compass quill from its
nib to its tip and a red drop runs down the map quill's rib to its nib. So each is a strip of frames, one above another, and a .png.mcmeta that says how long
each is held (the game ticks the screen's atlas as it does the block one); frame 0 is the resting picture, which is also all a game that
did not tick would show. Everything is seeded: a rerun gives the same files. With a preview dir it also writes the sheets: each sprite at
the two sizes the screen shows it (GUI scale 2 and 4) and as a loupe, on the compass strip's ground and the map's parchment, beside
three of the markers it stands among; every frame; and the animations as GIFs.
"""
import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import ui_art
import ui_hd

CL = ui_art.CL

# the colours the screen kit has, and the few a stone and a point of light want that it does not
PAL_COMPASS = dict(ui_art.MARK_PAL)
PAL_MAP = dict(ui_art.MAP_PAL)
PAL_MAP.update({
    'W': (255, 250, 236, 255),      # the light's white heart
    'y': (214, 160, 52, 255),       # a point of light on the parchment: a gold that shows on it
    'Y': (250, 214, 104, 255),
    'a': (190, 184, 172, 255),      # the stone: a lit face, a body and a shaded side, cool grey inside the ink outline
    'b': (142, 136, 128, 255),
    'c': (98, 94, 92, 255),
    'e': (92, 138, 70, 255),        # the grass it stands in
    'f': (60, 96, 52, 255),
})


class Sprite:
    """One marker: its 1x rows, which character draws the outline, and its frames. [frames] gives, for each frame, the pixels laid over
    the rows, {(x, y): character}; [hold] says for how many ticks each frame in the order it plays is shown, as (frame, ticks)."""

    def __init__(self, key, rows, line, frames, hold, seed):
        self.key, self.rows, self.line, self.frames, self.hold, self.seed = key, rows, line, frames, hold, seed
        self.compass = key.startswith('compass/')

    def rows_of(self, i):
        grid = [list(r) for r in self.rows]
        for (x, y), ch in self.frames[i].items():
            grid[y][x] = ch
        return [''.join(r) for r in grid]

    def image(self, i):
        pal = PAL_COMPASS if self.compass else PAL_MAP
        return ui_art.from_rows(self.rows_of(i), pal)


def glint(*pts):
    """A frame that differs from the resting one by single pixels: pts are (x, y, character)."""
    return {(x, y): ch for x, y, ch in pts}


# ================================================================ the compass's quill (7 x 7)
#
# a feather lying from the lower left to the upper right: a cream vane with a grey rib, a gold barrel at the foot, the compass's dark
# outline all round (so it shows against the sky and the strip alike). A gold spark runs up it from the nib.

COMPASS_TALE = Sprite(
    'compass/tale',
    [".....oo",
     "...oowo",
     "..oWwwo",
     ".oWwswo",
     ".owswo.",
     "oGGwo..",
     "oooo..."],
    'o',
    [{},
     glint((1, 5, 'W')),
     glint((2, 4, 'G')),
     glint((3, 3, 'G')),
     glint((4, 2, 'G')),
     glint((5, 1, 'G'))],
    [(0, 40), (1, 2), (2, 2), (3, 2), (4, 2), (5, 2)],
    seed=11)


# ================================================================ the map's quill (11 x 11)
#
# the same feather in ink on the parchment: a dark outline, a wide cream vane, a brown rib running down it to the barrel, and a red nib.
# A red drop of ink runs down the rib to the nib, which flashes.

MAP_TALE = Sprite(
    'map/tale',
    ["......kkk..",
     ".....kwwwk.",
     "....kwwwwwk",
     "...kwwwwwwk",
     "..kwwwswwk.",
     "..kwwswwwk.",
     "..kwswwwk..",
     ".kswwwwk...",
     ".kskkkk....",
     "krsk.......",
     ".kk........"],
    'k',
    [{},
     glint((6, 4, 'r')),
     glint((5, 5, 'r')),
     glint((4, 6, 'r')),
     glint((2, 7, 'r')),
     glint((2, 8, 'r')),
     glint((1, 9, 'R'), (2, 9, 'r'))],
    [(0, 40), (1, 3), (2, 3), (3, 3), (4, 3), (5, 3), (6, 4)],
    seed=12)


# ================================================================ the memorial's stone and its light
#
# a stone standing in grass (so it reads as a stone and not as a bottle or a mirror), a lit face and a shaded side, the map's leaning a
# little; over its top a point of light: at rest a small cross, then (in the order it plays) a flare, back to the cross, a dim cross, a
# bare dot, a dim cross, round again.

COMPASS_MEMORIAL = Sprite(
    'compass/memorial',
    [".......",
     ".ooooo.",
     ".oQQIo.",
     ".oQIio.",
     ".oIIio.",
     ".oIiio.",
     "eoooooe"],
    'o',
    [glint((3, 0, 'W'), (1, 1, 'd'), (2, 1, 'G'), (3, 1, 'W'), (4, 1, 'G'), (5, 1, 'd')),
     glint((2, 1, 'd'), (3, 1, 'G'), (4, 1, 'd')),
     glint((2, 0, 'G'), (3, 0, 'W'), (4, 0, 'G'), (1, 1, 'G'), (2, 1, 'W'), (3, 1, 'W'), (4, 1, 'W'), (5, 1, 'G')),
     glint((3, 1, 'W'))],
    [(0, 8), (2, 2), (0, 2), (1, 3), (3, 3), (1, 2)],
    seed=13)

MAP_MEMORIAL = Sprite(
    'map/memorial',
    ["...........",
     "...........",
     "...........",
     "...........",
     ".....kkk...",
     "....kaabk..",
     "....kaabk..",
     "...kaabck..",
     "...kabbck..",
     "..ekabcke..",
     ".eekkkkkee."],
    'k',
    [glint((5, 0, 'y'), (4, 1, 'y'), (5, 1, 'Y'), (6, 1, 'y'), (5, 2, 'y')),
     glint((5, 0, 'g'), (4, 1, 'g'), (5, 1, 'y'), (6, 1, 'g'), (5, 2, 'g')),
     glint((5, 0, 'Y'), (4, 1, 'Y'), (5, 1, 'W'), (6, 1, 'Y'), (5, 2, 'Y'), (3, 1, 'y'), (7, 1, 'y'),
           (4, 0, 'g'), (6, 0, 'g'), (4, 2, 'g'), (6, 2, 'g')),
     glint((5, 1, 'Y'))],
    [(0, 8), (2, 2), (0, 2), (1, 3), (3, 3), (1, 2)],
    seed=14)

SPRITES = (COMPASS_TALE, MAP_TALE, COMPASS_MEMORIAL, MAP_MEMORIAL)


# ================================================================ the files


def remastered(sp):
    """The sprite's frames at 2x, each remastered with the same seed so the grain stays put while a spark moves."""
    return [ui_hd.remaster(sp.image(i), seed=sp.seed, thin=not sp.compass) for i in range(len(sp.frames))]


def strip_of(frames):
    out = Image.new('RGBA', (frames[0].width, frames[0].height * len(frames)), CL)
    for i, f in enumerate(frames):
        out.paste(f, (0, i * f.height))
    return out


def meta_of(sp):
    return {'animation': {'frametime': 1, 'frames': [{'index': i, 'time': t} for i, t in sp.hold]}}


def write_json(path, obj):
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')


def write_all(assets):
    made = {}
    for sp in SPRITES:
        frames = remastered(sp)
        path = os.path.join(assets, 'textures', 'gui', 'sprites', sp.key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        strip_of(frames).save(path, optimize=True)
        write_json(path + '.mcmeta', meta_of(sp))
        made[sp.key] = frames
    return made


def verify(assets):
    """Every sprite the size the screen draws it from (14 or 22 px square frames), the strip a whole number of frames, the hold list
    naming only frames that exist and starting on frame 0, the resting picture's outline closed (no coloured pixel beside a see-through
    one inside the sprite: the sprite's own edge is not a gap; the grass and the light are not outlined), and a moving frame marking
    only the pixels of the quill it is a spark on."""
    bad = []
    for sp in SPRITES:
        side = 14 if sp.compass else 22
        n = 7 if sp.compass else 11
        if len(sp.rows) != n or any(len(r) != n for r in sp.rows):
            bad.append('%s: the rows are not %d x %d' % (sp.key, n, n))
        path = os.path.join(assets, 'textures', 'gui', 'sprites', sp.key + '.png')
        im = Image.open(path)
        if im.size != (side, side * len(sp.frames)):
            bad.append('%s: %d x %d, not %d x %d' % (sp.key, im.width, im.height, side, side * len(sp.frames)))
        for fr in json.load(open(path + '.mcmeta'))['animation']['frames']:
            if not 0 <= fr['index'] < len(sp.frames) or fr['time'] < 1:
                bad.append('%s: hold entry %s' % (sp.key, fr))
        if sp.hold[0][0] != 0:
            bad.append(sp.key + ': the loop does not start on the resting frame')
        for y, r in enumerate(sp.rows):
            for x, ch in enumerate(r):
                if ch in '.ef' or ch == sp.line:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < n and 0 <= ny < n and sp.rows[ny][nx] == '.':
                        bad.append('%s: the outline is open beside (%d, %d)' % (sp.key, x, y))
                        break
        quill = {'compass/tale': 'wG', 'map/tale': 'sr'}.get(sp.key)
        for i in range(1, len(sp.frames)):
            for (x, y) in sp.frames[i]:
                if quill is not None and sp.rows[y][x] not in quill:
                    bad.append('%s: frame %d marks (%d, %d), which is not on the quill' % (sp.key, i, x, y))
    return bad


# ================================================================ the previews

BG = (30, 30, 36, 255)
INKWELL = (230, 224, 210, 255)


def ground_of(assets, compass, w, h):
    """A piece of the compass strip's texture (over a sky) or of the map's parchment, w x h texels."""
    if compass:
        src = Image.open(os.path.join(assets, 'textures', 'gui', 'sprites', 'compass', 'strip.png')).convert('RGBA')
        out = Image.new('RGBA', (w, h), (48, 66, 98, 255))
        layer = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        layer.paste(src, (-(src.width // 2 - w // 2), (h - src.height) // 2))
        out.alpha_composite(layer)
        return out
    src = Image.open(os.path.join(assets, 'textures', 'gui', 'map', 'parchment.png')).convert('RGBA')
    return src.crop((60, 60, 60 + w, 60 + h))


def on_ground(assets, img, compass, scale, pad_texels):
    """The sprite at [scale] on a piece of its ground [pad_texels] larger all round, as the screen draws them at that GUI scale."""
    w, h = img.width + 2 * pad_texels, img.height + 2 * pad_texels
    g = ground_of(assets, compass, w, h)
    g.alpha_composite(img, (pad_texels, pad_texels))
    return g.resize((w * scale, h * scale), Image.NEAREST)


def neighbours(assets, compass):
    kind = 'compass' if compass else 'map'
    names = ('wardfire', 'lair', 'contract') if compass else ('wardfire', 'lair', 'death')
    out = []
    for n in names:
        p = os.path.join(assets, 'textures', 'gui', 'sprites', kind, n + '.png')
        if os.path.exists(p):
            out.append((n, Image.open(p).convert('RGBA')))
    return out


def previews(assets, made, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    # 1. each sprite in its place: GUI scale 2 (1 texel a pixel), GUI scale 4 (2), and a loupe (8), beside its neighbours
    rows = []
    for sp in SPRITES:
        rest = made[sp.key][0]
        pad = 6 if sp.compass else 8
        cells = [('%s' % sp.key.split('/')[1], rest)] + neighbours(assets, sp.compass)
        line = []
        for scale in (1, 2):
            strip = []
            for name, im in cells:
                strip.append(on_ground(assets, im, sp.compass, scale, pad))
            line.append(strip)
        loupe = on_ground(assets, rest, sp.compass, 8, 2)
        rows.append((sp, line, loupe, cells))
    W = 24 + max(sum(c.width + 8 for c in line[1]) + sum(c.width + 8 for c in line[0]) + loupe.width + 24 for sp, line, loupe, cells in rows)
    H = 16 + sum(max(max(c.height for c in line[1]), loupe.height) + 28 for sp, line, loupe, cells in rows)
    sheet = Image.new('RGBA', (W, H), BG)
    d = ImageDraw.Draw(sheet)
    y = 10
    for sp, line, loupe, cells in rows:
        d.text((12, y), '%s   (the first at GUI scale 2, then 4, then a loupe; beside %s)' % (sp.key, ', '.join(n for n, _ in cells[1:])), fill=INKWELL)
        y += 18
        x = 12
        for strip in line:
            for c in strip:
                sheet.alpha_composite(c, (x, y))
                x += c.width + 8
            x += 16
        sheet.alpha_composite(loupe, (x, y))
        y += max(max(c.height for c in line[1]), loupe.height) + 10
    sheet.convert('RGB').save(os.path.join(out_dir, 'tales_sprites.png'))

    # 2. every frame of each, large, and the loop's order
    scale = 10
    cells = []
    for sp in SPRITES:
        pad = 2
        frames = [on_ground(assets, f, sp.compass, scale, pad) for f in made[sp.key]]
        cells.append((sp, frames))
    W = 16 + max(sum(f.width + 8 for f in frames) for sp, frames in cells)
    H = 12 + sum(frames[0].height + 34 for sp, frames in cells)
    sheet = Image.new('RGBA', (W, H), BG)
    d = ImageDraw.Draw(sheet)
    y = 8
    for sp, frames in cells:
        d.text((8, y), '%s: frames 0..%d; plays %s' % (sp.key, len(frames) - 1, ' '.join('%d(%d)' % h for h in sp.hold)), fill=INKWELL)
        y += 18
        x = 8
        for i, f in enumerate(frames):
            sheet.alpha_composite(f, (x, y))
            d.text((x, y + f.height + 2), 'frame %d' % i, fill=INKWELL)
            x += f.width + 8
        y += frames[0].height + 16
    sheet.convert('RGB').save(os.path.join(out_dir, 'tales_sprites_frames.png'))

    # 3. the loops, one GIF each, at the speed the game plays them (a tick is 50 ms)
    for sp in SPRITES:
        pad = 4
        scale = 12 if sp.compass else 8
        imgs, durs = [], []
        for i, t in sp.hold:
            imgs.append(on_ground(assets, made[sp.key][i], sp.compass, scale, pad).convert('RGB'))
            durs.append(t * 50)
        name = 'tales_sprite_%s_%s.gif' % tuple(sp.key.split('/'))
        imgs[0].save(os.path.join(out_dir, name), save_all=True, append_images=imgs[1:], duration=durs, loop=0, optimize=False)


def main(assets, previews_dir=None):
    made = write_all(assets)
    problems = verify(assets)
    for p in problems:
        print('PROBLEM', p)
    print('tale_ui: %d sprites, %d frames%s' % (len(made), sum(len(v) for v in made.values()),
                                                 '' if not problems else ', %d problems' % len(problems)))
    if previews_dir:
        previews(assets, made, previews_dir)
    return problems


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    sys.exit(1 if main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None) else 0)
