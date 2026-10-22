"""The Spit's models (cook_art.py): two iron uprights, each with three notched rests, over a stone-lined pit with a bed of coals each side
of an enamel drip tray, unlit and lit, a burnt overlay, and the pieces the renderer moves: the rod (it turns about its own axis) and the crank
(it turns with the rod about the same axis).

Authored facing north: the rod runs east to west (along x) and the crank is at the east end; the block's `facing` turns the lot.
"""

from cook_geo import cube, crossed
import cook_tex

# ---------------------------------------------------------------- the numbers (px)
POST_X = ((0.8, 2.2), (13.0, 14.4))                      # the two uprights' x extents
POST_Z = (6.8, 9.2)                                      # their z extent (the rest's slot, 1 wide, is in the middle: z 7.5 to 8.5)
POST_TOP = 13.2
REST_Y = (10.7, 9.1, 7.5)                                # the height of the rod's axis in the rest of notch 0, 1, 2 (notch 0 the highest rod: the least heat)
AXIS_Y, AXIS_Z = REST_Y[1], 8.0                          # the rod is authored with its axis here (the middle rest): the renderer moves it REST_Y[n] - AXIS_Y up
REST_DY = tuple(round(y - AXIS_Y, 3) for y in REST_Y)    # (+1.6, 0, -1.6)
ROD_X = (0.4, 15.0)                                      # the rod's ends
JOINT_X = [3.0 + 1.82 * i for i in range(6)]             # where the six joints sit on the rod (x of each middle), the middle of each 1.8 px
CRANK_X = (14.8, 15.4)                                   # the crank arm's thickness, in x
CRANK_ARM = 3.3                                          # the arm's length from the axis (px)
PLINTH = (0.6, 0.0, 3.2, 15.4, 2.0, 12.8)                # the stone base: x0, y0, z0, x1, y1, z1
PIT = (2.4, 1.0, 12.6, 4.2, 11.8)                        # the pit's lining: x0, y0 (floor), x1, z0, z1
TRAY = (3.2, 12.0, 6.4, 9.6)                             # the drip tray: x0, x1, z0, z1; its rim top at TRAY_TOP
TRAY_TOP = 1.9
DRIP_FROM = 7.8                                          # a drip falls from about here (y) to TRAY_TOP
BED_Z = ((4.6, 6.0), (10.0, 11.4))                       # the two coal beds' z extents
BED_X = (3.0, 12.0)


def posts():
    els = []
    for (x0, x1) in POST_X:
        z0, z1 = POST_Z
        zm0, zm1 = 7.5, 8.5
        # The post: full where there is no rest, a fork (two prongs) at each rest, the slot 1.0 tall with the rod resting at its foot
        cuts = [(REST_Y[2] - 0.5, REST_Y[2] + 0.6), (REST_Y[1] - 0.5, REST_Y[1] + 0.6), (REST_Y[0] - 0.5, REST_Y[0] + 0.6)]
        full = [(2.0, cuts[0][0])]
        for a, b in zip(cuts, cuts[1:]):
            full.append((a[1], b[0]))
        full.append((cuts[-1][1], POST_TOP))
        for ya, yb in full:
            els.append(cube((x0, ya, z0), (x1, yb, z1), 'iron', ('up', 'down', 'north', 'south', 'west', 'east')))
        for ya, yb in cuts:
            els.append(cube((x0, ya, z0), (x1, yb, zm0), 'iron', ('up', 'down', 'north', 'south', 'west', 'east')))
            els.append(cube((x0, ya, zm1), (x1, yb, z1), 'iron', ('up', 'down', 'north', 'south', 'west', 'east')))
        # A finial on top, and a foot that spreads where it meets the plinth
        els.append(cube((x0 - 0.35, POST_TOP, z0 - 0.35), (x1 + 0.35, POST_TOP + 0.7, z1 + 0.35), 'iron_band'))
        els.append(cube((x0 - 0.5, 2.0, z0 - 0.5), (x1 + 0.5, 3.0, z1 + 0.5), 'iron_band'))
        # An iron rivet strap down its outer face
    return els


