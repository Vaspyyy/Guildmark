"""The Stewpot's models (cook_art.py): the camp style (a tripod of sticks over a ring of fire-stones) and the hearth style (a
swinging iron crane over a sunk fire pit in a paved hearth), each unlit and lit, a burnt overlay for each, the iron pot that hangs
from either one, the crane that swings it, and the soot that grows on the pot.

Everything here is authored in model space (x east, y up, z south, 16 px a block). The pot and the crane are the renderer's
pieces; their numbers are the contract with Part C and are the constants below (read back by `cook_art.py`'s first comment block).
"""
import math

import numpy as np

from cook_geo import cube, along, ring, lathe, disc, crossed, Ry, euler_xyz, r3
import cook_tex

# ---------------------------------------------------------------- the numbers (px)
CX, CZ = 8.0, 8.0                    # the pot hangs over the middle of the cell
POT_BOTTOM = 5.6                     # the pot's bottom at heat notch 0 (the lowest heat: the pot highest)
POT_H = 4.4                          # the pot's height, bottom to rim
POT_R = 3.7                          # its widest (apothem, px)
NOTCH_STEP = 1.0                     # px the pot comes down for each heat notch (0, 1, 2): the lower the pot, the higher the heat
HANG_Y = 12.9                        # the underside of what the chain goes up into: the camp's lashing, the crane's lug
CHAIN_TOP = 15.2                     # the chain's top at notch 0: inside the lashing or lug at every notch (13.2 at notch 2)
POT_TOP = POT_BOTTOM + POT_H
POT_MOUTH_R = 3.0                    # the mouth's inside radius at the rim
SURFACE_Y = (POT_BOTTOM + 1.1, POT_TOP - 0.6)    # the liquid's surface (the renderer's quad) from one ladleful to full, at notch 0
SURFACE_R = 2.9                      # the surface quad's half-width: a square whose texture is a disc

# The hearth's crane: its post stands at CRANE_POST, its arm reaches CRANE_ARM px toward the middle of the cell (swing 0) and swings
# about the post, off the fire toward the front (+z), by up to CRANE_MAX degrees
CRANE_POST = (13.2, 2.8)
CRANE_ARM = math.hypot(CRANE_POST[0] - CX, CRANE_POST[1] - CZ)
CRANE_HEADING = math.degrees(math.atan2(CZ - CRANE_POST[1], CX - CRANE_POST[0]))    # the arm's heading at swing 0 (0 = +x, 90 = +z)
CRANE_MAX = 60.0

# The camp's tripod
APEX = (CX, 14.15, CZ)               # where the legs cross
LASH = (12.9, 15.4)                  # the lashing's underside and top
LEG_R = 7.0                          # the legs' feet round the middle
LEG_ANGLES = (270.0, 30.0, 150.0)    # degrees, 0 = +x, 90 = +z (south): one leg behind, two in front

FIRE_Y = 0.5                         # the ash bed's surface
LOGS_TOP = 3.0


def _polar(r, deg, cx=CX, cz=CZ):
    a = math.radians(deg)
    return cx + r * math.cos(a), cz + r * math.sin(a)


# ================================================================ the fire: stones, logs, coals, flames

STONE_FACES = ('up', 'north', 'south', 'west', 'east')


def firestones(rad=5.5, n=8, top=2.8, seed=3, tex='firestone', grow_by=0.0):
    """A ring of [n] fire-stones round (CX, CZ): rounded lumps of different heights, each a box turned to the ring, with a smaller
    one set on a few. [grow_by] puffs each out (for the soot laid over them)."""
    rng = np.random.default_rng(seed)
    els = []
    g = grow_by
    for i in range(n):
        a = 360.0 * i / n + rng.uniform(-6, 6)
        x, z = _polar(rad + rng.uniform(-0.3, 0.4), a)
        w, d, h = rng.uniform(3.0, 4.0), rng.uniform(2.6, 3.3), rng.uniform(top - 0.9, top + 0.3)
        yaw = -a + 90.0 + rng.uniform(-12, 12)
        els.append(cube((x - w / 2 - g, 0, z - d / 2 - g), (x + w / 2 + g, h + g, z + d / 2 + g), tex, STONE_FACES, rot=(0.0, yaw, 0.0), origin=(x, 0, z)))
        if i % 3 == 0:
            w2, h2 = w * 0.62, rng.uniform(0.9, 1.4)
            yaw2 = -a + 90.0 + rng.uniform(-25, 25)
            els.append(cube((x - w2 / 2 - g, h - 0.1, z - w2 / 2 - g), (x + w2 / 2 + g, h + h2 + g, z + w2 / 2 + g), tex, STONE_FACES,
                            rot=(0.0, yaw2, 0.0), origin=(x, h, z)))
    return els


