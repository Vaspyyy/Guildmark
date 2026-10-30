"""The twenty-one Hearthcraft items as files (cook_art.py): their sprites, models and item definitions, with the overlays a dish wears.

    dishes (13: the twelve kinds and the gruel)   `items/<id>.json` is a `minecraft:composite` of three layers,
        1. the dish's sprite: a `select` on `guildmark:dish_stage` (1 stale: tinted dull, 2 gone off: tinted green, anything else, or none: plain)
           over a `select` on `guildmark:dish_tier` (1 Plain: the paler sprite `item/cook/<id>_pale`, anything else: the dish's own);
        2. a moving layer chosen by the stage: steam rising from a fresh dish (the strips shine instead), nothing when it is stale, three flies
           when it has gone off;
        3. a stamp chosen by the tier: 3 Fine wears a silver one, 4 Hallmarked a gold one with a glint that passes over it now and then.
    scorched_mess   the sprite and a wisp of dark smoke that never stops.
    tools, spoon, book, herb sprig   one sprite each, every one a short animation: a band of light that passes over its metal now and then
                    (over the spoon's bowl, a soft sheen).

A dish whose components are missing reads as a fresh dish of the middle tier (nothing is selected), as the design wants.
"""
import os

import cook_geo as G
import cook_items
import cook_dishes
import cook_herb
import cook_tools
import cook_overlays as O

# the dishes in the order that sets each one's steam its phase, and what moves over it when it is fresh
DISHES = [
    ('dish_pottage', 'steam'), ('dish_stew', 'steam'), ('dish_broth', 'steam'), ('dish_roots', 'steam'), ('dish_chowder', 'steam'),
    ('dish_cakes', 'steam'), ('dish_loaf', 'steam'), ('dish_pie', 'steam'), ('dish_tart', 'steam'), ('dish_fish', 'steam'),
    ('dish_roast', 'steam'), ('dish_strips', 'sheen'), ('gruel', 'steam'),
]
TOOLS = ('cooks_ladle', 'cooks_knife', 'turner', 'gilded_ladle')
SPOON = 'wooden_spoon'
ITEM_IDS = [d for d, _ in DISHES] + ['scorched_mess'] + list(TOOLS) + [SPOON, 'cook_book', 'herb_sprig']

TIER = 'guildmark:dish_tier'
STAGE = 'guildmark:dish_stage'
STALE_TINT = (199 << 16) | (194 << 8) | 184                  # a dulled wash
GONE_TINT = (158 << 16) | (199 << 8) | 128                   # a green tinge

