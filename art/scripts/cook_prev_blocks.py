"""The block previews (cook_prev.py): each station lit, unlit and burnt at noon and dusk beside the game's campfire and furnace at true scale, with the
click regions outlined; the helpers; the moving pieces with their pivots (the contract with Part C, whose first comment block is in `cook_art.py`),
in a sheet and as a GIF; the herb's four ages; every item and block on one sheet; the lit stations alive in a GIF; and two dioramas, the tavern's hearth
corner and the bakery counter. Everything is drawn by `cook_stage.py` from the files the generator has written, so a preview shows the mod as it is.
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw

import cook_prevkit as P
import cook_stage as K
import cook_view as V
import cook_stewpot as SP
import cook_hob as HB
import cook_oven as OV
import cook_spit as SK
import cook_helpers as HL

NOON, DUSK = V.NOON, V.DUSK
STEP = 21.0                                   # between two blocks in a row, px (a block is 16 wide)
STEW = (0.66, 0.40, 0.22)                     # the surface tinted for a stew
REGION = {'vessel': (255, 96, 96), 'fire': (255, 176, 48), 'heat': (96, 220, 255)}
TICK = 6                                      # a moment for the flames and coals


# ================================================================ scenes

def row(assets, game, entries, mode, tick=TICK, yaw=165.0, elev=28.0, scale=10.5, height=330, ss=3, setup=None, step=STEP, cy=5.0):
    """[entries] (label, fn(stage, at)) left to right on a line across the view ([step] px apart, so that the row is level in the picture whatever the
    yaw), on paving, each labelled under its front edge. The picture is as wide as the row needs. [setup] (stage) runs after the entries are placed,
    for what is added over the whole scene."""
    n = len(entries)
    st = K.Stage(assets, game)
    right = (math.cos(math.radians(yaw)), -math.sin(math.radians(yaw)))          # the screen's right on the ground, as (x, z)
    ats = [((i - (n - 1) / 2.0) * step * right[0], 0.0, (i - (n - 1) / 2.0) * step * right[1]) for i in range(n)]
    xs = [a[0] for a in ats]
    zs = [a[2] for a in ats]
    st.floor(int(math.floor(min(xs) / 16.0)) - 5, int(math.floor(min(zs) / 16.0)) - 5, int(math.floor(max(xs) / 16.0)) + 6, int(math.floor(max(zs) / 16.0)) + 7)
    for at, (label, fn) in zip(ats, entries):
        fn(st, at)
        if label:
            st.label(label, (8, 0, -2.5), at=at)
    if setup:
        setup(st)
    width = int(((n - 1) * step + 21) * scale) + 60
    return st.render(yaw=yaw, elev=elev, scale=scale, size=(width, height), center=(8, cy, 8), mode=mode, tick=tick, ground=False, ss=ss)


def stack(parts, title=None, gap=8, bg=P.BG, caption_size=13, pad=8):
    """[parts] (caption, image) one under another, each with its caption above, and a title over all."""
    th = (P.text_size(title, 17)[1] + 16) if title else 0
    w = max(im.width for _, im in parts) + 2 * pad
    h = th + sum(im.height + (caption_size + 8 if cap else 0) + gap for cap, im in parts) + pad
    sheet = Image.new('RGBA', (w, h), bg + (255,))
    d = ImageDraw.Draw(sheet)
    if title:
        P.text(d, (pad, 7), title, P.INK, 17)
    y = th
    for cap, im in parts:
        if cap:
            P.text(d, (pad, y), cap, P.DIM, caption_size)
            y += caption_size + 8
        sheet.alpha_composite(im.convert('RGBA'), (pad, y))
        y += im.height + gap
    return sheet


def hstack(images, gap=6, bg=P.BG):
    w = sum(im.width for im in images) + gap * (len(images) - 1)
    h = max(im.height for im in images)
    out = Image.new('RGBA', (w, h), bg + (255,))
    x = 0
    for im in images:
        out.alpha_composite(im.convert('RGBA'), (x, 0))
        x += im.width + gap
    return out


def _V(name):
    return lambda st, at: K.vanilla(st, name, at)


def _vcamp_lit():
    return ('vanilla campfire', _V('campfire'))


def _vfurnace():
    return ('vanilla furnace', _V('furnace'))


# ================================================================ the stations

def _around(first, entries, last):
    """[entries] with the game's blocks (if any) at each end."""
    return ([first] if first else []) + list(entries) + ([last] if last else [])


def _stew(style, **kw):
    def fn(st, at):
        K.stewpot(st, at, style, **kw)
    return fn


def station_entries(which, vanilla=True):
    """[(row caption, [entries])]: the states of each station, between the game's campfire and furnace (if [vanilla])."""
    cf, fu = (_vcamp_lit(), _vfurnace()) if vanilla else (None, None)
    if which == 'stewpot':
        out = []
        for style, nice in (('camp', 'camp style'), ('hearth', 'hearth style')):
            ents = [('unlit', _stew(style, lit=False, notch=1)),
                    ('lit, heat 1', _stew(style, lit=True, notch=1, level=0.7, tint=STEW)),
                    ('lit, heat 2 (lower)', _stew(style, lit=True, notch=2, level=0.7, tint=STEW)),
                    ('burnt', _stew(style, lit=False, burnt=True, notch=1, soot=3, level=0.7, crust=True))]
            if style == 'hearth':
                ents.insert(3, ('crane swung 40', _stew(style, lit=True, notch=1, level=0.7, tint=STEW, swing=40.0)))
            out.append((nice, _around(cf, ents, fu)))
        return out
    if which == 'hob':
        return [('hob', _around(cf, [
                         ('unlit', lambda st, at: K.hob(st, at, lit=False, notch=1)),
                         ('lit, damper 1', lambda st, at: K.hob(st, at, lit=True, notch=1)),
                         ('lit, damper 2, frying', lambda st, at: K.hob(st, at, lit=True, notch=2, pieces=_pan_pieces(st))),
                         ('burnt', lambda st, at: K.hob(st, at, lit=False, burnt=True, notch=1))], fu))]
    if which == 'oven':
        return [('bake oven', _around(cf, [
                               ('unlit', lambda st, at: K.oven(st, at, lit=False, trays=1)),
                               ('lit, door shut', lambda st, at: K.oven(st, at, lit=True, trays=2)),
                               ('lit, door open, trays out', lambda st, at: K.oven(st, at, lit=True, trays=2, door=100.0, out=5.0,
                                                                                       lower=_loaves(st), upper=_loaves(st))),
                               ('burnt', lambda st, at: K.oven(st, at, lit=False, burnt=True, trays=1))], fu))]
    if which == 'spit':
        return [('spit', _around(cf, [
                          ('unlit', lambda st, at: K.spit(st, at, lit=False)),
                          ('lit', lambda st, at: K.spit(st, at, lit=True)),
                          ('lit, rod turned 40', lambda st, at: K.spit(st, at, lit=True, angle=40.0)),
                          ('burnt', lambda st, at: K.spit(st, at, lit=False, burnt=True))], fu))]
    raise KeyError(which)


