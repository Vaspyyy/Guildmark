"""How every weapon class sits in the hand, for the combat clips and the item models.

Vanilla's own weapons keep their models: swords and axes use the handheld display, the mace its own, spears the
spear's (held level at the hip, point forward). Guildmark's greatswords, daggers and sabres are solved against the
sword (their grip lands in the fist where a sword's does and the blade points the same way, only longer or shorter),
the war hammers against the mace, so a class's clips fit everything in it. Sprite points are given in 32x pixels
along the sprite's diagonal: [s] runs from the bottom-left corner toward the top-right, [w] across it, positive
toward the upper left (the side the edge faces).

    python3 art/scripts/weaponkit.py      prints each class's first-person grip for FirstPersonCombat.kt
"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Geo, SWORD, aligned, unit, apply, FP_BASE

N = 32
ROOT2 = math.sqrt(2.0)


def at(s, w=0.0, size=N):
    """Item-space point (0..1, y up) of the sprite point [s] along the diagonal and [w] across it (32x pixels)."""
    x = s / ROOT2 - w / ROOT2
    y = s / ROOT2 + w / ROOT2
    return (x / size, y / size)


# ---------------------------------------------------------------- vanilla

AXE = Geo('axe', SWORD.tp, SWORD.fp, grip=SWORD.grip, tip=(0.656, 0.656))
MACE = Geo('mace', ((0, -90, 55), (0, 4, 1), (1, 1, 1)), ((0, -90, 25), (0, 3, 0.8), (0.9, 0.9, 0.9)),
           grip=(0.235, 0.235), tip=(0.69, 0.69))
# The spear's in-hand sprite runs point (top left) to butt (bottom right); held near the middle
SPEAR = Geo('spear', ((5, 270, -40), (0, 2, 2), (1.7, 1.7, 0.85)), ((-20, 90, -35), (3.13, 2.0, 0.13), (1.36, 1.36, 0.68)),
            grip=(0.604, 0.396), tip=(0.047, 0.953), edge=(1.0, 1.0))

# ---------------------------------------------------------------- Guildmark's own

# Sprite layouts (32x, along the diagonal): grip where the main hand closes, second where the off hand does
LAYOUT = {
    'greatsword': {'grip': 10.0, 'second': 5.6, 'tip': (44.6, 0.0), 'length': 21.5},
    'dagger': {'grip': 13.2, 'tip': (38.2, 0.0), 'length': 8.4},
    'sabre': {'grip': (7.0, 1.5), 'tip': (40.7, -3.5), 'length': 15.6},
    'war_hammer': {'grip': 9.0, 'second': 3.4, 'tip': (33.0, 0.0), 'length': 15.5},
    # The catalysts: a staff held a third of the way up, its head (the crystal or eye) at [tip]; a censer, a burner
    # on a short haft held like a mace, [tip] the middle of the burner
    'staff': {'grip': 12.0, 'tip': (43.0, 0.0), 'length': 22.0},
    'censer': {'grip': 9.0, 'tip': (32.0, 0.0), 'length': 12.5},
}


def _ours(name, ref):
    lay = LAYOUT[name]
    grip = np.array([*at(*np.atleast_1d(lay['grip'])), 0.5])
    tip = np.array([*at(*lay['tip']), 0.5])
    second = at(lay['second']) if 'second' in lay else None
    # Scale so grip to tip measures [length] model pixels in the hand
    base = Geo('probe', ref.tp, ref.fp, grip[:2], tip[:2])
    rot, trans, sc = ref.tp
    natural = base.length() / sc[0]
    return aligned(name, ref, grip[:2], tip[:2], lay['length'] / natural / sc[0], second=second)


GREATSWORD = _ours('greatsword', SWORD)
DAGGER = _ours('dagger', SWORD)
SABRE = _ours('sabre', SWORD)
WAR_HAMMER = _ours('war_hammer', MACE)
STAFF = _ours('staff', SWORD)
CENSER = _ours('censer', MACE)

# Every class and the geometry its clips are made with
CLASSES = {
    'straight_sword': SWORD,
    'greatsword': GREATSWORD,
    'dagger': DAGGER,
    'curved_sword': SABRE,
    'axe': AXE,
    'hammer': MACE,
    'spear': SPEAR,
}

# The catalysts the spells are cast with, and the geometry their cast clips are made with
CATALYSTS = {
    'staff': STAFF,
    'censer': CENSER,
}


def model(texture, geo):
    """An item model json for one of our weapons: the handheld parent with [geo]'s hand transforms."""
    return {'parent': 'minecraft:item/handheld', 'textures': {'layer0': texture}, 'display': geo.model_display()}


if __name__ == '__main__':
    np.set_printoptions(precision=4, suppress=True)
    for name, g in CLASSES.items():
        print(f'{name:15s} fp grip {g.fp_grip}  length {g.length():.1f}px  fp blade {g.fp_blade}')
    for name, g in CATALYSTS.items():
        print(f'{name:15s} fp grip {g.fp_grip}  length {g.length():.1f}px  fp blade {g.fp_blade}')
    for g in (GREATSWORD, DAGGER, SABRE, WAR_HAMMER, STAFF, CENSER):
        print(g.name, g.model_display()['thirdperson_righthand'], g.model_display()['firstperson_righthand'])
        # Check the solve: the grip lands where the reference's does
        ref = MACE if g in (WAR_HAMMER, CENSER) else SWORD
        print('   tp grip', g.tp_point(g.grip), 'ref', ref.tp_point(ref.grip), ' fp grip', g.fp_grip, 'ref', ref.fp_grip)
        print('   tp blade', g.tp_blade, 'ref', ref.tp_blade)