# a held tool turns about its grip as the swords do: the numbers are the game's own `item/handheld`, written out so that Part M3 can read the
# grip from the model and change it there
HANDHELD_DISPLAY = {
    'thirdperson_righthand': {'rotation': [0, -90, 55], 'translation': [0, 4.0, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'thirdperson_lefthand': {'rotation': [0, 90, -55], 'translation': [0, 4.0, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
}


# ---------------------------------------------------------------- files

def _save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def _animated(frames, times, path):
    _save(O.strip(frames), path)
    G.write_json(path + '.mcmeta', O.mcmeta(times))


def _model(assets, name, texture, handheld=False, display=None):
    """A flat item model: `models/item/<name>.json` showing `guildmark:item/<texture>`; held as a tool ([handheld]), with [display] for its own hold."""
    obj = {'parent': 'minecraft:item/handheld' if handheld else 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/' + texture}}
    if handheld:
        obj['display'] = display or HANDHELD_DISPLAY
    G.write_json(os.path.join(assets, 'models/item', name + '.json'), obj)


def _leaf(model, tint=None):
    d = {'type': 'minecraft:model', 'model': 'guildmark:item/' + model}
    if tint is not None:
        d['tints'] = [{'type': 'minecraft:constant', 'value': tint}]
    return d


def _select(component, cases, fallback):
    return {'type': 'minecraft:select', 'property': 'minecraft:component', 'component': component,
            'cases': [{'when': w, 'model': m} for w, m in cases], 'fallback': fallback}


EMPTY = {'type': 'minecraft:empty'}


def dish_definition(dish, moving):
    """The composite of one dish: its sprite by stage and tier, what moves over it by stage, its stamp by tier."""
    def sprite(tint):
        return _select(TIER, [(1, _leaf('cook/%s_pale' % dish, tint))], _leaf(dish, tint))
    body = _select(STAGE, [(1, sprite(STALE_TINT)), (2, sprite(GONE_TINT))], sprite(None))
    layer = _select(STAGE, [(1, EMPTY), (2, _leaf('cook/flies'))], _leaf('cook/%s_%s' % (dish, moving)))
    stamp = _select(TIER, [(3, _leaf('cook/stamp_fine')), (4, _leaf('cook/stamp_hallmarked'))], EMPTY)
    return {'model': {'type': 'minecraft:composite', 'models': [body, layer, stamp]}}


# ---------------------------------------------------------------- writing

def write_items(assets):
    tex = os.path.join(assets, 'textures/item')
    defs = os.path.join(assets, 'items')
    painters = dict(cook_items.PAINTERS)
    painters.update(cook_dishes.PAINTERS)
    # the shared overlays
    _animated(O.flies(), O.loop_times(O.FLY_FRAMES, 2), os.path.join(tex, 'cook/flies.png'))
    _animated(O.stamp_frames_fine(), O.loop_times(8, 2, 100), os.path.join(tex, 'cook/stamp_fine.png'))
    _animated(O.stamp_frames_hallmarked(), O.loop_times(9, 2, 60), os.path.join(tex, 'cook/stamp_hallmarked.png'))
    for shared in ('flies', 'stamp_fine', 'stamp_hallmarked'):
        _model(assets, 'cook/' + shared, 'cook/' + shared)
    # the dishes
    for k, (dish, moving) in enumerate(DISHES):
        base = painters[dish]()
        _save(base, os.path.join(tex, dish + '.png'))
        _save(O.pale(base), os.path.join(tex, 'cook/%s_pale.png' % dish))
        if moving == 'steam':
            _animated(O.steam(base, phase0=k * 0.9), O.loop_times(O.FRAMES, 3), os.path.join(tex, 'cook/%s_steam.png' % dish))
        else:
            frames = O.sheen(base)
            _animated(frames, [3] * (len(frames) - 1) + [70], os.path.join(tex, 'cook/%s_sheen.png' % dish))
        _model(assets, dish, dish)
        _model(assets, 'cook/%s_pale' % dish, 'cook/%s_pale' % dish)
        _model(assets, 'cook/%s_%s' % (dish, moving), 'cook/%s_%s' % (dish, moving))
        G.write_json(os.path.join(defs, dish + '.json'), dish_definition(dish, moving))
    # the scorched mess: smoke for ever
    mess = painters['scorched_mess']()
    _save(mess, os.path.join(tex, 'scorched_mess.png'))
    _animated(O.burnt_smoke(mess, phase0=1.3), O.loop_times(O.FRAMES, 3), os.path.join(tex, 'cook/scorched_mess_smoke.png'))
    _model(assets, 'scorched_mess', 'scorched_mess')
    _model(assets, 'cook/scorched_mess_smoke', 'cook/scorched_mess_smoke')
    G.write_json(os.path.join(defs, 'scorched_mess.json'), {'model': {'type': 'minecraft:composite', 'models': [
        _leaf('scorched_mess'), _leaf('cook/scorched_mess_smoke')]}})
    # the tools, the spoon and the book: a glint over the metal (a sheen over the spoon's bowl)
    for name in TOOLS + (SPOON, 'cook_book'):
        _animated(cook_tools.glint_frames(name), _glint_times(name), os.path.join(tex, name + '.png'))
        _model(assets, name, name, handheld=name in TOOLS or name == SPOON, display=spoon_display() if name == SPOON else None)
        G.write_json(os.path.join(defs, name + '.json'), {'model': _leaf(name)})
    # the herb sprig: a dewdrop that catches the light
    sprig = cook_herb.sprig()
    frames = O.sweep(sprig, 0.0, 34.0, 9, 2.0, star=_dew_spot(sprig))
    _animated(frames, O.loop_times(9, 2, 140), os.path.join(tex, 'herb_sprig.png'))
    _model(assets, 'herb_sprig', 'herb_sprig')
    G.write_json(os.path.join(defs, 'herb_sprig.json'), {'model': _leaf('herb_sprig')})


def spoon_display():
    """The spoon's `display`: its own hold in either hand (cook_tools.SPOON_DISPLAY), the left hands written as the handheld model writes them
    (the turn mirrored, the offset as the right hand's: the game mirrors both back)."""
    out = {}
    for view in ('thirdperson', 'firstperson'):
        rot, trans, scale = cook_tools.SPOON_DISPLAY[view]
        out[view + '_righthand'] = {'rotation': list(rot), 'translation': list(trans), 'scale': list(scale)}
        out[view + '_lefthand'] = {'rotation': [rot[0], -rot[1], -rot[2]], 'translation': list(trans), 'scale': list(scale)}
    return out


def _glint_times(name):
    spec = cook_tools.GLINT[name]
    return O.loop_times(spec['frames'], spec['tick'], spec['rest'])


def _dew_spot(img):
    """The sprig's highest leaf pixel, a little in: where a dewdrop sits."""
    import numpy as np
    a = np.array(img)[..., 3] > 0
    ys, xs = np.nonzero(a)
    i = int(np.argmin(ys * 1.0 + np.abs(xs - 16) * 0.3))
    return int(xs[i]), int(ys[i]) + 2