_SPRITES = {}


def _vanilla_item(st, name):
    """A game item's sprite (for the contents of a pan), or None without the game's assets."""
    key = (st.game, name)
    if key not in _SPRITES:
        path = os.path.join(st.game, 'textures/item', name + '.png') if st.game else None
        _SPRITES[key] = Image.open(path).convert('RGBA') if path and os.path.exists(path) else None
    return _SPRITES[key]


def _pan_pieces(st):
    names = ('beef', 'porkchop', 'cod', 'chicken', 'beef', 'salmon')
    out = [_vanilla_item(st, n) for n in names]
    return [im for im in out if im is not None]


def _loaves(st):
    it = P.Items(st.assets)
    return [it.render('dish_loaf', {}, 0)] * 5


def regions_view(assets, game, key, yaw, mode=NOON):
    """A station lit, with the click regions of `cook_art.STATION_BOXES` outlined, from [yaw]."""
    import cook_art
    st = K.Stage(assets, game)
    st.floor(-2, -2, 2, 2)
    boxes = cook_art.STATION_BOXES[key]
    for name, b in boxes.items():
        st.box(b[:3], b[3:], REGION[name], name=name)
    if key.startswith('stewpot'):
        K.stewpot(st, (0, 0, 0), key.split('_')[1], lit=True, notch=1, level=0.7, tint=STEW)
    elif key == 'hob':
        K.hob(st, (0, 0, 0), lit=True, notch=1)
    elif key == 'bake_oven':
        K.oven(st, (0, 0, 0), lit=True, trays=2)
    elif key == 'spit':
        K.spit(st, (0, 0, 0), lit=True)
    return st.render(yaw=yaw, elev=30.0, scale=19.0, size=(420, 400), center=(8, 7, 8), mode=mode, tick=TICK, ground=False, box_width=2)


def regions_block(assets, game, keys):
    """Per station: two views with the regions outlined, and the boxes written out."""
    import cook_art
    rows = []
    for key in keys:
        views = [regions_view(assets, game, key, yaw) for yaw in (160.0, 215.0)]
        legend = Image.new('RGBA', (420 * 2 + 6, 60), P.BG + (255,))
        d = ImageDraw.Draw(legend)
        y = 4
        for name, b in cook_art.STATION_BOXES[key].items():
            sx, sy, sz = (round(b[i + 3] - b[i], 1) for i in range(3))
            d.rectangle([4, y + 3, 16, y + 13], outline=REGION[name] + (255,), width=2)
            P.text(d, (24, y), '%s: from (%.1f, %.1f, %.1f) to (%.1f, %.1f, %.1f), %.1f x %.1f x %.1f px' % ((name,) + tuple(b) + (sx, sy, sz)), P.INK, 12)
            y += 18
        rows.append((key.replace('_', ' ') + ': click regions (facing north; each at least 4 px each way)', stack([('', hstack(views)), ('', legend)], gap=2, pad=0)))
    return rows


def station_sheet(assets, out, game, which, title):
    """One station's sheet: its states at noon and at dusk beside the game's campfire and furnace (the same scale), then its click regions."""
    parts = []
    for cap, ents in station_entries(which, game is not None):
        for mode, mname in ((NOON, 'noon'), (DUSK, 'dusk')):
            parts.append(('%s at %s: %s' % (cap, mname, ' | '.join(l for l, _ in ents if l)), row(assets, game, ents, mode)))
    keys = {'stewpot': ['stewpot_camp', 'stewpot_hearth'], 'hob': ['hob'], 'oven': ['bake_oven'], 'spit': ['spit']}[which]
    parts += regions_block(assets, game, keys)
    sheet = stack(parts, title)
    return P.save_png(sheet, os.path.join(out, 'cook_%s.png' % which))


# ================================================================ the helpers

def chalk(lines, w=244, h=152, size=17):
    """Writing in chalk for the slate (a stand-in: the game draws the real writing): [lines] (text, indent) top to bottom, on a clear ground."""
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    y = 8
    for text, indent in lines:
        P.text(d, (10 + indent, y), text, (236, 234, 224, 230), size)
        y += size + 7
    d.line([(8, y + 2), (w - 12, y + 3)], fill=(220, 218, 208, 170), width=2)
    return im


MENU = [("Today's pot", 0), ('Stew ..... 3', 8), ('Fresh loaf ..... 2', 8), ('Roast ..... 6', 8)]


def _heap(st):
    names = ('carrot', 'potato', 'beef', 'beetroot', 'apple', 'cod', 'carrot', 'potato', 'chicken', 'beetroot', 'apple', 'beef')
    return [im for im in (_vanilla_item(st, n) for n in names) if im is not None]


