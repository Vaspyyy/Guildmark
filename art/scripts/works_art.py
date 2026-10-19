"""
The town works' blocks (M6, influence: living-world/design-influence.md, sections 3, 5 and 8): the works post with its
drawing and seals, the patron's bronze plaque (bare, and under its cloth), the yard's displays (timber stack, stone
pile, glass in straw crates, iron bars, wool bales) heaped by how much is there, the mortar tub, the hoist on the
scaffold, the beacon brazier (out, lit and flaring), the alms table (its trestle's three blocks, and the pot, its stew,
the bowls and the ladle on its middle), the "To let" board and the empty display's slate. Run from the repo root:

    python3 -B art/scripts/works_art.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/works art/models [preview-dir]

Writes their block models, blockstates and 32x textures (HD, as the rest of the mod's blocks) under the assets; the
textures of what their renderers draw (textures/entity/works/: the yard's pieces, the hoist's pulley, rope, hook and
load, the "To let" board, the slate, the alms table's pot, stew, bowls and ladle, the post's parchments, seals and
stamp, the beacon's glow); WorksMesh.kt (those pieces' meshes) and WorksArt.kt (the numbers the renderers heap, fill,
seal, swing, haul, ladle and letter by) into the Kotlin folder; and each mesh's .bbmodel into art/models. None is
registered here: Part B registers the works' own blocks (their blockstates are written here, by the ids proposed), and
the alms table and the "To let" board are Part D2's blocks with D2's blockstates, which take these models
(living-world/works-art.md says what each needs). The clips that work at them (works_clips.py, street_clips.py) take
their numbers from here, so a hoe goes into the mortar and a torch into the coals.

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

# The alms table (townsfolk/streets/AlmsTableBlock): a trestle three blocks long (its TablePart left, middle and right,
# from the keeper's left), the keeper behind its middle (south of it, facing north) and whoever comes for a bowl in front
# of it (north, facing south), its top ALMS_TOP_Y up. On its middle block, the pot on the keeper's right and the stack of
# bowls on their left: the renderer's (WorksMesh.alms()), at these places in that block's model space.
ALMS_TOP_Y = 13.0
POT_AT = (11.5, 11.0)                   # model (x, z): the pot's middle, on the keeper's side (south) and their right (east)
POT_R = 3.6                             # its inside's radius at the rim (its wall POT_WALL thick)
POT_WALL = 0.6
POT_RIM_Y = ALMS_TOP_Y + 5.0
POT_INSIDE_Y = ALMS_TOP_Y + 0.5         # its inside's floor
POT_FLOOR_Y = ALMS_TOP_Y + 1.2          # the stew's lowest (one ladle's worth) and its highest, a little under the rim
POT_FULL_Y = POT_RIM_Y - 0.9
# The ladle left standing in the pot (the table's model draws it there, and LADLE takes it up and stands it back just
# so): leaning LADLE_LEAN degrees with its handle's top to the keeper's right (east), turned about the north-south line,
# its bowl's middle over model (x, z) LADLE_AT, its lowest corner LADLE_CLEAR over the pot's floor. Its shape is
# living_props.py's ladle's.
LADLE_LEAN = 22.5
LADLE_AT = (11.0, 11.4)
LADLE_CLEAR = 0.25
BOWLS_AT = (4.0, 11.0)                  # model (x, z): the stack of bowls' middle, on the keeper's left (west)
BOWL_TALL = 1.9                         # a bowl's height (the stew bowl's), and each more on the stack adds BOWL_H (they
BOWL_H = 1.2                            # nest)
BOWLS_MAX = 3                           # the most the stack shows (LADLE takes the top one off a stack of three)
BOWL_W = 4.5                            # a bowl across: the held one's (living_props.py's bowl_empty), square as it is
POT_LEVELS = 8                          # the clips' checks' levels (the renderer's stew rises and falls freely)
ALMS_TOP = float(clip_pt(0, ALMS_TOP_Y, 0)[1])                                   # clip y of the top: 11
POT_MOUTH = clip_pt(POT_AT[0], POT_RIM_Y, POT_AT[1], bz=-1)                     # the keeper's clip: the pot's rim's middle
BOWLS_TOP = clip_pt(BOWLS_AT[0], ALMS_TOP_Y + BOWL_TALL + BOWL_H * 2, BOWLS_AT[1], bz=-1)   # a stack of three's top
BOWL_TAKEN = clip_pt(BOWLS_AT[0], ALMS_TOP_Y + BOWL_H * 2, BOWLS_AT[1], bz=-1)              # its top bowl's bottom
ALMS_GAP = 32.0                         # keeper to taker, middle to middle (the table between them)


def pot_level_y(level):
    """The stew's top (model y) at [level] of POT_LEVELS (0: empty, none shown): what the clips check the ladle by."""
    if level <= 0:
        return None
    return POT_FLOOR_Y + (POT_FULL_Y - POT_FLOOR_Y) * (level - 1) / (POT_LEVELS - 1)


# The ladle standing in the pot, as the clips have it (street_clips.py's LA_REST_R and LA_REST_P, worked out here from
# the same numbers so the table's model and LADLE's first and last frames agree): its shape is living_props.py's
# ladle's, its bowl's box from (-1.3, 5.4, -0.4) to (1.3, 7.4, 2.2) and its handle's from (-0.35, -1.2, -0.35) to
# (0.35, 5.6, 0.35) in ladle space (y down from the fist to the bowl).
LADLE_BOWL_PT = np.array([0.0, 6.4, 0.9])
LADLE_CUBES = [((-0.35, -1.2, -0.35), (0.7, 6.8, 0.7), 'handle'), ((-1.3, 5.4, -0.4), (2.6, 2.0, 2.6), 'bowl')]


def ladle_turn(tip):
    """The ladle's turn in the keeper's clip space: upright, tipped [tip] degrees to their right about the line ahead."""
    a = math.radians(tip)
    return np.column_stack([np.array([0.0, 0.0, 1.0]), np.array([math.sin(a), math.cos(a), 0.0]),
                            np.array([-math.cos(a), math.sin(a), 0.0])])


def ladle_rest():
    """The standing ladle's turn and its bowl's middle in the keeper's clip space (the table the block north of them)."""
    R = ladle_turn(LADLE_LEAN)
    o, s, _ = LADLE_CUBES[1]
    corners = [np.array([x, y, z]) for x in (o[0], o[0] + s[0]) for y in (o[1], o[1] + s[1]) for z in (o[2], o[2] + s[2])]
    low = max(float((R @ (c - LADLE_BOWL_PT))[1]) for c in corners)
    at = clip_pt(LADLE_AT[0], 0.0, LADLE_AT[1], bz=-1)
    return R, np.array([at[0], 24.0 - POT_INSIDE_Y - LADLE_CLEAR - low, at[2]])


# ================================================================== painting (32x: two texels to a model pixel)

from PIL import Image, ImageDraw, ImageFilter  # noqa: E402
from hdkit import (PAL, Canvas, grid, vnoise, fbm, rand, tone, ramp, bands, poly, seg_dist, disc, ellipse, rect,  # noqa: E402
                   blur, grow, edge, dist_in, light, cavity, outline, glint, bevel, sheet)
import island_blockview as BV  # noqa: E402
import market_board_letters as ML  # noqa: E402

T = 32                                  # texels across a block face
ALL = np.ones((T, T), bool)

OAK = [(30, 21, 18), (56, 40, 30), (84, 61, 43), (112, 83, 58), (140, 108, 76), (170, 136, 98)]          # the works' oak
FRESH = [(62, 44, 30), (106, 76, 50), (146, 108, 70), (180, 140, 94), (206, 170, 120), (228, 200, 152)]    # new-sawn
PINE = [(40, 30, 26), (72, 56, 42), (106, 84, 60), (138, 112, 80), (168, 140, 102), (196, 170, 130)]       # boards
GREY = [(32, 30, 32), (58, 54, 52), (88, 82, 76), (118, 110, 100), (148, 140, 126), (176, 168, 152)]       # old boards
IRONS = [(14, 14, 18), (28, 28, 34), (46, 46, 52), (68, 68, 72), (98, 96, 96), (136, 132, 126)]           # black iron
BRONZE = [(28, 16, 12), (62, 36, 22), (102, 62, 32), (146, 96, 48), (190, 138, 74), (226, 186, 118)]
VERDIGRIS = (74, 120, 98)
VELVET = [(48, 6, 14), (84, 10, 22), (118, 18, 32), (150, 30, 42), (186, 54, 64), (214, 92, 96)]
GILT = [(70, 46, 18), (122, 86, 30), (170, 128, 46), (212, 170, 70), (240, 210, 118)]
MORTAR = [(66, 62, 58), (104, 100, 92), (140, 134, 122), (172, 166, 152), (200, 194, 178), (226, 220, 204)]
CHAR = [(10, 9, 10), (22, 20, 22), (38, 35, 36), (58, 54, 52), (86, 80, 76), (120, 114, 108)]             # charcoal, ash
EMBER = PAL['ember']
STEW = [(40, 18, 10), (74, 36, 18), (108, 58, 28), (140, 82, 40), (172, 112, 58), (204, 148, 86)]
BOWLWOOD = [(66, 46, 30), (110, 80, 52), (150, 116, 76), (184, 150, 104), (210, 180, 132), (230, 206, 160)]  # the props' PALE
ROPE = [(52, 40, 26), (92, 72, 46), (128, 104, 68), (160, 134, 92), (190, 164, 118), (214, 192, 146)]
STRAW = [(84, 62, 26), (132, 102, 44), (172, 140, 64), (204, 172, 88), (228, 200, 120), (244, 226, 160)]
CANVAS = [(76, 70, 58), (122, 114, 96), (160, 150, 126), (192, 182, 156), (214, 206, 180), (234, 228, 208)]
GREEN = [(14, 22, 20), (24, 38, 34), (36, 56, 48), (50, 74, 62), (68, 94, 78)]                             # the board's paint
CREAM = (222, 210, 176)


def wood(pal, seed, vertical=True, checks=2, knots=1, lift=0.0, warp=5.0):
    """Plain wood with the grain along it (up the texture if [vertical]): streaks that wander a little, fine fibre, a
    knot or two with the grain bending round them, and [checks]: thin dark cracks running with the grain, lit on one side."""
    xs, ys = grid(T)
    a, b = (xs, ys) if vertical else (ys, xs)
    wv = fbm(T, T, 10, seed) * warp
    t = 0.5 + lift + 0.16 * (np.sin((a + wv) * 1.9) * 0.5) + 0.07 * (vnoise(T, T, 2, seed + 7) - 0.5) \
        + 0.14 * (fbm(T, T, 6, seed + 3) - 0.5)
    rng = np.random.default_rng(seed)
    for _ in range(knots):
        ka, kb = rng.uniform(5, T - 5), rng.uniform(5, T - 5)
        d = np.hypot(a - ka, (b - kb) * 0.55)
        t = t - 0.3 * np.exp(-d * d * 1.2) + 0.06 * np.sin(d * 5.0) * np.exp(-d * 0.6)
    for _ in range(checks):
        x0, y0, L = rng.uniform(4, T - 4), rng.uniform(-4, T - 10), rng.uniform(9, 18)
        run = (b > y0) & (b < y0 + L)
        off = a - x0 - 0.7 * np.sin(b * 0.37 + x0)
        t = np.where(run & (np.abs(off) < 0.5), t - 0.34, t)
        t = np.where(run & (np.abs(off - 1.0) < 0.5), t + 0.07, t)
    cv = Canvas(T)
    cv.put(ALL, bands(pal, t, 0.3))
    return cv


def end_grain(pal, seed, cx=16.0, cy=16.0, sawn=True):
    """A timber's end: growth rings round its heart, a check or two split out from it, and the saw's marks across."""
    xs, ys = grid(T)
    r = np.hypot(xs - cx, ys - cy)
    ang = np.arctan2(ys - cy, xs - cx)
    wob = fbm(T, T, 8, seed) * 2.5
    t = 0.52 + 0.11 * np.sin((r + wob) * 1.6) + 0.08 * (fbm(T, T, 4, seed + 1) - 0.5) - 0.006 * r
    rng = np.random.default_rng(seed)
    for _ in range(2):
        a0 = rng.uniform(-math.pi, math.pi)
        da = np.angle(np.exp(1j * (ang - a0)))
        t = np.where((np.abs(da) < 0.08) & (r > 1.5) & (r < rng.uniform(8, 14)), t - 0.3, t)
    if sawn:
        t = t + 0.05 * np.sin(ys * 2.4 + 0.6 * np.sin(xs * 0.3))
    cv = Canvas(T)
    cv.put(ALL, bands(pal, t, 0.3))
    return cv


def boards(pal, seed, height=8, vertical=False, gap=0.34, nails=(), lift=0.0):
    """Boards [height] texels wide side by side, each its own tone with the grain along it, a lit edge and a dark seam
    between; nail heads at the (along, across) texels in [nails]."""
    xs, ys = grid(T)
    along, across = (ys, xs) if vertical else (xs, ys)
    row = np.floor(across / height)
    within = across - row * height
    t = 0.5 + lift + 0.15 * (fbm(T, T, 4, seed) - 0.5) + 0.09 * np.sin(across * 2.3 + vnoise(T, T, 3, seed + 9) * 6.5 + row * 2.9)
    t += 0.07 * np.sin(row * 4.7 + seed * 1.3)
    t = np.where(within < 1.0, t + 0.1, t)
    t = np.where(within >= height - 1.0, t - gap, t)
    rng = np.random.default_rng(seed)
    for _ in range(2):
        kx, ky = rng.uniform(4, 28), rng.uniform(4, 28)
        d = np.hypot((along - kx) * 0.7, across - ky)
        t = np.where(d < 1.3, t - 0.26, np.where(d < 2.4, t + 0.05 * np.cos(d * 4), t))
    cv = Canvas(T)
    cv.put(ALL, bands(pal, t, 0.24))
    for a, b in nails:
        x, y = (b, a) if vertical else (a, b)
        nail(cv, x, y)
    return cv


def nail(cv, x, y, big=False):
    """An iron nail's head (2 texels; 3 [big]) at texel (x, y), lit on its upper left."""
    cv.px(x, y, IRONS[4])
    cv.px(x + 1, y, IRONS[2])
    cv.px(x, y + 1, IRONS[2])
    cv.px(x + 1, y + 1, IRONS[1])
    if big:
        for dx, dy in ((2, 0), (2, 1), (0, 2), (1, 2), (2, 2)):
            cv.px(x + dx, y + dy, IRONS[1])


def iron(seed=301, rust=0.3, hammered=True):
    """Black wrought iron, its face dented by the hammer and catching the light on the dents' rims, rust blooming in the
    hollows."""
    n = fbm(T, T, 4, seed)
    H = n * 1.4 + (vnoise(T, T, 3, seed + 1) - 0.5) * (1.6 if hammered else 0.4)
    t = 0.44 + 0.22 * (n - 0.5) + 1.1 * light(H, 1.3)
    cv = Canvas(T)
    cv.put(ALL, bands(IRONS, t, 0.3))
    if rust:
        hollow = cavity(H, 2) > 0.05
        bloom = (fbm(T, T, 5, seed + 3) > 0.58) | hollow
        cv.over(bloom & (rand(T, T, seed + 5) > 0.35), (104, 58, 34), rust)
    return cv


def bronze_metal(seed=401):
    """Cast bronze: warm, smooth, darker where it dips, verdigris in the hollows."""
    n = fbm(T, T, 6, seed)
    t = 0.56 + 0.22 * (n - 0.5) + 0.06 * (vnoise(T, T, 2, seed + 1) - 0.5)
    cv = Canvas(T)
    cv.put(ALL, bands(BRONZE, t, 0.3))
    cv.over((fbm(T, T, 4, seed + 2) > 0.66) & (rand(T, T, seed + 3) > 0.4), VERDIGRIS, 0.4)
    return cv


def rope_tex(seed=501, vertical=True):
    """Twisted hemp: three strands laid in a slant, each lit along its top, a few loose fibres."""
    xs, ys = grid(T)
    a, b = (xs, ys) if vertical else (ys, xs)
    u = (b * 0.5 + a * 0.75) % 4.0
    t = 0.62 - 0.3 * np.abs(u - 2.0) / 2.0 + 0.08 * (fbm(T, T, 3, seed) - 0.5)
    t = np.where(u < 0.6, t - 0.22, t)
    cv = Canvas(T)
    cv.put(ALL, bands(ROPE, t, 0.25))
    cv.over(rand(T, T, seed + 1) > 0.95, ROPE[5], 0.6)
    return cv


def straw_tex(seed=601, density=46):
    """Loose straw: stalks lying every way over one another, lit along their length, the dark between."""
    xs, ys = grid(T)
    cv = Canvas(T)
    cv.put(ALL, bands(STRAW, 0.18 + 0.12 * fbm(T, T, 4, seed), 0.3))
    rng = np.random.default_rng(seed)
    for i in range(density):
        x0, y0 = rng.uniform(-4, T + 4), rng.uniform(-4, T + 4)
        ang = rng.normal(0.4, 0.7)
        L = rng.uniform(6, 15)
        x1, y1 = x0 + math.cos(ang) * L, y0 + math.sin(ang) * L
        for ox in (-T, 0, T):
            for oy in (-T, 0, T):
                d, f = seg_dist(x0 + ox, y0 + oy, x1 + ox, y1 + oy, T)
                m = d < 0.75
                if m.any():
                    k = rng.uniform(0.45, 0.85)
                    cv.put(m, bands(STRAW, np.full((T, T), k) + 0.12 * np.cos(f * 7.0) - 0.25 * (d > 0.4), 0.2))
    return cv


def canvas_tex(seed=701, seams=True):
    """Undyed canvas: a tight plain weave, seamed down its length now and then, the folds soft."""
    xs, ys = grid(T)
    weave = ((np.floor(xs) + np.floor(ys)) % 2) * 0.04
    t = 0.62 + weave + 0.1 * (fbm(T, T, 6, seed) - 0.5) + 0.06 * np.sin(xs * 0.9 + fbm(T, T, 8, seed + 1) * 3.0)
    if seams:
        t = np.where(np.abs(xs - 15.5) < 0.6, t - 0.18, t)
        t = np.where((np.abs(xs - 15.5) < 0.6) & (ys % 3 < 1), t + 0.12, t)
    cv = Canvas(T)
    cv.put(ALL, bands(CANVAS, t, 0.22))
    return cv


def burlap(seed=801):
    """Coarse sacking for the bales: thick threads in a loose weave, slubs here and there."""
    xs, ys = grid(T)
    over = ((np.floor(xs) + np.floor(ys)) % 2) == 0
    thread = np.where(over, np.sin(xs * math.pi) ** 2 * 0.6 + 0.4, np.sin(ys * math.pi) ** 2 * 0.6 + 0.4)
    t = 0.48 + 0.18 * (thread - 0.7) + 0.16 * (fbm(T, T, 6, seed) - 0.5)
    s = rand(T, T, seed + 1)
    t = np.where(s > 0.95, t + 0.12, np.where(s < 0.04, t - 0.12, t))
    cv = Canvas(T)
    cv.put(ALL, bands([(44, 34, 24), (78, 62, 44), (112, 92, 64), (142, 120, 86), (170, 148, 108), (196, 176, 136)], t, 0.2))
    return cv


# ---------------------------------------------------------------- the post

def post_board():
    """The post's notice board: three upright boards of grey old pine, nailed to two battens behind (their nails in a
    row across, top and bottom), a few old tack holes where earlier notices hung."""
    cv = boards(GREY, 1101, height=11, vertical=True, gap=0.3, lift=0.04,
                nails=[(3, 4), (3, 15), (3, 26), (27, 4), (27, 15), (27, 26)])
    rng = np.random.default_rng(1103)
    for _ in range(9):
        x, y = int(rng.integers(3, 29)), int(rng.integers(3, 29))
        cv.px(x, y, GREY[0])
    return cv


BOX = (7.0, 4.5, 4.0)                   # the pledge box: px across, high and deep (its faces at the textures' top left)


def _box_boards(w, h, seed):
    """Dark oak boards across a face [w] x [h] texels, iron corner bands down both ends with a nail in each."""
    cv = boards(OAK, seed, height=5, gap=0.32, lift=-0.04)
    xs, ys = grid(T)
    band = ((xs < 2) | ((xs >= w - 2) & (xs < w))) & (ys < h)
    cv.put(band, bands(IRONS, 0.48 + 0.5 * light(fbm(T, T, 3, seed + 2) * 1.5, 1.0) + 0.08 * (ys < 1), 0.3))
    for y in (1, h - 3):
        cv.px(0, y, IRONS[4])
        cv.px(w - 2, y, IRONS[4])
    cv.a[(xs >= w) | (ys >= h)] = 0
    return cv


def box_side():
    """The pledge box's ends: its boards between the corner irons."""
    return _box_boards(int(BOX[2] * 2), int(BOX[1] * 2), 1201)


def box_front():
    """The box's front: its boards and corner irons, the lid's hasp hanging down over a black padlock in the middle
    with its keyhole, the iron round them rubbed bright by the hands that pledge."""
    w, h = int(BOX[0] * 2), int(BOX[1] * 2)
    cv = _box_boards(w, h, 1221)
    cx = w // 2
    cv.put(rect(cx - 1, 0, cx + 1, 4, T), IRONS[3])
    cv.px(cx - 1, 0, IRONS[5])
    cv.put(rect(cx - 2, 4, cx + 2, 8, T), IRONS[1])
    cv.put(rect(cx - 2, 4, cx + 2, 5, T), IRONS[4])
    cv.px(cx - 2, 5, IRONS[3])
    cv.px(cx, 6, (6, 4, 6))
    cv.px(cx - 1, 3, IRONS[2])
    cv.px(cx, 3, IRONS[2])
    return cv


def box_lid():
    """The box's lid from above: oak, an iron plate in its middle with the slot cut through it, worn bright by coins."""
    w, h = int(BOX[0] * 2), int(BOX[2] * 2)
    cv = _box_boards(w, h, 1211)
    cx, cy = w // 2, h // 2
    cv.put(rect(cx - 4, cy - 2, cx + 4, cy + 2, T), bands(IRONS, 0.55 + 0.12 * fbm(T, T, 2, 1213), 0.3))
    cv.put(rect(cx - 3, cy - 1, cx + 3, cy, T), (8, 6, 8))
    cv.put(rect(cx - 3, cy, cx + 3, cy + 1, T), IRONS[5])
    for x, y in ((cx - 4, cy - 2), (cx + 3, cy - 2), (cx - 4, cy + 1), (cx + 3, cy + 1)):
        cv.px(x, y, IRONS[4])
    cv.a[(grid(T)[0] >= w) | (grid(T)[1] >= h)] = 0
    return cv


# ---------------------------------------------------------------- the plaque

PLAQUE_TPX = 4                          # plaque.png's texels to a model pixel (finer than the rest, for the casting)


def plaque_face():
    """The patron's plaque, cast in bronze (64 x 64, its 12 x 10 face at the top left): a raised frame chamfered at its
    edge, a rivet in each corner, a bead round its inside; a sunk field for the lettering (the renderer writes it), the
    frame's shadow along the field's top and left; between the first two lines a raised rule with a lozenge in its
    middle; a little verdigris gathered where the field meets the frame."""
    N = 16 * PLAQUE_TPX
    W, H = 12 * PLAQUE_TPX, 10 * PLAQUE_TPX
    xs, ys = grid(N)
    n = fbm(N, N, 8, 1301)
    face = rect(0, 0, W, H, N)
    t = 0.74 + 0.08 * (n - 0.5) - 0.06 * ((xs + ys) / (W + H) - 0.5)
    field = rect(7, 7, W - 7, H - 7, N)
    t = np.where(field, 0.56 + 0.05 * (n - 0.5) + 0.03 * (vnoise(N, N, 2, 1303) - 0.5), t)
    # The chamfer at the plate's edge, the bead inside the frame, the field's sunk edges
    t = np.where(face & ((xs < 1) | (ys < 1)), 0.97, t)
    t = np.where(face & ((xs > W - 1) | (ys > H - 1)), 0.22, t)
    bead = rect(5, 5, W - 5, H - 5, N) & ~rect(6, 6, W - 6, H - 6, N)
    t = np.where(bead & ((xs < W - 5.5) & (ys < H - 5.5)) & ((xs < 6) | (ys < 6)), 0.95, t)
    t = np.where(bead & ((xs > W - 6) | (ys > H - 6)), 0.36, t)
    t = np.where(field & ((xs < 8) | (ys < 8)), 0.36, t)
    t = np.where(field & ((xs > W - 8) | (ys > H - 8)) & ~((xs < 8) | (ys < 8)), 0.72, t)
    cv = Canvas(N)
    cv.put(face, bands(BRONZE, t, 0.0))
    # The rule between the lines, with its lozenge
    ry = int(round((PLAQUE_HI[1] - PLAQUE_RULE_Y) * PLAQUE_TPX))
    cv.put(rect(12, ry, W - 12, ry + 1, N), BRONZE[5])
    cv.put(rect(12, ry + 1, W - 12, ry + 2, N), BRONZE[2])
    cx = W / 2
    d = np.abs(xs - cx) + np.abs(ys - (ry + 1)) * 1.2
    cv.put(d < 3.2, BRONZE[4])
    cv.put((d < 3.2) & (xs > cx) & (ys > ry + 1), BRONZE[2])
    cv.put((d < 3.2) & (xs < cx) & (ys < ry + 1), BRONZE[5])
    # A dome-headed rivet in each corner of the frame
    for rx, rr in ((3, 3), (W - 3, 3), (3, H - 3), (W - 3, H - 3)):
        dd = np.hypot(xs - rx, ys - rr)
        cv.put(dd < 1.9, BRONZE[3])
        cv.put((dd < 1.9) & (xs - rx + ys - rr > 0.8), BRONZE[1])
        cv.put(np.hypot(xs - rx + 0.6, ys - rr + 0.6) < 0.9, BRONZE[5])
    green = field & (dist_in(field, 3) <= 2) & (rand(N, N, 1305) > 0.6) & (fbm(N, N, 6, 1307) > 0.45)
    cv.over(green, VERDIGRIS, 0.35)
    cv.a[~face] = 0
    return cv