def logs(y0=FIRE_Y - 0.05, glow=False):
    """Two charred logs crossed in the fire, their ends on the stones: each 2.3 thick and 9.4 long, the one a little over the other. When
    [glow] a skin of the glowing cracks lies over each (it shines at night)."""
    els = []
    end_uv = (4, 4, 12, 12)
    for k, (a, y, dz) in enumerate(((22.0, y0, -0.2), (-38.0, y0 + 0.05, 0.3))):
        c = np.array([CX + 0.1 * k, y + 1.15, CZ + dz])
        L = 9.4
        els.append(cube((c[0] - L / 2, y, c[2] - 1.15), (c[0] + L / 2, y + 2.3, c[2] + 1.15), {'west': 'charred_end', 'east': 'charred_end', '*': 'charred_log'},
                        uv={'west': end_uv, 'east': end_uv}, rot=(0.0, -a, 0.0), origin=tuple(c)))
        if glow:
            els.append(cube((c[0] - L / 2 - 0.04, y - 0.04, c[2] - 1.19), (c[0] + L / 2 + 0.04, y + 2.34, c[2] + 1.19), 'log_glow',
                            ('up', 'north', 'south'), rot=(0.0, -a, 0.0), origin=tuple(c), light_emission=11))
    return els


def coal_bed(r=4.6, lit=True):
    """The bed the fire sits on: ash all round, and where the logs lie the coals (glowing and animated when lit, cold and grey when not)."""
    els = disc(CX, CZ, 0.0, FIRE_Y, r + 0.5, 'ash', faces=('up', 'north', 'south', 'west', 'east'))
    inner = r - 1.2
    if lit:
        els += [dict(e, light_emission=14) for e in disc(CX, CZ, FIRE_Y - 0.02, FIRE_Y + 0.12, inner, 'coals', faces=('up',))]
    else:
        els += disc(CX, CZ, FIRE_Y - 0.02, FIRE_Y + 0.12, inner, 'coals_cold', faces=('up',))
    return els


def flames(h=8.0, y0=0.6, w=6.0, light=15, turn=0.0):
    """Flames: two crossed quads of the animated flame texture (12 x 8 px, the part of each 32x32 frame the flames are drawn in) and a
    smaller pair turned 45 degrees, all glowing."""
    uv = cook_tex.FLAME_UV
    els = crossed(0, 0, y0, y0 + h, 'flame', cx=CX, cz=CZ, half=w, uv=uv, turn=turn, light_emission=light)
    els += crossed(0, 0, y0, y0 + h * 0.8, 'flame', cx=CX, cz=CZ, half=w * 0.8, uv=uv, turn=turn + 45.0, light_emission=light)
    return els


# ================================================================ the camp

def tripod():
    """Three sticks leaning together, crossing over the middle, their tops left sticking out above the lashing."""
    els = []
    for ang in LEG_ANGLES:
        x, z = _polar(LEG_R, ang)
        base = np.array([x, 0.15, z])
        apex = np.array(APEX)
        d = (apex - base) / np.linalg.norm(apex - base)
        top = apex + d * 1.55
        els.append(along(base, top, 1.3, 1.3, 'bark'))
    return els


def lashing():
    """The twine wound thick round the crossing: a block of it and three turns standing proud."""
    y0, y1 = LASH
    els = [cube((CX - 1.25, y0, CZ - 1.25), (CX + 1.25, y1, CZ + 1.25), 'rope')]
    for yy in (y0 + 0.2, (y0 + y1) / 2 - 0.2, y1 - 1.0):
        els.append(cube((CX - 1.4, yy, CZ - 1.4), (CX + 1.4, yy + 0.8, CZ + 1.4), 'rope', uv=(0, 0, 16, 16)))
    return els


def camp(lit):
    els = coal_bed(lit=lit)
    els += firestones()
    els += logs(glow=lit)
    els += tripod()
    els += lashing()
    if lit:
        els += flames()
    return els


def camp_burnt():
    """The burnt look laid over the camp: every stone blackened with soot, a black scorch on the ash round the fire."""
    els = firestones(tex='soot_wall', grow_by=0.07)
    els.append(cube((CX - 6.5, FIRE_Y + 0.15, CZ - 6.5), (CX + 6.5, FIRE_Y + 0.2, CZ + 6.5), 'soot_wall', ('up',)))
    return els


# ================================================================ the hearth

HEARTH_TOP = 1.8
PIT = (3.2, 12.8)


