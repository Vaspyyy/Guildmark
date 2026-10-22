"""Hearthcraft's art: the stations' and helpers' block models, the dishes and tools, the herb, the renderers' textures, the effect icons, the
cold vignette, the Cook's Book sprites, the seven particles and the previews.

    python3 -B art/scripts/cook_art.py [assets dir [preview dir [game assets dir]]]

writes every file under `src/main/resources/assets/guildmark/` (or [assets dir]) that Hearthcraft owns: `blockstates/`, `models/block/`,
`models/item/`, `items/`, `textures/block/cook/`, `textures/item/`, `textures/entity/cook/`, `textures/mob_effect/`, `textures/gui/chill.png`,
`textures/gui/sprites/cook/`, `textures/particle/` and `particles/`; with a [preview dir] it also draws the contact sheets and GIFs there (the
third argument, a game `assets/minecraft` folder unpacked from the jar, is read only for the vanilla campfire and furnace drawn beside the blocks).
A rerun gives the same files. Run it from the repository's root.

THE MOVING PIECES: the contract with Part C (who draws them) -----------------------------------------------------------------------
Every model below is a block model in `models/block/`, authored in model space (x east, y up, z south, 16 px to a block; the renderer divides by
16), FACING NORTH: its front is toward -z. A block with `facing` is turned by its blockstate (east y=90, south y=180, west y=270) and the renderer
turns its pieces the same way, about the middle of the cell (8, 8). Angles are degrees as `Axis.YP.rotationDegrees` takes them (YP turns +z toward
+x). A piece is drawn with the cell's light.

  piece (model)               pivot, or the line it turns about  range / how it moves
  --------------------------  ---------------------------------  --------------------------------------------------------------------------
  cook_stewpot_pot            hangs at (8, 12.9, 8)              slides straight down NOTCH_STEP 1.0 px per heat notch (0, 1, 2: the lower the
                                                                 pot, the hotter): the bottom is at y 5.6 at notch 0 and 3.6 at notch 2. It
                                                                 may sway up to 4 degrees about (8, 12.9, 8). Its chain's top is 15.2 at
                                                                 notch 0 and is still inside the lashing (camp) or the crane's lug (hearth)
                                                                 at notch 2.
  cook_stewpot_soot_1..3      as the pot                         laid over the pot and moved with it: soot stage 1 to 3 (3 is a crust)
  cook_stewpot_crane          vertical axis at x 13.2, z 2.8     hearth style only: yaw 0 to +60 degrees about that axis (the arm points at the
                                                                 middle of the cell at 0; +60 swings it off the fire toward +x and +z). The
                                                                 pot, its chain and its soot turn about the same axis by the same angle (the
                                                                 pot's middle reaches x 15.1, z 9.9 at 60, so its edge overhangs the cell).
  cook_hob_pan                none                               static: stands on the iron top at y 10.0, its floor y 10.5 (PAN_FLOOR_Y)
  hob_damper_0..2             none                               static: the damper plate of notch 0 to 2 (the blockstate picks it)
  cook_hob_burnt              none                               static: laid over the hob while the vessel is BURNT
  cook_oven_door              vertical hinge at x 4.8, z 1.0     yaw 0 (shut) to +100 degrees about the hinge (positive swings OUTWARD, -z)
  cook_oven_door_hot          the same as the door               the same door with its window glowing (emissive): drawn in place of cook_oven_door
                                                                 while the oven is hot and shut; fade it in with the heat
  cook_oven_tray              none                               static, at the lower tray level (rails at y 8.6 and 10.8): lift it 2.2 px for the
                                                                 upper tray; it may slide out toward the door by up to 6 px (-z) when the door is
                                                                 open. Five items lie on a tray, at TRAY_SLOTS, on y 8.9 (the upper: 11.1).
  bake_oven_damper_0..2       none                               static: the damper plate of notch 0 to 2 (the blockstate picks it)
  cook_oven_burnt             none                               static: laid over the oven while the vessel is BURNT
  cook_spit_rod               the line y 9.1, z 8, along x       turns about that line, any angle; lifted REST_DY = +1.6, 0, -1.6 px for notch
                                                                 0, 1, 2 (the rod sits in the highest rest at notch 0). The six joints are
                                                                 drawn on it at x = 3.0, 4.82, 6.64, 8.46, 10.28, 12.1 (1.82 apart).
  cook_spit_crank             the same line as the rod           turns with the rod about that line, lifted with it; angle 0 has the arm up;
                                                                 a quarter turn is 90. It stays inside the cell at every angle.
  cook_spit_burnt             none                               static: laid over the spit while the vessel is BURNT
  cook_larder_door            vertical hinge at x 2.0, z 3.0     yaw 0 (shut) to +100 degrees about the hinge (positive swings OUTWARD, -z)

Also models, not pieces: `larder` (door shut: the door is part of the shell), `larder_open` (no door: the renderer draws cook_larder_door swung),
`menu_board_wall` (the slate flat on the wall behind, at z 16; no blockstate uses it) and the four `herb_stage0..3` (four crossed quads from y -1 to 15, as
the game's own `block/crop` has them, so that the plant stands on the top of farmland; the blockstate's `age` picks one).

WHERE THINGS ARE DRAWN (px, model space, facing north):
  stewpot     the liquid's surface is a quad at y 6.7 (a ladleful) up to 9.4 (full) at notch 0, 2.9 px from the middle to each edge (the
              texture is a disc), lowered with the pot; ingredient chunks bob within 2.4 px of (8, 8); steam leaves the pot's mouth, 3.0 px
              in radius, at the pot's top (y 10.0 at notch 0).
  hob         pieces in the pan lie on y 10.5 at PAN_SLOTS (x, z): (5, 6.1) (8, 6.1) (11, 6.1) (5, 9.9) (8, 9.9) (11, 9.9); steam over the pan;
              smoke from the flue's top at (13.4, 15.4, 13.4); the glow in the mouth is part of the lit shell.
  oven        smoke from the chimney's top at (11.7, 16.0, 11.8); the door's window is x 5.6 to 10.4, y 9.6 to 12.4, on z 1.0; five items on
              a tray at TRAY_SLOTS (x, z): (6.5, 6.9) (8, 6.9) (9.5, 6.9) (7.25, 4.5) (8.75, 4.5).
  spit        fat drips fall from y 7.8 to the tray's rim at y 1.9 (x 3.2 to 12, z 6.4 to 9.6); a flare leaps from the coal beds (x 3 to 12,
              z 4.6 to 6 and 10 to 11.4) at y 2.
  prep_board  the heap of up to 16 items lies in the box (4.2, 10.2, 4.6) to (11.8, 14.4, 12.2), on the chopping board (its top is y 10.2).
  plate       the dish lies flat at (8, 1.55, 8) on the plate (4.4 px apothem).
  larder      the three dishes seen through the slats stand at (5.6, 2.7, 8) (8, 7.0, 8) (10.4, 11.3, 8): x, y, z of each middle.
  menu_board  the chalking area is x 1.9 to 14.1, y 6.7 to 14.3 on the plane z 7.05 (it looks toward -z); on the wall variant x 1.9 to 14.1,
              y 3.3 to 12.7 on z 14.75.

CLICK REGIONS (1.1): `STATION_BOXES` below, each box (x0, y0, z0, x1, y1, z1), at least 4 px each way, facing north. They are the art's
suggestion for Part B (a click is read from where it lands in the block's own shape, and each box holds the hit points of what it names);
Part M3 reads them too, for its "hand through the box" check. The helpers are one region each and have no boxes.

TOOLS (Part M3): each tool model carries a `display` block for the held item, grip toward the sprite's lower left and the working end toward
its upper right, as the swords are; `TOOL_LAYOUT` below gives each tool's grip and tip on its 32 px sprite (x, y from the top left).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import cook_geo as G
import cook_tex
import cook_stewpot
import cook_hob
import cook_oven
import cook_spit
import cook_helpers
import cook_herb
import cook_tools
import cook_itemdefs
import cook_fx
import cook_gui
import cook_part
import cook_render

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')

# ---------------------------------------------------------------- click regions (x0, y0, z0, x1, y1, z1), px, facing north
STATION_BOXES = {
    'stewpot_camp': {'vessel': (4.0, 4.0, 4.0, 12.0, 10.5, 12.0), 'fire': (2.5, 0.0, 2.5, 13.5, 4.0, 13.5), 'heat': (6.0, 10.5, 6.0, 10.0, 15.4, 10.0)},
    'stewpot_hearth': {'vessel': (4.0, 4.0, 4.0, 12.0, 10.5, 12.0), 'fire': (3.2, 0.0, 3.2, 12.8, 4.0, 12.8), 'heat': (6.0, 10.5, 6.0, 10.0, 15.4, 10.0)},
    'hob': {'vessel': (2.5, 9.5, 2.5, 13.5, 14.0, 13.5), 'fire': (3.5, 0.0, 0.0, 12.5, 4.5, 6.0), 'heat': (1.0, 5.0, 0.0, 15.0, 9.0, 5.0)},
    'bake_oven': {'vessel': (3.6, 8.0, 0.0, 12.4, 14.6, 4.0), 'fire': (4.5, 0.0, 0.0, 11.5, 4.0, 6.0), 'heat': (3.4, 4.0, 0.0, 12.6, 8.0, 4.0)},
    'spit': {'vessel': (2.4, 6.4, 5.5, 12.0, 11.8, 10.5), 'fire': (2.4, 0.0, 4.0, 12.6, 4.0, 12.0), 'heat': (12.0, 5.5, 5.5, 16.4, 13.4, 10.5)},
}

# ---------------------------------------------------------------- the tools: grip and tip on the 32 px sprite (x, y from the top left)
TOOL_LAYOUT = cook_tools.LAYOUT                    # painted by cook_tools.py, from its own constants

MODULES = (cook_stewpot, cook_hob, cook_oven, cook_spit, cook_helpers)


def check_contract():
    """The numbers written above are the modules' constants: a change to one without the other stops the run."""
    S, H, O, P, L = cook_stewpot, cook_hob, cook_oven, cook_spit, cook_helpers
    assert (S.HANG_Y, S.CHAIN_TOP, S.POT_BOTTOM, S.NOTCH_STEP) == (12.9, 15.2, 5.6, 1.0)
    assert (round(S.SURFACE_Y[0], 3), round(S.SURFACE_Y[1], 3), S.SURFACE_R) == (6.7, 9.4, 2.9)
    assert S.CRANE_POST == (13.2, 2.8) and S.CRANE_MAX == 60.0 and abs(S.CRANE_HEADING - 135.0) < 1e-9
    assert (H.PAN_FLOOR_Y, H.PAN_SLOTS) == (10.5, [(5.0, 6.1), (8.0, 6.1), (11.0, 6.1), (5.0, 9.9), (8.0, 9.9), (11.0, 9.9)])
    assert H.FLUE == (13.4, 13.4) and H.FLUE_TOP == 15.4
    assert (O.HINGE, O.DOOR_MAX, O.TRAY_Y, O.CHIMNEY, O.CHIMNEY_TOP) == ((4.8, 1.0), 100.0, (8.6, 10.8), (11.7, 11.8), 16.0)
    assert O.TRAY_SLOTS == [(6.5, 6.9), (8.0, 6.9), (9.5, 6.9), (7.25, 4.5), (8.75, 4.5)]
    assert (P.AXIS_Y, P.AXIS_Z, P.REST_DY) == (9.1, 8.0, (1.6, 0.0, -1.6))
    assert [round(x, 2) for x in P.JOINT_X] == [3.0, 4.82, 6.64, 8.46, 10.28, 12.1]
    assert (L.HEAP_BOX, L.PLATE_AT, L.LARDER_DOOR_HINGE, L.LARDER_DOOR_MAX) == ((4.2, 10.2, 4.6, 11.8, 14.4, 12.2), (8.0, 1.55, 8.0), (2.0, 3.0), 100.0)
    assert L.LARDER_DISHES == ((5.6, 2.7, 8.0), (8.0, 7.0, 8.0), (10.4, 11.3, 8.0))
    assert (L.SLATE_FACE, L.SLATE_Z, L.WALL_FACE, L.WALL_Z) == ((1.9, 6.7, 14.1, 14.3), 7.05, (1.9, 3.3, 14.1, 12.7), 14.75)
    for station, boxes in STATION_BOXES.items():
        for region, (x0, y0, z0, x1, y1, z1) in boxes.items():
            assert min(x1 - x0, y1 - y0, z1 - z0) >= 4.0 - 1e-9, (station, region)