# The lettering (written by the renderer) and the rule, on the plaque's face: model y of each line's baseline, its cap
# height and the most width it may take (px), the line above the name ("Raised for the town by"), the patron's name
# and the line under it ("and 3 others"); the rule's model y (between the first two lines).
PLAQUE_RULE_Y = 9.5
PLAQUE_LINES = {'above': (10.3, 0.9, 9.0), 'name': (6.9, 2.0, 9.4), 'below': (5.1, 0.9, 9.0)}


def velvet():
    """The cloth over the plaque, as UNVEIL's (living_props.py's veil): crimson velvet with a soft sheen across its
    folds, a gilt border down each side and a gilt fringe along its foot, 28 x 26 texels at the top left."""
    W, H = 28, 26
    xs, ys = grid(T)
    U, V = xs / W, ys / H
    sheen = np.sin(U * 5.0 + 1.3) * 0.5 + 0.5
    fold = np.sin(U * 9.0 + 2.0 * vnoise(T, T, 8, 1401)) * 0.5
    t = 0.38 + 0.3 * sheen * 0.6 + 0.12 * fold + 0.05 * (rand(T, T, 1403) - 0.5) - 0.1 * V
    cv = Canvas(T)
    cv.put(rect(0, 0, W, H, T), bands(VELVET, t, 0.3))
    border = rect(0, 0, 2, H, T) | rect(W - 2, 0, W, H, T)
    cv.put(border, bands(GILT, 0.55 + 0.25 * np.sin(ys * 1.7) * 0.5 + 0.1 * (rand(T, T, 1405) - 0.5), 0.3))
    fringe = rect(0, H - 4, W, H, T)
    cv.put(fringe, bands(GILT, 0.7 - 0.12 * (ys - (H - 4)), 0.3))
    tassel = fringe & (ys >= H - 2) & ((np.floor(xs) % 2) == 1)
    cv.a[tassel] = 0
    cv.put(rect(0, H - 5, W, H - 4, T), GILT[1])
    cv.a[~rect(0, 0, W, H, T)] = 0
    return cv


# ---------------------------------------------------------------- the mortar tub

MORTAR_FRAMES = 8


def mortar_frames():
    """The mortar's top, as the hoe leaves it: heaped in soft ridges across the tub, grey-white lime with grit in it,
    wet and glistening; the wet spots catch the light in turn (a loop of MORTAR_FRAMES, slow and blended)."""
    xs, ys = grid(T)
    ridge = np.sin(xs * 0.42 + 2.2 * fbm(T, T, 10, 1501)) * 0.5 + 0.35 * np.sin(ys * 0.31 + xs * 0.12)
    H = ridge * 1.2 + fbm(T, T, 5, 1503) * 1.4
    base = 0.56 + 0.5 * light(H, 1.0) + 0.08 * (fbm(T, T, 3, 1505) - 0.5)
    grit = rand(T, T, 1507)
    wet = fbm(T, T, 6, 1509)
    sheet_ = Canvas(T, T * MORTAR_FRAMES)
    for f in range(MORTAR_FRAMES):
        ph = f / MORTAR_FRAMES * math.tau
        shine = np.clip((wet - 0.55) * 4.0, 0, 1) * (0.5 + 0.5 * np.sin(ph + wet * 9.0)) * np.clip(light(H, 1.6) * 6 + 0.4, 0, 1)
        t = base + 0.22 * shine
        cv = Canvas(T)
        cv.put(ALL, bands(MORTAR, t, 0.3))
        cv.over(grit > 0.93, MORTAR[1], 0.6)
        cv.over(grit < 0.03, MORTAR[5], 0.5)
        sheet_.rgb[f * T:(f + 1) * T] = cv.rgb
        sheet_.a[f * T:(f + 1) * T] = 1.0
    return sheet_


def tub_staves():
    """The tub's sides: upright staves of weathered oak, mortar slopped down them from the rim and dried pale."""
    cv = boards(OAK, 1601, height=6, vertical=True, gap=0.32, lift=0.02)
    xs, ys = grid(T)
    drip = np.zeros((T, T), bool)
    rng = np.random.default_rng(1603)
    for _ in range(7):
        x0, L, w = rng.uniform(1, 31), rng.uniform(2, 11), rng.uniform(0.6, 1.6)
        drip |= (np.abs(xs - x0) < w * (1 - ys / max(L, 1)) + 0.3) & (ys < L)
    drip |= ys < 1.5
    cv.put(drip, bands(MORTAR, 0.62 + 0.15 * fbm(T, T, 3, 1605) - 0.12 * (ys > 3), 0.3))
    return cv


# ---------------------------------------------------------------- the beacon

FLAME_FRAMES = 8


def coals(lit, frame=0):
    """The brazier's coals from above: lumps of charcoal heaped in the basket; cold, black with grey ash on them; lit,
    the cracks between them glowing and the lumps' edges burning orange, the glow breathing (by [frame])."""
    xs, ys = grid(T)
    rng = np.random.default_rng(1701)
    pts = []
    while len(pts) < 30:
        p = rng.uniform(0, T, 2)
        # Lumps no closer than 4.2 texels (round the tile's wrap), so no sliver of a lump glows as a whole crack
        if all(min(abs(p[0] - q[0]), T - abs(p[0] - q[0])) ** 2 + min(abs(p[1] - q[1]), T - abs(p[1] - q[1])) ** 2 > 4.2 ** 2
               for q in pts):
            pts.append(p)
    pts = np.array(pts)
    best = np.full((T, T), 1e9)
    second = np.full((T, T), 1e9)
    owner = np.zeros((T, T), int)
    for i, (px_, py_) in enumerate(pts):
        for ox in (-T, 0, T):
            for oy in (-T, 0, T):
                d = np.hypot(xs - px_ - ox, ys - py_ - oy)
                closer = d < best
                second = np.where(closer, best, np.minimum(second, d))
                owner = np.where(closer, i, owner)
                best = np.where(closer, d, best)
    gap = second - best
    tilt = rng.uniform(-1, 1, (len(pts), 2))
    Hh = np.clip(gap, 0, 3.2) + tilt[owner, 0] * (xs % 6) * 0.06 + tilt[owner, 1] * (ys % 6) * 0.06
    t = 0.3 + 0.9 * light(Hh, 1.2) + 0.06 * tilt[owner, 0]
    cv = Canvas(T)
    cv.put(ALL, bands(CHAR, t, 0.3))
    ash = (fbm(T, T, 4, 1703) > 0.55) & (gap > 1.0)
    if not lit:
        cv.put(ash, bands(CHAR, 0.75 + 0.2 * rand(T, T, 1705), 0.3))
        cv.put(gap < 0.6, CHAR[0])
        return cv
    breathe = 0.5 + 0.5 * math.sin(frame / FLAME_FRAMES * math.tau)
    flick = vnoise(T, T, 5, 1707 + frame)
    crack = np.clip(1.0 - gap / (0.9 + 0.35 * breathe), 0, 1) ** 1.6
    cv.put(crack > 0.15, bands(EMBER, np.clip(0.35 + 0.65 * crack * (0.8 + 0.4 * flick), 0, 1), 0.4))
    # The lumps stay black, some glowing dull red through their skin of ash as the fire breathes
    smoulder = (fbm(T, T, 6, 1711) > 0.52) & (gap > 0.9)
    cv.over(smoulder, EMBER[1], 0.3 + 0.4 * breathe * flick)
    cv.over(ash & (rand(T, T, 1709) > 0.55), CHAR[5], 0.4)
    return cv


def flame_frames(tall=False, seed=1801):
    """Flames licking up out of the coals: tongues that rise and sway, red at their edges, yellow-white at the heart,
    every tongue a whole number of cycles a loop so the strip runs on. [tall]: the flare's, higher and wilder."""
    rng = np.random.default_rng(seed + (7 if tall else 0))
    tongues = []
    n = 9 if tall else 7
    for i in range(n):
        tongues.append((4.0 + i * (24.0 / (n - 1)) + rng.uniform(-1.0, 1.0), rng.uniform(0, math.tau), int(rng.choice((1, 2))),
                        rng.uniform(26, 31) if tall else rng.uniform(15, 27)))
    xs, ys = grid(T)
    lift = T - ys
    sheet_ = Canvas(T, T * FLAME_FRAMES)
    for f in range(FLAME_FRAMES):
        t = f / FLAME_FRAMES * math.tau
        heat = np.zeros((T, T))
        for cx, ph, speed, top0 in tongues:
            top = top0 * (0.78 + 0.22 * math.sin(t * speed + ph))
            k = np.clip(lift / top, 0, 1)
            sway = np.sin(t * speed + ph + k * 2.6) * (3.0 if tall else 2.2) * k
            half = (3.8 if tall else 3.0) * (1 - k) ** 0.6 + 0.7
            heat += np.where(lift <= top, np.exp(-((xs - cx - sway) / half) ** 2) * (1 - k ** 1.4), 0)
        heat += np.clip(1 - lift / 7.0, 0, None) * np.exp(-((xs - 16) / 11) ** 2) * 0.95
        m = heat > 0.4
        layer = Canvas(T)
        layer.put(m, bands(EMBER[1:], np.clip((heat - 0.4) / (1.1 if tall else 1.25), 0, 1), 0.5))
        sheet_.rgb[f * T:(f + 1) * T] = layer.rgb
        sheet_.a[f * T:(f + 1) * T] = layer.a
    return sheet_


def basket_iron():
    """The fire basket's straps: black iron, scaled and burnt brown-red near the top where the fire licks it."""
    cv = iron(1901, rust=0.2)
    xs, ys = grid(T)
    cv.over(ys < 10, (92, 46, 30), 0.35 * (1 - ys / 10))
    return cv


# ---------------------------------------------------------------- the alms table

STEW_FRAMES = 8




def pot_iron():
    """The soup pot: black iron gone sooty from the fire, browner where it's been scrubbed, its rim a little brighter."""
    cv = iron(2101, rust=0.15, hammered=False)
    xs, ys = grid(T)
    cv.over(ys > 18, (6, 6, 8), 0.5 * (ys - 18) / 14)
    return cv








TABLE_PARTS = ('left', 'middle', 'right')      # TablePart's ids, from the keeper's left (west) to their right (east)
TABLE_Z = (1.0, 15.5)                          # the top's front edge (north, toward the hungry) and back (the keeper's)
TABLE_UNDER = ALMS_TOP_Y - 1.6                 # the boards' underside
TRESTLE_X = {'left': 1.6, 'right': 12.8}       # each end's trestle: its bearer's west side, in its own block (1.6 wide)
RAIL_Y, RAIL_Z = (3.9, 5.1), (7.4, 8.6)        # the long rail between the trestles, low down along the table's middle


def trestle_strip():
    """The table's top from above, all three of its blocks (96 x 32 texels, the keeper's left at the left): three long
    planed pine boards running its length, each its own tone with the grain wandering along it, a dark seam between
    them; nailed down over the trestle at each end; scrubbed paler round the middle where the pot and the bowls stand,
    with the pot's sooty ring and a stew stain or two."""
    W, H = 3 * T, T
    xs, ys = grid(W, H)
    z0, z1 = TABLE_Z[0] * 2, TABLE_Z[1] * 2                  # the boards run from the front edge to the back, in texels
    height = (z1 - z0) / 3.0
    row = np.clip(np.floor((ys - z0) / height), 0, 2)
    within = ys - z0 - row * height
    t = 0.5 + 0.04 + 0.13 * (fbm(W, H, 5, 2401) - 0.5) \
        + 0.08 * np.sin(ys * 2.1 + vnoise(W, H, 7, 2409) * 7.0 + row * 2.9 + 0.05 * xs)
    t += 0.07 * np.sin(row * 4.7 + 1.3)
    t = np.where(within < 1.0, t + 0.09, t)
    t = np.where(within >= height - 1.0, t - 0.3, t)
    rng = np.random.default_rng(2405)
    for _ in range(5):                                       # knots, the grain bending round them
        kx, ky = rng.uniform(6, W - 6), rng.uniform(z0 + 3, z1 - 3)
        d = np.hypot((xs - kx) * 0.6, ys - ky)
        t = np.where(d < 1.3, t - 0.26, np.where(d < 2.6, t + 0.05 * np.cos(d * 4), t))
    scrub = np.exp(-((xs - W / 2) / 26.0) ** 2) * (fbm(W, H, 6, 2403) > 0.45)
    t = t + 0.1 * scrub
    cv = Canvas(W, H)
    cv.put(np.ones((H, W), bool), bands(PINE, t, 0.24))
    # The pot's foot has left a sooty ring where it stands (the middle block's POT_AT), and the ladle drips
    px_, pz_ = (POT_AT[0] + 16.0) * 2, POT_AT[1] * 2
    r = np.hypot(xs - px_, ys - pz_)
    cv.over((np.abs(r - (POT_R + POT_WALL) * 2 + 0.6) < 0.9) & (rand(W, H, 2407) > 0.3), PINE[0], 0.35)
    for sx, sy, sr in ((px_ - 9.0, pz_ - 6.0, 1.3), (px_ - 4.0, pz_ + 7.5, 0.9), ((BOWLS_AT[0] + 16.0) * 2 + 4.0, pz_ - 9.0, 1.1)):
        cv.over(np.hypot(xs - sx, ys - sy) < sr, STEW[1], 0.4)
    for bx in (TRESTLE_X['left'] + 0.8, 32.0 + TRESTLE_X['right'] + 0.8):    # nailed down over each trestle's bearer
        for k in range(3):
            nail(cv, int(round(bx * 2)) - 1, int(round(z0 + (k + 0.5) * height)) - 1)
    return cv


def trestle_tops():
    """The strip cut into its three blocks' textures, alms_top_left, _middle and _right."""
    s = trestle_strip()
    out = {}
    for i, part in enumerate(TABLE_PARTS):
        cv = Canvas(T)
        cv.rgb = s.rgb[:, i * T:(i + 1) * T].copy()
        cv.a = s.a[:, i * T:(i + 1) * T].copy()
        out[f'alms_top_{part}'] = cv
    return out


# ---------------------------------------------------------------- the "To let" board, the slate

def letters(hand, text, px_per_font, tex_per_px, seed=0):
    """[text] in [hand] (market_board_letters.py's strokes), as alpha over texels at [tex_per_px] to a model pixel and
    [px_per_font] model pixels to a font pixel: (alpha array, width texels, baseline row)."""
    k = px_per_font * tex_per_px / ML.T          # atlas texels to ours
    x = 0.0
    pieces = []
    for ch in text:
        if ch == ' ':
            x += hand.advance(' ')
            continue
        strokes = hand.shape(ch)
        box = ML.glyph_box(hand, strokes, ch)
        a, _ = ML.raster(hand, ch, strokes, box, 3000 + seed * 31 + ord(ch))
        pieces.append((x + box[0], box[1], a))
        x += hand.advance(ch)
    top = min(p[1] for p in pieces)
    placed = []
    for gx, gy, a in pieces:
        im = Image.fromarray((a * 255).astype(np.uint8), 'L')
        w = max(1, int(round(a.shape[1] * k)))
        h = max(1, int(round(a.shape[0] * k)))
        im = np.asarray(im.resize((w, h), Image.LANCZOS), float) / 255.0
        placed.append((int(round(gx * px_per_font * tex_per_px)) + 2, int(round((gy - top) * px_per_font * tex_per_px)) + 2, im))
    lead = max(0, -min(ox for ox, _, _ in placed))
    placed = [(ox + lead, oy, im) for ox, oy, im in placed]
    # As wide and tall as the glyphs reach (a glyph's ink can run past its advance), and the advance at least
    W = max(int(math.ceil(x * px_per_font * tex_per_px)) + 4, max(ox + im.shape[1] for ox, _, im in placed) + 2)
    H = max(oy + im.shape[0] for _, oy, im in placed) + 2
    out = np.zeros((H, W))
    for ox, oy, im in placed:
        h, w = im.shape
        out[oy:oy + h, ox:ox + w] = np.maximum(out[oy:oy + h, ox:ox + w], im)
    base = int(round((ML.CAP - top) * px_per_font * tex_per_px)) + 2
    return np.clip(out, 0, 1), W, base


PAINT = ML.Hand('paint', slant=0.0, radius=0.78, edge=0.18, gap=1.1, jitter=0.03)


def charred():
    """Half-burnt sticks: black, cracked across the grain into the scales charcoal takes, grey ash on the scales' rims."""
    xs, ys = grid(T)
    cv = wood(CHAR, 1903, vertical=False, checks=2, knots=0, lift=-0.08)
    crack = (np.abs(np.sin(xs * 0.9 + 0.8 * fbm(T, T, 6, 1905) * 4)) < 0.16) | (np.abs(np.sin(ys * 1.4)) < 0.1)
    cv.put(crack, CHAR[0])
    cv.over(edge(~crack) & (rand(T, T, 1907) > 0.5), CHAR[5], 0.5)
    return cv


def pot_inside():
    """The pot's inside: iron gone black from the fire and the stew, a brown crust at the bottom."""
    cv = iron(2111, rust=0.0, hammered=False)
    cv.over(ALL, (10, 8, 8), 0.45)
    cv.over(fbm(T, T, 5, 2113) > 0.55, STEW[1], 0.5)
    return cv


# ================================================================== the block models

TEX_NS = 'guildmark:block/works/'
MODEL_NS = 'guildmark:block/works/'
SIX = ('down', 'up', 'north', 'south', 'west', 'east')
SIDES = ('north', 'south', 'west', 'east')


def r3(v):
    v = round(float(v), 3)
    return 0 if v == 0 else (int(v) if v == int(v) else v)


def Rx(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[1.0, 0, 0], [0, c, -s], [0, s, c]])


def Ry(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[c, 0, s], [0, 1.0, 0], [-s, 0, c]])


def Rz(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1.0]])


def euler_xyz(R):
    """(x, y, z) degrees with R = Rx(x) Ry(y) Rz(z): an element's `rotation`, which the game applies z first, then y,
    then x (island_art.py's)."""
    y = math.asin(max(-1.0, min(1.0, R[0, 2])))
    if abs(math.cos(y)) > 1e-6:
        return math.degrees(math.atan2(-R[1, 2], R[2, 2])), math.degrees(y), math.degrees(math.atan2(-R[0, 1], R[0, 0]))
    return math.degrees(math.atan2(R[2, 1], R[1, 1])), math.degrees(y), 0.0


def turn_to(v_from, v_to):
    """The rotation taking the direction [v_from] to [v_to] by the shortest turn (island_art.py's)."""
    a = np.asarray(v_from, float) / np.linalg.norm(v_from)
    b = np.asarray(v_to, float) / np.linalg.norm(v_to)
    k = np.cross(a, b)
    sn, cs = float(np.linalg.norm(k)), float(a @ b)
    if sn < 1e-9:
        return np.eye(3) if cs > 0 else Rx(180.0)
    k = k / sn
    K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + sn * K + (1 - cs) * (K @ K)


def _uv_default(frm, to, d):
    fx, fy, fz = frm
    tx, ty, tz = to
    return {'down': (fx, 16 - tz, tx, 16 - fz), 'up': (fx, fz, tx, tz),
            'north': (16 - tx, 16 - ty, 16 - fx, 16 - fy), 'south': (fx, 16 - ty, tx, 16 - fy),
            'west': (fz, 16 - ty, tz, 16 - fy), 'east': (16 - tz, 16 - ty, 16 - fz, 16 - fy)}[d]


def _into(a, b):
    """A uv span moved whole into the texture (0 to 16), keeping its texels to the pixel; squeezed only if it's longer."""
    span = b - a
    if span > 16.0:
        return 0.0, 16.0
    a2 = a % 16.0
    if a2 + span > 16.0 + 1e-6:
        a2 = 16.0 - span
    return a2, a2 + span


def uv_for(frm, to, d):
    """The uv a face takes by default, moved into the texture: so boards and grain run on from one element to the next,
    two texels to a pixel, wherever the element is."""
    u0, v0, u1, v1 = _uv_default(frm, to, d)
    u0, u1 = _into(u0, u1)
    v0, v1 = _into(v0, v1)
    return u0, v0, u1, v1


def cube(frm, to, tex, faces=SIX, uv=None, rot=None, origin=None, **extra):
    """An element from [frm] to [to] (px) with [faces], each in texture [tex] (a name, or {face: name} with '*' for the
    rest; None leaves the face out) at [uv] (None: uv_for's; one uv for all; or {face: uv} with '*'), turned by [rot] (a
    matrix, or (x, y, z) degrees as the game takes them) about [origin]; [extra]: light_emission and the like."""
    e = {'from': [r3(v) for v in frm], 'to': [r3(v) for v in to]}
    if rot is not None:
        ang = euler_xyz(rot) if isinstance(rot, np.ndarray) else rot
        if any(abs(a) > 1e-4 for a in ang):
            e['rotation'] = {'origin': [r3(v) for v in origin], 'x': r3(ang[0]), 'y': r3(ang[1]), 'z': r3(ang[2])}
    e.update(extra)
    fs = {}
    for d in faces:
        t = (tex.get(d, tex.get('*')) if isinstance(tex, dict) else tex)
        if t is None:
            continue
        u = uv.get(d, uv.get('*')) if isinstance(uv, dict) else uv
        if u is None:
            u = uv_for(frm, to, d)
        fs[d] = {'uv': [r3(v) for v in u], 'texture': '#' + t}
    e['faces'] = fs
    return e


def along(p0, p1, w, d, tex, faces=SIX, roll=0.0, uv=None, **extra):
    """A [w] x [d] bar from p0 to p1: laid up its y, rolled [roll] degrees about its length, then turned onto the line."""
    p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
    m = (p0 + p1) / 2
    L = float(np.linalg.norm(p1 - p0))
    R = turn_to((0, 1, 0), p1 - p0) @ Ry(roll)
    return cube(m - (w / 2, L / 2, d / 2), m + (w / 2, L / 2, d / 2), tex, faces, uv=uv, rot=R, origin=m, **extra)


def ring(cx, cz, y0, y1, r_out, thick, tex, inner=None, top=True, bottom=False, turn=0.0, uv=None):
    """An octagonal wall round (cx, cz): eight slabs, the outer faces at apothem [r_out], [thick] px thick, from y0 to
    y1; the inside in [inner] (or [tex]); turned [turn] degrees."""
    s = 2.0 * r_out * math.tan(math.pi / 8)
    slabs = [((cx - s / 2, cz - r_out), (cx + s / 2, cz - r_out + thick), 'north', 'south'),
             ((cx - s / 2, cz + r_out - thick), (cx + s / 2, cz + r_out), 'south', 'north'),
             ((cx - r_out, cz - s / 2), (cx - r_out + thick, cz + s / 2), 'west', 'east'),
             ((cx + r_out - thick, cz - s / 2), (cx + r_out, cz + s / 2), 'east', 'west')]
    els = []
    for diag in (0.0, 45.0):
        for (ax, az), (bx, bz), out, inn in slabs:
            fs = [out, inn] + (['up'] if top else []) + (['down'] if bottom else [])
            els.append(cube((ax, y0, az), (bx, y1, bz), {inn: inner or tex, '*': tex}, fs, uv=uv,
                            rot=(0.0, diag + turn, 0.0), origin=(cx, y0, cz)))
    return els


def disc(cx, cz, y0, y1, r, tex, top=None, faces=SIX, turn=0.0):
    """A filled octagon round (cx, cz), apothem [r], from y0 to y1: two squares, one turned 45 degrees (its top a hair
    lower so the two tops never fight)."""
    els = []
    for k, diag in enumerate((0.0, 45.0)):
        yt = y1 - 0.01 * k
        els.append(cube((cx - r, y0, cz - r), (cx + r, yt, cz + r), {'up': top or tex, '*': tex}, faces,
                        rot=(0.0, diag + turn, 0.0), origin=(cx, y0, cz)))
    return els


def model(textures, elements, particle, ao=True):
    m = {'parent': 'minecraft:block/block'}
    if not ao:
        m['ambientocclusion'] = False
    m['textures'] = {'particle': TEX_NS + particle} | {t: TEX_NS + t for t in textures}
    m['elements'] = elements
    return m


def used(elements):
    """The textures [elements] name, in order."""
    out = []
    for e in elements:
        for f in e['faces'].values():
            t = f['texture'][1:]
            if t not in out:
                out.append(t)
    return out


def finish(elements, particle, ao=True):
    return model(used(elements), elements, particle, ao)


# ---------------------------------------------------------------- the alms table

def alms_trestle(part):
    """One of the alms table's three blocks (TablePart [part], authored facing north as its blockstate turns it): the
    three long pine boards of its top running on from block to block, nailed down to a trestle under each end (a bearer
    under the boards, two splayed legs and a low cross-stretcher), and a long rail low down tying the two trestles
    together, through each and wedged on its far side. The pot, its stew, the bowls and the ladle on the middle block
    are the renderer's (WorksMesh.alms())."""
    z0, z1 = TABLE_Z
    x0 = 0.5 if part == 'left' else 0.0
    x1 = 15.5 if part == 'right' else 16.0
    ends = (['west'] if part == 'left' else []) + (['east'] if part == 'right' else [])
    top = {'up': f'alms_top_{part}', 'west': 'pine_end', 'east': 'pine_end', '*': 'pine'}
    els = [cube((x0, TABLE_UNDER, z0), (x1, ALMS_TOP_Y, z1), top, ['up', 'down', 'north', 'south'] + ends)]
    r0 = TRESTLE_X['left'] - 0.9 if part == 'left' else 0.0
    r1 = TRESTLE_X['right'] + 1.6 + 0.9 if part == 'right' else 16.0
    rail = cube((r0, RAIL_Y[0], RAIL_Z[0]), (r1, RAIL_Y[1], RAIL_Z[1]), {'west': 'oak_end', 'east': 'oak_end', '*': 'oak_h'},
                ['up', 'down', 'north', 'south'] + ends)
    els.append(rail)
    if part in TRESTLE_X:
        bx = TRESTLE_X[part]
        legs = ('down', 'north', 'south', 'west', 'east')
        els.append(cube((bx, 10.2, 1.8), (bx + 1.6, TABLE_UNDER, 14.7), {'north': 'oak_end', 'south': 'oak_end', '*': 'oak_h'}, legs))
        for zc, ang in ((3.4, 10.0), (12.6, -10.0)):
            els.append(cube((bx + 0.3, -0.3, zc - 0.65), (bx + 1.3, 10.3, zc + 0.65), 'oak', legs, rot=(ang, 0.0, 0.0),
                            origin=(bx + 0.8, 10.2, zc)))
        els.append(cube((bx + 0.4, 3.0, 2.2), (bx + 1.2, RAIL_Y[0], 13.8), 'oak_h'))
        # The rail's wedge, driven down through it on the trestle's far side
        wx = bx - 0.75 if part == 'left' else bx + 1.6 + 0.25
        els.append(cube((wx, RAIL_Y[0] - 0.7, 7.55), (wx + 0.5, RAIL_Y[1] + 0.9, 8.45), 'oak_h'))
    return finish(els, 'pine')