def helper_entries():
    it = {}

    def dish(st, name):
        if name not in it:
            it[name] = P.Items(st.assets).render(name, {}, 0)
        return it[name]

    def board(st, at):
        K.prep_board(st, at, heap=_heap(st))

    def plate_(st, at):
        K.plate(st, at, dish=dish(st, 'dish_stew'))

    def larder_shut(st, at):
        K.larder(st, at, open_=False, dishes=[dish(st, n) for n in ('dish_pie', 'dish_stew', 'dish_loaf')])

    def larder_open(st, at):
        K.larder(st, at, open_=True, door=75.0, dishes=[dish(st, n) for n in ('dish_pie', 'dish_stew', 'dish_loaf')])

    def menu(st, at):
        K.menu_board(st, at, chalk=chalk(MENU))

    def menu_wall(st, at):
        if st.game:
            K.vanilla(st, 'stone_bricks', (at[0], at[1], at[2] + 16.0))             # a wall to hang it on
        K.menu_board(st, at, wall=True, chalk=chalk(MENU))

    return [('prep board, with a heap', board), ('plate, with a dish', plate_), ('larder, door shut', larder_shut), ('larder, door open', larder_open),
            ('menu board', menu), ('menu board, wall variant', menu_wall)]


def helper_sheet(assets, out, game):
    parts = []
    for mode, name in ((NOON, 'noon'), (DUSK, 'dusk')):
        parts.append(('at %s: %s' % (name, ' | '.join(l for l, _ in helper_entries())), row(assets, game, helper_entries(), mode, elev=30.0, scale=10.5, height=300)))
    sheet = stack(parts, 'Helpers: Prep Board, Plate, Larder, Menu Board (the items and the chalk on them are stand-ins for what the renderers draw)')
    return P.save_png(sheet, os.path.join(out, 'cook_helpers.png'))


# ================================================================ the moving pieces and their pivots

PIVOT = (255, 64, 200)             # an axis, and the point on it
PATH = (255, 214, 64)              # the path an edge or a middle follows


def mech_pot(notch, marks=True, liquid=True):
    """The camp stewpot's pot at heat [notch] (a number: the pot slides straight down 1 px for each), with the line it slides along."""
    def fn(st, at):
        K.stewpot(st, at, 'camp', lit=False, notch=notch, level=0.7 if liquid else None, tint=STEW)
        if marks:
            st.line((8, 2.5, 8), (8, 16.8, 8), PIVOT, at)
            st.dot((8, SP.HANG_Y, 8), PIVOT, at)
            st.dot((8, SP.HANG_Y - SP.NOTCH_STEP * notch, 8), PATH, at, r=3)
    return fn


def mech_crane(swing, marks=True):
    """The hearth stewpot's crane swung [swing] degrees about its post, the pot with it."""
    def fn(st, at):
        K.stewpot(st, at, 'hearth', lit=False, notch=1, swing=swing, level=0.7, tint=STEW)
        if marks:
            x, z = SP.CRANE_POST
            st.line((x, 0.0, z), (x, 17.0, z), PIVOT, at)
            st.dot((x, 17.0, z), PIVOT, at)
            r = math.hypot(8 - x, 8 - z)
            h = math.degrees(math.atan2(-(8 - z), 8 - x))
            st.arc((x, z), r, 0.0, SP.CRANE_MAX, 1.2, PATH, at, heading=h)
    return fn


def mech_oven(door, out=0.0, marks=True):
    """The oven's door opened [door] degrees about its hinge, the trays drawn out [out] px."""
    def fn(st, at):
        K.oven(st, at, lit=False, trays=2, door=door, out=out, lower=_loaves(st), upper=())
        if marks:
            x, z = OV.HINGE
            st.line((x, 0.5, z), (x, 15.5, z), PIVOT, at)
            st.dot((x, 15.5, z), PIVOT, at)
            st.arc((x, z), 6.4, 0.0, OV.DOOR_MAX, 12.6, PATH, at)
    return fn


def mech_spit(notch, angle, marks=True):
    """The spit's rod and crank turned [angle] degrees about their line, lifted to the rest of heat [notch]."""
    def fn(st, at):
        K.spit(st, at, lit=False, notch=notch, angle=angle)
        if marks:
            y = SK.AXIS_Y + SK.REST_DY[notch]
            st.line((-0.5, y, SK.AXIS_Z), (16.5, y, SK.AXIS_Z), PIVOT, at)
            st.dot((16.5, y, SK.AXIS_Z), PIVOT, at)
    return fn


def mech_larder(door, marks=True):
    """The larder's door opened [door] degrees about its hinge."""
    def fn(st, at):
        K.larder(st, at, open_=True, door=door)
        if marks:
            x, z = HL.LARDER_DOOR_HINGE
            st.line((x, 1.0, z), (x, 16.5, z), PIVOT, at)
            st.dot((x, 16.5, z), PIVOT, at)
            st.arc((x, z), 12.0, 0.0, HL.LARDER_DOOR_MAX, 9.0, PATH, at)
    return fn


def pieces_table():
    """The contract's table as lines (piece, pivot or line, how it moves), from the modules' own numbers."""
    S, O, SKT, L = SP, OV, SK, HL
    return [
        ('cook_stewpot_pot', 'hangs at (8, %.1f, 8)' % S.HANG_Y, 'slides straight down %.1f px for each heat notch (0, 1, 2); bottom at y %.1f at notch 0' % (S.NOTCH_STEP, S.POT_BOTTOM)),
        ('cook_stewpot_soot_1..3', 'as the pot', 'laid over the pot and moved with it (soot stage 1 to 3; 3 is a crust)'),
        ('cook_stewpot_crane', 'vertical axis x %.1f, z %.1f' % S.CRANE_POST, 'hearth style: yaw 0 to +%d degrees; the pot, its chain and its soot turn with it' % S.CRANE_MAX),
        ('cook_oven_door (_hot)', 'vertical hinge x %.1f, z %.1f' % O.HINGE, 'yaw 0 (shut) to +%d degrees, outward (-z); _hot is the glowing window, drawn while hot and shut' % O.DOOR_MAX),
        ('cook_oven_tray', 'none', 'static at y %.1f (rails %.1f and %.1f); lift %.1f for the upper tray; slides out up to 6 px (-z)' % (O.TRAY_Y[0], O.TRAY_Y[0], O.TRAY_Y[1], O.TRAY_Y[1] - O.TRAY_Y[0])),
        ('cook_spit_rod / _crank', 'the line y %.1f, z %.1f, along x' % (SKT.AXIS_Y, SKT.AXIS_Z), 'turns any angle; lifted %+.1f, %+.1f, %+.1f px for heat notch 0, 1, 2; crank arm up at angle 0' % SKT.REST_DY),
        ('cook_larder_door', 'vertical hinge x %.1f, z %.1f' % L.LARDER_DOOR_HINGE, 'yaw 0 (shut) to +%d degrees, outward (-z)' % L.LARDER_DOOR_MAX),
        ('cook_hob_pan, dampers, burnt overlays', 'none', 'static (the blockstate picks the damper; burnt is laid over the shell)'),
    ]