def hearth_slab():
    """The paved hearth: a slab 1.8 high with a fire pit sunk in its middle, the pit lined with fire-stone, and a crane's iron footplate
    at the back right."""
    els = []
    y1 = HEARTH_TOP
    x0, x1, z0, z1 = 0.5, 15.5, 0.5, 15.5
    p0, p1 = PIT
    els.append(cube((x0, 0, z0), (x1, y1, p0), {'*': 'flagstone', 'south': 'firestone'}))
    els.append(cube((x0, 0, p1), (x1, y1, z1), {'*': 'flagstone', 'north': 'firestone'}))
    els.append(cube((x0, 0, p0), (p0, y1, p1), {'*': 'flagstone', 'east': 'firestone'}))
    els.append(cube((p1, 0, p0), (x1, y1, p1), {'*': 'flagstone', 'west': 'firestone'}))
    cx, cz = CRANE_POST
    els.append(cube((cx - 1.6, y1, cz - 1.6), (cx + 1.6, y1 + 0.4, cz + 1.6), 'iron', ('up', 'north', 'south', 'west', 'east')))
    return els


def hearth(lit):
    els = hearth_slab()
    els += coal_bed(r=4.0, lit=lit)
    els += logs(glow=lit)
    if lit:
        els += flames(w=4.4)
    return els


def hearth_burnt():
    """The burnt look over the hearth: the flags round the pit and the pit's lining blackened, a scorch on the ash."""
    y1 = HEARTH_TOP
    p0, p1 = PIT
    g = 0.05
    tex = 'soot_wall'
    return [
        cube((0.5, y1 - 0.2, 0.5), (15.5, y1 + g, p0 + 0.1), tex, ('up',)),
        cube((0.5, y1 - 0.2, p1 - 0.1), (15.5, y1 + g, 15.5), tex, ('up',)),
        cube((p0 - g, 0.4, p0), (p0 + 0.01, y1, p1), tex, ('east',)),
        cube((p1 - 0.01, 0.4, p0), (p1 + g, y1, p1), tex, ('west',)),
        cube((p0, 0.4, p0 - g), (p1, y1, p0 + 0.01), tex, ('south',)),
        cube((p0, 0.4, p1 - 0.01), (p1, y1, p1 + g), tex, ('north',)),
        cube((CX - 4.2, FIRE_Y + 0.15, CZ - 4.2), (CX + 4.2, FIRE_Y + 0.2, CZ + 4.2), tex, ('up',)),
    ]


# ================================================================ the pot (a piece)

POT_PROFILE = [(0.0, 0.9, 2.55), (0.9, 1.8, 3.2), (1.8, 2.7, 3.65), (2.7, 3.4, 3.5), (3.4, 4.0, 3.1)]    # (y0, y1, apothem) up from the pot's bottom
POT_LIP = (4.0, POT_H, 3.6)                                                                               # the lip flares out at the rim


def pot_body(grow_by=0.0, tex='pot_iron', inner='iron_inside', outer_only=False):
    """The pot's belly, turned from POT_PROFILE (a rounded belly narrowing to a base) with a flared lip: rings of eight slabs. With
    [outer_only] only the outside faces (the soot laid over it), [grow_by] puffing it out."""
    b = POT_BOTTOM
    g = grow_by
    th = 0.1 if outer_only else 0.75
    prof = [(b + y0, b + y1, r) for y0, y1, r in POT_PROFILE]
    els = disc(CX, CZ, b - g, b + 0.6, 2.5 + g, tex, faces=('down', 'north', 'south', 'west', 'east') if outer_only else ('down', 'up', 'north', 'south', 'west', 'east'))
    els += lathe(CX, CZ, prof, th, tex, inner=inner, inside=not outer_only, grow=g)
    y0, y1, r = POT_LIP
    els += ring(CX, CZ, b + y0, b + y1, r + g, th if outer_only else 0.95, tex, inner=inner, top=not outer_only, bottom=True, inside=not outer_only)
    return els


def pot():
    """The iron pot on its chain, centred on (CX, CZ) at notch 0: the belly, an inside floor, the bail (a wire arch) with its eye, and the
    chain going up through the eye, crossed quads, ending inside the lashing or lug at every notch."""
    els = pot_body()
    b = POT_BOTTOM
    # The inside's floor, dark, seen from above through the mouth
    els += disc(CX, CZ, b + 0.55, b + 0.62, 2.45, 'iron_inside', faces=('up',))
    top = POT_TOP
    pts = [(CX - 3.7, top - 0.2), (CX - 3.0, top + 1.05), (CX, top + 1.75), (CX + 3.0, top + 1.05), (CX + 3.7, top - 0.2)]
    for (xa, ya), (xb, yb) in zip(pts, pts[1:]):
        els.append(along((xa, ya, CZ), (xb, yb, CZ), 0.6, 0.6, 'pot_iron'))
    for sx in (-1, 1):
        els.append(cube((CX + sx * 3.7 - 0.45, top - 0.7, CZ - 0.5), (CX + sx * 3.7 + 0.45, top + 0.1, CZ + 0.5), 'pot_iron'))
    els.append(cube((CX - 0.7, top + 1.5, CZ - 0.3), (CX + 0.7, top + 2.5, CZ + 0.3), 'pot_iron'))
    els += chain_up(top + 2.4, CHAIN_TOP)
    return els