def pit(lit):
    """The pit: the stone plinth, the lining a step down, the enamel drip tray in the middle, a bed of coals each side, a log end at each
    bed, and (lit) flames over the beds."""
    x0, y0, z0, x1, y1, z1 = PLINTH
    px0, py0, px1, pz0, pz1 = PIT
    els = []
    # The plinth: four walls round the pit (the pit is sunk to its floor)
    els.append(cube((x0, y0, z0), (x1, y1, pz0), 'flagstone'))
    els.append(cube((x0, y0, pz1), (x1, y1, z1), 'flagstone'))
    els.append(cube((x0, y0, pz0), (px0, y1, pz1), 'flagstone'))
    els.append(cube((px1, y0, pz0), (x1, y1, pz1), 'flagstone'))
    # The pit's lining: fire-stone, down to its floor
    els.append(cube((px0, py0 - 0.4, pz0), (px1, py0, pz1), 'firestone', ('up',)))
    els.append(cube((px0, py0, pz0), (px0 + 0.02, y1, pz1), 'firestone', ('east',)))
    els.append(cube((px1 - 0.02, py0, pz0), (px1, y1, pz1), 'firestone', ('west',)))
    els.append(cube((px0, py0, pz0), (px1, y1, pz0 + 0.02), 'firestone', ('south',)))
    els.append(cube((px0, py0, pz1 - 0.02), (px1, y1, pz1), 'firestone', ('north',)))
    # The drip tray: enamel, a rim all round, the floor a little dished
    tx0, tx1, tz0, tz1 = TRAY
    th = TRAY_TOP
    els.append(cube((tx0, py0, tz0), (tx1, py0 + 0.45, tz1), 'enamel', ('up', 'down', 'north', 'south', 'west', 'east')))
    els.append(cube((tx0, py0 + 0.45, tz0), (tx1, th, tz0 + 0.45), 'enamel', ('up', 'north', 'south', 'west', 'east')))
    els.append(cube((tx0, py0 + 0.45, tz1 - 0.45), (tx1, th, tz1), 'enamel', ('up', 'north', 'south', 'west', 'east')))
    els.append(cube((tx0, py0 + 0.45, tz0 + 0.45), (tx0 + 0.45, th, tz1 - 0.45), 'enamel', ('up', 'west', 'north', 'south')))
    els.append(cube((tx1 - 0.45, py0 + 0.45, tz0 + 0.45), (tx1, th, tz1 - 0.45), 'enamel', ('up', 'east', 'north', 'south')))
    # The coal beds either side of it
    bx0, bx1 = BED_X
    for (bz0, bz1) in BED_Z:
        if lit:
            els.append(dict(cube((bx0, py0 + 0.35, bz0), (bx1, py0 + 0.9, bz1), 'coals', ('up', 'north', 'south', 'west', 'east')), light_emission=14))
        else:
            els.append(cube((bx0, py0 + 0.35, bz0), (bx1, py0 + 0.9, bz1), 'coals_cold', ('up', 'north', 'south', 'west', 'east')))
    # A short log end on each bed, charred
    for (bz0, bz1) in BED_Z:
        zc = (bz0 + bz1) / 2
        els.append(cube((bx0 - 0.4, py0 + 0.9, zc - 0.7), (bx0 + 3.4, py0 + 2.3, zc + 0.7), {'west': 'charred_end', 'east': 'charred_end', '*': 'charred_log'},
                        uv={'west': (4, 4, 12, 12), 'east': (4, 4, 12, 12)}))
        if lit:
            els.append(cube((bx0 - 0.44, py0 + 0.86, zc - 0.74), (bx0 + 3.44, py0 + 2.34, zc + 0.74), 'log_glow', ('up', 'north', 'south'), light_emission=11))
    if lit:
        uv = cook_tex.FLAME_UV
        for (bz0, bz1) in BED_Z:
            zc = (bz0 + bz1) / 2
            for k, xc in enumerate((5.4, 9.6)):
                els += crossed(0, 0, py0 + 0.8, py0 + 0.8 + 3.6 + 0.6 * (k % 2), 'flame', cx=xc, cz=zc, half=1.9, uv=uv, turn=30.0 * k, light_emission=15)
    return els


def shell(lit):
    return pit(lit) + posts()