def pieces_sheet(assets, out, game):
    """The contract with Part C as a picture: each piece in its poses, its axis (magenta line and dot) and the path an edge or middle follows (gold)."""
    rows = [
        ('stewpot pot at heat notch 0, 1, 2 (camp style) and the hearth crane at 0, 30, 60 degrees',
         [('notch 0', mech_pot(0)), ('notch 1', mech_pot(1)), ('notch 2', mech_pot(2)), ('crane 0', mech_crane(0.0)), ('crane 30', mech_crane(30.0)),
          ('crane 60', mech_crane(SP.CRANE_MAX))]),
        ('oven door at 0, 50, 100 degrees (trays out 0, 3, 6 px) and larder door at 0, 50, 100 degrees',
         [('door 0', mech_oven(0.0)), ('door 50, trays 3', mech_oven(50.0, 3.0)), ('door 100, trays 6', mech_oven(OV.DOOR_MAX, 6.0)),
          ('door 0', mech_larder(0.0)), ('door 50', mech_larder(50.0)), ('door 100', mech_larder(HL.LARDER_DOOR_MAX))]),
        ('spit rod and crank: heat notch 0, 1, 2 at angle 0, then notch 1 at 90, 180, 270 degrees',
         [('notch 0 (highest)', mech_spit(0, 0.0)), ('notch 1', mech_spit(1, 0.0)), ('notch 2 (lowest)', mech_spit(2, 0.0)), ('notch 1, 90', mech_spit(1, 90.0)),
          ('notch 1, 180', mech_spit(1, 180.0)), ('notch 1, 270', mech_spit(1, 270.0))]),
    ]
    parts = [(cap, row(assets, game, ents, NOON, elev=30.0, scale=10.5, height=330)) for cap, ents in rows]
    table = pieces_table()
    w = max(im.width for _, im in parts)
    th = 28 + 20 * len(table)
    tab = Image.new('RGBA', (w, th), P.BG + (255,))
    d = ImageDraw.Draw(tab)
    for x, h in ((4, 'piece'), (250, 'pivot, or the line it turns about'), (500, 'how it moves')):
        P.text(d, (x, 2), h, P.INK, 13)
    for i, (a, b, c) in enumerate(table):
        y = 26 + i * 20
        for x, t in ((4, a), (250, b), (500, c)):
            P.text(d, (x, y), t, P.DIM if x == 4 else P.INK, 12)
    parts.append(('the contract, as the first comment block of cook_art.py gives it (px, model space, facing north; angles as Axis.YP turns)', tab))
    sheet = stack(parts, 'Moving pieces and their pivots: axes in magenta, the path an edge or a middle follows in gold')
    return P.save_png(sheet, os.path.join(out, 'cook_pieces.png'))


def _wave(t, loop, phase=0.0):
    """0 to 1 and back to 0 once in [loop] ticks."""
    return 0.5 - 0.5 * math.cos(2.0 * math.pi * (t / float(loop) + phase))


def pieces_gif(assets, out, game, loop=96, step=2):
    """The pieces through their ranges: the pot down and up its three notches, the crane round and back, the oven door open with its trays drawn out and
    shut, the spit turning once, the larder door open and shut. One loop is [loop] ticks, a frame each [step]."""
    frames = []
    for t in range(0, loop, step):
        w = _wave(t, loop)
        door = OV.DOOR_MAX * w
        ents = [('pot notch %.1f' % (2 * w), mech_pot(2.0 * w)),
                ('crane %d' % round(SP.CRANE_MAX * w), mech_crane(SP.CRANE_MAX * w)),
                ('door %d, trays %.1f' % (round(door), 6.0 * min(1.0, max(0.0, (door - 50.0) / 50.0))),
                 mech_oven(door, 6.0 * min(1.0, max(0.0, (door - 50.0) / 50.0)))),
                ('rod %d' % (round(360.0 * t / loop) % 360), mech_spit(1, 360.0 * t / loop)),
                ('door %d' % round(HL.LARDER_DOOR_MAX * _wave(t, loop, 0.25)), mech_larder(HL.LARDER_DOOR_MAX * _wave(t, loop, 0.25)))]
        frames.append(row(assets, game, ents, NOON, tick=0, elev=30.0, scale=8.5, height=290, ss=2))
    return P.save_gif(frames, os.path.join(out, 'cook_pieces.gif'), 50 * step)


# ================================================================ the herb