# ---------------------------------------------------------------- the beacon brazier

BRAZIER_LEGS = (0.0, 120.0, 240.0)       # the legs' bearings from north toward east: one ahead, two by the lighter


def brazier_frame():
    """The fire basket: an iron dish on three splayed legs with pad feet, a band round its foot, eight straps flaring out
    to the rim (BRAZIER_R out at BRAZIER_RIM_Y), the rim's ring."""
    c = np.array([8.0, 0.0, 8.0])
    els = []
    for b in BRAZIER_LEGS:
        d = np.array([math.sin(math.radians(b)), 0.0, -math.cos(math.radians(b))])
        top = c + d * 3.6 + (0, 9.0, 0)
        foot = c + d * 6.4
        els.append(along(foot + (0, 0.4, 0), top, 1.0, 1.0, 'brazier', roll=-b))
        els.append(cube(foot + (-0.9, 0, -0.9), foot + (0.9, 0.6, 0.9), 'brazier'))
    els += disc(8.0, 8.0, 8.6, 9.4, 4.5, 'brazier', faces=('down',) + SIDES)
    els += ring(8.0, 8.0, 8.8, 9.6, 4.75, 0.5, 'brazier', top=True, bottom=True)
    els += ring(8.0, 8.0, BRAZIER_RIM_Y - 0.6, BRAZIER_RIM_Y, BRAZIER_R + 0.1, 0.6, 'brazier', top=True, bottom=True)
    # The straps: the north one laid from the band to the rim, the rest turned round the middle by eighths
    p0 = np.array([8.0, 9.2, 8.0 - 4.45])
    p1 = np.array([8.0, BRAZIER_RIM_Y - 0.3, 8.0 - BRAZIER_R + 0.15])
    m = (p0 + p1) / 2
    L = float(np.linalg.norm(p1 - p0))
    tilt = -math.degrees(math.atan2(p0[2] - p1[2], p1[1] - p0[1]))
    mid = np.array([8.0, m[1], 8.0])
    for k in range(8):
        R = Ry(k * 45.0) @ Rx(tilt)
        mk = mid + Ry(k * 45.0) @ (m - mid)
        els.append(cube(mk - (0.4, L / 2, 0.25), mk + (0.4, L / 2, 0.25), 'brazier', SIX, rot=R, origin=mk))
    return els


def brazier_coals(lit):
    """The coals heaped in the basket to BRAZIER_COALS_Y, and two half-burnt sticks crossed on them."""
    t = 'coals_lit' if lit else 'coals'
    glow = dict(light_emission=12) if lit else {}
    els = []
    for e in disc(8.0, 8.0, 9.4, 12.4, 4.3, t, faces=('up',) + SIDES) + disc(8.0, 8.0, 12.4, BRAZIER_COALS_Y, 3.2, t,
                                                                            faces=('up',) + SIDES, turn=22.5):
        e.update(glow)
        els.append(e)
    els.append(along((3.6, 12.6, 6.2), (12.2, 13.5, 10.4), 1.2, 1.2, 'char', roll=20.0))
    els.append(along((5.4, 13.3, 12.5), (10.6, 12.5, 3.6), 1.1, 1.1, 'char', roll=-35.0))
    return els


def flame_sheets(tex, x0, x1, y0, y1, inner=0.0):
    """Four sheets of flame crossed over the coals, as a campfire's: two corner to corner, two across (inset [inner])."""
    lit = dict(light_emission=15, shade_direction_override='up')
    els = []
    for turn, (a0, a1) in ((45.0, (x0, x1)), (0.0, (x0 + inner, x1 - inner))):
        els.append(cube((a0, y0, 8.0), (a1, y1, 8.0), tex, ('north', 'south'), uv=(0, 0, 16, 16),
                        rot=(0.0, turn, 0.0), origin=(8.0, y0, 8.0), **lit))
        els.append(cube((8.0, y0, a0), (8.0, y1, a1), tex, ('west', 'east'), uv=(0, 0, 16, 16),
                        rot=(0.0, turn, 0.0), origin=(8.0, y0, 8.0), **lit))
    return els


def works_beacon(fire):
    """The beacon brazier [fire]: 'out' (cold coals and ash), 'lit' (the coals aglow, flames licking a block up out of
    the basket) or 'flare' (flames twice as wild, as high as a model may go)."""
    els = brazier_frame() + brazier_coals(fire != 'out')
    if fire == 'lit':
        els += flame_sheets('flame', 2.5, 13.5, 12.4, 28.4, inner=1.5)
    elif fire == 'flare':
        els += flame_sheets('flare', 0.5, 15.5, 12.4, 32.0, inner=1.0)
    return finish(els, 'brazier', ao=False)


# ---------------------------------------------------------------- the mortar tub

def works_mortar_tub():
    """A square tub of oak staves on the ground, iron-hooped twice, a cleat for a handle at each end, the mortar in it to
    TUB_MORTAR_Y (its top glistening as the hoe leaves it, mortar_frames), slopped over the rim and dried."""
    (x0, _, z0), (x1, y1, z1) = TUB_LO, TUB_HI
    t = 1.0
    walls = [((x0, 0, z0), (x1, y1, z0 + t)), ((x0, 0, z1 - t), (x1, y1, z1)),
             ((x0, 0, z0 + t), (x0 + t, y1, z1 - t)), ((x1 - t, 0, z0 + t), (x1, y1, z1 - t))]
    els = []
    for a, b in walls:
        w = max(b[0] - a[0], b[2] - a[2])
        els.append(cube(a, b, 'staves', ('up',) + SIDES, uv={'up': None, '*': (0, 0, min(w, 16), y1)}))
    els.append(cube((x0 + t, TUB_FLOOR_Y, z0 + t), (x1 - t, TUB_MORTAR_Y, z1 - t), 'mortar', ('up',), uv=(2, 2, 14, 14)))
    for ya, yb in ((1.2, 2.0), (4.9, 5.7)):
        els.append(cube((x0 - 0.15, ya, z0 - 0.15), (x1 + 0.15, yb, z1 + 0.15), 'hoop', SIDES, uv=(0, 2, 14.3, 2 + yb - ya)))
    for sx in (-1, 1):
        xa = x0 - 0.8 if sx < 0 else x1
        els.append(cube((xa, 5.6, 7.0), (xa + 0.8, 6.6, 9.0), 'oak'))
    return finish(els, 'staves')


# ---------------------------------------------------------------- the works post

POST_TOP = 28.0                         # the post's top: it stands a block and three quarters
BOARD_LO, BOARD_HI = (1.0, 11.0, 9.0), (15.0, 26.0, 10.0)      # the notice board on its front, its face toward north
BOX_LO = (4.5, 5.0, 6.0)                # the pledge box's low corner (BOX across, high and deep)


def works_post():
    """The proposal post: a square oak post knocked into the ground, a little pyramid cap on it; a notice board of grey
    pine on its front under a drip hood, on two battens; the iron-bound pledge box below it with its slot and padlock.
    The parchment (the works' drawing, its seals, the stamp) is the renderer's, on the board's face."""
    els = [cube((6.5, 0.0, 10.0), (9.5, POST_TOP, 13.0), {'up': 'oak_end', '*': 'oak'}),
           cube((6.0, POST_TOP, 9.5), (10.0, POST_TOP + 0.6, 13.5), 'oak'),
           cube((6.7, POST_TOP + 0.6, 10.2), (9.3, POST_TOP + 1.2, 12.8), {'up': 'oak_end', '*': 'oak'})]
    els.append(cube(BOARD_LO, BOARD_HI, {'north': 'post_board', '*': 'oak'}, SIX, uv={'north': (1, 0, 15, 15)}))
    for y in (13.0, 23.0):
        els.append(cube((1.5, y, BOARD_HI[2]), (14.5, y + 1.2, BOARD_HI[2] + 0.8), 'oak', ('up', 'down', 'south', 'west', 'east')))
    els.append(cube((0.4, 26.0, 6.4), (15.6, 26.8, 10.6), {'up': 'pine', '*': 'oak'}, rot=(-22.0, 0.0, 0.0), origin=(8.0, 26.4, 10.4)))
    for x in (1.2, 13.8):
        els.append(cube((x, 24.8, 9.0), (x + 1.0, 26.2, 10.0), 'oak', ('north', 'west', 'east', 'down')))
    bx, by, bz = BOX_LO
    els.append(cube(BOX_LO, (bx + BOX[0], by + BOX[1], bz + BOX[2]),
                    {'north': 'box_front', 'up': 'box_lid', '*': 'box_side'}, ('north', 'up', 'down', 'west', 'east'),
                    uv={'north': (0, 0, BOX[0], BOX[1]), 'up': (0, 0, BOX[0], BOX[2]), 'down': (0, 0, BOX[0], BOX[2]),
                        '*': (0, 0, BOX[2], BOX[1])}))
    return finish(els, 'oak')


# ---------------------------------------------------------------- the patron's plaque

def works_plaque(covered):
    """The patron's plaque: a plate of cast bronze on the wall (the wall its north side; its face, toward south, is the
    plaque texture's, the lettering the renderer's). [covered]: under the crimson cloth UNVEIL pulls off, which hangs
    from the plate's top edge back to the wall."""
    els = [cube(PLAQUE_LO, PLAQUE_HI, {'south': 'plaque', '*': 'bronze'}, ('south', 'up', 'down', 'west', 'east'),
                uv={'south': (0, 0, 12, 10), '*': (0, 0, 12, 1)})]
    if covered:
        (x0, y0, z0), (x1, y1, z1) = VEIL_LO, VEIL_HI
        els.append(cube(VEIL_LO, VEIL_HI, 'velvet', ('south', 'down', 'west', 'east'),
                        uv={'south': (0, 0, 14, 13), '*': (0, 12, 14, 12.3)}))
        els.append(cube((x0, y1 - 0.3, 0.0), (x1, y1, z0), 'velvet', ('up', 'west', 'east'), uv=(0, 0, 14, 0.6)))
    return finish(els, 'bronze')


# ---------------------------------------------------------------- the "To let" board

TO_LET_PIVOT = (8.0, 20.0, 4.6)        # the line through its two hooks, which the board swings from (along x)
TO_LET_BAR = (20.4, 21.6)               # the crossbar's underside and top
TO_LET_TEX = (128, 64)                  # to_let_board.png (the block's): the board's faces at TO_LET_SCALE texels a pixel
TO_LET_UV = {'north': (0, 0, 60, 39), 'south': (64, 0, 124, 39), 'up': (0, 42, 60, 47), 'down': (64, 42, 124, 47),
             'west': (124, 0, 128, 39), 'east': (124, 0, 128, 39)}     # ... where each face of the board is in it (texels)


def to_let_board(board):
    """A post knocked in beside the door (in the middle of its block, where the trim's post shape is), a bracket out
    from its top toward the street carrying a crossbar, a knee brace under the bracket, and two iron hooks under the
    crossbar. The board hangs from the hooks with its foot clear of the post: still, in this model (as the festival trim
    the board is draws it), or, without the board ([board] False), left to a renderer to hang and swing in the wind
    (WorksMesh.toLet() at WorksArt.TO_LET_X, _Y, _Z)."""
    px, py, pz = TO_LET_PIVOT
    b0, b1 = TO_LET_BAR
    els = [cube((7.0, 0.0, 7.0), (9.0, b1 + 0.4, 9.0), {'up': 'oak_end', 'down': 'oak_end', '*': 'oak'}),
           cube((7.2, b0 + 0.1, pz + 0.4), (8.8, b1 - 0.1, 7.0), 'oak_h', ('down', 'up', 'west', 'east')),
           cube((2.4, b0, pz - 0.7), (13.6, b1, pz + 0.7), {'west': 'oak_end', 'east': 'oak_end', '*': 'oak_h'}),
           along((8.0, b0 - 4.2, 7.15), (8.0, b0 + 0.15, pz + 1.3), 0.9, 0.8, 'oak')]
    for x in (px - 3.5, px + 3.5):
        els.append(cube((x - 0.35, py - 0.25, pz - 0.35), (x + 0.35, b0, pz + 0.35), 'iron'))
    if board:
        w, h, d = TO_LET_BOARD
        W, H = TO_LET_TEX
        uv = {f: (u0 * 16.0 / W, v0 * 16.0 / H, u1 * 16.0 / W, v1 * 16.0 / H) for f, (u0, v0, u1, v1) in TO_LET_UV.items()}
        top = py - 0.8
        els.append(cube((px - w / 2, top - h, pz - d / 2), (px + w / 2, top, pz + d / 2), 'to_let_board', uv=uv))
        for x in (px - 3.5, px + 3.5):           # the rings the board hangs by, as WorksMesh.toLet()'s
            els.append(cube((x - 0.2, top - 0.2, pz - 0.2), (x + 0.2, py, pz + 0.2), 'iron'))
    return finish(els, 'oak')


def to_let_block_texture():
    """The board's faces for the block model (to_let_board.png): both lettered as WorksMesh.toLet()'s, its edges green."""
    W, H = TO_LET_TEX
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    paint = to_let_painter('t_board')
    names = {'north': 'front', 'south': 'back', 'up': 'top', 'down': 'bottom', 'west': 'right'}
    for f, name in names.items():
        u0, v0, u1, v1 = TO_LET_UV[f]
        paint(img, name, u0, v0, u1 - u0, v1 - v0)
    return Canvas.of(img)


# ---------------------------------------------------------------- the yard's displays: what they stand on

def yard_base(stuff):
    """What a yard display's pieces are heaped on: bearers for the timber and the iron, a pallet for the stone, a bed of
    straw for the glass crates, boards for the wool bales."""
    if stuff == 'timber':
        els = [cube((1.5, 0.0, 0.5), (3.5, 1.5, 15.5), 'oak'), cube((12.5, 0.0, 0.5), (14.5, 1.5, 15.5), 'oak')]
    elif stuff == 'iron':
        els = [cube((2.25, 0.0, 1.0), (3.75, 1.2, 15.0), 'oak'), cube((12.25, 0.0, 1.0), (13.75, 1.2, 15.0), 'oak')]
    elif stuff == 'stone':
        els = [cube((x, 0.0, 1.0), (x + 1.5, 1.0, 15.0), 'oak', ('up', 'west', 'east', 'north', 'south')) for x in (1.5, 7.25, 13.0)]
        els.append(cube((1.0, 1.0, 1.0), (15.0, 1.6, 15.0), 'pallet'))
    elif stuff == 'glass':
        els = [cube((0.5, 0.0, 0.5), (15.5, 0.8, 15.5), 'straw'), cube((2.0, 0.8, 1.5), (14.0, 1.2, 14.5), 'straw', ('up',) + SIDES)]
    else:
        els = [cube((1.0, 0.0, 1.0), (15.0, 1.0, 15.0), 'pallet')]
    return finish(els, els[0]['faces']['up']['texture'][1:] if 'up' in els[0]['faces'] else 'oak')


# ---------------------------------------------------------------- the hoist

SHEAVE_AT = (3.5, 15.5, -15.0)          # the pulley's middle: its axle along z, hung under the jib's end
SHEAVE_R = 4.5                          # the rope's radius round it: the falls hang at x 8 (hauled) and x -1 (the load)
SHEAVE_W = 1.2


def works_hoist():
    """A hoist for the scaffold's top: an oak post on a sole, a jib out from its top a block and more to the north, a
    brace under it; at the jib's end two iron straps and an axle pin for the pulley; a cleat on the post to make the
    rope fast. The pulley, the rope, the hook and the load are the renderer's (WorksMesh.hoist())."""
    sx, sy, sz = SHEAVE_AT
    els = [cube((1.0, 0.0, 10.5), (6.0, 1.0, 16.0), 'oak'),
           cube((2.0, 1.0, 12.0), (5.0, 26.0, 15.0), {'up': 'oak_end', '*': 'oak'}),
           cube((2.25, 23.0, -16.0), (4.75, 25.5, 12.0), {'north': 'oak_end', '*': 'oak'}),
           along((3.5, 13.0, 12.2), (3.5, 23.2, 2.0), 1.4, 1.4, 'oak')]
    for z0 in (sz - SHEAVE_W / 2 - 0.6, sz + SHEAVE_W / 2 + 0.1):
        els.append(cube((sx - 0.5, sy - 0.6, z0), (sx + 0.5, 23.0, z0 + 0.5), 'iron'))
    els.append(cube((sx - 0.45, sy - 0.45, sz - SHEAVE_W / 2 - 0.9), (sx + 0.45, sy + 0.45, sz + SHEAVE_W / 2 + 0.9), 'iron'))
    els.append(cube((5.0, 8.5, 12.8), (5.9, 9.2, 14.2), 'iron'))
    return finish(els, 'oak')


# ---------------------------------------------------------------- every model, and the blockstates

STUFF = ('timber', 'stone', 'glass', 'iron', 'wool')      # Stuff.PAID's order
FIRES = ('out', 'lit', 'flare')


def models():
    """Every block model: its name under models/block/works/ and its JSON."""
    out = {f'alms_table_{p}': alms_trestle(p) for p in TABLE_PARTS}
    for f in FIRES:
        out['works_beacon' + ('' if f == 'out' else '_' + f)] = works_beacon(f)
    out['works_mortar_tub'] = works_mortar_tub()
    out['works_post'] = works_post()
    out['works_plaque'] = works_plaque(False)
    out['works_plaque_covered'] = works_plaque(True)
    out['to_let_board'] = to_let_board(True)
    out['to_let_post'] = to_let_board(False)
    for s in STUFF:
        out[f'works_yard_{s}'] = yard_base(s)
    out['works_hoist'] = works_hoist()
    return out


FACING_Y = (('north', 0), ('east', 90), ('south', 180), ('west', 270))
FACING_OUT_Y = (('south', 0), ('west', 90), ('north', 180), ('east', 270))      # the plaque: authored facing south


def _variant(name, y):
    return {'model': MODEL_NS + name} | ({'y': y} if y else {})


def blockstates():
    """The blockstates (blockstates/<id>.json) of the works' own blocks, by their proposed ids (Part B's). The alms
    table's and the "To let" board's blocks are Part D2's, with blockstates of their own (alms_table.json, and
    festival_trim.json's look=to_let): works-art.md says which of these models they take."""
    out = {}
    for bid in ['works_post', 'works_mortar_tub', 'works_hoist'] + [f'works_yard_{s}' for s in STUFF]:
        out[bid] = {'variants': {f'facing={f}': _variant(bid, y) for f, y in FACING_Y}}
    out['works_plaque'] = {'variants': {f'covered={c},facing={f}': _variant('works_plaque' + ('_covered' if c == 'true' else ''), y)
                                        for c in ('false', 'true') for f, y in FACING_OUT_Y}}
    out['works_beacon'] = {'variants': {f'facing={f},fire={fi}': _variant('works_beacon' + ('' if fi == 'out' else '_' + fi), y)
                                        for f, y in FACING_Y for fi in FIRES}}
    return out


# ---------------------------------------------------------------- every block texture

def strip(frames):
    """Canvases stacked into an animation strip (frame 0 at the top)."""
    out = Canvas(frames[0].w, frames[0].h * len(frames))
    for i, f in enumerate(frames):
        out.rgb[i * f.h:(i + 1) * f.h] = f.rgb
        out.a[i * f.h:(i + 1) * f.h] = f.a
    return out


def block_textures():
    """Every texture under textures/block/works/: name -> (Canvas, its .mcmeta's animation or None)."""
    still = {
        'oak': lambda: wood(OAK, 101),
        'oak_h': lambda: wood(OAK, 111, vertical=False, checks=1),
        'oak_end': lambda: end_grain(OAK, 103),
        'pine': lambda: wood(PINE, 105, vertical=False, checks=1),
        'pine_end': lambda: end_grain(PINE, 109, cx=10.0, cy=40.0),
        'to_let_board': to_let_block_texture,
        'pallet': lambda: boards(FRESH, 107, height=8, gap=0.3,
                                 nails=[(2, 3), (28, 3), (2, 11), (28, 11), (2, 19), (28, 19), (2, 27), (28, 27)]),
        'post_board': post_board,
        'box_front': box_front, 'box_side': box_side, 'box_lid': box_lid,
        'bronze': bronze_metal, 'plaque': plaque_face, 'velvet': velvet,
        'staves': tub_staves, 'hoop': lambda: iron(1611, rust=0.25),
        'brazier': basket_iron, 'coals': lambda: coals(False), 'char': charred,
        'straw': straw_tex, 'iron': iron,
    }
    out = {k: (f(), None) for k, f in still.items()}
    out.update({k: (cv, None) for k, cv in trestle_tops().items()})
    out['mortar'] = (mortar_frames(), {'frametime': 6, 'interpolate': True})
    out['coals_lit'] = (strip([coals(True, f) for f in range(FLAME_FRAMES)]), {'frametime': 3, 'interpolate': True})
    out['flame'] = (flame_frames(), {'frametime': 2})
    out['flare'] = (flame_frames(True), {'frametime': 1})
    return out


# ================================================================== the meshes the renderers draw (WorksMesh.kt)
#
# Box UV on entity textures, painted at a higher resolution than declared (each mesh's [scale]) as the townsfolk's and the
# handcart's are. ModelPart space as everywhere: pixels, y down, the front -z, x mirrored (a renderer turns a block's
# model space into it with poseStack.scale(-1, -1, 1)); each piece stands on the middle of its underside unless it says.

from modelkit import Cube, Part, Model  # noqa: E402
import townsfolk_tools as tt  # noqa: E402
import handcart as hc  # noqa: E402

LIME = [(70, 64, 56), (108, 100, 88), (146, 138, 122), (178, 170, 152), (204, 196, 178), (226, 220, 204)]   # dressed stone
WOOL = [(118, 108, 90), (160, 150, 128), (196, 186, 162), (222, 214, 194), (240, 234, 218)]
GLASS = [(52, 84, 92), (86, 128, 136), (130, 176, 180), (186, 222, 220), (232, 248, 244)]
SLATE = PAL['slate']


def ashlar_tile():
    """Dressed limestone: warm grey, pitted, the claw-tool's marks running across it on a slant."""
    xs, ys = grid(T)
    t = 0.52 + 0.12 * (fbm(T, T, 6, 3101) - 0.5) + 0.05 * np.sin((xs - ys * 0.6) * 1.7) + 0.04 * (rand(T, T, 3103) - 0.5)
    t = np.where(rand(T, T, 3105) < 0.015, t - 0.1, t)
    cv = Canvas(T)
    cv.put(ALL, bands(LIME, t, 0.3))
    return cv


TILE_PAINTERS = {
    'fresh_h': lambda: wood(FRESH, 3001, vertical=False, checks=1, knots=1),
    'fresh_v': lambda: wood(FRESH, 3003, vertical=True, checks=1, knots=1),
    'fresh_end': lambda: end_grain(FRESH, 3005, cx=15.0, cy=17.0),
    'oak_h': lambda: wood(OAK, 3007, vertical=False, checks=1),
    'oak_v': lambda: wood(OAK, 3009, vertical=True, checks=1),
    'pine_h': lambda: wood(PINE, 3011, vertical=False, checks=0),
    'stone': ashlar_tile,
    'iron': lambda: iron(3013, rust=0.35),
    'burlap': lambda: burlap(3015),
    'rope_v': lambda: rope_tex(3017, vertical=True),
    'rope_h': lambda: rope_tex(3019, vertical=False),
    'straw': lambda: straw_tex(3021),
    'pot': pot_iron,
    'pot_in': pot_inside,
}
_TILES = {}


def tile(name):
    if name not in _TILES:
        cv = TILE_PAINTERS[name]()
        _TILES[name] = np.dstack([cv.rgb, cv.a * 255.0])
    return _TILES[name]


def from_tile(name, w, h, rng):
    """A [w] x [h] piece of tile [name] (32 texels, two to a pixel), from a random place in it, wrapping."""
    a = tile(name)
    th, tw = a.shape[:2]
    oy, ox = int(rng.integers(0, th)), int(rng.integers(0, tw))
    return a[(np.arange(h) + oy) % th][:, (np.arange(w) + ox) % tw].copy()


def tiled(key, faces, edge=0.2):
    """A painter giving each face a piece of a tile: [faces] {face: tile name} ('*' for the rest)."""
    def fn(face, w, h, rng):
        name = faces.get(face, faces.get('*'))
        if name is None:
            return None
        return tt.shade_edges(from_tile(name, w, h, rng), edge)
    return tt.painter(key, fn)


def ashlar_painter(key):
    """A dressed block: the claw-tooled face inside a smooth drafted margin a pixel wide, its arrises a little chipped."""
    def fn(face, w, h, rng):
        a = from_tile('stone', w, h, rng)
        ys, xs = np.mgrid[0:h, 0:w]
        d = np.minimum.reduce([xs, ys, w - 1 - xs, h - 1 - ys])
        margin = d < 2
        a[..., :3] = np.where(margin[..., None], tt.mix(a[..., :3], LIME[4], 0.25), a[..., :3])
        chip = (d == 0) & (rng.random((h, w)) < 0.12)
        a[..., :3] = np.where(chip[..., None], np.array(LIME[1], float), a[..., :3])
        return tt.shade_edges(a, 0.16)
    return tt.painter(key, fn)