# ================================================================ writing

def write_textures(assets):
    d = os.path.join(assets, 'textures/block/cook')
    os.makedirs(d, exist_ok=True)
    painters = dict(cook_tex.PAINTERS)
    painters.update(cook_herb.PAINTERS)
    for name, paint in painters.items():
        paint().save(os.path.join(d, name + '.png'))
        if name in cook_tex.ANIMATED:
            G.write_json(os.path.join(d, name + '.png.mcmeta'), {'animation': cook_tex.ANIMATED[name]})


def block_models():
    out = {}
    for mod in MODULES:
        out.update(mod.models())
    return out


def write_models(assets, models):
    for name, (els, particle, ao) in models.items():
        G.write_json(os.path.join(assets, 'models/block', name + '.json'), G.model(els, particle, ao))


# ---------------------------------------------------------------- blockstates

FACING_Y = {'north': 0, 'east': 90, 'south': 180, 'west': 270}


def _apply(model, y=0):
    a = {'model': 'guildmark:block/' + model}
    if y:
        a['y'] = y
    return a


def blockstates():
    """{block id: blockstate JSON} for the nine blocks, using only the properties of Appendix C.1."""
    out = {}
    # The stewpot: style and lit pick the shell, burnt lays the soot over it
    parts = []
    for style in ('camp', 'hearth'):
        for lit in ('false', 'true'):
            parts.append({'when': {'style': style, 'lit': lit}, 'apply': _apply('stewpot_%s%s' % (style, '_lit' if lit == 'true' else ''))})
    for style in ('camp', 'hearth'):
        parts.append({'when': {'style': style, 'burnt': 'true'}, 'apply': _apply('stewpot_%s_burnt' % style)})
    out['stewpot'] = {'multipart': parts}
    # The hob: lit picks the shell, notch the damper
    parts = [{'when': {'lit': lit}, 'apply': _apply('hob_lit' if lit == 'true' else 'hob')} for lit in ('false', 'true')]
    parts += [{'when': {'notch': str(n)}, 'apply': _apply('hob_damper_%d' % n)} for n in range(3)]
    out['hob'] = {'multipart': parts}
    # The oven: facing and lit pick the shell, facing and notch the damper
    parts = []
    for facing, y in FACING_Y.items():
        for lit in ('false', 'true'):
            parts.append({'when': {'facing': facing, 'lit': lit}, 'apply': _apply('bake_oven_lit' if lit == 'true' else 'bake_oven', y)})
        for n in range(3):
            parts.append({'when': {'facing': facing, 'notch': str(n)}, 'apply': _apply('bake_oven_damper_%d' % n, y)})
    out['bake_oven'] = {'multipart': parts}
    # The spit: facing and lit (the rod's rest is the renderer's: the rod is its piece)
    parts = []
    for facing, y in FACING_Y.items():
        for lit in ('false', 'true'):
            parts.append({'when': {'facing': facing, 'lit': lit}, 'apply': _apply('spit_lit' if lit == 'true' else 'spit', y)})
    out['spit'] = {'multipart': parts}
    out['prep_board'] = {'variants': {'': _apply('prep_board')}}
    out['plate'] = {'variants': {'': _apply('plate')}}
    out['larder'] = {'variants': {'open=false': _apply('larder'), 'open=true': _apply('larder_open')}}
    out['menu_board'] = {'variants': {'facing=%s' % f: _apply('menu_board', y) for f, y in FACING_Y.items()}}
    out['herb'] = {'variants': {'age=%d' % a: _apply('herb_stage%d' % a) for a in range(cook_helpers.HERB_AGES)}}
    return out