def herb_sheet(assets, out, game):
    """The herb's four ages on farmland beside a wheat plant (the game's own crop), at noon and at dusk, and the sprig that plants it."""
    def farm(inner):
        def fn(st, at):
            if st.game:
                K.vanilla(st, 'farmland', at)
            inner(st, (at[0], at[1] + (16.0 if st.game else 0.0), at[2]))
        return fn

    ents = [('wheat, ripe (the game)', farm(lambda st, at: K.vanilla(st, 'wheat_stage7', at)))] if game else []
    names = ('seedling', 'young', 'leafy', 'flowering: ripe')
    for age in range(HL.HERB_AGES):
        ents.append(('age %d, %s' % (age, names[age]), farm((lambda a: lambda st, at: st.block('herb_stage%d' % a, at))(age))))
    parts = []
    for mode, name in ((NOON, 'noon'), (DUSK, 'dusk')):
        parts.append(('at %s' % name, row(assets, game, ents, mode, scale=11.0, height=400, elev=24.0, step=19.0, cy=14.0)))
    it = P.Items(assets)
    sprig = P.sprite_cell(it.render('herb_sprig', {}, 0), 4, pad=4)
    note = Image.new('RGBA', (sprig.width + 520, sprig.height), P.BG + (255,))
    note.alpha_composite(sprig, (0, 0))
    d = ImageDraw.Draw(note)
    P.text(d, (sprig.width + 12, 20), 'The sprig (item): planted on farmland it grows through the four ages;', P.INK, 13)
    P.text(d, (sprig.width + 12, 42), 'a harvest leaves the plant standing at age 1, bonemeal works.', P.INK, 13)
    P.text(d, (sprig.width + 12, 72), 'Four crossed quads from 1 px below the cell to 15, as the game\'s crops', P.DIM, 12)
    P.text(d, (sprig.width + 12, 90), 'have them, so each plant stands on the top of the farmland.', P.DIM, 12)
    parts.append(('', note))
    sheet = stack(parts, "The herb: four ages on farmland beside the game's wheat")
    return P.save_png(sheet, os.path.join(out, 'cook_herb.png'))


# ================================================================ what rises from the stations: the particles in a scene

class Particles:
    """Emitters whose particles live in a loop of [loop] ticks (each is born at a fixed moment of the loop and dies before it comes round again), so
    that a GIF of one loop closes without a seam. The sprites are the mod's own (read back from the written files), tinted as the game tints them;
    where and how they move is a guess at what Part C will do, the art's suggestion: sizes are in model px (16 to a block)."""

    def __init__(self, assets, loop=48, seed=11):
        import cook_prev_flat as F
        self.loop = loop
        self.rng = np.random.default_rng(seed)
        self.sprites = {n: [F._tint(f, F.PART_TINT[n]) for f in F._part_frames(assets, n)] for n in F.PART_FRAMES}
        self.emitters = []

    def add(self, kind, p, at=(0, 0, 0), facing=0, spread=(0.0, 0.0, 0.0), v=(0.0, 0.0, 0.0), g=0.0, life=30, size=3.0, grow=0.0, n=4, fade=0.0):
        """[n] particles of [kind] born round the model px point [p] (within [spread] of it) once a loop each, moving at [v] px a tick and falling at
        [g]; [life] ticks, [size] px across growing by [grow] times, fading out over the last [fade] of their life."""
        jit = self.rng.uniform(-1.0, 1.0, (n, 3)) * np.asarray(spread, float)
        start = [(i + self.rng.uniform(-0.25, 0.25)) * self.loop / n for i in range(n)]
        self.emitters.append(dict(kind=kind, p=np.asarray(p, float), at=at, facing=facing, v=np.asarray(v, float), g=g, life=life, size=size, grow=grow,
                                  jit=jit, start=start, fade=fade))

    def draw(self, st, tick):
        t = tick % self.loop
        for e in self.emitters:
            frames = self.sprites[e['kind']]
            for jit, s in zip(e['jit'], e['start']):
                age = (t - s) % self.loop
                if age >= e['life']:
                    continue
                u = age / e['life']
                pos = e['p'] + jit + e['v'] * age
                pos[1] -= 0.5 * e['g'] * age * age
                fr = frames[min(len(frames) - 1, int(u * len(frames)))]
                alpha = 1.0 if not e['fade'] else min(1.0, (1.0 - u) / e['fade'])
                st.sprite(fr, pos, e['size'] * (1.0 + e['grow'] * u), e['at'], e['facing'], alpha)


def pot_centre(swing):
    """Where the pot's middle is (x, z) when the crane is swung [swing] degrees: on the arc about the post."""
    x0, z0 = SP.CRANE_POST
    r = math.hypot(8 - x0, 8 - z0)
    h = math.degrees(math.atan2(-(8 - z0), 8 - x0)) + swing
    return x0 + r * math.cos(math.radians(h)), z0 - r * math.sin(math.radians(h))


def fx_stewpot(fx, at, style='camp', notch=1, swing=0.0, level=0.7, foam=False, call=False, facing=0):
    cx, cz = pot_centre(swing) if style == 'hearth' else (8.0, 8.0)
    top = 10.0 - SP.NOTCH_STEP * notch
    surf = SP.SURFACE_Y[0] + (SP.SURFACE_Y[1] - SP.SURFACE_Y[0]) * level - SP.NOTCH_STEP * notch
    fx.add('cook_steam', (cx, top + 1.0, cz), at, facing, spread=(2.0, 0.0, 2.0), v=(0.0, 0.17, 0.0), life=40, size=3.4, grow=0.9, n=4, fade=0.45)
    fx.add('stew_bubble', (cx, surf + 0.3, cz), at, facing, spread=(2.3, 0.0, 2.3), v=(0.0, 0.01, 0.0), life=9, size=1.5, n=6)
    if foam:
        fx.add('cook_foam', (cx, top + 0.4, cz), at, facing, spread=(3.0, 0.0, 3.0), v=(0.0, 0.03, 0.0), life=24, size=2.2, n=4)
    if call:
        fx.add('cook_call', (cx, top + 5.0, cz), at, facing, spread=(0.0, 0.0, 0.0), v=(0.0, 0.05, 0.0), life=26, size=4.5, n=1)


