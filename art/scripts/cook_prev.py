"""Hearthcraft's previews: every contact sheet and GIF that lets the art be judged without the game, drawn from the files `cook_art.py` has written
(so a preview shows the mod as it is) into a preview folder. Run by `cook_art.py` given a preview folder:

    python3 -B art/scripts/cook_art.py [assets dir [preview dir [game assets dir]]]

The game assets (an unpacked client jar's `assets/minecraft`, read only) are for the vanilla campfire and furnace the blocks are drawn beside, the
farmland and wheat the herb stands beside, and the dioramas' walls and furniture; without them those are left out and the rest is drawn.
A rerun gives the same files: nothing is random but from a fixed seed, each GIF's colours are chosen from the clip itself, and no file carries a date
(except that the cloud environment adds a content-credentials block to every picture written under `/mnt/project-files`; compare what they show there).

THE FILES
  cook_everything.png        every item and block on one sheet
  cook_dish_states.png       the dishes by tier and stage, 64 px
  cook_dishes_sizes.png      every dish at 16, 32 and 64 px
  cook_dishes_marks_16.png   the four tier marks and two stage washes on every dish at 16 px, and the two finer marks under a full stack's number
  cook_dishes.gif            what moves on the dishes (steam, sheen, smoke, flies, stamp glints)
  cook_tools.png / .gif      the tools, the book and the herb sprig, with grip and tip; and their glints
  cook_stewpot.png, cook_hob.png, cook_oven.png, cook_spit.png
                             each station lit, unlit and burnt at noon and dusk beside the game's campfire and furnace at true scale, then its click regions
  cook_helpers.png           the prep board, plate, larder and menu board
  cook_pieces.png / .gif     the moving pieces and their pivots (the contract with Part C), still and moving
  cook_herb.png              the herb's four ages beside wheat
  cook_blocks_lit.gif        the lit stations alive: flames, coals, steam, smoke, sizzle, drips
  cook_block_textures.gif    the block textures that move
  cook_effects.png / .gif    the five effect icons
  cook_chill.png             the cold vignette over a picture
  cook_gui.png, cook_gui_page.png, cook_gui.gif
                             the 21 GUI sprites, the Book's page stretched, and the sprites that move
  cook_particles.png / .gif  the seven particles, and each in motion
  cook_renderer_textures.png, cook_pot_surface.gif
                             the textures the renderers draw, and the pot's surface as a renderer would use them
  cook_cookshop_parchment.png  the works post's six parchments, the cookshop's outlined
  cook_stage_tavern.png / .gif, cook_stage_bakery.png / .gif
                             the tavern's hearth corner and the bakery counter built from the models
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)


def previews(assets, out, game=None, log=print):
    """Draws every preview into the folder [out] from the mod's assets folder [assets] (and the game's `assets/minecraft` [game], if given); returns the
    files, in the order written."""
    import cook_prev_blocks as B
    import cook_prev_flat as F
    os.makedirs(out, exist_ok=True)
    written = []

    def step(name, fn, *args):
        t0 = time.time()
        res = fn(*args)
        res = res if isinstance(res, (list, tuple)) else [res]
        written.extend(res)
        log('  %-26s %5.1f s  %s' % (name, time.time() - t0, ', '.join(os.path.basename(r) for r in res)))

    step('items', F.item_sheets, assets, out)
    step('tools', F.tools_sheet, assets, out)
    step('item animations', F.items_gifs, assets, out)
    step('effects', F.effects_sheet, assets, out)
    step('chill', F.chill_sheet, assets, out)
    step('gui', F.gui_sheet, assets, out)
    step('particles', F.particle_sheet, assets, out)
    step('renderer textures', F.renderer_sheet, assets, out)
    step('block textures', F.block_texture_gif, assets, out)
    step('parchment', F.parchment_sheet, out)
    step('everything', B.everything_sheet, assets, out, game)
    titles = {'stewpot': 'Stewpot: camp and hearth styles, unlit, lit and burnt, at noon and dusk',
              'hob': 'Hob: unlit, lit and burnt, at noon and dusk',
              'oven': 'Bake Oven: unlit, lit and burnt, at noon and dusk',
              'spit': 'Spit: unlit, lit and burnt, at noon and dusk'}
    for which, title in titles.items():
        if game:
            title += ', beside the game\'s campfire and furnace (the same scale)'
        step(which, B.station_sheet, assets, out, game, which, title)
    step('helpers', B.helper_sheet, assets, out, game)
    step('pieces', B.pieces_sheet, assets, out, game)
    step('pieces animation', B.pieces_gif, assets, out, game)
    step('herb', B.herb_sheet, assets, out, game)
    step('lit stations', B.alive_gif, assets, out, game)
    if game:
        for which in ('tavern', 'bakery'):
            step('stage ' + which, B.stage_sheet, assets, out, game, which)
            step('stage ' + which + ' animation', B.stage_gif, assets, out, game, which)
    else:
        log('  no game assets given: the vanilla campfire and furnace, the herb on farmland and the two dioramas are left out')
    return written


if __name__ == '__main__':
    # python3 -B art/scripts/cook_prev.py <assets dir> <preview dir> [game assets dir]
    previews(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