# ---------------------------------------------------------------- block items

def model_item(model):
    return {'model': {'type': 'minecraft:model', 'model': model}}


SIMPLE_BLOCK_ITEMS = {'prep_board': 'guildmark:block/prep_board', 'plate': 'guildmark:block/plate', 'larder': 'guildmark:block/larder',
                      'menu_board': 'guildmark:block/menu_board'}


def station_item_models():
    """The four stations' item models: the unlit shell with the piece that stands in it at rest (the pot on its chain, the pan, the oven's door,
    the spit's rod and crank), merged into one model, since an item model has no renderer. {block id: (elements, particle)}"""
    S, H, O, P = cook_stewpot, cook_hob, cook_oven, cook_spit
    return {
        'stewpot': (S.camp(False) + G.move(S.pot(), dy=-S.NOTCH_STEP), 'firestone'),
        'hob': (H.shell(False) + H.damper(1) + H.pan(), 'brick'),
        'bake_oven': (O.shell(False) + O.door(False) + O.H.damper(1, O.SLOT, O.FRONT_Z - O.REGISTER_PROUD, w=O.DAMPER_W, slide=O.DAMPER_SLIDE), 'clay'),
        'spit': (P.shell(False) + P.rod() + P.crank(), 'flagstone'),
    }


def write_states_and_items(assets):
    for block, state in blockstates().items():
        G.write_json(os.path.join(assets, 'blockstates', block + '.json'), state)
    for block, (els, particle) in station_item_models().items():
        G.write_json(os.path.join(assets, 'models/item', block + '.json'), G.model(els, particle, True))
        G.write_json(os.path.join(assets, 'items', block + '.json'), model_item('guildmark:item/' + block))
    for block, model in SIMPLE_BLOCK_ITEMS.items():
        G.write_json(os.path.join(assets, 'items', block + '.json'), model_item(model))


def main(argv):
    assets = argv[0] if argv and argv[0] != '-' else ASSETS
    check_contract()
    write_textures(assets)
    write_models(assets, block_models())
    write_states_and_items(assets)
    cook_itemdefs.write_items(assets)
    cook_fx.write(assets)
    cook_gui.write(assets)
    cook_part.write(assets)
    cook_render.write(assets)


if __name__ == '__main__':
    main(sys.argv[1:])