def fx_hob(fx, at, facing=0):
    fx.add('cook_sizzle', (8.0, HB.PAN_FLOOR_Y + 0.8, 8.0), at, facing, spread=(4.4, 0.0, 2.4), v=(0.0, 0.26, 0.0), g=0.03, life=14, size=1.5, n=8)
    fx.add('cook_steam', (8.0, 12.0, 8.0), at, facing, spread=(3.0, 0.0, 2.0), v=(0.0, 0.18, 0.0), life=38, size=2.8, grow=0.8, n=3, fade=0.45)
    fx.add('cook_smoke_dark', (HB.FLUE[0], HB.FLUE_TOP + 0.4, HB.FLUE[1]), at, facing, spread=(0.4, 0.0, 0.4), v=(0.0, 0.2, 0.0), life=44, size=2.6,
           grow=1.3, n=3, fade=0.5)


def fx_oven(fx, at, facing=0, call=False):
    fx.add('cook_smoke_dark', (OV.CHIMNEY[0], OV.CHIMNEY_TOP + 0.4, OV.CHIMNEY[1]), at, facing, spread=(0.4, 0.0, 0.4), v=(0.0, 0.2, 0.0), life=44, size=3.0,
           grow=1.3, n=3, fade=0.5)
    if call:
        fx.add('cook_call', (8.0, 15.0, 3.0), at, facing, v=(0.0, 0.05, 0.0), life=26, size=4.5, n=1)


def fx_spit(fx, at, facing=0):
    # fat drips from the joint to the tray, and a flare leaps from the coals now and then
    fx.add('cook_sizzle', (7.6, SK.DRIP_FROM, 8.0), at, facing, spread=(4.4, 0.0, 1.5), g=0.118, life=10, size=1.2, n=5)
    fx.add('cook_sizzle', (7.5, 2.4, 5.3), at, facing, spread=(4.5, 0.0, 0.7), v=(0.0, 0.34, 0.0), g=0.01, life=12, size=1.6, n=3)
    fx.add('cook_smoke_dark', (7.5, 11.5, 8.0), at, facing, spread=(3.5, 0.0, 1.0), v=(0.0, 0.16, 0.0), life=40, size=2.2, grow=1.0, n=3, fade=0.5)


# ================================================================ the stations alive

def meat():
    """A stand-in for the roast turning on the spit (the renderer draws the real items): a joint of three lumps on the rod, coloured like cooked meat."""
    rng = np.random.default_rng(7)
    a = np.zeros((16, 16, 4), np.uint8)
    cols = np.array([(104, 62, 36), (128, 78, 42), (150, 98, 50), (176, 124, 64), (84, 50, 32)])
    idx = rng.choice(len(cols), (16, 16), p=[0.16, 0.34, 0.28, 0.1, 0.12])
    a[..., :3] = cols[idx]
    a[..., 3] = 255
    return Image.fromarray(a, 'RGBA')


def roast_elements():
    """The joint: a body and a knuckle at each end, centred on the spit's axis (y, z), over the middle four joints of the rod."""
    y, z = SK.AXIS_Y, SK.AXIS_Z
    els = []
    for x0, x1, ry, rz in ((4.6, 10.6, 2.1, 2.3), (3.8, 4.6, 1.4, 1.6), (10.6, 11.4, 1.4, 1.6)):
        faces = {f: {'uv': [0, 0, 16, 16], 'texture': '#t'} for f in ('north', 'south', 'east', 'west', 'up', 'down')}
        els.append({'from': [x0, y - ry, z - rz], 'to': [x1, y + ry, z + rz], 'faces': faces})
    return {'textures': {'t': None}, 'elements': els}


def spit_with_roast(st, at, lit=True, notch=1, angle=0.0, facing=0):
    """The spit and, turning with its rod, a stand-in joint."""
    K.spit(st, at, lit=lit, notch=notch, angle=angle, facing=facing)
    m = roast_elements()
    m['textures']['t'] = st._ref(meat(), 'roast_meat')
    st.piece(m, at, facing, turn=angle, about=(SK.AXIS_Y, SK.AXIS_Z), axis='x', slide=(0, SK.REST_DY[notch], 0))


def alive_entries(fx, level=0.7):
    def camp(st, at):
        K.stewpot(st, at, 'camp', lit=True, notch=1, level=level, tint=STEW)
        fx_stewpot(fx, at, 'camp', 1, level=level)

    def hearth(st, at):
        K.stewpot(st, at, 'hearth', lit=True, notch=1, level=level, tint=STEW)
        fx_stewpot(fx, at, 'hearth', 1, level=level, call=True)

    def hob_(st, at):
        K.hob(st, at, lit=True, notch=2, pieces=_pan_pieces(st))
        fx_hob(fx, at)

    def oven_(st, at):
        K.oven(st, at, lit=True, trays=2)
        fx_oven(fx, at)

    return camp, hearth, hob_, oven_


def alive_gif(assets, out, game, loop=48, step=2):
    """The lit stations at dusk with what rises from them, over one loop of [loop] ticks: flames and coals as their strips run, steam, bubbles, smoke,
    sizzle, drips and a glint, and the spit's rod turning once."""
    frames = []
    for t in range(0, loop, step):
        fx = Particles(assets, loop)
        camp, hearth, hob_, oven_ = alive_entries(fx)
        ents = [('camp pot', camp), ('hearth pot', hearth), ('hob', hob_), ('oven', oven_),
                ('spit', lambda st, at: (spit_with_roast(st, at, True, 1, 360.0 * t / loop), fx_spit(fx, at)))]
        frames.append(row(assets, game, ents, DUSK, tick=t, scale=9.0, height=300, ss=2, setup=lambda st: fx.draw(st, t)))
    return P.save_gif(frames, os.path.join(out, 'cook_blocks_lit.gif'), 50 * step)


# ================================================================ the dioramas

def _cell(i, j, k):
    """The px position of the cell [i] east, [j] up, [k] south of the scene's origin cell."""
    return (16.0 * i, 16.0 * j, 16.0 * k)


def _fill(st, name, i0, i1, j0, j1, k0, k1, facing=0):
    """Whole cells of a game block, from cell (i0, j0, k0) to (i1, j1, k1) inclusive."""
    for i in range(i0, i1 + 1):
        for j in range(j0, j1 + 1):
            for k in range(k0, k1 + 1):
                K.vanilla(st, name, _cell(i, j, k), facing)