def crate_painter(key):
    """A crate of glass packed in straw: new pine slats with straw bristling from the gaps between them, the corners
    lapped; open on top, straw packed round the panes' edges standing up out of it, greenish and catching the light."""
    def fn(face, w, h, rng):
        U, V = tt.uv(w, h)
        ys, xs = np.mgrid[0:h, 0:w]
        if face == 'top':
            a = from_tile('straw', w, h, rng)
            for k in range(3):
                x = int(w * (0.25 + 0.25 * k)) + int(rng.integers(-1, 2))
                pane = (np.abs(xs - x) < 1) & (ys > h * 0.15) & (ys < h * 0.85)
                g = tt.mix(GLASS[2], GLASS[4], ((1 - V) * 0.6)[..., None])
                a[..., :3] = np.where(pane[..., None], g, a[..., :3])
                a[..., :3] = np.where((pane & (ys < h * 0.25))[..., None], np.array(GLASS[4], float), a[..., :3])
            rim = (xs < 2) | (xs >= w - 2) | (ys < 2) | (ys >= h - 2)
            a[..., :3] = np.where(rim[..., None], from_tile('fresh_h', w, h, rng)[..., :3], a[..., :3])
            return tt.shade_edges(a, 0.12)
        if face == 'bottom':
            return from_tile('fresh_h', w, h, rng)
        a = from_tile('fresh_h', w, h, rng)
        n = 3
        gap = ((V * n) % 1.0) > 0.8
        straw = from_tile('straw', w, h, rng)
        a[..., :3] = np.where(gap[..., None], straw[..., :3] * 0.8, a[..., :3])
        post = (U < 0.12) | (U > 0.88)
        a[..., :3] = np.where(post[..., None], tt.mix(a[..., :3], FRESH[1], 0.35), a[..., :3])
        for row in range(n):
            for cu in (0.06, 0.94):
                hc.nail(a, cu, (row + 0.4) / n, 0.45)
        return tt.shade_edges(a, 0.18)
    return tt.painter(key, fn)


