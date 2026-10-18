"""
The town works' blocks (M6, influence: living-world/design-influence.md, sections 3, 5 and 8): the works post with its
drawing and seals, the patron's bronze plaque (bare, and under its cloth), the yard's displays (timber stack, stone
pile, glass in straw crates, iron bars, wool bales) heaped by how much is there, the mortar tub, the hoist on the
scaffold, the beacon brazier (out, lit and flaring), the alms table with its pot and bowls, the "To let" board and the
empty display's chalked slate. Run from the repo root:

    python3 -B art/scripts/works_art.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/works [preview-dir]

Writes their block models, blockstates and 32x textures (HD, as the rest of the mod's blocks) under the assets, and
WorksArt.kt (the numbers the renderers heap, fill, seal, flare and letter by) into the Kotlin folder; none is
registered here (Parts B and D2 register them: living-world/works-art.md says what each needs). The clips that work at
them (works_clips.py, street_clips.py) take their numbers from here, so a hoe goes into the mortar and a torch into the
coals.

Block model space: x east, y up, z south, 0 to 16 a block; every block faces north (its front toward -z) in its model,
and its blockstate turns it by facing. Clip space (the townsfolk's): pixels, y down, facing -z, the right hand at -x, the
feet at y 24 on the floor of their own block; a block [bx, by, bz] blocks from the townsperson's own (who faces north)
has its model point (X, Y, Z) at clip (8 - X - 16 bx, 24 - Y - 16 by, Z - 8 + 16 bz).
"""
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)


def clip_pt(X, Y, Z, bx=0, by=0, bz=0):
    """A block model point (X, Y, Z) of the block [bx, by, bz] from a townsperson's own (who faces north), in their clip
    space."""
    return np.array([8.0 - X - 16.0 * bx, 24.0 - Y - 16.0 * by, Z - 8.0 + 16.0 * bz])

# ================================================================== the numbers the clips work to

# The mortar tub: a block of its own on the ground, its staves 1 px thick, the mortar's top TUB_MORTAR_Y up. MIX_MORTAR is
# played standing in the block south of it, facing it (north).
TUB_LO, TUB_HI = (1.0, 0.0, 1.0), (15.0, 7.0, 15.0)
TUB_MORTAR_Y = 5.5
TUB_FLOOR_Y = 1.0
_tlo, _thi = clip_pt(*TUB_LO, bz=-1), clip_pt(*TUB_HI, bz=-1)
TUB_X0, TUB_X1 = float(min(_tlo[0], _thi[0])), float(max(_tlo[0], _thi[0]))       # clip: -7 .. 7
TUB_Z0, TUB_Z1 = float(min(_tlo[2], _thi[2])), float(max(_tlo[2], _thi[2]))       # clip: -23 .. -9
TUB_TOP = float(clip_pt(0, TUB_HI[1], 0)[1])                                      # clip y of the rim: 17
TUB_MORTAR = float(clip_pt(0, TUB_MORTAR_Y, 0)[1])                                # 18.5
TUB_FLOOR = float(clip_pt(0, TUB_FLOOR_Y, 0)[1])                                  # 23

# The hoist: its rope hangs plumb from the pulley at the jib's end; the hauler stands HOIST_BEHIND px south of the rope
# (facing it, north) on the ground under it, and the clip draws the rope from HOIST_ROPE_TOP px above their feet down;
# the block's renderer draws it from the pulley down to there.
HOIST_BEHIND = 7.0
HOIST_ROPE_TOP = 44.0
HOIST_ROPE = np.array([0.0, 24.0 - HOIST_ROPE_TOP, -HOIST_BEHIND])               # clip: (0, -20, -7)

# The beacon brazier: an iron basket on three legs, its coals' top BRAZIER_COALS_Y up; LIGHT_BEACON is played standing in
# the block south of it, facing it.
BRAZIER_COALS_Y = 13.0
BRAZIER_RIM_Y = 14.5
BRAZIER_R = 5.5
BRAZIER_COALS = clip_pt(8.0, BRAZIER_COALS_Y, 8.0, bz=-1)                        # clip: (0, 11, -16)

# The patron's plaque: bronze, on the works' wall in the block above the one UNVEIL is played in (the wall the north
# face of both), its face PLAQUE_Z out from the wall; the cloth over it hangs from its top edge, VEIL_TOP up.
PLAQUE_LO, PLAQUE_HI = (2.0, 3.0, 0.0), (14.0, 13.0, 1.0)
PLAQUE_Z = PLAQUE_HI[2]
VEIL_LO, VEIL_HI = (1.0, 1.7, 1.0), (15.0, 14.3, 1.3)                            # the cloth: the props' 14 x 12.6
PLAQUE_FACE = clip_pt(8.0, 8.0, PLAQUE_Z, by=1)                                   # clip: (0, 0, -7)
VEIL_TOP = float(clip_pt(0, VEIL_HI[1], 0, by=1)[1])                             # clip y of the cloth's top: -6.3
VEIL_FRONT = float(clip_pt(0, 0, VEIL_HI[2], by=1)[2])                           # clip z of its front: -6.7

# The alms table: a trestle across the block between the keeper (south, facing north) and whoever comes for a bowl (north,
# facing south), its top ALMS_TOP_Y up, the pot on the keeper's right and the stack of bowls on their left.
ALMS_TOP_Y = 13.0
POT_AT = (11.5, 11.0)                   # model (x, z): the pot's middle, on the keeper's side (south) and their right (east)
POT_R = 3.6                             # its inside's radius at the rim
POT_RIM_Y = ALMS_TOP_Y + 6.5
POT_FLOOR_Y = ALMS_TOP_Y + 1.5          # the stew's lowest (one ladle's worth) and its highest, a little under the rim
POT_FULL_Y = POT_RIM_Y - 1.2
BOWLS_AT = (4.0, 11.0)                  # model (x, z): the stack of bowls' middle, on the keeper's left (west)
BOWL_H = 1.2                            # each bowl the stack's height more
BOWLS_MAX = 4
POT_LEVELS = 8                          # the stew's levels (0 empty .. 8 full)
ALMS_TOP = float(clip_pt(0, ALMS_TOP_Y, 0)[1])                                   # clip y of the top: 11
POT_MOUTH = clip_pt(POT_AT[0], POT_RIM_Y, POT_AT[1], bz=-1)                     # the keeper's clip: the pot's rim's middle
BOWLS_TOP = clip_pt(BOWLS_AT[0], ALMS_TOP_Y + BOWL_H * 3, BOWLS_AT[1], bz=-1)   # the top of a stack of three
ALMS_GAP = 32.0                         # keeper to taker, middle to middle (the table between them)


def pot_level_y(level):
    """The stew's top (model y) at [level] of POT_LEVELS (0: empty, none shown)."""
    if level <= 0:
        return None
    return POT_FLOOR_Y + (POT_FULL_Y - POT_FLOOR_Y) * (level - 1) / (POT_LEVELS - 1)