def _put(st, name, i, j, k, facing=0, dy=0.0):
    K.vanilla(st, name, (16.0 * i, 16.0 * j + dy, 16.0 * k), facing)


def _dish(assets, name, tier=None):
    it = P.Items(assets)
    return it.render(name, {} if tier is None else {'guildmark:dish_tier': tier}, 0)


def tavern_scene(st, fx, assets, t=0):
    """The tavern's hearth corner: the kitchen row against the stone wall (a prep board, the house pot on its crane, the guest pot on its tripod, the
    spit with a joint, the larder), a table with a lantern and two dishes, the slate by the hearth and another on the timber wall."""
    dish = lambda n: _dish(assets, n)
    # the room: boards on the floor, stone before the hearth, a stone wall behind and a timber one at the side
    _fill(st, 'oak_planks', 0, 6, -1, -1, 2, 3)
    _fill(st, 'stone_bricks', 0, 6, -1, -1, 4, 5)
    _fill(st, 'stone_bricks', -1, 7, -1, 1, 6, 6)
    _fill(st, 'spruce_planks', -1, -1, -1, 0, 2, 5)
    _fill(st, 'stripped_oak_log', -1, -1, 0, 0, 2, 2)
    _fill(st, 'red_carpet', 2, 4, 0, 0, 4, 4)
    # the kitchen row against the wall, k = 5 (the stations face the room, which is north)
    _put(st, 'barrel', 0, 0, 5)
    K.prep_board(st, _cell(1, 0, 5), heap=_heap(st))
    fx.add('cook_crumb', (8.0, 14.0, 8.0), _cell(1, 0, 5), 0, spread=(3.0, 0.0, 3.0), v=(0.0, -0.05, 0.0), life=22, size=2.0, n=3)
    K.stewpot(st, _cell(2, 0, 5), 'hearth', lit=True, notch=1, swing=25.0, level=0.8, tint=STEW)
    fx_stewpot(fx, _cell(2, 0, 5), 'hearth', 1, swing=25.0, level=0.8, foam=True)
    K.stewpot(st, _cell(3, 0, 5), 'camp', lit=True, notch=1, level=0.72, tint=(0.46, 0.68, 0.30))
    fx_stewpot(fx, _cell(3, 0, 5), 'camp', 1, level=0.72, call=True)
    spit_with_roast(st, _cell(4, 0, 5), True, 1, 360.0 * t / fx.loop)
    fx_spit(fx, _cell(4, 0, 5))
    K.larder(st, _cell(5, 0, 5), open_=False, dishes=[dish(n) for n in ('dish_pie', 'dish_stew', 'dish_loaf')])
    _put(st, 'lantern', 5, 0, 5, dy=15.2)
    _put(st, 'barrel', 6, 0, 5)
    # the slates
    K.menu_board(st, _cell(6, 0, 3), chalk=chalk(MENU))
    K.menu_board(st, _cell(0, 0, 3), wall=True, facing=90, chalk=chalk(MENU))
    # the table
    _fill(st, 'oak_slab', 3, 4, 0, 0, 3, 3)
    K.plate(st, (48.0, 8.0, 48.0), dish=dish('dish_stew'))
    K.plate(st, (64.0, 8.0, 48.0), dish=dish('dish_pie'))
    fx.add('cook_steam', (8.0, 11.0, 8.0), (48.0, 8.0, 48.0), 0, spread=(0.8, 0.0, 0.8), v=(0.0, 0.1, 0.0), life=36, size=2.2, grow=0.8, n=2, fade=0.5)


def bakery_scene(st, fx, assets, t=0):
    """The bakery counter: a counter of dark oak with four plates of bakes, a cake and a slate at its end, and behind it the baker's wall: the bake
    oven, lit, its door open and its trays drawn out with loaves; a prep board with a heap of flour and fruit; a larder with its door ajar."""
    dish = lambda n: _dish(assets, n)
    # the shop: light boards before the counter, stone behind it, plastered walls with timber posts and a stone nook for the oven
    _fill(st, 'birch_planks', 0, 6, -1, -1, 1, 3)
    _fill(st, 'stone_bricks', 0, 6, -1, -1, 4, 5)
    _fill(st, 'white_terracotta', -1, 7, -1, 1, 6, 6)
    _fill(st, 'stone_bricks', 4, 6, -1, 1, 6, 6)
    for i in (3, 7):
        _fill(st, 'dark_oak_planks', i, i, -1, 1, 6, 6)
    _fill(st, 'white_terracotta', -1, -1, -1, 0, 1, 5)
    _fill(st, 'dark_oak_planks', -1, -1, -1, 0, 1, 1)
    _fill(st, 'red_carpet', 1, 5, 0, 0, 2, 2)
    # the counter and what is on it
    _fill(st, 'dark_oak_slab', 1, 5, 0, 0, 3, 3)
    for i, name in ((1, 'dish_pie'), (2, 'dish_loaf'), (4, 'dish_tart'), (5, 'dish_cakes')):
        K.plate(st, (16.0 * i, 8.0, 48.0), dish=dish(name))
        fx.add('cook_steam', (8.0, 11.0, 8.0), (16.0 * i, 8.0, 48.0), 0, spread=(0.8, 0.0, 0.8), v=(0.0, 0.07, 0.0), life=34, size=1.7, grow=0.8, n=2, fade=0.5)
    _put(st, 'cake', 3, 0, 3, dy=8.0)
    K.menu_board(st, _cell(0, 0, 3), chalk=chalk([('Bakes', 0), ('Fresh loaf ..... 2', 8), ('Pie ..... 5', 8), ('Tart ..... 4', 8)]))
    # the baker's row, k = 5: from the east (the left of the picture) the oven, the board, the larder, and sacks and barrels
    K.oven(st, _cell(5, 0, 5), lit=True, trays=2, door=80.0, out=5.0, lower=[dish('dish_loaf')] * 5, upper=[dish('dish_cakes')] * 5)
    fx_oven(fx, _cell(5, 0, 5), call=False)
    K.prep_board(st, _cell(4, 0, 5), heap=_heap(st))
    fx.add('cook_crumb', (8.0, 14.0, 8.0), _cell(4, 0, 5), 0, spread=(3.0, 0.0, 3.0), v=(0.0, -0.05, 0.0), life=22, size=2.0, n=3)
    K.larder(st, _cell(2, 0, 5), open_=True, door=60.0, dishes=[dish(n) for n in ('dish_pie', 'dish_tart', 'dish_loaf')])
    _put(st, 'lantern', 2, 0, 5, dy=15.2)
    _put(st, 'barrel', 1, 0, 5)
    _put(st, 'hay_block', 0, 0, 5)