def bale_painter(key):
    """A bale of wool sewn into sacking, plump, the fleece showing cream at the stitched ends."""
    def fn(face, w, h, rng):
        a = from_tile('burlap', w, h, rng)
        U, V = tt.uv(w, h)
        bulge = np.sin(U * math.pi) * np.sin(V * math.pi)
        a[..., :3] *= (0.82 + 0.25 * bulge)[..., None]
        if face in ('front', 'back'):
            # The sewn end: a seam across it, and fleece bulging out between the stitches
            seam = np.abs(V - 0.5) < 0.06
            a[..., :3] = np.where(seam[..., None], tt.mix(a[..., :3], (60, 46, 30), 0.6), a[..., :3])
            fleece = (np.abs(V - 0.5) < 0.16) & ~seam & (rng.random((h, w)) < 0.55)
            a[..., :3] = np.where(fleece[..., None], np.array(WOOL[3], float), a[..., :3])
        return tt.shade_edges(a, 0.3, width=max(1, min(w, h) // 3))
    return tt.painter(key, fn)


def iron_bar_painter(key):
    """A wrought-iron bar: black, its length hammered, the ends sheared bright."""
    def fn(face, w, h, rng):
        a = from_tile('iron', w, h, rng)
        if face in ('right', 'left'):
            a[..., :3] = tt.mix(a[..., :3], IRONS[5], 0.45)
        return tt.shade_edges(a, 0.3, width=1)
    return tt.painter(key, fn)


def sheave_painter(key):
    """The pulley's face: a round wooden sheave with an iron band round its rim, four holes and an iron hub."""
    def fn(face, w, h, rng):
        if face not in ('front', 'back'):
            a = from_tile('iron', w, h, rng)
            return tt.shade_edges(a, 0.25, width=1)
        ys, xs = np.mgrid[0:h, 0:w]
        cx, cy = w / 2, h / 2
        r = np.hypot(xs + 0.5 - cx, ys + 0.5 - cy) / (w / 2)
        ang = np.arctan2(ys + 0.5 - cy, xs + 0.5 - cx)
        a = from_tile('oak_h', w, h, rng)
        a[..., :3] *= (0.85 + 0.2 * np.cos(ang + 2.3))[..., None]
        band = r > 0.8
        a[..., :3] = np.where(band[..., None], from_tile('iron', w, h, rng)[..., :3], a[..., :3])
        holes = (np.abs(r - 0.52) < 0.14) & (np.abs(np.sin(2 * ang)) > 0.72)
        a[..., :3] = np.where(holes[..., None], np.array((18, 14, 12), float), a[..., :3])
        hub = r < 0.22
        a[..., :3] = np.where(hub[..., None], tt.mix(from_tile('iron', w, h, rng)[..., :3], IRONS[4], 0.3), a[..., :3])
        a[..., 3] = np.where(r <= 1.0, 255, 0)
        return a
    return tt.painter(key, fn, light=False)


def coil_painter(key):
    """A coil of rope on the ground: from above, turns round and round to a hole in the middle; round its side, the turns
    laid one over another."""
    def fn(face, w, h, rng):
        ys, xs = np.mgrid[0:h, 0:w]
        if face == 'top':
            r = np.hypot(xs + 0.5 - w / 2, ys + 0.5 - h / 2) / (w / 2)
            ang = np.arctan2(ys + 0.5 - h / 2, xs + 0.5 - w / 2)
            turns = (r * 4.5 + ang / math.tau) % 1.0
            a = tt.solid(w, h, ROPE[3])
            a[..., :3] = tt.mix(np.array(ROPE[1], float), np.array(ROPE[4], float), (np.sin(turns * math.pi) ** 0.7)[..., None])
            a[..., :3] = np.where((r < 0.3)[..., None], np.array(ROPE[0], float), a[..., :3])
            a[..., 3] = np.where(r <= 1.0, 255, 0)
            return a
        a = from_tile('rope_h', w, h, rng)
        band = (ys % 3) == 2
        a[..., :3] = np.where(band[..., None], a[..., :3] * 0.6, a[..., :3])
        return a
    return tt.painter(key, fn, light=False)


def board_face(text_rows, w, h, scale, bg, ink, seed, hand, border=None, grain_tile=None):
    """A painted or chalked face [w] x [h] texels: [bg] ground (a palette, or a tile), the rows of text centred in
    [hand] ([(text, px_per_font, baseline px)]), an optional border line."""
    rng = np.random.default_rng(seed)
    N = max(w, h)
    xs, ys = np.mgrid[0:h, 0:w]
    t = 0.56 + 0.12 * (rng.random((h, w)) - 0.5) * 0.4
    nz = fbm(N, N, 6 * scale // 2, seed)[:h, :w]
    t = t + 0.14 * (nz - 0.5)
    a = np.zeros((h, w, 4))
    a[..., :3] = bands(bg, t, 0.25)
    a[..., 3] = 255
    if border is not None:
        b0, b1 = border
        line = (np.minimum.reduce([xs, ys, w - 1 - xs, h - 1 - ys]) >= b0) & (np.minimum.reduce([xs, ys, w - 1 - xs, h - 1 - ys]) < b1)
        a[..., :3] = np.where(line[..., None], tt.mix(a[..., :3], ink, 0.85), a[..., :3])
    for text, ppf, base in text_rows:
        al, tw_, tb = letters(hand, text, ppf, scale, seed=seed)
        ox = (w - tw_) // 2
        oy = int(round(base * scale)) - tb
        hh, ww = al.shape
        m = np.zeros((h, w))
        y0, x0 = max(0, oy), max(0, ox)
        y1, x1 = min(h, oy + hh), min(w, ox + ww)
        m[y0:y1, x0:x1] = al[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
        a[..., :3] = tt.mix(a[..., :3], ink, np.clip(m * 1.15, 0, 1)[..., None])
    return a


TO_LET_SCALE = 6                        # the board's and the slate's textures: six texels to a pixel, for their letters
TO_LET_BOARD = (10.0, 6.5, 0.8)         # the board: across, high, thick; hung 0.8 under its hooks' line


def to_let_painter(key):
    """The board's faces: dark green paint gone soft and a little flaked, a cream line round its edge, TO LET in cream
    capitals, a sign-painter's (both sides alike); its edges green."""
    def fn(face, w, h, rng):
        if face not in ('front', 'back'):
            a = tt.solid(w, h, GREEN[2])
            a[..., :3] *= (1 + rng.normal(0, 0.06, (h, w, 1)))
            return a
        a = board_face([('TO', 0.28, 3.05), ('LET', 0.28, 5.55)], w, h, TO_LET_SCALE, GREEN, np.array(CREAM, float), 2501,
                       PAINT, border=(2, 4))
        flake = (rng.random((h, w)) < 0.025)
        a[..., :3] = np.where(flake[..., None], tt.mix(a[..., :3], GREY[3], 0.7), a[..., :3])
        return tt.shade_edges(a, 0.12)
    return tt.painter(key, fn, light=False)


SLATE_BOARD = (11.0, 8.0, 0.5)          # the slate in its frame: across, high, thick
SLATE_FRAME = 0.75                      # px of its frame round the slate's face


SLATE_GHOSTS = [('Eggs 2d', 0.2, 3.3), ('Fresh peas', 0.17, 6.0)]     # old chalk wiped off it, still faintly there


def slate_painter(key):
    """The slate a stall-keeper chalks on: dark slate, rubbed clean with a cloth, but old chalk is never quite gone,
    the ghosts of an earlier day's words (SLATE_GHOSTS) and the cloth's smears faint on it, for "Nothing today" to be
    chalked over (the renderer's font, as the language has it); its frame plain pine, its back boarded."""
    def fn(face, w, h, rng):
        if face != 'front':
            return tt.shade_edges(from_tile('pine_h', w, h, rng), 0.15)
        ghost = board_face(SLATE_GHOSTS, w, h, TO_LET_SCALE, SLATE, np.array((236, 234, 226), float), 2601, ML.CHALK)
        ghost = np.asarray(Image.fromarray(np.clip(ghost[..., :3], 0, 255).astype(np.uint8), 'RGB')
                           .filter(ImageFilter.GaussianBlur(TO_LET_SCALE * 0.3)), float)
        a = board_face([], w, h, TO_LET_SCALE, SLATE, np.array((236, 234, 226), float), 2601, ML.CHALK)
        a[..., :3] = tt.mix(a[..., :3], ghost, 0.08)
        ys, xs = np.mgrid[0:h, 0:w]
        n = max(w, h)
        swipe = np.sin((xs * 0.9 + ys * 0.35) / TO_LET_SCALE * 1.7 + fbm(n, n, 8, 2605)[:h, :w] * 5.0)
        smear = (fbm(n, n, 10, 2603)[:h, :w] > 0.58) & (swipe > 0.2)
        a[..., :3] = np.where(smear[..., None], tt.mix(a[..., :3], (120, 124, 130), 0.18), a[..., :3])
        frame = np.minimum.reduce([xs, ys, w - 1 - xs, h - 1 - ys]) < TO_LET_SCALE * SLATE_FRAME
        a[..., :3] = np.where(frame[..., None], from_tile('pine_h', w, h, rng)[..., :3], a[..., :3])
        return tt.shade_edges(a, 0.1)
    return tt.painter(key, fn, light=False)


def P(name, cubes=(), pivot=(0, 0, 0), rot=(0, 0, 0), children=()):
    return Part(name, pivot, rot, cubes=list(cubes), children=list(children))


def C(origin, size, name, paint, faces=None):
    c = Cube(origin, size, paint, name=name, faces=faces)
    c.share = None
    return c


# The yard's pieces, each standing on the middle of its underside
BAULK = (14.0, 3.0, 3.0)                # timber: along x
ASHLAR = (4.4, 3.4, 6.4)
CRATE = (7.0, 6.0, 7.0)
BAR = (14.0, 1.75, 1.75)                # iron: along x
BALE = (7.0, 5.5, 12.5)                 # wool: along z
STICKER = (1.2, 0.6, 14.0)              # the battens between the timber's layers, along z


def yard_parts():
    wood_x = tiled('y_baulk', {'right': 'fresh_end', 'left': 'fresh_end', '*': 'fresh_h'})
    w, h, d = BAULK
    baulk = P('baulk', [C((-w / 2, -h, -d / 2), (w, h, d), 'y_baulk', wood_x)])
    w, h, d = STICKER
    sticker = P('sticker', [C((-w / 2, -h, -d / 2), (w, h, d), 'y_sticker',
                               tiled('y_sticker', {'front': 'oak_v', 'back': 'oak_v', '*': 'oak_h'}))])
    w, h, d = ASHLAR
    ashlar = P('ashlar', [C((-w / 2, -h, -d / 2), (w, h, d), 'y_ashlar', ashlar_painter('y_ashlar'))])
    w, h, d = CRATE
    posts = tiled('y_crate_post', {'*': 'fresh_v', 'top': 'fresh_end', 'bottom': 'fresh_end'})
    crate = P('crate', [C((-w / 2, -h, -d / 2), (w, h, d), 'y_crate', crate_painter('y_crate'))] +
              [C((sx * (w / 2 - 0.35) - 0.45, -h - 0.2, sz * (d / 2 - 0.35) - 0.45), (0.9, h + 0.2, 0.9), f'y_crate_post{i}', posts)
               for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1)))])
    w, h, d = BAR
    bar = P('bar', [C((-w / 2, -h, -d / 2), (w, h, d), 'y_bar', iron_bar_painter('y_bar'))])
    w, h, d = BALE
    ties = tiled('y_bale_tie', {'*': 'rope_h'}, edge=0.1)
    bale = P('bale', [C((-w / 2, -h, -d / 2), (w, h, d), 'y_bale', bale_painter('y_bale'))] +
             [C((-w / 2 - 0.12, -h - 0.12, z - 0.3), (w + 0.24, h + 0.24, 0.6), f'y_bale_tie{i}', ties,
                faces=['right', 'left', 'top', 'bottom']) for i, z in enumerate((-4.0, 0.0, 4.0))])
    return [baulk, sticker, ashlar, crate, bar, bale]


ROPE_W = 0.6                            # the hoist's rope: square, this thick


def hoist_parts():
    """The hoist's moving parts: the pulley ("sheave", turning about z at its middle), the rope's wrap over its top
    ("wrap", still), a block's length of rope ("rope", hanging from its top), the hook ("hook", from its eye's top), the
    load slung under it ("load", from the hook's bottom: a timber baulk in two slings) and the fall's slack coiled on the
    ground ("coil")."""
    r = SHEAVE_R
    face = sheave_painter('h_sheave')
    rim = tiled('h_sheave_rim', {'*': 'iron'}, edge=0.1)
    chord = 2.0 * r * math.tan(math.radians(15.0)) + 0.1
    sheave = P('sheave', [C((-r, -r, -SHEAVE_W / 2), (2 * r, 2 * r, SHEAVE_W), 'h_sheave', face, faces=['front', 'back'])],
               children=[P(f'sheave_rim{k}', [C((-chord / 2, -r, -SHEAVE_W / 2 + 0.05), (chord, 0.7, SHEAVE_W - 0.1),
                                                f'h_sheave_rim{k}', rim, faces=['top', 'bottom'])],
                           rot=(0, 0, 30.0 * k)) for k in range(12)])
    rope_paint = tiled('h_rope', {'*': 'rope_v', 'top': 'rope_h', 'bottom': 'rope_h'}, edge=0.05)
    seg = math.pi * (r + ROPE_W / 2) / 6
    wrap = P('wrap', children=[P(f'wrap{k}', [C((-seg / 2 - 0.05, -r - ROPE_W, -ROPE_W / 2), (seg + 0.1, ROPE_W, ROPE_W), f'h_wrap{k}', rope_paint)],
                                 rot=(0, 0, -75.0 + 30.0 * k)) for k in range(6)])
    rope = P('rope', [C((-ROPE_W / 2, 0, -ROPE_W / 2), (ROPE_W, 16.0, ROPE_W), 'h_rope', rope_paint)])
    ironp = tiled('h_hook', {'*': 'iron'}, edge=0.12)
    hook = P('hook', [C((-0.9, 0.0, -0.35), (1.8, 0.6, 0.7), 'h_hook_eye', ironp),
                      C((-0.9, 0.6, -0.35), (0.6, 1.2, 0.7), 'h_hook_eye_r', ironp),
                      C((0.3, 0.6, -0.35), (0.6, 1.2, 0.7), 'h_hook_eye_l', ironp),
                      C((-0.4, 1.8, -0.4), (0.8, 2.6, 0.8), 'h_hook_shank', ironp),
                      C((-0.4, 4.4, -0.4), (2.6, 0.8, 0.8), 'h_hook_bow', ironp),
                      C((1.4, 3.0, -0.4), (0.8, 1.6, 0.8), 'h_hook_tip', ironp)])
    w, h, d = BAULK
    slings = tiled('h_sling', {'*': 'rope_v'}, edge=0.05)
    load = P('load', [C((-h / 2, 4.0, -w / 2), (h, d, w), 'h_load', tiled('h_load', {'front': 'fresh_end', 'back': 'fresh_end',
                                                                                      'top': 'fresh_v', 'bottom': 'fresh_v', '*': 'fresh_h'}))],
             children=[P(f'sling{k}', [C((-0.25, 0.0, -0.25), (0.5, 6.4, 0.5), f'h_sling{k}', slings)],
                         rot=(sk * 50.0, 0, 0)) for k, sk in enumerate((-1, 1))])
    coil = P('coil', [C((-3.0, -1.6, -3.0), (6.0, 1.6, 6.0), 'h_coil', coil_painter('h_coil'))],
             children=[P('coil45', [C((-3.0, -1.6, -3.0), (6.0, 1.6, 6.0), 'h_coil45', coil_painter('h_coil45'),
                                      faces=['right', 'left', 'front', 'back'])], rot=(0, 45.0, 0))])
    return [sheave, wrap, rope, hook, load, coil]


def to_let_parts():
    """The board, hanging from its two rings' tops at the origin (the hooks' line), its face toward -z."""
    w, h, d = TO_LET_BOARD
    ring = tiled('t_ring', {'*': 'iron'}, edge=0.1)
    return [P('board', [C((-w / 2, 0.8, -d / 2), (w, h, d), 't_board', to_let_painter('t_board')),
                        C((-3.7, 0.0, -0.2), (0.4, 1.0, 0.4), 't_ring_r', ring),
                        C((3.3, 0.0, -0.2), (0.4, 1.0, 0.4), 't_ring_l', ring)])]


def slate_parts():
    """The slate in its frame, standing on the middle of its foot, its face toward -z."""
    w, h, d = SLATE_BOARD
    return [P('slate', [C((-w / 2, -h, -d / 2), (w, h, d), 's_slate', slate_painter('s_slate'))])]


# ---------------------------------------------------------------- the alms table's pot, stew, bowls and ladle

import living_props as lp  # noqa: E402

POT_WALL_TOP = POT_RIM_Y - 0.7 - ALMS_TOP_Y        # the pot's walls' top over its foot, under the lip
STEW_TICKS = 3.0                                   # ticks each of the STEW_FRAMES shows, round and round
ALMS_SCALE = 4                                     # alms.png's texels to a declared one


def euler_zyx(R):
    """(x, y, z) degrees with R = Rz(z) Ry(y) Rx(x): a ModelPart's rotation, as PartPose turns it."""
    b = math.asin(max(-1.0, min(1.0, -R[2, 0])))
    if abs(math.cos(b)) > 1e-6:
        x, z = math.atan2(R[2, 1], R[2, 2]), math.atan2(R[1, 0], R[0, 0])
    elif R[2, 0] > 0:
        x, z = math.atan2(-R[0, 1], R[1, 1]), 0.0
    else:
        x, z = math.atan2(R[0, 1], R[1, 1]), 0.0
    out = (math.degrees(x), math.degrees(b), math.degrees(z))
    assert np.abs(Rz(out[2]) @ Ry(out[1]) @ Rx(out[0]) - R).max() < 1e-9, 'euler_zyx'
    return out


def stew_frame(f, w, h):
    """Frame [f] of STEW_FRAMES of the stew in the pot from above, painted at [w] x [h] texels, its octagon (the pot's
    inside) filling the rect, the corners clear: thick and brown, bits of carrot and peas and barley and meat in it
    drifting a little, and bubbles that swell and burst in turn, one or two at a time."""
    n = max(w, h)
    k = n / T
    xs, ys = grid(n)
    c = n / 2.0
    oct_ = (np.abs(xs - c) + np.abs(ys - c) <= c * math.sqrt(2)) & (xs < n) & (ys < n)
    base = 0.46 + 0.16 * (fbm(n, n, 6 * k, 2001) - 0.5) + 0.14 * np.sin(xs / k * 0.5 + fbm(n, n, 9 * k, 2003) * 4) * 0.5
    rng = np.random.default_rng(2005)
    bits = [(rng.uniform(3, 29) * k, rng.uniform(3, 29) * k, rng.choice(['carrot', 'pea', 'barley', 'meat']),
             rng.uniform(0.7, 1.4) * k) for _ in range(16)]
    bubbles = [(rng.uniform(6, 26) * k, rng.uniform(6, 26) * k, rng.uniform(0, 1), rng.uniform(1.4, 2.6) * k) for _ in range(6)]
    u = f / STEW_FRAMES
    t = base + 0.04 * np.sin(u * math.tau + xs / k * 0.3)
    a = np.zeros((n, n, 4))
    a[..., :3] = bands(STEW, t, 0.3)
    for bx_, by_, kind, r in bits:
        m = np.hypot(xs - bx_ - 0.4 * k * math.sin(u * math.tau), ys - by_) < r
        a[m] = list({'carrot': (214, 110, 40), 'pea': (110, 146, 56), 'barley': (210, 190, 140), 'meat': (96, 48, 30)}[kind]) + [0]
    for bx_, by_, ph, r in bubbles:
        g = (u - ph) % 1.0
        d = np.hypot(xs - bx_, ys - by_)
        if g < 0.55:
            rr = r * (0.3 + g / 0.55 * 0.7)
            a[(d < rr) & (d > rr - 0.9 * k), :3] = STEW[4]
            a[d < rr - 0.9 * k, :3] = STEW[2]
            a[(np.abs(xs - (bx_ - rr * 0.4)) < 0.5 * k) & (np.abs(ys - (by_ - rr * 0.4)) < 0.5 * k), :3] = STEW[5]
        elif g < 0.66:
            a[(d < r + 0.8 * k) & (d > r - 0.4 * k), :3] = STEW[3]
    a[..., 3] = np.where(oct_, 255, 0)
    return a[:h, :w]


def stew_painter(f):
    def fn(face, w, h, rng):
        return stew_frame(f, w, h) if face == 'top' else None
    return tt.painter(f'a_stew_{f}', fn, light=False)


def ripple_painter(key):
    """A ring on the broth where the ladle goes in, or a gift: pale and greasy, thinning to nothing at its edges."""
    def fn(face, w, h, rng):
        if face != 'top':
            return None
        U, V = tt.uv(w, h)
        r = np.hypot(U - 0.5, V - 0.5) * 2
        a = tt.solid(w, h, STEW[4])
        a[..., :3] = tt.mix(a[..., :3], STEW[5], (rng.random((h, w)) < 0.3)[..., None] * 0.5)
        a[..., 3] = np.where(np.abs(r - 0.84) < 0.12, 255, 0)
        return a
    return tt.painter(key, fn, light=False)


def pot_painter(key, inner=None):
    """The pot's iron: sooty black outside, browner where it's been scrubbed; the face [inner] its inside, blacker, the
    stew's crust on it."""
    def fn(face, w, h, rng):
        a = from_tile('pot_in' if face == inner else 'pot', w, h, rng)
        return tt.shade_edges(a, 0.15)
    return tt.painter(key, fn)


def pot_floor_painter(key):
    """The pot's floor, seen when it's scraped empty: its inside's iron, an octagon to the walls' (tucked under them,
    never past them), the corners clear."""
    def fn(face, w, h, rng):
        if face != 'top':
            return None
        a = from_tile('pot_in', w, h, rng)
        U, V = tt.uv(w, h)
        a[..., 3] = np.where(np.abs(U - 0.5) + np.abs(V - 0.5) <= 0.5 * math.sqrt(2), 255, 0)
        return a
    return tt.painter(key, fn)


def alms_parts():
    """The alms table's pieces, each standing on the middle of its underside unless it says: "pot" (black iron,
    eight-sided as its walls are two squares of four, the second turned 45 degrees in its child "pot45"; a lip round its
    rim and a lug each side), "stew_0" to "stew_7" (the stew's top, its frames, an octagon fitting the pot's inside: the
    renderer shows one at a time), "ripple" (a ring on the stew's top, 4 px across, for the renderer to spread), "drop"
    (a splash of stew, about its middle), "bowl" (an empty bowl as LADLE's keeper takes it up: the held one's shape and
    paint), "bowl_under" (one under it on the stack, a hair bigger all round so the two never fight) and "ladle" (as it
    stands in the pot: its bowl's middle at the origin, turned as it leans there)."""
    ro = POT_R + POT_WALL
    side = 2.0 * ro * math.tan(math.pi / 8)
    lip_r = ro + 0.3
    lip_side = 2.0 * lip_r * math.tan(math.pi / 8)
    floor_h = POT_INSIDE_Y - ALMS_TOP_Y
    lip0, lip1 = -(POT_RIM_Y - ALMS_TOP_Y), -POT_WALL_TOP

    def walls(tag):
        hw = POT_WALL_TOP
        out = [C((-side / 2, -hw, -ro), (side, hw, POT_WALL), f'a_pot_wall_n{tag}', pot_painter(f'a_pot_wall_n{tag}', 'back'), ['front', 'back']),
               C((-side / 2, -hw, ro - POT_WALL), (side, hw, POT_WALL), f'a_pot_wall_s{tag}', pot_painter(f'a_pot_wall_s{tag}', 'front'), ['front', 'back']),
               C((-ro, -hw, -side / 2), (POT_WALL, hw, side), f'a_pot_wall_w{tag}', pot_painter(f'a_pot_wall_w{tag}', 'left'), ['right', 'left']),
               C((ro - POT_WALL, -hw, -side / 2), (POT_WALL, hw, side), f'a_pot_wall_e{tag}', pot_painter(f'a_pot_wall_e{tag}', 'right'), ['right', 'left'])]
        lt = 1.0
        out += [C((-lip_side / 2, lip0, -lip_r), (lip_side, lip1 - lip0, lt), f'a_pot_lip_n{tag}', pot_painter(f'a_pot_lip_n{tag}')),
                C((-lip_side / 2, lip0, lip_r - lt), (lip_side, lip1 - lip0, lt), f'a_pot_lip_s{tag}', pot_painter(f'a_pot_lip_s{tag}')),
                C((-lip_r, lip0, -lip_side / 2), (lt, lip1 - lip0, lip_side), f'a_pot_lip_w{tag}', pot_painter(f'a_pot_lip_w{tag}')),
                C((lip_r - lt, lip0, -lip_side / 2), (lt, lip1 - lip0, lip_side), f'a_pot_lip_e{tag}', pot_painter(f'a_pot_lip_e{tag}'))]
        return out
    f = ro - 0.3
    floor = C((-f, -floor_h, -f), (2 * f, 0.0, 2 * f), 'a_pot_floor', pot_floor_painter('a_pot_floor'), ['top'])
    lug_y = -(16.6 - ALMS_TOP_Y)
    lugs = [C((ro - 0.1, lug_y, -0.9), (1.0, 1.0, 1.8), 'a_pot_lug0', pot_painter('a_pot_lug0')),
            C((-ro - 0.9, lug_y, -0.9), (1.0, 1.0, 1.8), 'a_pot_lug1', pot_painter('a_pot_lug1'))]
    pot = P('pot', walls('') + lugs + [floor], children=[P('pot45', walls('45'), rot=(0.0, 45.0, 0.0))])
    stews = [P(f'stew_{f}', [C((-POT_R, 0.0, -POT_R), (2 * POT_R, 0.0, 2 * POT_R), f'a_stew_{f}', stew_painter(f), ['top'])])
             for f in range(STEW_FRAMES)]
    ripple = P('ripple', [C((-2.0, 0.0, -2.0), (4.0, 0.0, 4.0), 'a_ripple', ripple_painter('a_ripple'), ['top'])])
    drop = P('drop', [C((-0.3, -0.3, -0.3), (0.6, 0.6, 0.6), 'a_drop',
                        tt.painter('a_drop', lambda face, w, h, rng: tt.mix(tt.solid(w, h, STEW[3]), (214, 110, 40, 255),
                                                                         (rng.random((h, w, 1)) < 0.2) * 0.6)))])
    bw = BOWL_W
    bowl = P('bowl', [C((-bw / 2, -BOWL_TALL, -bw / 2), (bw, BOWL_TALL, bw), 'a_bowl', lp.empty_bowl_painter('a_bowl'))])
    under = Cube((-bw / 2, -BOWL_TALL, -bw / 2), (bw, BOWL_TALL, bw), lp.empty_bowl_painter('a_bowl_under'), inflate=0.1,
                 name='a_bowl_under')
    under.share = None
    bowl_under = P('bowl_under', [under])
    R, _ = ladle_rest()
    lb = np.asarray(LADLE_BOWL_PT, float)
    (ho, hs, _), (bo, bs, _) = LADLE_CUBES
    ladle = P('ladle', [C(tuple(np.asarray(ho) - lb), hs, 'a_ladle_handle', tt.wood_painter('a_ladle_handle', tt.PALE, tt.PALE_D, tt.PALE_L, 'y')),
                        C(tuple(np.asarray(bo) - lb), bs, 'a_ladle_bowl', lp.ladle_bowl_painter('a_ladle_bowl', False))],
              rot=euler_zyx(R))
    return [pot] + stews + [ripple, drop, bowl, bowl_under, ladle]


RIPPLE_TICKS = 18.0                     # the ladle's ripple spreading over the stew
SPLASH_TICKS = 14.0                     # a gift's ring
SPLASH_DROPS = 6                        # ... and its drops: thrown up SPLASH_UP px a tick, falling back SPLASH_FALL a tick
SPLASH_UP, SPLASH_FALL, SPLASH_OUT = 0.55, 0.04, 0.16      # a tick, and going out SPLASH_OUT px a tick
POT_SHAPE = (POT_AT[0] - POT_R - POT_WALL - 0.9, ALMS_TOP_Y, POT_AT[1] - POT_R - POT_WALL - 0.3, 16.0, POT_RIM_Y,
             POT_AT[1] + POT_R + POT_WALL + 0.3)


def stew_y(fill):
    return POT_FLOOR_Y + (POT_FULL_Y - POT_FLOOR_Y) * min(1.0, max(0.0, fill))


def ripple_scale(age):
    u = min(1.0, max(0.0, age / RIPPLE_TICKS))
    return 0.36 + 1.6 * (1 - (1 - u) ** 2)


def splash_scale(age):
    u = min(1.0, max(0.0, age / SPLASH_TICKS))
    return 0.5 + 1.5 * (1 - (1 - u) ** 2)


def splash_drop(j, age):
    up = SPLASH_UP * age - SPLASH_FALL * age * age
    if age <= 0 or up < 0:
        return None
    a = math.radians(j * 60.0 + 23.0)
    out = 0.4 + SPLASH_OUT * age
    return np.array([out * math.cos(a), up, out * math.sin(a)])


def ladle_rest_at():
    """Where the standing ladle's bowl's middle is in the middle block's model space (px): "ladle" is drawn there."""
    _, Pc = ladle_rest()
    return np.array([8.0 - Pc[0], 24.0 - Pc[1], Pc[2] + 24.0])


class Mesh:
    """One LayerDefinition of WorksMesh: its parts, its texture's declared size and the scale it's painted at."""

    def __init__(self, fun, texture, parts, tex_w, tex_h, scale, doc):
        self.fun, self.texture, self.parts, self.scale, self.doc = fun, texture, parts, scale, doc
        self.model = Model(fun, tex_w, tex_h, parts)
        cubes = list(self.model.all_cubes())
        names = [c.name for c in cubes]
        dup = {n for n in names if names.count(n) > 1}
        if dup:
            raise SystemExit(f'works_art: {fun}: cube names must be unique: {sorted(dup)}')
        pack_cubes(cubes, tex_w, tex_h, fun)

    def paint(self):
        m = self.model
        img = Image.new('RGBA', (m.tex_w * self.scale, m.tex_h * self.scale), (0, 0, 0, 0))
        for c in m.all_cubes():
            for face, (x, y, w, h) in hc.rects2x(c, self.scale).items():
                if w > 0 and h > 0 and c.shows(face):
                    c.paint(img, face, x, y, w, h)
        return img


def pack_cubes(cubes, W, H, what):
    """Skyline-pack the cubes' box UV, the tallest first (handcart.py's packing)."""
    sky = [[0, 0, W]]
    for c in sorted(cubes, key=lambda c: (-c.footprint()[1], -c.footprint()[0], c.name)):
        fw, fh = c.footprint()
        best = None
        for i in range(len(sky)):
            x = sky[i][0]
            if x + fw > W:
                break
            y, j, room = 0, i, 0
            while room < fw:
                y = max(y, sky[j][1])
                room += sky[j][2]
                j += 1
            key = (y + fh, y, x)
            if best is None or key < best[0]:
                best = (key, x, y)
        if best is None:
            raise SystemExit(f'works_art: {what}: {c.name} ({fw}x{fh}) does not fit')
        _, x, y = best
        c.uv = (x, y)
        new, end = [], x + fw
        for sx, sy, sw in sky:
            if sx + sw <= x or sx >= end:
                new.append([sx, sy, sw])
                continue
            if sx < x:
                new.append([sx, sy, x - sx])
            if sx + sw > end:
                new.append([end, sy, sx + sw - end])
        new.append([x, y + fh, fw])
        new.sort()
        sky = []
        for seg in new:
            if sky and sky[-1][1] == seg[1] and sky[-1][0] + sky[-1][2] == seg[0]:
                sky[-1][2] += seg[2]
            else:
                sky.append(seg)
    used = max(c.uv[1] + c.footprint()[1] for c in cubes)
    if used > H:
        raise SystemExit(f'works_art: {what}: texture too small, need {used} rows of {H}')


def meshes():
    return [
        Mesh('yard', 'yard', yard_parts(), 128, 64, 2,
             'The yard\'s pieces, heaped by [WorksArt.YARD]: "baulk" (timber, along x), "sticker" (the battens between '
             'its layers, along z), "ashlar" (stone), "crate" (glass packed in straw), "bar" (iron, along x) and "bale" '
             '(wool, along z), each standing on the middle of its underside.'),
        Mesh('hoist', 'hoist', hoist_parts(), 64, 64, 2,
             'The hoist\'s moving parts: "sheave" (the pulley, turning about z at its middle), "wrap" (the rope over its '
             'top, still), "rope" (a block\'s length, hanging from its top), "hook" (from the top of its eye), "load" (a '
             'baulk slung in two slings, from the hook\'s bottom) and "coil" (the fall\'s slack on the ground).'),
        Mesh('toLet', 'to_let', to_let_parts(), 32, 16, TO_LET_SCALE,
             'The "To let" board, "board", hanging from its rings\' tops at the origin (the line through the hooks), its '
             'lettered face toward -z (both faces are lettered).'),
        Mesh('slate', 'slate', slate_parts(), 32, 16, TO_LET_SCALE,
             'The slate in its frame, "slate", standing on the middle of its foot, its face toward -z: rubbed clean but '
             'for the ghosts of old chalk, for the words to be chalked on (client/streets/StreetSlate writes them).'),
        Mesh('alms', 'alms', alms_parts(), 128, 64, ALMS_SCALE,
             'The alms table\'s pieces on its middle block, placed by [WorksArt]\'s alms numbers: "pot" (its foot\'s '
             'middle at the origin), "stew_0" to "stew_7" (the stew\'s top, a frame each: one at a time), "ripple" '
             '(a ring on the stew 4 px across, to spread), "drop" (a splash, about its middle), "bowl" (the top of the '
             'stack: an empty bowl as LADLE takes it up), "bowl_under" (each under it, a hair bigger) and "ladle" (as '
             'it stands in the pot: drawn at [WorksArt.LADLE_REST]).'),
    ]



# ================================================================== the post's parchment, its seals and its stamp
#
# The post's renderer draws them on the notice board's face from post.png (entity/works/post.png, 256 x 128, four texels
# to a pixel): the works' parchment as a grid of quads that lifts off the board as the wind takes it (paper_lift), a red
# seal hanging on its cord from the parchment's folded foot for each tenth pledged, swinging (seal_swing), and once the
# works is funded the stamp pressed across the drawing (stamp_in). The parchment's texture is laid on the board's face as
# it's seen from the front (the north): its u runs from the board's east end to its west end, its v from the top down.

PAPER_TPX = 4
PAPER_LO, PAPER_HI = (2.0, 13.0), (14.0, 25.5)          # model (x, y) of the parchment's corners on the board's face
PAPER_Z = BOARD_LO[2] - 0.04                             # the face looks north (-z): the parchment just proud of it
PAPER_W = int(round((PAPER_HI[0] - PAPER_LO[0]) * PAPER_TPX))      # 48 texels
PAPER_H = int(round((PAPER_HI[1] - PAPER_LO[1]) * PAPER_TPX))      # 50
PLICA = 1.5                                              # px: the parchment's foot, folded up, the seals' cords through it
WORKS = ('oven', 'granary', 'great_granary', 'tower', 'stall')     # WorksKind's ids, in its order
PAPER_AT = {k: (1 + 51 * i, 1) for i, k in enumerate(WORKS)}       # each works' parchment's top left in post.png
POST_TEX = (256, 128)
SEALS = 10                                               # a seal for each tenth pledged
SEAL_W = 1.5                                             # a seal across (px)
SEAL_CORD = (1.5, 2.75)                                  # its cord (px): the even seals' and the odd ones', so they stagger
SEAL_V = 1.0 - 0.9 / (PAPER_HI[1] - PAPER_LO[1])        # v of the slits in the fold the cords come out of
SEAL_U = tuple(round((0.6 + 1.2 * k) / (PAPER_HI[0] - PAPER_LO[0]), 4) for k in range(SEALS))   # each one's u, first on the left
SEAL_AT = ((2, 56), (10, 56))                            # the short- and the long-corded seal's sprites in post.png
STAMP_AT, STAMP_TEX = (20, 56), 24                       # the stamp's sprite in post.png
STAMP = (0.74, 0.6, 5.2, 14.0)                           # the stamp pressed: its middle (u, v), across (px), turned (degrees,
                                                         # clockwise as seen)
INK = (66, 44, 30)
WASH = {'oven': (196, 112, 76), 'granary': (206, 170, 104), 'tower': (150, 146, 136), 'stall': (186, 64, 52),
        'roof': (150, 96, 70), 'dark': (40, 26, 18)}
WORKS_TITLE = {'oven': 'Second oven', 'granary': 'Granary', 'great_granary': 'Great granary', 'tower': 'Watchtower',
               'stall': 'Stall'}


def paper_xy(u, v):
    """The model (x, y) of the parchment's point (u, v)."""
    return PAPER_HI[0] + (PAPER_LO[0] - PAPER_HI[0]) * u, PAPER_HI[1] + (PAPER_LO[1] - PAPER_HI[1]) * v


class Sheet:
    """A parchment being drawn on, w x h texels: aged, a little torn at its edges, its foot folded up; ink lines, washes
    and hatching on it."""

    def __init__(self, w, h, seed):
        self.w, self.h = w, h
        self.xs, self.ys = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
        N = max(w, h)
        n = fbm(N, N, 9, seed)[:h, :w]
        d = np.minimum.reduce([self.xs, self.ys, w - self.xs, h - self.ys])
        t = 0.66 + 0.16 * (n - 0.5) - 0.22 * np.clip(1 - d / 4.0, 0, 1) + 0.05 * (rand(N, N, seed + 1)[:h, :w] - 0.5)
        stain = fbm(N, N, 14, seed + 2)[:h, :w] > 0.64
        t = np.where(stain, t - 0.07, t)
        fold = h - PLICA * PAPER_TPX
        t = np.where(self.ys > fold, t - 0.06, t)
        t = np.where(np.abs(self.ys - fold - 0.5) < 0.5, t - 0.2, t)
        t = np.where(np.abs(self.ys - fold - 1.5) < 0.5, t + 0.08, t)
        self.rgb = bands([(120, 96, 64), (168, 146, 106), (204, 186, 146), (226, 212, 176), (240, 230, 202), (248, 242, 222)],
                         t, 0.3).astype(float)
        self.a = np.ones((h, w))
        self.a[(d < 1.0) & (rand(N, N, seed + 3)[:h, :w] < 0.3)] = 0
        for cx in (0, w):
            self.a[np.hypot(self.xs - cx, self.ys - h) < 2.2] = 0
        self.fold = fold

    def ink(self, pts, width=0.75, col=INK, k=1.0):
        d = np.full((self.h, self.w), 1e9)
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            dd, _ = seg_dist(x0, y0, x1, y1, self.w, self.h)
            d = np.minimum(d, dd)
        cov = np.clip(width + 0.5 - d, 0, 1) * k
        self.rgb = self.rgb * (1 - cov[..., None]) + np.array(col, float) * cov[..., None]

    def box(self, x0, y0, x1, y1, **kw):
        self.ink([(x0, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0)], **kw)

    def wash(self, mask, col, k=0.32):
        self.rgb = np.where(mask[..., None], self.rgb * (1 - k) + np.array(col, float) * k, self.rgb)

    def hatch(self, mask, step=2.0, slope=1.0, k=0.55):
        line = ((self.xs * slope + self.ys) % step) < 0.7
        self.rgb = np.where((mask & line)[..., None], self.rgb * (1 - k) + np.array(INK, float) * k, self.rgb)

    def poly(self, pts):
        return poly(pts, self.w, self.h)

    def rect(self, x0, y0, x1, y1):
        return (self.xs >= x0) & (self.xs < x1) & (self.ys >= y0) & (self.ys < y1)

    def dot(self, x, y, r, col, k=1.0):
        m = np.hypot(self.xs - x, self.ys - y) < r
        self.rgb = np.where(m[..., None], self.rgb * (1 - k) + np.array(col, float) * k, self.rgb)

    def write(self, text, base, ppf=0.15, col=INK, maxw=None):
        """[text] in the board's ink hand centred on the baseline row [base], shrunk to [maxw] texels if it's longer."""
        maxw = maxw or self.w - 8
        al, tw_, b = letters(ML.INK, text, ppf, PAPER_TPX, seed=7)
        if tw_ > maxw:
            al, tw_, b = letters(ML.INK, text, ppf * maxw / tw_, PAPER_TPX, seed=7)
        ox, oy = int((self.w - tw_) // 2), int(round(base)) - b
        hh, ww = al.shape
        y0, x0 = max(0, oy), max(0, ox)
        y1, x1 = min(self.h, oy + hh), min(self.w, ox + ww)
        m = np.zeros((self.h, self.w))
        m[y0:y1, x0:x1] = al[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
        k = np.clip(m * 1.1, 0, 1)[..., None] * 0.92
        self.rgb = self.rgb * (1 - k) + np.array(col, float) * k

    def slits(self):
        """The ten slits in the fold for the seals' cords, each a little dark nick."""
        y = SEAL_V * self.h
        for u in SEAL_U:
            x = u * self.w
            self.ink([(x - 0.7, y), (x + 0.7, y)], 0.3, col=(70, 52, 34), k=0.9)

    def nails(self):
        for x in (3.0, self.w - 3.0):
            self.dot(x + 0.3, 3.3, 1.5, (60, 50, 40), 0.35)
            self.dot(x, 3.0, 1.2, IRONS[2])
            self.dot(x - 0.35, 2.65, 0.5, IRONS[4])

    def image(self):
        return Image.fromarray(np.dstack([np.clip(self.rgb, 0, 255), self.a * 255]).round().astype(np.uint8), 'RGBA')


def draw_oven(sh):
    """A domed bread oven on its hearth: brick courses, the mouth's arch at the front, the flue behind with its cap and a
    curl of smoke, a measured line under it."""
    g = 28.5
    sh.ink([(3, g), (45, g)], 0.5, k=0.7)
    sh.wash(sh.rect(9, 21, 39, g), WASH['oven'])
    sh.box(9, 21, 39, g)
    for y in (23.5, 26.0):
        sh.ink([(9, y), (39, y)], 0.35, k=0.6)
    for i, y in enumerate((21, 23.5, 26.0)):
        for x in np.arange(11 + (i % 2) * 2.5, 39, 5.0):
            sh.ink([(x, y), (x, y + 2.5)], 0.35, k=0.55)
    dome = (((sh.xs - 24) / 13) ** 2 + ((sh.ys - 21) / 11) ** 2 <= 1) & (sh.ys < 21)
    sh.wash(dome, WASH['oven'], 0.4)
    sh.hatch(dome & (sh.xs > 29), 2.2, -1.0, 0.35)
    sh.ink([(24 + 13 * math.cos(a), 21 - 11 * math.sin(a)) for a in np.linspace(0, math.pi, 24)], 0.6)
    mouth = (((sh.xs - 24) / 3.6) ** 2 + ((sh.ys - 21) / 4.2) ** 2 <= 1) & (sh.ys < 21)
    sh.wash(mouth, WASH['dark'], 0.85)
    sh.box(32, 4, 36, 13.5, width=0.5)
    sh.wash(sh.rect(32, 4, 36, 13.5), WASH['oven'], 0.3)
    sh.box(31, 3, 37, 4.6, width=0.5)
    sh.ink([(34.0, 2.4), (35.4, 1.2), (34.6, 0.2), (36.0, -0.8)], 0.3, k=0.45)
    sh.ink([(9, 31.0), (39, 31.0)], 0.3, k=0.6)
    for x in (9, 39):
        sh.ink([(x, 30.0), (x, 32.0)], 0.3, k=0.6)


def _staddle(sh, x, g):
    sh.ink([(x - 0.6, g), (x - 0.4, g - 4.5)], 0.5)
    sh.ink([(x + 0.6, g), (x + 0.4, g - 4.5)], 0.5)
    sh.ink([(x - 3, g - 4.6), (x + 3, g - 4.6)], 0.6)
    sh.ink([(x - 2.4, g - 5.6), (x + 2.4, g - 5.6)], 0.5)
    sh.wash(sh.rect(x - 2.6, g - 5.6, x + 2.6, g - 4.6) | sh.rect(x - 0.6, g - 4.5, x + 0.6, g), WASH['tower'], 0.45)


def _granary_body(sh, x0, x1, g, top):
    """A timber granary's box on its staddles: weatherboarded, framed and braced, over [x0, x1]."""
    sh.wash(sh.rect(x0, top, x1, g - 6), WASH['granary'])
    sh.box(x0, top, x1, g - 6)
    for y in np.arange(top + 1.6, g - 6, 1.6):
        sh.ink([(x0, y), (x1, y)], 0.25, k=0.35)
    for x in np.arange(x0 + 6, x1 - 1, 6):
        sh.ink([(x, top), (x, g - 6)], 0.45, k=0.8)
    sh.ink([(x0, g - 6), (x0 + 6, top)], 0.4, k=0.7)
    sh.ink([(x1, g - 6), (x1 - 6, top)], 0.4, k=0.7)


def _roof(sh, x0, x1, y0, peak):
    m = (x0 + x1) / 2
    roof = sh.poly([(x0 - 2, y0), (m, peak), (x1 + 2, y0)])
    sh.wash(roof, WASH['roof'], 0.35)
    sh.hatch(roof, 1.8, 0.0, 0.3)
    sh.ink([(x0 - 2, y0), (m, peak), (x1 + 2, y0), (x0 - 2, y0)], 0.55)


def draw_granary(sh):
    """A timber granary up on staddle stones: weatherboarded and framed, a gable roof, the loading door high in the
    gable with the hoist beam out over it, the steps up."""
    g = 28.5
    sh.ink([(3, g), (45, g)], 0.5, k=0.7)
    for x in (10, 19, 29, 38):
        _staddle(sh, x, g)
    _granary_body(sh, 7, 41, g, 13)
    _roof(sh, 7, 41, 13, 3.5)
    sh.box(21.5, 8.5, 26.5, 12.5, width=0.45)
    sh.wash(sh.rect(21.5, 8.5, 26.5, 12.5), (90, 60, 40), 0.5)
    sh.ink([(24, 6.5), (24, 2.6), (30, 2.6)], 0.5)
    sh.ink([(29.5, 2.8), (29.5, 6.2)], 0.25, k=0.7)
    sh.ink([(3.5, g), (7, g - 6)], 0.4)
    sh.ink([(5.5, g), (9, g - 6)], 0.4)
    for k in range(3):
        y = g - 1.5 - 1.6 * k
        sh.ink([(3.5 + 0.9 * (k + 0.8), y), (5.5 + 0.9 * (k + 0.8), y)], 0.3)


def draw_great_granary(sh):
    """The great granary: two bays under two gables side by side on more staddles, and the root cellar dug under it, its
    walls dashed below the ground line and the steps down to it."""
    g = 26.0
    sh.ink([(2, g), (46, g)], 0.5, k=0.7)
    for x in (6, 15, 24, 33, 42):
        _staddle(sh, x, g)
    _granary_body(sh, 3, 45, g, 12)
    _roof(sh, 3, 24, 12, 4.5)
    _roof(sh, 24, 45, 12, 4.5)
    for x0, x1 in ((11.5, 15.5), (32.5, 36.5)):
        sh.box(x0, 8.5, x1, 11.8, width=0.4)
        sh.wash(sh.rect(x0, 8.5, x1, 11.8), (90, 60, 40), 0.5)
    for x in np.arange(5, 44, 2.4):
        sh.ink([(x, g + 0.8), (x + 1.2, g + 0.8)], 0.3, k=0.75)
        sh.ink([(x, g + 5.8), (x + 1.2, g + 5.8)], 0.3, k=0.75)
    for y in np.arange(g + 0.8, g + 5.8, 2.0):
        sh.ink([(5, y), (5, y + 1.0)], 0.3, k=0.75)
        sh.ink([(43.4, y), (43.4, y + 1.0)], 0.3, k=0.75)
    sh.wash(sh.rect(5, g + 0.8, 43.4, g + 5.8), (120, 96, 70), 0.18)
    for k in range(4):
        sh.ink([(38 - 1.6 * k, g + 1.2 * k + 0.4), (39.6 - 1.6 * k, g + 1.2 * k + 0.4)], 0.3)


def draw_tower(sh):
    """The watchtower: a stone plinth, a timber tower on it braced crosswise, the watch platform with its rail and
    roof, the beacon's brazier ablaze on it and the horn hung at the rail."""
    g = 30.0
    sh.ink([(3, g), (45, g)], 0.5, k=0.7)
    sh.wash(sh.rect(15, 22, 33, g), WASH['tower'], 0.5)
    sh.box(15, 22, 33, g)
    for y in (24.6, 27.3):
        sh.ink([(15, y), (33, y)], 0.3, k=0.6)
    for i, y in enumerate((22, 24.6, 27.3)):
        for x in np.arange(17 + (i % 2) * 2, 33, 4.0):
            sh.ink([(x, y), (x, y + 2.6)], 0.3, k=0.55)
    sh.ink([(16.5, 22), (19, 9)], 0.6)
    sh.ink([(31.5, 22), (29, 9)], 0.6)
    for y0, y1 in ((22, 17.7), (17.7, 13.4), (13.4, 9.2)):
        xa, xb = 16.5 + (22 - y0) * 2.5 / 13, 31.5 - (22 - y0) * 2.5 / 13
        xc, xd = 16.5 + (22 - y1) * 2.5 / 13, 31.5 - (22 - y1) * 2.5 / 13
        sh.ink([(xa, y0), (xd, y1)], 0.35, k=0.8)
        sh.ink([(xb, y0), (xc, y1)], 0.35, k=0.8)
        sh.ink([(xc, y1), (xd, y1)], 0.35, k=0.8)
    sh.wash(sh.rect(14, 8, 34, 9.6), WASH['granary'], 0.5)
    sh.box(14, 8, 34, 9.6, width=0.45)
    for x in (14.5, 33.5):
        sh.ink([(x, 8), (x, 4.6)], 0.4)
    sh.ink([(14.5, 6.2), (33.5, 6.2)], 0.3, k=0.7)
    _roof(sh, 14.5, 33.5, 4.6, 0.8)
    flame = ((sh.xs - 24) / 2.2) ** 2 + ((sh.ys - 6.0) / 2.6) ** 2 <= 1
    sh.wash(flame, (236, 120, 40), 0.75)
    sh.wash(flame & (sh.ys > 6.0), (252, 200, 90), 0.6)
    sh.box(22, 7, 26, 8, width=0.35)
    sh.ink([(30.5, 6.4), (31.2, 7.6), (32.6, 7.8)], 0.45, col=(110, 80, 50))


def draw_stall(sh):
    """A market stall: a plank counter between two posts, an awning in stripes with a scalloped edge, goods set out on
    the counter, the patron's pennant on a pole beside it."""
    g = 29.0
    sh.ink([(3, g), (45, g)], 0.5, k=0.7)
    sh.wash(sh.rect(9, 20, 39, g), WASH['granary'], 0.3)
    sh.box(9, 20, 39, g)
    sh.ink([(9, 22), (39, 22)], 0.4)
    for x in np.arange(14, 39, 5):
        sh.ink([(x, 22), (x, g)], 0.25, k=0.45)
    for x in (10, 38):
        sh.ink([(x, 20), (x, 8.5)], 0.6)
    aw = sh.poly([(6, 9.5), (24, 5.5), (42, 9.5), (42, 11.5), (6, 11.5)])
    stripes = ((sh.xs // 4) % 2) == 0
    sh.wash(aw & stripes, WASH['stall'], 0.6)
    sh.wash(aw & ~stripes, (240, 230, 206), 0.4)
    sh.ink([(6, 9.5), (24, 5.5), (42, 9.5)], 0.5)
    for x in np.arange(6, 42, 4):
        sh.ink([(x + 2 + 2 * math.cos(a), 11.5 + 1.6 * math.sin(a)) for a in np.linspace(0, math.pi, 7)], 0.35)
    for x in (13, 15.6, 18.2):
        sh.dot(x, 19.0, 1.1, (176, 52, 40), 0.8)
    sh.box(27, 16.5, 33, 20, width=0.35)
    sh.wash(sh.rect(27, 16.5, 33, 20), WASH['granary'], 0.4)
    sh.ink([(44, g), (44, 7.5)], 0.45)
    sh.wash(sh.poly([(44, 7.5), (47.2, 9.0), (44, 10.5)]), WASH['stall'], 0.7)


DRAWINGS = {'oven': draw_oven, 'granary': draw_granary, 'great_granary': draw_great_granary, 'tower': draw_tower,
            'stall': draw_stall}


def seal_sprite(cord):
    """A red wax seal hanging on a cord of green silk: SEAL_W across, [cord] px of cord above it; the wax pressed flat
    with the guild's ring struck in it, lit from the upper left."""
    W = int(round(SEAL_W * PAPER_TPX))
    ch = int(round(cord * PAPER_TPX))
    H = ch + W
    xs, ys = np.meshgrid(np.arange(W) + 0.5, np.arange(H) + 0.5)
    a = np.zeros((H, W, 4))
    on = (np.abs(xs - W / 2) < 1.0) & (ys < ch + 1.5)
    twist = ((ys + xs * 0.9) % 2.0) < 1.0
    a[..., :3] = np.where(twist[..., None], np.array((60, 104, 70), float), np.array((34, 70, 46), float))
    a[..., 3] = np.where(on, 255, 0)
    cx, cy = W / 2, ch + W / 2
    ang = np.arctan2(ys - cy, xs - cx)
    rr = np.hypot(xs - cx, ys - cy)
    edge_r = W / 2 - 0.15 + 0.3 * np.sin(ang * 5 + 1.0)
    wax = rr <= edge_r
    lit = np.clip(((cx - xs) + (cy - ys)) / W, -1, 1)
    t = 0.5 + 0.3 * lit - 0.25 * np.clip(rr / edge_r - 0.75, 0, 1)
    ring = np.abs(rr - W * 0.27) < 0.55
    t = np.where(ring, t - 0.28, t)
    t = np.where(rr < 0.6, t - 0.2, t)
    col = bands([(70, 8, 12), (110, 16, 18), (150, 26, 28), (182, 42, 38), (218, 88, 74)], np.clip(t, 0, 1), 0.0)
    a[..., :3] = np.where(wax[..., None], col, a[..., :3])
    a[..., 3] = np.where(wax, 255, a[..., 3])
    return Image.fromarray(a.round().astype(np.uint8), 'RGBA')


def stamp_sprite():
    """The funded stamp, 24 x 24: a round impression in red-brown ink, two rings with a bead of dots between them and a
    hammer and trowel crossed in the middle, inked unevenly as a hand stamp is (its alpha: draw it translucent)."""
    S = STAMP_TEX
    xs, ys = np.meshgrid(np.arange(S) + 0.5, np.arange(S) + 0.5)
    c = S / 2
    r = np.hypot(xs - c, ys - c)
    ang = np.arctan2(ys - c, xs - c)
    m = (np.abs(r - 11.0) < 0.8) | (np.abs(r - 8.6) < 0.5)
    m |= (np.abs(r - 9.8) < 0.45) & ((np.floor((ang + math.pi) / math.tau * 24) % 2) == 0)

    def seg(x0, y0, x1, y1, w):
        return seg_dist(x0, y0, x1, y1, S)[0] < w
    m |= seg(c - 4.6, c + 4.6, c + 3.6, c - 3.6, 0.65)                   # the hammer's haft
    m |= seg(c + 2.0, c - 5.6, c + 5.6, c - 2.0, 1.15)                   # its head
    m |= seg(c + 4.6, c + 4.6, c - 1.6, c - 1.6, 0.55)                   # the trowel's handle
    m |= poly([(c - 1.4, c - 1.4), (c - 6.4, c - 2.6), (c - 2.6, c - 6.4)], S)   # its blade
    ink = (rand(S, S, 4101) > 0.18) & (fbm(S, S, 5, 4103) > 0.28)
    a = np.zeros((S, S, 4))
    a[..., :3] = np.array((140, 36, 40), float)
    a[..., 3] = np.where(m & ink, 225, np.where(m, 96, 0))
    return Image.fromarray(a.round().astype(np.uint8), 'RGBA')


def paper_sheet(kind):
    """The parchment of the works [kind]: its drawing, its name, the slits for its seals and the nails at its top."""
    sh = Sheet(PAPER_W, PAPER_H, 4001 + 17 * WORKS.index(kind))
    DRAWINGS[kind](sh)
    sh.write(WORKS_TITLE[kind], 39.0)
    sh.slits()
    sh.nails()
    return sh.image()


def post_texture():
    """post.png: each works' parchment in a row (PAPER_AT), the two seals and the stamp under them."""
    img = Image.new('RGBA', POST_TEX, (0, 0, 0, 0))
    for k in WORKS:
        img.alpha_composite(paper_sheet(k), PAPER_AT[k])
    for at, cord in zip(SEAL_AT, SEAL_CORD):
        img.alpha_composite(seal_sprite(cord), at)
    img.alpha_composite(stamp_sprite(), STAMP_AT)
    return img


def glow_texture():
    """beacon_glow.png: a soft round glow, orange to clear, for a quad facing the camera over the fire."""
    S = 64
    xs, ys = np.meshgrid(np.arange(S) + 0.5, np.arange(S) + 0.5)
    r = np.hypot(xs - S / 2, ys - S / 2) / (S / 2)
    k = np.clip(1 - r, 0, 1) ** 2.2
    a = np.zeros((S, S, 4))
    a[..., :3] = ramp([(120, 30, 10), (236, 110, 30), (255, 196, 110)], k)
    a[..., 3] = k * 200
    return Image.fromarray(a.round().astype(np.uint8), 'RGBA')


# ---------------------------------------------------------------- how the parchment, its seals and the stamp move
# (the same arithmetic as WorksArt.kt's, which kotlin_art() writes from these)

PAPER_WIND = 0.55                       # px: the most the wind lifts the parchment off the board (half again when wet)
SEAL_DROP = 6.0                         # ticks a new seal takes to drop out of its slit, before it swings and settles
STAMP_TICKS = 4.0                       # ticks the stamp's impression takes to settle


def gust(t):
    """The wind on the post at tick [t], 0 to 1: mostly a breath, now and then a gust."""
    s = 0.5 + 0.3 * math.sin(0.043 * t) + 0.2 * math.sin(0.0171 * t + 1.1)
    return 0.2 + 0.8 * s ** 3


def paper_lift(u, v, t, wet=False):
    """How far (px) the parchment's point (u, v) stands off the board at tick [t]: nailed at its top corners, its foot
    lifting and its lower corners curling in the gusts, a ripple running up it."""
    g = gust(t) * PAPER_WIND * (1.5 if wet else 1.0)
    shape = v ** 1.6 * (0.8 + 0.2 * abs(2 * u - 1))
    ripple = 0.75 + 0.25 * math.sin(6.0 * v - 0.31 * t + 2.0 * u)
    curl = 0.45 * v ** 4 * abs(2 * u - 1) ** 3 * (0.5 + 0.5 * math.sin(0.2 * t + 3.0 * u))
    return g * (shape * ripple + curl)


def seal_swing(k, t, wet=False):
    """Seal [k]'s swing at tick [t]: (degrees about its cord's top across the board, clockwise as seen; degrees its
    foot swings out from the board)."""
    g = gust(t) * (1.5 if wet else 1.0)
    side = (2.0 + 7.0 * g) * math.sin(0.13 * t + 1.7 * k) + 1.5 * math.sin(0.31 * t + 0.9 * k)
    out = (3.0 + 9.0 * g) * (0.5 + 0.5 * math.sin(0.09 * t + 2.3 * k))
    return side, out


def seal_drop(age, cord):
    """A new seal on a [cord] px cord, [age] ticks after its tenth came in: (px of its cord still drawn up into the
    slit, degrees more of swing): it drops out over SEAL_DROP ticks, then swings from the jolt as it settles."""
    if age < 0:
        return cord, 0.0
    if age < SEAL_DROP:
        u = age / SEAL_DROP
        return cord * (1.0 - u * u), 0.0
    a = age - SEAL_DROP
    return 0.0, 18.0 * math.exp(-a / 10.0) * math.sin(a * 0.55)


def stamp_in(age):
    """The stamp's impression [age] ticks after the works was funded: (its scale, its ink's opacity): struck a little
    big and faint, settling to its size and full colour over STAMP_TICKS."""
    if age < 0:
        return 1.0, 0.0
    if age < STAMP_TICKS:
        u = age / STAMP_TICKS
        return 1.0 + 0.12 * (1.0 - u) ** 2, 0.55 + 0.45 * u
    return 1.0, 1.0


# ================================================================== the yard's heaps (WorksArt.YARD)

def yard_layout():
    """Each display's heap: {stuff: (full, [(part, n, x, y, z, yaw)])}, the pieces in the order they go on: a piece
    shows once n of its stuff's are there (the timber's stickers with the first baulk they go under), standing on the
    middle of its underside at (x, y, z) px of the display's block model, turned yaw degrees about up (counterclockwise
    seen from above); a little out of true each, as a heap set by hand is."""
    rng = np.random.default_rng(7001)

    def j(a):
        return round(float(rng.uniform(-a, a)), 3)
    out = {}
    # Timber: four layers of three baulks along x on the bearers, a sticker across them at each end under each layer
    pcs, n = [], 0
    for i in range(4):
        y = 1.5 + i * (BAULK[1] + STICKER[1])
        if i:
            for x in (3.0, 13.0):
                pcs.append(('sticker', n + 1, round(x + j(0.15), 3), round(y - STICKER[1], 3), round(8.0 + j(0.1), 3), j(1.0)))
        for z in (12.5, 8.0, 3.5):
            n += 1
            pcs.append(('baulk', n, round(8.0 + j(0.4), 3), round(y, 3), round(z + j(0.1), 3), j(0.8)))
    out['timber'] = (n, pcs)
    # Stone: dressed blocks on the pallet, six, then four over their joints, then two
    pcs, n = [], 0
    for y, xs in ((1.6, (3.4, 8.0, 12.6)), (1.6 + ASHLAR[1], (5.7, 10.3)), (1.6 + 2 * ASHLAR[1], (8.0,))):
        for z in (11.3, 4.5):
            for x in xs:
                n += 1
                pcs.append(('ashlar', n, round(x + j(0.08), 3), round(y, 3), round(z + j(0.1), 3), j(1.5)))
    out['stone'] = (n, pcs)
    # Glass: crates on the straw, four, then two across their joins
    pcs, n = [], 0
    for x, y, z in ((4.3, 1.2, 11.7), (11.7, 1.2, 11.7), (4.3, 1.2, 4.3), (11.7, 1.2, 4.3), (4.3, 7.2, 8.0), (11.7, 7.2, 8.0)):
        n += 1
        pcs.append(('crate', n, round(x + j(0.05), 3), y, round(z + j(0.05), 3), j(1.0)))
    out['glass'] = (n, pcs)
    # Iron: bars along x on the bearers, laid five, four, three, two and one, each on the two under it
    pcs, n = [], 0
    for layer in range(5):
        y = 1.2 + layer * BAR[1]
        for z in np.arange(4.0 + layer, 12.0 - layer + 1e-6, 2.0):
            n += 1
            pcs.append(('bar', n, round(8.0 + j(0.5), 3), round(y, 3), round(float(z) + j(0.04), 3), j(0.8)))
    out['iron'] = (n, pcs)
    # Wool: bales along z on the boards, two side by side, three high
    pcs, n = [], 0
    for y in (1.0, 1.0 + BALE[1], 1.0 + 2 * BALE[1]):
        for x in (4.4, 11.6):
            n += 1
            pcs.append(('bale', n, round(x + j(0.04), 3), round(y, 3), round(8.0 + j(0.2), 3), j(0.8)))
    out['wool'] = (n, pcs)
    return out


def shown(here, bill, full):
    """How many of a heap's [full] pieces show with [here] units in the yard of a [bill]: none when there's none, all of
    them when it's all here, and one as soon as there's any."""
    if here <= 0 or bill <= 0:
        return 0
    return min(full, max(1, math.ceil(full * here / bill)))


DROP_TICKS = 8.0                        # a piece set on the heap: dropped a hand's height, a little bounce
TAKE_TICKS = 6.0                        # a piece taken off: lifted away, shrinking as it goes


def drop(age):
    """px a newly set piece is still above its place [age] ticks after it came."""
    if age < 0:
        return 4.0
    if age < 5.0:
        u = age / 5.0
        return 4.0 * (1.0 - u * u)
    if age < DROP_TICKS:
        return 0.4 * math.sin(math.pi * (age - 5.0) / (DROP_TICKS - 5.0))
    return 0.0


def take(age):
    """A piece taken [age] ticks ago: (px it's lifted, its scale); gone at TAKE_TICKS."""
    u = min(1.0, max(0.0, age / TAKE_TICKS))
    return 5.0 * u ** 0.7, 1.0 - u * u


# ================================================================== the hoist at work (WorksArt.HOIST_*)

HAUL_LEN = 40                           # HOIST's loop (ticks), and its four heaves, a hand's pull each (works_clips' hoist)
HAUL_HEAVES = (0.5, 10.5, 20.5, 30.5)
HAUL_PULL = 11.2                        # px of rope a heave hauls (works_clips' HO_HIGH to HO_LOW)
HAUL_PULL_TICKS = 5.5
HAUL_EASE = 2.2
HAUL_LOOP = HAUL_PULL * len(HAUL_HEAVES)        # px a loop: 44.8
HOOK_TOP = SHEAVE_AT[1] - SHEAVE_R - 1.5        # the highest the hook's eye comes, under the pulley
HOOK_LOAD = 5.0                         # px under the hook's eye the load's slings hang from (the hook's bow)
HOOK_REST = 12.0                        # px over the ground the hook's eye hangs at with its load on the ground
HOIST_LAND = 12.0                       # ticks the load hangs at the top while the builders take it in
HOIST_LOWER = 2.0                       # px a tick the hook is let down again
COIL_AT = (8.0 - 3.4, 0.0, -8.0 - 4.0)  # the rope's coil on the ground by the hauler (the hoist's model, y from the ground)


def haul(t):
    """px of rope hauled at tick [t] of HOIST's loop (0 to HAUL_LEN): works_clips' ho_hauled, in closed form (that
    script checks the two agree)."""
    total = 0.0
    for s in HAUL_HEAVES:
        u = min(1.0, max(0.0, (t - s) / HAUL_PULL_TICKS))
        total += HAUL_PULL * (1.0 - (1.0 - u) ** HAUL_EASE)
    return total


def hauled(ticks):
    """px of rope hauled [ticks] after the hauling began."""
    laps = math.floor(ticks / HAUL_LEN)
    return laps * HAUL_LOOP + haul(ticks - laps * HAUL_LEN)


def ticks_for(travel):
    """How many ticks of hauling it takes to haul [travel] px of rope."""
    laps = math.floor(travel / HAUL_LOOP)
    rest = travel - laps * HAUL_LOOP
    lo, hi = 0.0, float(HAUL_LEN)
    for _ in range(40):
        mid = (lo + hi) / 2
        if haul(mid) < rest:
            lo = mid
        else:
            hi = mid
    return laps * HAUL_LEN + hi


def hook_at(elapsed, travel):
    """The hook's height over its rest (px) [elapsed] ticks into a haul of [travel] px, and whether the load hangs on
    it: up with the rope, held at the top for HOIST_LAND ticks, then let down empty."""
    up = ticks_for(travel)
    if elapsed <= up:
        return hauled(max(0.0, elapsed)), True
    if elapsed <= up + HOIST_LAND:
        return travel, True
    return max(0.0, travel - HOIST_LOWER * (elapsed - up - HOIST_LAND)), False


# ================================================================== the rest of the renderers' numbers

TO_LET_SWAY = ((5.0, 0.07), (2.5, 0.031))       # the board's two sways: degrees and radians a tick
GLOW = {'lit': ((8.0, 19.0, 8.0), 26.0), 'flare': ((8.0, 23.0, 8.0), 44.0)}     # the glow's middle and px across
SLATE_LEAN = 14.0                       # degrees the slate leans back against the display
PLAQUE_TEXT_Z = PLAQUE_HI[2] + 0.02
PLAQUE_INK = (52, 30, 18)               # the cast letters' colour, and their lit edge a hair up and to the left
PLAQUE_EDGE = (226, 186, 118)


def to_let_swing(t, phase, wet=False):
    """Degrees the "To let" board swings out on its hooks at tick [t] (its foot toward the street when positive);
    [phase]: from its position, so no two swing together; twice as far in rain."""
    (a1, w1), (a2, w2) = TO_LET_SWAY
    return (2.0 if wet else 1.0) * (a1 * math.sin(w1 * t + phase) + a2 * math.sin(w2 * t + 2.0 * phase))


def glow_pulse(t):
    """The beacon's glow's flicker at tick [t], about 1."""
    return 0.86 + 0.1 * math.sin(0.9 * t) + 0.06 * math.sin(2.3 * t + 1.0)


# ================================================================== WorksArt.kt and WorksMesh.kt

KT_HEADER = '// GENERATED by art/scripts/works_art.py: edit the script, not this file.\n'


def kf(v):
    """A Kotlin float literal."""
    s = ('%.4f' % float(v)).rstrip('0').rstrip('.')
    if s in ('-0', ''):
        s = '0'
    return s + 'f'


def kt_floats(vals):
    return 'floatArrayOf(' + ', '.join(kf(v) for v in vals) + ')'


def kotlin_art():
    """WorksArt.kt: every number the works' renderers draw by, from this script."""
    yard = yard_layout()
    heaps = []
    for s in STUFF:
        full, pcs = yard[s]
        rows = ',\n'.join(f'                Piece("{p}", {n}, {kf(x)}, {kf(y)}, {kf(z)}, {kf(yaw)})' for p, n, x, y, z, yaw in pcs)
        heaps.append(f'        "{s}" to Heap(\n            {full},\n            listOf(\n{rows},\n            ),\n        ),')
    heaps = '\n'.join(heaps)
    papers = ',\n'.join(f'        "{k}" to intArrayOf({x}, {y})' for k, (x, y) in PAPER_AT.items())
    sx, sy, sz = SHEAVE_AT
    plaque = ',\n'.join(f'        floatArrayOf({kf(b)}, {kf(cap)}, {kf(w)})' for b, cap, w in
                        (PLAQUE_LINES['above'], PLAQUE_LINES['name'], PLAQUE_LINES['below']))
    (gl_at, gl_w), (gf_at, gf_w) = GLOW['lit'], GLOW['flare']
    (a1, w1), (a2, w2) = TO_LET_SWAY

    def rgb(c):
        return '0x%02X%02X%02X' % tuple(int(v) for v in c)
    return KT_HEADER + f'''package io.github.vaspyyy.guildmark.client.works

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The town works' blocks as their renderers draw them (living-world/works-art.md says which renderer draws what, and
 * how): the yard's heaps, the post's parchment, seals and stamp, the plaque's lettering, the hoist at work, the "To let"
 * board's swing, the beacon's glow, the slate's lean, and the alms table's pot, stew, bowls and ladle, ladled from and
 * given to. Sizes are pixels (a sixteenth of a block) in the block's own
 * model space (x east, y up, z south, the block facing north, its front toward -z), which a renderer turns by its facing
 * as the blockstate turns the model; times are ticks; the meshes are [WorksMesh]'s.
 */
internal object WorksArt {{
    const val YARD_TEXTURE = "textures/entity/works/yard.png"
    const val HOIST_TEXTURE = "textures/entity/works/hoist.png"
    const val TO_LET_TEXTURE = "textures/entity/works/to_let.png"
    const val SLATE_TEXTURE = "textures/entity/works/slate.png"
    const val ALMS_TEXTURE = "textures/entity/works/alms.png"
    const val POST_TEXTURE = "textures/entity/works/post.png"
    const val GLOW_TEXTURE = "textures/entity/works/beacon_glow.png"

    // ---------------------------------------------------------------- the yard

    /**
     * A piece of a yard display: [part] of [WorksMesh.yard], shown once [n] of its heap's pieces are there, standing on
     * the middle of its underside at [x], [y], [z], turned [yaw] degrees about up (counterclockwise seen from above).
     */
    class Piece(val part: String, val n: Int, val x: Float, val y: Float, val z: Float, val yaw: Float)

    /** A display's heap: [full] pieces with all its bill there, and its pieces in the order they go on. */
    class Heap(val full: Int, val pieces: List<Piece>)

    /** The heaps, by [io.github.vaspyyy.guildmark.homestead.plan.Stuff.key]. */
    val YARD: Map<String, Heap> = mapOf(
{heaps}
    )

    /** How many of a heap's [full] pieces show with [here] units of its stuff in the yard of a [bill]. */
    fun shown(here: Int, bill: Int, full: Int): Int =
        if (here <= 0 || bill <= 0) 0 else min(full, max(1, ceil(full * here.toDouble() / bill).toInt()))

    const val DROP_TICKS = {kf(DROP_TICKS)}
    const val TAKE_TICKS = {kf(TAKE_TICKS)}

    /** px a newly set piece is still above its place [age] ticks after it came: dropped a hand's height, a bounce. */
    fun drop(age: Float): Float = when {{
        age < 0f -> 4f
        age < 5f -> 4f * (1f - (age / 5f) * (age / 5f))
        age < DROP_TICKS -> 0.4f * sin(PI * (age - 5f) / (DROP_TICKS - 5f))
        else -> 0f
    }}

    /** px a piece taken [age] ticks ago is lifted away (it's gone at [TAKE_TICKS]). */
    fun takeLift(age: Float): Float = 5f * (age / TAKE_TICKS).coerceIn(0f, 1f).pow(0.7f)

    /** Its scale meanwhile. */
    fun takeScale(age: Float): Float {{
        val u = (age / TAKE_TICKS).coerceIn(0f, 1f)
        return 1f - u * u
    }}

    // ---------------------------------------------------------------- the post

    const val POST_TEX_W = {POST_TEX[0]}
    const val POST_TEX_H = {POST_TEX[1]}

    /** Texels of post.png to a pixel. */
    const val PAPER_TPX = {PAPER_TPX}

    /** The parchment on the board's face: x where its u is 0 and 1 (east end, west end), y where its v is 0 and 1. */
    const val PAPER_X0 = {kf(PAPER_HI[0])}
    const val PAPER_X1 = {kf(PAPER_LO[0])}
    const val PAPER_Y0 = {kf(PAPER_HI[1])}
    const val PAPER_Y1 = {kf(PAPER_LO[1])}

    /** The board's face (z), the parchment's back on it. */
    const val PAPER_Z = {kf(PAPER_Z)}
    const val PAPER_W = {PAPER_W}
    const val PAPER_H = {PAPER_H}

    /** Each works' parchment's top left in post.png (texels), by WorksKind.id. */
    val PAPER_AT: Map<String, IntArray> = mapOf(
{papers},
    )

    /** px the wind lifts the parchment at most (half again in rain). */
    const val PAPER_WIND = {kf(PAPER_WIND)}

    /** The wind on the post at tick [t], 0 to 1: mostly a breath, now and then a gust. */
    fun gust(t: Float): Float {{
        val s = 0.5f + 0.3f * sin(0.043f * t) + 0.2f * sin(0.0171f * t + 1.1f)
        return 0.2f + 0.8f * s * s * s
    }}

    /**
     * px the parchment's point ([u], [v]) stands off the board (toward -z) at tick [t]: nailed at its top corners, its
     * foot lifting and its lower corners curling in the gusts, a ripple running up it.
     */
    fun paperLift(u: Float, v: Float, t: Float, wet: Boolean): Float {{
        val g = gust(t) * PAPER_WIND * (if (wet) 1.5f else 1f)
        val shape = v.pow(1.6f) * (0.8f + 0.2f * abs(2f * u - 1f))
        val ripple = 0.75f + 0.25f * sin(6f * v - 0.31f * t + 2f * u)
        val curl = 0.45f * v.pow(4) * abs(2f * u - 1f).pow(3) * (0.5f + 0.5f * sin(0.2f * t + 3f * u))
        return g * (shape * ripple + curl)
    }}

    const val SEALS = {SEALS}

    /** A seal's width (px), and the length of the cord above it: [SEAL_CORD]`[k % 2]`. */
    const val SEAL_W = {kf(SEAL_W)}
    val SEAL_CORD = {kt_floats(SEAL_CORD)}

    /** The slits the cords come out of: their v, and each seal's u (the first on the left as seen). */
    const val SEAL_V = {kf(SEAL_V)}
    val SEAL_U = {kt_floats(SEAL_U)}

    /** The short- and the long-corded seal's sprites in post.png: top left, texels; each SEAL_W wide, cord and seal tall. */
    val SEAL_SPRITE = arrayOf(intArrayOf({SEAL_AT[0][0]}, {SEAL_AT[0][1]}), intArrayOf({SEAL_AT[1][0]}, {SEAL_AT[1][1]}))

    /** Seal [k]'s swing at tick [t]: degrees about its cord's top across the board (clockwise as seen), and out from it. */
    fun sealSwing(k: Int, t: Float, wet: Boolean): FloatArray {{
        val g = gust(t) * (if (wet) 1.5f else 1f)
        val side = (2f + 7f * g) * sin(0.13f * t + 1.7f * k) + 1.5f * sin(0.31f * t + 0.9f * k)
        val out = (3f + 9f * g) * (0.5f + 0.5f * sin(0.09f * t + 2.3f * k))
        return floatArrayOf(side, out)
    }}

    const val SEAL_DROP = {kf(SEAL_DROP)}

    /**
     * A new seal on a [cord] px cord, [age] ticks after its tenth came in: px of its cord still drawn up into the slit
     * (hide that much of the sprite's top), and degrees more of swing; it drops out, then swings from the jolt.
     */
    fun sealDrop(age: Float, cord: Float): FloatArray {{
        if (age < 0f) return floatArrayOf(cord, 0f)
        if (age < SEAL_DROP) {{
            val u = age / SEAL_DROP
            return floatArrayOf(cord * (1f - u * u), 0f)
        }}
        val a = age - SEAL_DROP
        return floatArrayOf(0f, 18f * exp(-a / 10f) * sin(a * 0.55f))
    }}

    /** The stamp's sprite in post.png (texels): top left and size. */
    const val STAMP_U0 = {STAMP_AT[0]}
    const val STAMP_V0 = {STAMP_AT[1]}
    const val STAMP_TEX = {STAMP_TEX}

    /** Where it's pressed: its middle (u, v on the parchment), px across, degrees turned clockwise as seen. */
    const val STAMP_U = {kf(STAMP[0])}
    const val STAMP_V = {kf(STAMP[1])}
    const val STAMP_SIZE = {kf(STAMP[2])}
    const val STAMP_TURN = {kf(STAMP[3])}
    const val STAMP_TICKS = {kf(STAMP_TICKS)}

    /** The stamp's impression [age] ticks after the works was funded: its scale and its ink's opacity (struck big and faint, settling). */
    fun stampIn(age: Float): FloatArray {{
        if (age < 0f) return floatArrayOf(1f, 0f)
        if (age < STAMP_TICKS) {{
            val u = age / STAMP_TICKS
            return floatArrayOf(1f + 0.12f * (1f - u) * (1f - u), 0.55f + 0.45f * u)
        }}
        return floatArrayOf(1f, 1f)
    }}

    // ---------------------------------------------------------------- the plaque

    /**
     * Its three lines (above the name, the name, below it): each one's baseline (y), its capitals' height and the most
     * it may be across (px), centred on x 8; shrink a line to fit. Written on the plate's face at [PLAQUE_TEXT_Z], facing
     * +z (the plaque's model faces south).
     */
    val PLAQUE_LINES = arrayOf(
{plaque},
    )
    const val PLAQUE_TEXT_Z = {kf(PLAQUE_TEXT_Z)}

    /** The cast letters' colour, and their lit edge, drawn under them a tenth of a pixel up and to the left. */
    const val PLAQUE_INK = {rgb(PLAQUE_INK)}
    const val PLAQUE_EDGE = {rgb(PLAQUE_EDGE)}

    // ---------------------------------------------------------------- the hoist

    /** The pulley's middle (its axle along z) and the rope's radius round it: the falls hang at x SHEAVE_X +- SHEAVE_R. */
    const val SHEAVE_X = {kf(sx)}
    const val SHEAVE_Y = {kf(sy)}
    const val SHEAVE_Z = {kf(sz)}
    const val SHEAVE_R = {kf(SHEAVE_R)}

    /** The hauled fall (the hauler's, east) and the load's (west). */
    const val HAUL_X = {kf(sx + SHEAVE_R)}
    const val LOAD_X = {kf(sx - SHEAVE_R)}

    /** The hauled fall ends this far over the hauler's ground while HOIST plays (the clip draws it on from there). */
    const val HAUL_TOP = {kf(HOIST_ROPE_TOP)}

    /** The coil of slack on the ground by the hauler (x, z). */
    const val COIL_X = {kf(COIL_AT[0])}
    const val COIL_Z = {kf(COIL_AT[2])}

    /**
     * The highest the hook's eye comes (y), how far over the ground it hangs with its load on the ground (a haul's
     * travel is from there to HOOK_TOP), and how far under its eye the load's slings hang from ([WorksMesh.hoist]'s
     * "load" from there).
     */
    const val HOOK_TOP = {kf(HOOK_TOP)}
    const val HOOK_REST = {kf(HOOK_REST)}
    const val HOOK_LOAD = {kf(HOOK_LOAD)}

    /** Ticks the load hangs at the top while it's taken in, and px a tick the hook is let down after. */
    const val HOIST_LAND = {kf(HOIST_LAND)}
    const val HOIST_LOWER = {kf(HOIST_LOWER)}

    const val HAUL_LEN = {HAUL_LEN}
    const val HAUL_LOOP = {kf(HAUL_LOOP)}
    private val HEAVES = {kt_floats(HAUL_HEAVES)}

    /** px of rope hauled at tick [t] of HOIST's loop (0 to [HAUL_LEN]), heave by heave as the clip hauls it. */
    fun haul(t: Float): Float {{
        var total = 0f
        for (s in HEAVES) {{
            val u = ((t - s) / {kf(HAUL_PULL_TICKS)}).coerceIn(0f, 1f)
            total += {kf(HAUL_PULL)} * (1f - (1f - u).pow({kf(HAUL_EASE)}))
        }}
        return total
    }}

    /** px of rope hauled [ticks] after the hauling began. */
    fun hauled(ticks: Float): Float {{
        val laps = floor(ticks / HAUL_LEN)
        return laps * HAUL_LOOP + haul(ticks - laps * HAUL_LEN)
    }}

    /** Ticks of hauling it takes to haul [travel] px of rope. */
    fun ticksFor(travel: Float): Float {{
        val laps = floor(travel / HAUL_LOOP)
        val rest = travel - laps * HAUL_LOOP
        var lo = 0f
        var hi = HAUL_LEN.toFloat()
        repeat(24) {{
            val mid = (lo + hi) / 2f
            if (haul(mid) < rest) lo = mid else hi = mid
        }}
        return laps * HAUL_LEN + hi
    }}

    /**
     * The hook's height over its rest (px) [elapsed] ticks into a haul of [travel] px: up with the rope, held at the
     * top [HOIST_LAND] ticks, then let down. The load hangs on it until [loaded] says not.
     */
    fun hookAt(elapsed: Float, travel: Float): Float {{
        val up = ticksFor(travel)
        return when {{
            elapsed <= up -> hauled(max(0f, elapsed))
            elapsed <= up + HOIST_LAND -> travel
            else -> max(0f, travel - HOIST_LOWER * (elapsed - up - HOIST_LAND))
        }}
    }}

    fun loaded(elapsed: Float, travel: Float): Boolean = elapsed <= ticksFor(travel) + HOIST_LAND

    // ---------------------------------------------------------------- the "To let" board, the beacon, the slate

    /** The line through the board's hooks, which it swings about (along x). */
    const val TO_LET_X = {kf(TO_LET_PIVOT[0])}
    const val TO_LET_Y = {kf(TO_LET_PIVOT[1])}
    const val TO_LET_Z = {kf(TO_LET_PIVOT[2])}

    /** Degrees the board swings on its hooks at tick [t] ([phase] from its position; twice as far in rain). */
    fun toLetSwing(t: Float, phase: Float, wet: Boolean): Float =
        (if (wet) 2f else 1f) * ({kf(a1)} * sin({kf(w1)} * t + phase) + {kf(a2)} * sin({kf(w2)} * t + 2f * phase))

    /** The beacon's glow: its middle and px across, lit and flaring. */
    val GLOW_LIT = {kt_floats(list(gl_at) + [gl_w])}
    val GLOW_FLARE = {kt_floats(list(gf_at) + [gf_w])}

    /** The glow's flicker at tick [t], about 1. */
    fun glowPulse(t: Float): Float = 0.86f + 0.1f * sin(0.9f * t) + 0.06f * sin(2.3f * t + 1f)

    /** Degrees the slate leans back against the display (its foot at the display's front middle). */
    const val SLATE_LEAN = {kf(SLATE_LEAN)}

    /**
     * The slate ([WorksMesh.slate]: its foot's middle at the mesh's origin, its face toward -z): px across, high and
     * thick; and the chalk's room on its face, inside the frame (px across and high, its middle half the slate's
     * height up), for client/streets/StreetSlate's words in place of its FACE_W and FACE_H.
     */
    val SLATE_SIZE = {kt_floats(SLATE_BOARD)}
    val SLATE_CHALK = {kt_floats((SLATE_BOARD[0] - 2 * SLATE_FRAME, SLATE_BOARD[1] - 2 * SLATE_FRAME))}

    // ---------------------------------------------------------------- the alms table

    /*
     * The pot, its stew, the bowls and the ladle on the table's middle block ([WorksMesh.alms], drawn by its block
     * entity's renderer): in that block's model space, turned about its middle as the blockstate turns the trestle's
     * model (Axis.YP by 180 - facing.toYRot() degrees for a table whose front is to facing), each piece drawn at its
     * place here through poseStack.scale(-1f, -1f, 1f).
     */

    /** The table top's height, and the pot's foot's middle on it ("pot"). */
    const val ALMS_TOP = {kf(ALMS_TOP_Y)}
    const val POT_X = {kf(POT_AT[0])}
    const val POT_Z = {kf(POT_AT[1])}

    /** The pot's rim's height (its steam rises from here, over [POT_X], [POT_Z]). */
    const val POT_RIM = {kf(POT_RIM_Y)}

    /** The stew's top for a pot [fill] full (0 to 1: shown when there's any in it), "stew_k" drawn there. */
    fun stewY(fill: Float): Float = {kf(POT_FLOOR_Y)} + {kf(POT_FULL_Y - POT_FLOOR_Y)} * fill.coerceIn(0f, 1f)

    /** The stew's frames: "stew_0" up to [STEW_FRAMES], each [STEW_TICKS] ticks, round and round as it simmers. */
    const val STEW_FRAMES = {STEW_FRAMES}
    const val STEW_TICKS = {kf(STEW_TICKS)}

    fun stewFrame(t: Float): Int = floor(t / STEW_TICKS).toInt().mod(STEW_FRAMES)

    /** px the stew's top rocks up and down as it simmers, at tick [t]. */
    fun stewBob(t: Float): Float = 0.05f * sin(0.35f * t)

    /**
     * The stack of bowls on the keeper's left: its middle, and the [i]th bowl's foot up it (from 0); "bowl" the top
     * one, "bowl_under" every one under it. It shows [BOWLS_MAX] at most (draw min(bowls, BOWLS_MAX)): LADLE lifts the
     * top one off a stack of three (its take, [LADLE_TAKE]), and a taller stack would swallow the keeper's hand.
     */
    const val BOWLS_X = {kf(BOWLS_AT[0])}
    const val BOWLS_Z = {kf(BOWLS_AT[1])}
    const val BOWLS_MAX = {BOWLS_MAX}

    fun bowlY(i: Int): Float = ALMS_TOP + {kf(BOWL_H)} * i

    /** The ladle standing in the pot: its bowl's middle ("ladle", already turned as it leans). */
    val LADLE_REST = {kt_floats(ladle_rest_at())}

    /**
     * LADLE's moments, ticks from its start: the bowl lifted off the stack, the ladle in the stew, the pour into the
     * bowl and the bowl given. The keeper holds the ladle from its first tick to its last (the table's own is hidden
     * meanwhile); the stack is a bowl short from the take.
     */
    const val LADLE_LEN = {kf(30)}
    const val LADLE_TAKE = {kf(3.5)}
    const val LADLE_DIP = {kf(7.5)}
    const val LADLE_POUR = {kf(12.5)}
    const val LADLE_GIVE = {kf(21.0)}

    /** Where the ladle goes into the stew (x, z): its ripple spreads from here from [LADLE_DIP]. */
    const val DIP_X = {kf(POT_AT[0] - 0.2)}
    const val DIP_Z = {kf(POT_AT[1] - 0.2)}

    /** "ripple" (4 px across): the scale of it across [age] ticks after it starts, gone after [RIPPLE_TICKS]. */
    const val RIPPLE_TICKS = {kf(RIPPLE_TICKS)}

    fun rippleScale(age: Float): Float {{
        val u = (age / RIPPLE_TICKS).coerceIn(0f, 1f)
        return {kf(0.36)} + {kf(1.6)} * (1f - (1f - u) * (1f - u))
    }}

    /** A gift tipped in: its ring spreading from the pot's middle (as the ripple, quicker), and its splash. */
    const val SPLASH_TICKS = {kf(SPLASH_TICKS)}

    fun splashScale(age: Float): Float {{
        val u = (age / SPLASH_TICKS).coerceIn(0f, 1f)
        return {kf(0.5)} + {kf(1.5)} * (1f - (1f - u) * (1f - u))
    }}

    /**
     * The splash's drops ("drop"), [SPLASH_DROPS] of them: where the [j]th is [age] ticks on, from the stew's top over
     * the pot's middle (x, up, z px), or null once it's fallen back in.
     */
    const val SPLASH_DROPS = {SPLASH_DROPS}

    fun splashDrop(j: Int, age: Float): FloatArray? {{
        val up = {kf(SPLASH_UP)} * age - {kf(SPLASH_FALL)} * age * age
        if (age <= 0f || up < 0f) return null
        val a = (j * 60f + 23f) * PI / 180f
        val out = 0.4f + {kf(SPLASH_OUT)} * age
        return floatArrayOf(out * cos(a), up, out * sin(a))
    }}

    /** The pot's outline over the middle block for its block's shape (a box: x0, y0, z0, x1, y1, z1). */
    val POT_SHAPE = {kt_floats(POT_SHAPE)}

    private const val PI = {kf(math.pi)}
}}
'''


def kotlin_mesh(meshes_):
    def body(m):
        return m.model.kotlin().rstrip('\n')
    funs = []
    for m in meshes_:
        funs.append(f'''    /** {m.doc} On a {m.model.tex_w} x {m.model.tex_h} texture ({m.texture}.png ships at {m.scale}x that). */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun {m.fun}(): LayerDefinition {{
{body(m)}
    }}''')
    funs = '\n\n'.join(funs)
    return KT_HEADER + f'''package io.github.vaspyyy.guildmark.client.works

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.core.Direction
import java.util.EnumSet

/**
 * The works' moving and heaped pieces, box UV, each texture painted and shipped at a higher resolution than declared
 * here (the textures are [WorksArt]'s). ModelPart space: pixels, y down, the front -z, x mirrored: a renderer turns a
 * block's model space into it with poseStack.scale(-1f, -1f, 1f).
 */
object WorksMesh {{
{funs}
}}
'''


# ================================================================== the previews

PREVIEW_BG = (40, 42, 52)


def true_rects(c):
    """A cube's six faces' uv rects in declared texels, from its float size, as ModelPart.Cube lays box UV."""
    u, v = c.uv
    w, h, d = c.size
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d), 'right': (u, v + d, d, h),
            'front': (u + d, v + d, w, h), 'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}


def part_faces(part, pose=None):
    """A part's (and its children's) cube faces in its own ModelPart space: (A, B, D, rect, cube), the corners at the
    rect's top left, along its u and along its v; [pose] {part name: rotation matrix} overrides a part's turn."""
    out = []
    pose = pose or {}

    def rot_of(p):
        if p.name in pose:
            return pose[p.name]
        rx, ry, rz = p.rot
        return Rz(rz) @ Ry(ry) @ Rx(rx)

    def go(p, M, T):
        R = M @ rot_of(p)
        P = T + M @ np.array(p.pivot, float)
        for c in p.cubes:
            g = c.inflate or 0.0
            x0, y0, z0 = (o - g for o in c.origin)
            x1, y1, z1 = (o + s + g for o, s in zip(c.origin, c.size))

            def W(x, y, z):
                return P + R @ np.array([x, y, z], float)
            quads = {'front': (W(x0, y0, z0), W(x1, y0, z0), W(x0, y1, z0)),
                     'back': (W(x1, y0, z1), W(x0, y0, z1), W(x1, y1, z1)),
                     'right': (W(x0, y0, z1), W(x0, y0, z0), W(x0, y1, z1)),
                     'left': (W(x1, y0, z0), W(x1, y0, z1), W(x1, y1, z0)),
                     'top': (W(x0, y0, z1), W(x1, y0, z1), W(x0, y0, z0)),
                     'bottom': (W(x0, y1, z1), W(x1, y1, z1), W(x0, y1, z0))}
            rects = true_rects(c)
            for face, (A, B, D) in quads.items():
                if c.shows(face):
                    out.append((A, B, D, rects[face]))
        for ch in p.children:
            go(ch, R, P)
    go(part, np.eye(3), np.zeros(3))
    return out


S_FLIP = np.diag([-1.0, -1.0, 1.0])


class Scene:
    """Faces gathered for island_blockview's renderer, in block model px (the first block's), from block models,
    meshes' parts and plain quads."""

    def __init__(self, assets):
        self.assets, self.faces = assets, []

    def _add(self, corners, vuv, ref, emit=False, shade=True):
        c = np.asarray(corners, float)
        n = np.cross(c[1] - c[0], c[2] - c[1])
        n = n / max(np.linalg.norm(n), 1e-9)
        sh = BV.SHADE[BV.nearest_direction(n)] if shade and not emit else 1.0
        self.faces.append((c, vuv, ref, sh, emit, n))

    def model(self, m, at=(0.0, 0.0, 0.0), y=0, R=None):
        """A block model (a dict) turned by a blockstate's [y] (or about its middle by [R]) with its block at [at] px."""
        Rm = R if R is not None else Ry(-y)
        mid = np.array([8.0, 0.0, 8.0])
        for c, vuv, ref, sh, emit, n in BV.faces_of(m):
            cc = (c - mid) @ Rm.T + mid + np.asarray(at, float)
            self._add(cc, vuv, ref, emit, shade=True)

    def mesh(self, mesh, part, place, pose=None, scale=1.0, double=False):
        """Part [part] of [mesh] by name, through [place] (a function of a ModelPart-space point to block px)."""
        p = next(q for q in mesh.parts if q.name == part)
        W, H = mesh.model.tex_w, mesh.model.tex_h
        ref = 'guildmark:entity/works/' + mesh.texture
        for A, B, D, (u, v, w, h) in part_faces(p, pose):
            a, b, d = place(A * scale), place(B * scale), place(D * scale)
            u0, v0, u1, v1 = u / W * 16, v / H * 16, (u + w) / W * 16, (v + h) / H * 16
            self._add([a, d, d + (b - a), b], [(u0, v0), (u0, v1), (u1, v1), (u1, v0)], ref)
            if double:
                self._add([b, b + (d - a), d, a], [(u1, v0), (u1, v1), (u0, v1), (u0, v0)], ref)

    def quad(self, a, b, d, uv, ref, emit=False, double=False, shade=True):
        """A quad from corner [a] along [b] - a (its u) and [d] - a (its v), uv (u0, v0, u1, v1) in 16ths."""
        a, b, d = (np.asarray(p, float) for p in (a, b, d))
        u0, v0, u1, v1 = uv
        self._add([a, d, d + (b - a), b], [(u0, v0), (u0, v1), (u1, v1), (u1, v0)], ref, emit, shade)
        if double:
            self._add([b, b + (d - a), d, a], [(u1, v0), (u1, v1), (u0, v1), (u0, v0)], ref, emit, shade)

    def render(self, **kw):
        faces = self.faces
        orig = BV.faces_of
        BV.faces_of = lambda m: faces
        try:
            kw.setdefault('bg', PREVIEW_BG)
            return Image.fromarray(BV.render_model({'elements': []}, self.assets, **kw))
        finally:
            BV.faces_of = orig


def placer(at=(0.0, 0.0, 0.0), yaw=0.0, y=0, extra=None):
    """ModelPart space to block px: scale(-1, -1, 1), turned [yaw] about up, at [at] (block px), the lot turned by a
    blockstate's [y] about the block's middle; [extra] a matrix applied in ModelPart space first."""
    Rb = Ry(-y)
    Rp = Ry(yaw)
    mid = np.array([8.0, 0.0, 8.0])
    at = np.asarray(at, float)

    def f(p):
        q = np.asarray(p, float)
        if extra is not None:
            q = extra @ q
        return (Rp @ (S_FLIP @ q) + at - mid) @ Rb.T + mid
    return f


def label(img, text, sub=None):
    d = ImageDraw.Draw(img)
    d.text((8, 6), text, fill=(240, 236, 220))
    if sub:
        d.text((8, 20), sub, fill=(176, 172, 160))
    return img


def tiles(images, cols, title=None, sub=None, bg=PREVIEW_BG, pad=6):
    """[images] in a grid [cols] wide, under a header with [title] and [sub] if given."""
    w, h = images[0].size
    rows = (len(images) + cols - 1) // cols
    top = 36 if title else 0
    out = Image.new('RGB', (cols * w + (cols + 1) * pad, top + rows * h + (rows + 1) * pad), tuple(int(c * 0.8) for c in bg))
    for i, im in enumerate(images):
        out.paste(im, (pad + (i % cols) * (w + pad), top + pad + (i // cols) * (h + pad)))
    if title:
        label(out, title, sub)
    return out


def save_gif(frames, path, ms=100):
    pal = [f.convert('RGB').quantize(colors=160, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE) for f in frames]
    pal[0].save(path, save_all=True, append_images=pal[1:], duration=ms, loop=0, optimize=True, disposal=1)
    return path


def preview_assets(assets, out):
    """A scratch assets folder with the works' textures as written, and a plain ground and scaffold for the previews."""
    pa = os.path.join(out, '_assets')
    for sub in ('block/works', 'entity/works', 'block/preview'):
        os.makedirs(os.path.join(pa, 'textures', sub), exist_ok=True)
    import shutil
    for sub in ('block/works', 'entity/works'):
        src = os.path.join(assets, 'textures', sub)
        for f in os.listdir(src):
            shutil.copy(os.path.join(src, f), os.path.join(pa, 'textures', sub, f))
    g = Canvas(T)
    xs, ys = grid(T)
    g.put(ALL, bands([(58, 52, 40), (78, 70, 52), (96, 86, 62), (112, 100, 72)], 0.5 + 0.25 * (fbm(T, T, 5, 9001) - 0.5)
                     + 0.1 * (rand(T, T, 9003) - 0.5), 0.3))
    g.image().save(os.path.join(pa, 'textures', 'block', 'preview', 'ground.png'))
    return pa


def ground(sc, x0, z0, x1, z1, y=0.0):
    """A floor of the preview's ground from (x0, z0) to (x1, z1) px at height y."""
    for x in np.arange(x0, x1, 16.0):
        for z in np.arange(z0, z1, 16.0):
            sc.quad((x, y, z), (x + 16, y, z), (x, y, z + 16), (0, 0, 16, 16), 'guildmark:block/preview/ground')


def blocker(at=(0.0, 0.0, 0.0), y=0):
    """A block's model px, the block turned by a blockstate's [y] and standing at [at], to the scene's px."""
    Rb = Ry(-y)
    mid = np.array([8.0, 0.0, 8.0])
    at = np.asarray(at, float)
    return lambda p: (np.asarray(p, float) - mid) @ Rb.T + mid + at


def post_faces(sc, kind, t, seals, ages, funded_age, y=0, wet=False, at=(0.0, 0.0, 0.0), grid_n=8):
    """The post's parchment, its seals and its stamp at tick [t] as its renderer draws them: [seals] seals there, [ages]
    {index: ticks since it came}, the stamp [funded_age] ticks after the works was funded (None: not yet)."""
    place = blocker(at, y)
    ref = 'guildmark:entity/works/post'
    W, H = POST_TEX
    ox, oy = PAPER_AT[kind]

    def surf(u, v):
        x, yy = paper_xy(u, v)
        return np.array([x, yy, PAPER_Z - paper_lift(u, v, t, wet)])
    for i in range(grid_n):
        for j in range(grid_n):
            u0, u1, v0, v1 = i / grid_n, (i + 1) / grid_n, j / grid_n, (j + 1) / grid_n
            uv = ((ox + u0 * PAPER_W) / W * 16, (oy + v0 * PAPER_H) / H * 16, (ox + u1 * PAPER_W) / W * 16,
                  (oy + v1 * PAPER_H) / H * 16)
            sc.quad(place(surf(u0, v0)), place(surf(u1, v0)), place(surf(u0, v1)), uv, ref)
    for k in range(seals):
        cord = SEAL_CORD[k % 2]
        up, kick = seal_drop(ages.get(k, 1e9), cord)
        side, out = seal_swing(k, t, wet)
        R = Rz(side + kick) @ Rx(out)            # clockwise as seen from the front; its foot out toward -z
        top = surf(SEAL_U[k], SEAL_V) + np.array([0.0, 0.0, -0.02])
        vis = cord - up + SEAL_W
        a, b = top + R @ np.array([SEAL_W / 2, 0.0, 0.0]), top + R @ np.array([-SEAL_W / 2, 0.0, 0.0])
        d = top + R @ np.array([SEAL_W / 2, -vis, 0.0])
        sx, sy = SEAL_AT[k % 2]
        uv = (sx / W * 16, (sy + up * PAPER_TPX) / H * 16, (sx + SEAL_W * PAPER_TPX) / W * 16,
              (sy + (cord + SEAL_W) * PAPER_TPX) / H * 16)
        sc.quad(place(a), place(b), place(d), uv, ref)
    if funded_age is not None:
        s, alpha = stamp_in(funded_age)
        if alpha > 0.05:
            u, v, size, turn = STAMP
            c, sn = math.cos(math.radians(turn)), math.sin(math.radians(turn))
            ex = np.array([-c, -sn, 0.0]) * size * s          # its u: the viewer's right (-x), turned clockwise
            ey = np.array([sn, -c, 0.0]) * size * s           # its v: down
            a = surf(u, v) + np.array([0.0, 0.0, -0.04]) - ex / 2 - ey / 2
            uv = (STAMP_AT[0] / W * 16, STAMP_AT[1] / H * 16, (STAMP_AT[0] + STAMP_TEX) / W * 16, (STAMP_AT[1] + STAMP_TEX) / H * 16)
            sc.quad(place(a), place(a + ex), place(a + ey), uv, ref)


PLAQUE_SAMPLE = ('Raised by the gift of', 'Aldric Venn', 'and three others')


def plaque_text(pa, tpx=24):
    """The sample lines as the plaque's renderer letters them over its face (drawn on a clear quad just proud of it, as
    the renderer's text is, finer than the casting's texture): textures/block/preview/plaque_text.png, [tpx] to a px."""
    W_, H_ = int(round((PLAQUE_HI[0] - PLAQUE_LO[0]) * tpx)), int(round((PLAQUE_HI[1] - PLAQUE_LO[1]) * tpx))
    a = np.zeros((H_, W_, 4))
    for (base, cap, maxw), text in zip((PLAQUE_LINES['above'], PLAQUE_LINES['name'], PLAQUE_LINES['below']), PLAQUE_SAMPLE):
        ppf = cap / ML.CAP
        al, tw, b = letters(PAINT, text, ppf, tpx, seed=3)
        if tw > maxw * tpx:
            al, tw, b = letters(PAINT, text, ppf * maxw * tpx / tw, tpx, seed=3)
        ox = int(round((8.0 - PLAQUE_LO[0]) * tpx - tw / 2))
        oy = int(round((PLAQUE_HI[1] - base) * tpx)) - b
        off = max(1, int(round(0.1 * tpx)))
        for dx, dy, col in ((-off, -off, PLAQUE_EDGE), (0, 0, PLAQUE_INK)):
            m = np.zeros((H_, W_))
            hh, ww = al.shape
            y0, x0 = max(0, oy + dy), max(0, ox + dx)
            y1, x1 = min(H_, oy + dy + hh), min(W_, ox + dx + ww)
            m[y0:y1, x0:x1] = al[y0 - oy - dy:y1 - oy - dy, x0 - ox - dx:x1 - ox - dx]
            k = np.clip(m * 1.3, 0, 1)
            a[..., :3] = a[..., :3] * (1 - k[..., None]) + np.array(col, float) * k[..., None]
            a[..., 3] = np.maximum(a[..., 3], k * 255)
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), 'RGBA').save(os.path.join(pa, 'textures', 'block', 'preview', 'plaque_text.png'))


def plaque_text_quad(sc):
    z = PLAQUE_TEXT_Z
    (x0, y0, _), (x1, y1, _) = PLAQUE_LO, PLAQUE_HI
    sc.quad((x0, y1, z), (x1, y1, z), (x0, y0, z), (0, 0, 16, 16), 'guildmark:block/preview/plaque_text')


ALMS_STORY_LEN = 200
ALMS_BUILD = (4, 16, 10)                # the trestle's left, middle and right go up (AlmsKeeper's order: ends, then middle)
ALMS_POT = 24
ALMS_BOWLS = (38, 46, 54)
ALMS_LADLES = (72, 150)
ALMS_RESTOCK = 116
ALMS_GIFT = 126
ALMS_FILL = 0.8


def alms_story():
    """The previews' morning at the alms table, tick by tick, as AlmsTable plays it with works-art.md's timing: the
    trestle up a block at a time, the pot set on it, three bowls set out; then LADLE (the top bowl off the stack at its
    take, the ladle in the keeper's hand from its start to its end, its ripple from the dip, the pot a ladleful lower),
    a bowl set out again, a gift tipped in (its splash, the pot fuller), and LADLE again."""
    def ease(u):
        u = min(1.0, max(0.0, u))
        return u * u * (3 - 2 * u)

    def at(t):
        st = {'up': [t >= ALMS_BUILD[i] for i in range(3)], 'pot': t >= ALMS_POT, 'pot_age': t - ALMS_POT}
        fill = ALMS_FILL
        for L in ALMS_LADLES:
            fill -= 0.07 * ease((t - L - 7.5) / 6.0)
        fill += 0.14 * ease((t - ALMS_GIFT) / 10.0)
        st['fill'] = fill
        stack = []                                  # when each bowl on the stack was set there, the top last
        events = sorted([(b, 'set') for b in ALMS_BOWLS + (ALMS_RESTOCK,)] + [(L + 3.5, 'take') for L in ALMS_LADLES])
        for when, what in events:
            if when > t:
                break
            if what == 'set':
                stack.append(when)
            elif stack:
                stack.pop()
        st['bowls'] = len(stack)
        st['bowl_age'] = t - stack[-1] if stack else 1e9
        st['ladle'] = st['pot'] and not any(L <= t < L + 30 for L in ALMS_LADLES)
        dips = [t - L - 7.5 for L in ALMS_LADLES if t >= L + 7.5]
        st['ripple'] = min(dips) if dips else None
        st['splash'] = t - ALMS_GIFT if t >= ALMS_GIFT else None
        lad = [t - L for L in ALMS_LADLES if L <= t < L + 30]
        if not st['up'][2] or not all(st['up']):
            what = 'the trestle set up, a block at a time'
        elif not st['pot'] or t < ALMS_POT + 10:
            what = 'the pot set down on the middle'
        elif t < ALMS_BOWLS[-1] + 10:
            what = 'the bowls set out on the keeper\'s left'
        elif lad:
            k = lad[0]
            what = 'LADLE %2d: ' % int(k) + ('the ladle taken up' if k < 3.5 else 'a bowl off the stack' if k < 7.5 else
                                            'the ladle in: its ripple' if k < 12.5 else 'poured' if k < 21 else 'the bowl given')
        elif ALMS_RESTOCK <= t < ALMS_RESTOCK + 8:
            what = 'a bowl set out again'
        elif ALMS_GIFT <= t < ALMS_GIFT + 18:
            what = 'a gift tipped in: its splash, the pot fuller'
        else:
            what = 'serving, the stew simmering'
        st['what'] = what
        return st
    return at


def alms_faces(sc, m, st, t):
    """The alms table's pieces on its middle block (at the scene's origin block) in state [st] at tick [t], as its
    renderer draws them from WorksArt's numbers."""
    if st['pot']:
        lift = drop(st['pot_age'])
        sc.mesh(m, 'pot', placer(at=(POT_AT[0], ALMS_TOP_Y + lift, POT_AT[1])))
        if st['fill'] > 0:
            sy = stew_y(st['fill']) + 0.05 * math.sin(0.35 * t) + lift
            sc.mesh(m, f'stew_{int(t // STEW_TICKS) % STEW_FRAMES}', placer(at=(POT_AT[0], sy, POT_AT[1])))
            r = st['ripple']
            if r is not None and r < RIPPLE_TICKS:
                k = ripple_scale(r)
                sc.mesh(m, 'ripple', placer(at=(POT_AT[0] - 0.2, sy + 0.03, POT_AT[1] - 0.2), extra=np.diag([k, 1.0, k])))
            g = st['splash']
            if g is not None and g < SPLASH_TICKS:
                k = splash_scale(g)
                sc.mesh(m, 'ripple', placer(at=(POT_AT[0], sy + 0.04, POT_AT[1]), extra=np.diag([k, 1.0, k])))
            if g is not None:
                for j in range(SPLASH_DROPS):
                    d = splash_drop(j, g)
                    if d is not None:
                        sc.mesh(m, 'drop', placer(at=(POT_AT[0] + d[0], sy + d[1], POT_AT[1] + d[2])))
        if st['ladle']:
            sc.mesh(m, 'ladle', placer(at=tuple(ladle_rest_at() + np.array([0.0, lift, 0.0]))))
    n = st['bowls']
    for i in range(n):
        top = i == n - 1
        y = ALMS_TOP_Y + BOWL_H * i + (drop(st['bowl_age']) if top else 0.0)
        sc.mesh(m, 'bowl' if top else 'bowl_under', placer(at=(BOWLS_AT[0], y, BOWLS_AT[1])))


SLATE_WORDS = [('Nothing', 0.21, 3.6), ('today', 0.21, 6.15)]       # the previews' chalk: StreetSlate's words, two lines
SLATE_PLACE, SLATE_WRITE, SLATE_LETTER = 14.0, 20.0, 2.5               # StreetSlate's PLACE, WRITE_FROM and PER_LETTER


def chalk_textures(pa):
    """The previews' stand-in for StreetSlate's chalk (the game writes it in its font): SLATE_WORDS on the slate's face,
    the first k letters of them for each k, as _chalk_<k>.png."""
    w, h = int(SLATE_BOARD[0] * TO_LET_SCALE), int(SLATE_BOARD[1] * TO_LET_SCALE)
    total = sum(len(t) for t, _, _ in SLATE_WORDS)
    rows = []
    for text, ppf, base in SLATE_WORDS:
        _, tw_, _ = letters(ML.CHALK, text, ppf, TO_LET_SCALE, seed=2601)
        rows.append((text, ppf, base, (w - tw_) // 2))
    for k in range(total + 1):
        a = np.zeros((h, w, 4))
        a[..., :3] = (236, 234, 226)
        left = k
        for text, ppf, base, ox in rows:
            n = min(len(text), left)
            left -= len(text)
            if n <= 0:
                continue
            al, _, tb = letters(ML.CHALK, text[:n], ppf, TO_LET_SCALE, seed=2601)
            oy = int(round(base * TO_LET_SCALE)) - tb
            hh, ww = al.shape
            y0, x0 = max(0, oy), max(0, ox)
            y1, x1 = min(h, oy + hh), min(w, ox + ww)
            a[y0:y1, x0:x1, 3] = np.maximum(a[y0:y1, x0:x1, 3], al[y0 - oy:y1 - oy, x0 - ox:x1 - ox] * 255)
        a[..., 3] = np.where(a[..., 3] > 110, 255, 0)
        Image.fromarray(a.astype(np.uint8), 'RGBA').save(os.path.join(pa, 'textures', 'entity', 'works', f'_chalk_{k}.png'))
    return total


def slate_faces(sc, m, t):
    """The slate as StreetSlate brings it at tick [t] (set down from the keeper's side over SLATE_PLACE ticks, rocking as
    it settles, chalked a letter at a time from SLATE_WRITE), leant on what's behind it; how many letters are written."""
    u = min(1.0, t / SLATE_PLACE)
    u = u * u * (3 - 2 * u)
    y = 3.5 * (1 - u) + 6.1 * math.sin(math.pi * u)
    z = 8.0 + 8.0 * (1 - u)
    lean = SLATE_LEAN + 25.0 * (1 - u)
    if SLATE_PLACE < t < SLATE_PLACE + 20:
        lean += 4.0 * math.exp(-(t - SLATE_PLACE) / 2.5) * math.sin((t - SLATE_PLACE) * 1.6)
    place = placer(at=(8.0, y, z), extra=Rx(-lean))
    sc.mesh(m, 'slate', place)
    total = sum(len(text) for text, _, _ in SLATE_WORDS)
    k = min(total, int((t - SLATE_WRITE) / SLATE_LETTER)) if t >= SLATE_WRITE else 0
    if k:
        w, h, d = SLATE_BOARD
        zf = -d / 2 - 0.03
        a, b, dd = place((-w / 2, -h, zf)), place((w / 2, -h, zf)), place((-w / 2, 0.0, zf))
        sc.quad(a, b, dd, (0, 0, 16, 16), f'guildmark:entity/works/_chalk_{k}')
    return k


def write_previews(assets, out):
    """The previews for Ransom, into [out]: GIFs of the yard filling, the post sealed and stamped, the beacon, the alms
    table's pot, the hoist hauling, and the "To let" board, tub, plaque and slate; a sheet of the five drawings."""
    os.makedirs(out, exist_ok=True)
    pa = preview_assets(assets, out)
    ms = models()
    mesh = {m.fun: m for m in meshes()}
    written = []

    # ---- the five drawings, and the post with its seals at five counts
    sheet_ = Image.new('RGB', (5 * (PAPER_W * 4 + 12) + 12, PAPER_H * 4 + 40), (36, 34, 40))
    for i, k in enumerate(WORKS):
        im = paper_sheet(k).resize((PAPER_W * 4, PAPER_H * 4), Image.NEAREST)
        bg = Image.new('RGBA', im.size, (90, 84, 76, 255))
        bg.alpha_composite(im)
        sheet_.paste(bg.convert('RGB'), (12 + i * (PAPER_W * 4 + 12), 30))
        ImageDraw.Draw(sheet_).text((12 + i * (PAPER_W * 4 + 12), 10), k, fill=(240, 236, 220))
    p = os.path.join(out, 'works_post_drawings.png')
    sheet_.save(p)
    written.append(p)

    # ---- the post: its seals coming in one by one, then the stamp
    frames = []
    for f in range(56):
        t = f * 2.0
        n = min(SEALS, int(t // 7) + 1)
        ages = {k: t - 7 * k for k in range(n)}
        funded = t - 7 * SEALS if t >= 7 * SEALS else None
        imgs = []
        for yaw, elev, sc_, cen in ((200.0, 12.0, 15.0, (8, 18, 9)), (150.0, 16.0, 9.5, (8, 15, 8))):
            sc = Scene(pa)
            ground(sc, -16, -16, 32, 32)
            sc.model(ms['works_post'])
            post_faces(sc, 'oven', t, n, ages, funded)
            imgs.append(sc.render(yaw=yaw, elev=elev, scale=sc_, size=(360, 420), center=cen, tick=t, ss=2))
        frames.append(tiles(imgs, 2, 'works post: a seal for each tenth pledged, then stamped',
                            f'{n}/10 sealed' + ('  FUNDED' if funded is not None else '')))
    written.append(save_gif(frames, os.path.join(out, 'works_post_seals.gif')))

    # ---- the yard: each display filling to its full heap
    yard = yard_layout()
    frames = []
    for f in range(50):
        t = f * 2.0
        imgs = []
        for s in STUFF:
            full, pcs = yard[s]
            step = 84.0 / full
            sc = Scene(pa)
            ground(sc, -16, -16, 32, 32)
            sc.model(ms[f'works_yard_{s}'])
            for part, n, x, yy, z, yaw in pcs:
                age = t - (n - 1) * step
                if age < 0:
                    continue
                sc.mesh(mesh['yard'], part, placer(at=(x, yy + drop(age), z), yaw=yaw))
            imgs.append(label(sc.render(yaw=205.0, elev=28.0, scale=11.0, size=(250, 250), center=(8, 7, 8), ss=2), s))
        frames.append(tiles(imgs, 3, 'yard displays, each heaped by how much of its bill is in the yard'))
    written.append(save_gif(frames, os.path.join(out, 'works_yard_filling.gif')))

    # ---- the beacon, out, lit and flaring, at dusk
    frames = []
    for f in range(24):
        t = float(f)
        imgs = []
        for fire in FIRES:
            sc = Scene(pa)
            ground(sc, -16, -16, 32, 32)
            sc.model(ms['works_beacon' + ('' if fire == 'out' else '_' + fire)])
            if fire != 'out':
                (cx, cy, cz), wdt = GLOW[fire]
                wdt *= glow_pulse(t)
                yaw = math.radians(200.0)
                right = np.array([math.cos(yaw), 0.0, -math.sin(yaw)])
                a = np.array([cx, cy + wdt / 2, cz]) - right * wdt / 2
                sc.quad(a, a + right * wdt, a + np.array([0, -wdt, 0]), (0, 0, 16, 16), 'guildmark:entity/works/beacon_glow', emit=True)
            imgs.append(label(sc.render(yaw=200.0, elev=18.0, scale=8.0, size=(260, 360), center=(8, 15, 8), tick=t, ss=2,
                                        ambient=0.7), fire))
        frames.append(tiles(imgs, 3, 'beacon brazier: out, lit and flaring, at dusk'))
    written.append(save_gif(frames, os.path.join(out, 'works_beacon.gif'), ms=50))

    # ---- the alms table: set up, the pot and the bowls set out, ladled from, given to
    frames = []
    story = alms_story()
    for f in range(0, ALMS_STORY_LEN, 2):
        t = float(f)
        st = story(t)
        imgs = []
        for yaw, elev, scale, cen, size in ((205.0, 26.0, 7.0, (8, 9, 8), (440, 300)), (160.0, 52.0, 16.0, (9, 14, 10), (300, 300))):
            sc = Scene(pa)
            ground(sc, -32, -16, 48, 32)
            for i, part in enumerate(TABLE_PARTS):
                if st['up'][i]:
                    sc.model(ms[f'alms_table_{part}'], at=((i - 1) * 16.0, 0.0, 0.0))
            alms_faces(sc, mesh['alms'], st, t)
            imgs.append(sc.render(yaw=yaw, elev=elev, scale=scale, size=size, center=cen, ss=2))
        frames.append(tiles(imgs, 2, 'alms table: the trestle, the pot, the bowls; ladled from, a gift tipped in', st['what']))
    written.append(save_gif(frames, os.path.join(out, 'works_alms_table.gif')))

    # ---- the hoist on the scaffold, hauling a beam up
    frames = []
    up_y = 48.0
    travel = (up_y + HOOK_TOP) - HOOK_REST
    total = ticks_for(travel) + HOIST_LAND + travel / HOIST_LOWER + 8
    sx, sy, sz = SHEAVE_AT
    hm = mesh['hoist']
    for f in range(int(total // 2) + 1):
        t = f * 2.0
        run, loaded = hook_at(t, travel)
        hook_y = HOOK_REST + run
        sc = Scene(pa)
        ground(sc, -16, -32, 32, 32, y=0.0)
        for x in (1.0, 13.0):
            for z in (1.0, 13.0):
                sc.model({'elements': [cube((x, 0, z), (x + 2, up_y - 1, z + 2), 'oak')], 'textures': {'oak': TEX_NS + 'oak'}})
        sc.model({'elements': [cube((0, up_y - 1, 0), (16, up_y, 16), 'pallet')], 'textures': {'pallet': TEX_NS + 'pallet'}})
        sc.model(ms['works_hoist'], at=(0, up_y, 0))
        sc.mesh(hm, 'sheave', placer(at=(sx, up_y + sy, sz)), pose={'sheave': Rz(-math.degrees(run / SHEAVE_R))})
        sc.mesh(hm, 'wrap', placer(at=(sx, up_y + sy, sz)))
        # The falls in 16 px lengths, the first cut short by how far the rope has run (the hauled one down, the load's up)
        for x, y1, shift in ((sx + SHEAVE_R, 1.0, run), (sx - SHEAVE_R, hook_y, -run)):
            y = up_y + sy
            seg = min((shift % 16.0) or 16.0, y - y1)
            while y - y1 > 1e-3:
                sc.mesh(hm, 'rope', placer(at=(x, y, sz), extra=np.diag([1.0, seg / 16.0, 1.0])))
                y -= seg
                seg = min(16.0, y - y1)
        sc.mesh(hm, 'hook', placer(at=(sx - SHEAVE_R, hook_y, sz)))
        if loaded:
            sc.mesh(hm, 'load', placer(at=(sx - SHEAVE_R, hook_y - HOOK_LOAD, sz)))
        sc.mesh(hm, 'coil', placer(at=(COIL_AT[0], 0.0, COIL_AT[2])))
        im = sc.render(yaw=235.0, elev=14.0, scale=5.6, size=(400, 560), center=(4, 35, -8), ss=2)
        frames.append(label(im, 'hoist: a beam hauled up to the scaffold', 'loaded' if loaded else 'the hook let down'))
    written.append(save_gif(frames, os.path.join(out, 'works_hoist.gif')))

    # ---- "To let" (still, as its trim block draws it, and swinging, as a renderer would), the tub, the plaque under
    # its cloth and bare, the slate set down and chalked a letter at a time (as client/streets/StreetSlate does it)
    frames = []
    plaque_text(pa)
    chalk_textures(pa)
    for f in range(40):
        t = f * 2.0
        wet = f >= 20
        imgs = []
        for yaw, name in ((200.0, '"To let", as its trim draws it'), (20.0, '... from the house side')):
            sc = Scene(pa)
            ground(sc, -16, -16, 32, 32)
            sc.model(ms['to_let_board'])
            imgs.append(label(sc.render(yaw=yaw, elev=12.0, scale=9.0, size=(300, 300), center=(8, 12, 7), ss=2), name))
        sc = Scene(pa)
        ground(sc, -16, -16, 32, 32)
        sc.model(ms['to_let_post'])
        sw = to_let_swing(t, 0.7, wet=wet)
        sc.mesh(mesh['toLet'], 'board', placer(at=TO_LET_PIVOT), pose={'board': Rx(-sw)})
        imgs.append(label(sc.render(yaw=235.0, elev=12.0, scale=9.0, size=(300, 300), center=(8, 12, 7), ss=2),
                          '... swung by a renderer' + (' (in rain)' if wet else '')))
        sc = Scene(pa)
        ground(sc, -16, -16, 32, 32)
        sc.model(ms['works_mortar_tub'])
        imgs.append(label(sc.render(yaw=200.0, elev=40.0, scale=12.0, size=(300, 300), center=(8, 4, 8), tick=t, ss=2), 'mortar tub'))
        sc = Scene(pa)
        covered = f < 20
        sc.model(ms['works_plaque_covered'] if covered else ms['works_plaque'], y=0)
        if not covered:
            plaque_text_quad(sc)
        imgs.append(label(sc.render(yaw=20.0, elev=5.0, scale=19.0, size=(300, 300), center=(8, 8, 1), ss=2),
                          'plaque' + (' (covered)' if covered else ', its lines lettered')))
        sc = Scene(pa)
        ground(sc, -16, -16, 32, 32)
        written_ = slate_faces(sc, mesh['slate'], t)
        imgs.append(label(sc.render(yaw=200.0, elev=15.0, scale=14.0, size=(300, 300), center=(8, 5, 8), ss=2),
                          'slate, chalked' if written_ else 'slate, set down'))
        frames.append(tiles(imgs, 3, 'the "To let" board, the mortar tub, the patron' + "'" + 's plaque, the slate'))
    written.append(save_gif(frames, os.path.join(out, 'works_blocks_more.gif')))
    return written


def dump_json(obj):
    return json.dumps(obj, indent=2) + '\n'


def write(path, text, mode='w'):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(text)


def main(argv):
    if len(argv) < 4:
        raise SystemExit(__doc__)
    assets, kotlin_dir, models_dir = argv[1], argv[2], argv[3]
    prev = argv[4] if len(argv) > 4 else None
    tdir = os.path.join(assets, 'textures', 'block', 'works')
    os.makedirs(tdir, exist_ok=True)
    for name, (cv, anim) in block_textures().items():
        cv.image().save(os.path.join(tdir, name + '.png'))
        meta = os.path.join(tdir, name + '.png.mcmeta')
        if anim:
            write(meta, dump_json({'animation': anim}))
    for name, m in models().items():
        write(os.path.join(assets, 'models', 'block', 'works', name + '.json'), dump_json(m))
    for bid, bs in blockstates().items():
        write(os.path.join(assets, 'blockstates', bid + '.json'), dump_json(bs))
    edir = os.path.join(assets, 'textures', 'entity', 'works')
    os.makedirs(edir, exist_ok=True)
    ms_ = meshes()
    os.makedirs(models_dir, exist_ok=True)
    for m in ms_:
        tex = m.paint()
        tex.save(os.path.join(edir, m.texture + '.png'))
        small = tex.resize((m.model.tex_w, m.model.tex_h), Image.LANCZOS)
        with open(os.path.join(models_dir, f'works_{m.texture}.bbmodel'), 'w') as f:
            json.dump(m.model.bbmodel(small, m.texture), f)
    post_texture().save(os.path.join(edir, 'post.png'))
    glow_texture().save(os.path.join(edir, 'beacon_glow.png'))
    os.makedirs(kotlin_dir, exist_ok=True)
    write(os.path.join(kotlin_dir, 'WorksArt.kt'), kotlin_art())
    write(os.path.join(kotlin_dir, 'WorksMesh.kt'), kotlin_mesh(ms_))
    yard = yard_layout()
    print('works_art: ' + ', '.join(f'{s} {yard[s][0]} pieces' for s in STUFF) +
          f'; haul {HAUL_LOOP:g} px a loop, {ticks_for(48.0):.1f} ticks to haul a block and a half')
    if prev:
        for p in write_previews(assets, prev):
            print('preview', p, os.path.getsize(p) // 1024, 'KB')


if __name__ == '__main__':
    main(sys.argv)