def burnt():
    """The burnt look laid over the spit: the pit's lining and the plinth blackened, soot up the uprights' feet, the tray gone black."""
    x0, y0, z0, x1, y1, z1 = PLINTH
    px0, py0, px1, pz0, pz1 = PIT
    g = 0.05
    tx0, tx1, tz0, tz1 = TRAY
    els = [cube((x0, y1 - 0.02, z0), (x1, y1 + g, pz0), 'soot_wall', ('up',)),
           cube((x0, y1 - 0.02, pz1), (x1, y1 + g, z1), 'soot_wall', ('up',)),
           cube((px0, py0 + 0.9, pz0), (px1, py0 + 0.95, pz1), 'soot_wall', ('up',)),
           cube((tx0, TRAY_TOP - 0.02, tz0), (tx1, TRAY_TOP + 0.01, tz1), 'soot_wall', ('up',))]
    for (a, b) in POST_X:
        els.append(cube((a - g, 2.0, POST_Z[0] - g), (b + g, 6.4, POST_Z[0]), 'soot_wall', ('north',)))
        els.append(cube((a - g, 2.0, POST_Z[0]), (a, 6.4, POST_Z[1]), 'soot_wall', ('west',)))
    return els


# ---------------------------------------------------------------- the pieces

def rod():
    """The rod, a piece that turns about its own axis (the line y = AXIS_Y, z = AXIS_Z along x; any angle), authored at the middle rest: the
    renderer lifts it REST_DY[notch] for the rest it sits in. A square iron rod ROD_X long, with a pair of skewer forks (two prongs on a
    collar) near each end that grip the joints, a ring at the west end and a square tang at the east end that the crank is fitted to."""
    y, z = AXIS_Y, AXIS_Z
    x0, x1 = ROD_X
    t = 0.45                                              # half the rod's thickness
    els = [cube((x0, y - t, z - t), (x1, y + t, z + t), 'pot_iron', ('up', 'down', 'north', 'south', 'west', 'east'))]
    # Collars: where each joint is skewered, a pair of prongs rising from the rod (they turn with it, so you see the turn)
    for k, xc in enumerate((JOINT_X[0] - 0.9, JOINT_X[-1] + 0.9)):
        els.append(cube((xc - 0.3, y - t - 0.25, z - t - 0.25), (xc + 0.3, y + t + 0.25, z + t + 0.25), 'iron_band'))
        els.append(cube((xc - 0.25, y + t, z - 0.9), (xc + 0.25, y + 2.0, z - 0.45), 'pot_iron'))
        els.append(cube((xc - 0.25, y + t, z + 0.45), (xc + 0.25, y + 2.0, z + 0.9), 'pot_iron'))
        els.append(cube((xc - 0.25, y - 2.0, z - 0.9), (xc + 0.25, y - t, z - 0.45), 'pot_iron'))
        els.append(cube((xc - 0.25, y - 2.0, z + 0.45), (xc + 0.25, y - t, z + 0.9), 'pot_iron'))
    # The ring at the west end
    els.append(cube((x0 - 0.4, y - 0.8, z - 0.8), (x0 + 0.5, y + 0.8, z + 0.8), 'iron_band'))
    # The tang at the east end
    els.append(cube((CRANK_X[0] - 0.4, y - 0.6, z - 0.6), (x1, y + 0.6, z + 0.6), 'iron_band'))
    return els


def crank():
    """The crank, a piece that turns about the same axis as the rod (y = AXIS_Y, z = AXIS_Z along x; the renderer lifts it with the rod): an iron
    arm CRANK_ARM long pointing up at angle 0 (a quarter turn per click: the angle goes 0, 90, 180, 270), a wooden knob at its end. At every
    angle it stays inside the cell."""
    y, z = AXIS_Y, AXIS_Z
    a0, a1 = CRANK_X
    els = [cube((a0, y - 0.6, z - 0.6), (a1, y + CRANK_ARM + 0.5, z + 0.6), 'iron_band', ('up', 'down', 'north', 'south', 'west', 'east')),
           cube((a0 - 0.3, y - 1.0, z - 1.0), (a1 + 0.1, y + 1.0, z + 1.0), 'brass', uv=(0, 0, 2, 2))]
    els.append(cube((a1, y + CRANK_ARM - 0.6, z - 0.7), (a1 + 0.9, y + CRANK_ARM + 0.8, z + 0.7), 'darkwood'))
    return els


def models():
    return {
        'spit': (shell(False), 'flagstone', True),
        'spit_lit': (shell(True), 'flagstone', False),
        'cook_spit_burnt': (burnt(), 'soot_wall', True),
        'cook_spit_rod': (rod(), 'pot_iron', True),
        'cook_spit_crank': (crank(), 'iron_band', True),
    }