# the camera of each diorama: scale, the point it looks at, yaw, elevation, and the picture's size (the sheet's; the GIF is the same view smaller)
DIORAMA = {'tavern': dict(scale=11.0, center=(50, 10, 78), yaw=150.0, elev=28.0, size=(1400, 780)),
           'bakery': dict(scale=12.0, center=(54, 10, 72), yaw=158.0, elev=28.0, size=(1480, 780))}


def diorama(assets, game, scene, mode, t=0, loop=48, scale=11.0, size=(1280, 780), center=(54, 10, 78), yaw=150.0, elev=28.0, ss=3):
    st = K.Stage(assets, game)
    fx = Particles(assets, loop)
    scene(st, fx, assets, t)
    fx.draw(st, t)
    return st.render(yaw=yaw, elev=elev, scale=scale, size=size, center=center, mode=mode, tick=t, ground=False, ss=ss, bloom=0.8)


SCENES = {'tavern': (tavern_scene, "The tavern's hearth corner, built from Hearthcraft's models",
                     "the house pot on its crane, the guest pot on its tripod, the spit with a joint (a stand-in), the larder, the prep board and the slates"),
          'bakery': (bakery_scene, "The bakery counter, built from Hearthcraft's models",
                     'plates of bakes and a cake on the counter, and behind it the oven with its door open and its trays out, the prep board and the larder')}


def stage_sheet(assets, out, game, which, t=14):
    """cook_stage_<which>.png: the diorama at noon and at dusk, one over the other, with the particles caught at tick [t]."""
    scene, title, what = SCENES[which]
    parts = [('at noon: %s. What rises, and where, is the art\'s suggestion.' % what, diorama(assets, game, scene, NOON, t=t, **DIORAMA[which])),
             ('at dusk', diorama(assets, game, scene, DUSK, t=t, **DIORAMA[which]))]
    return P.save_png(stack(parts, title), os.path.join(out, 'cook_stage_%s.png' % which))


def stage_gif(assets, out, game, which, loop=48, step=2, scale=7.2):
    """cook_stage_<which>.gif: the diorama at dusk through one loop of [loop] ticks (flames, coals and glow as their strips run, and the particles)."""
    scene = SCENES[which][0]
    kw = dict(DIORAMA[which])
    k = scale / kw['scale']
    kw['scale'] = scale
    kw['size'] = (int(kw['size'][0] * k), int(kw['size'][1] * k))
    frames = [diorama(assets, game, scene, DUSK, t=t, loop=loop, ss=2, **kw) for t in range(0, loop, step)]
    return P.save_gif(frames, os.path.join(out, 'cook_stage_%s.gif' % which), 50 * step)


# ================================================================ everything on one sheet

SLOT = dict(ambient=1.0, bg=(70, 66, 78))


def gui_icon(assets, game, model, size=132, scale=6.6):
    """A block model as the inventory draws it: from the north-west and above (the game's GUI turn of 30 and 225 degrees), on a dark slot."""
    st = K.Stage(assets, game)
    st.block(model)
    return st.render(yaw=225.0, elev=30.0, scale=scale, size=(size, size), center=(8, 7.5, 8), mode=SLOT, ground=False, tick=TICK)


def everything_sheet(assets, out, game):
    """Every item and every block, each as it is drawn in a slot of the inventory, enlarged: the dishes, the tools and the book and sprig, and the
    eight block items."""
    import cook_prev_flat as F
    it = P.Items(assets)
    sections = []
    cells, labels = [], []
    for n in F.DISHES + ['scorched_mess']:
        cells.append(P.sprite_cell(it.render(n, {}, F.TICK), 4, pad=4))
        labels.append(F.NICE[n])
    sections.append(('Dishes (fresh; they steam, shine or smoke as their strips run)', P.contact(cells, 7, labels, gap=6)))
    cells, labels = [], []
    for n in ('cooks_ladle', 'cooks_knife', 'turner', 'gilded_ladle', 'cook_book', 'herb_sprig'):
        cells.append(P.sprite_cell(it.render(n, {}, F.TICK), 4, pad=4))
        labels.append(F.NICE[n])
    sections.append(('Tools, the book and the herb sprig', P.contact(cells, 6, labels, gap=6)))
    cells, labels = [], []
    for n, model, label in (('stewpot', 'item/stewpot', 'Stewpot'), ('hob', 'item/hob', 'Hob'), ('bake_oven', 'item/bake_oven', 'Bake Oven'),
                            ('spit', 'item/spit', 'Spit'), ('prep_board', 'prep_board', 'Prep Board'), ('plate', 'plate', 'Plate'),
                            ('larder', 'larder', 'Larder'), ('menu_board', 'menu_board', 'Menu Board')):
        cells.append(gui_icon(assets, game, model))
        labels.append(label)
    sections.append(('Blocks, as the inventory shows them (the stations carry the piece that stands in them at rest)', P.contact(cells, 4, labels, gap=6)))
    sheet = stack(sections, 'Hearthcraft: every item and block')
    return P.save_png(sheet, os.path.join(out, 'cook_everything.png'))