def chain_up(y0, y1):
    """The chain: two crossed quads 2 px across (uv 0 to 2 of the chain texture, which tiles up), from y0 to y1."""
    return crossed(0, 0, y0, y1, 'chain', cx=CX, cz=CZ, half=1.0, uv=(0, 0, 2, y1 - y0))


def pot_soot(stage):
    """The soot on the pot, stage 1 to 3: the belly again, a hair bigger, in the stage's stain (nothing of the bail or chain)."""
    return pot_body(grow_by=0.06, tex='soot_%d' % stage, outer_only=True)


# ================================================================ the crane (a piece)

def _arm_piece(frm, to, tex, ang, px, pz):
    """A box in the arm's own frame (x along the arm from the post, z across it) turned to the arm's heading [ang] about the post."""
    return cube((px + frm[0], frm[1], pz + frm[2]), (px + to[0], to[1], pz + to[2]), tex, rot=(0.0, -ang, 0.0), origin=(px, 0, pz))


def turned_about(el, yaw, ox, oz):
    """An element (turned or not) turned further about the vertical through (ox, oz) by [yaw] degrees."""
    from island_blockview import rotation_matrix
    e = dict(el)
    rot = e.get('rotation')
    c = np.array(rot['origin'] if rot else [(el['from'][i] + el['to'][i]) / 2 for i in range(3)], float)
    R_old = rotation_matrix(rot) if rot else np.eye(3)
    R_yaw = Ry(yaw)
    c2 = R_yaw @ (c - np.array([ox, 0, oz])) + np.array([ox, 0, oz])
    shift = c2 - c
    e['from'] = [r3(v + s) for v, s in zip(el['from'], shift)]
    e['to'] = [r3(v + s) for v, s in zip(el['to'], shift)]
    a = euler_xyz(R_yaw @ R_old)
    e['rotation'] = {'origin': [r3(v) for v in c2], 'x': r3(a[0]), 'y': r3(a[1]), 'z': r3(a[2])}
    return e


def crane():
    """The hearth's crane, a piece that swings about its post's vertical (CRANE_POST): the post (a square iron bar) with a collar at its foot
    and a cap, the arm out over the fire with a brace under it, and the lug at the arm's end that the chain goes up into. At swing 0 the arm
    points at the middle of the cell."""
    px, pz = CRANE_POST
    ang = CRANE_HEADING
    L = CRANE_ARM
    base_y = HEARTH_TOP + 0.4
    els = [
        cube((px - 0.6, base_y, pz - 0.6), (px + 0.6, 15.4, pz + 0.6), 'iron'),
        cube((px - 0.9, 15.0, pz - 0.9), (px + 0.9, 15.8, pz + 0.9), 'iron_band'),
        cube((px - 1.0, base_y, pz - 1.0), (px + 1.0, base_y + 1.0, pz + 1.0), 'iron_band'),
        _arm_piece((0.0, 14.4, -0.55), (L + 1.1, 15.4, 0.55), 'iron', ang, px, pz),
        _arm_piece((L - 1.2, HANG_Y, -1.2), (L + 1.2, 14.4, 1.2), 'iron_band', ang, px, pz),
    ]
    p0 = np.array([px + 0.6, 8.2, pz])
    p1 = np.array([px + L * 0.62, 14.3, pz])
    els.append(turned_about(along(p0, p1, 0.9, 0.9, 'iron'), -ang, px, pz))
    return els


# ================================================================ the models

def models():
    """{name: (elements, particle texture, ambientocclusion)} for every model of the Stewpot."""
    out = {}
    for lit in (False, True):
        suffix = '_lit' if lit else ''
        out['stewpot_camp' + suffix] = (camp(lit), 'firestone', not lit)
        out['stewpot_hearth' + suffix] = (hearth(lit), 'flagstone', not lit)
    out['stewpot_camp_burnt'] = (camp_burnt(), 'soot_wall', True)
    out['stewpot_hearth_burnt'] = (hearth_burnt(), 'soot_wall', True)
    out['cook_stewpot_pot'] = (pot(), 'pot_iron', True)
    for s in (1, 2, 3):
        out['cook_stewpot_soot_%d' % s] = (pot_soot(s), 'soot_%d' % s, True)
    out['cook_stewpot_crane'] = (crane(), 'iron', True)
    return out
