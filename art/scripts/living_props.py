"""
The Living World's held props for the townsfolk (art/scripts/living_anims.py animates them): food to eat, a bowl and
spoon, the well's rope and bucket, coins, a seed bag, a scythe, an axe, a crier's handbell and scroll, a lamplighter's
pole, a basket on the hip, a sack to tip out and a grindstone's crank; and the road's (art/scripts/road_clips.py): a
caravan driver's knife and reins, a guard's spear laid across the knees, a waylayer's sack of loot and a horse's
nosebag; and the works' and the streets' (M6: art/scripts/works_clips.py, street_clips.py): a trowel, a mortar hoe, a
mallet and chisel, a plumb line, a sighting staff, a beam for two, a cup for the toast, the alms table's ladle and bowls,
a torch, the watch's horn, a household's bundle, the cloth over a patron's plaque and an empty basket.

townsfolk_tools.py takes them in at its end (BUILDERS, GLOW_CUBES, TOOL_NOTES), so townsfolk.py builds them into the
model like every other tool, and art/scripts/townsfolk.py packs their cubes after everyone else's (CUBES), so adding
them never moves an older cube on the texture. Model space as in townsfolk_tools: pixels, y down, the character faces
-z, its right hand at -x; right grip (-1, 10, 0) in right_arm space, left grip (1, 10, 0) in left_arm space.

Long things (the rope, the poles, the scythe's snath) are modelled along x and turned into place by their part's
rotation: a cube's box UV takes 2 (w + d) x (h + d) texels, so a long thin cube lying along x needs one short strip.
The rope, the bucket and the empty sack hang straight down from the hand however the arm turns (HANG: the game turns
them each frame, as it does the fishing line and the lantern). The reins are three pieces each, which the driving
clips' prop tracks lay from the fist to the rein ring on the horse's saddle.
"""
import math
import numpy as np

import townsfolk_tools as tt
from townsfolk_tools import (C, Part, RIGHT_GRIP, LEFT_GRIP, painter, solid, mix, uv, grain, metal, weave, noise_fill,
                             shade_edges, ramp, wood_painter, iron_painter, brass_painter, cord_painter, sack_painter,
                             basket_painter, coin_painter, staves_painter, leather_painter,
                             WOOD, WOOD_D, WOOD_L, OAK, OAK_D, OAK_L, PALE, PALE_D, PALE_L, EBONY, EBONY_D, EBONY_L,
                             IRON, IRON_D, IRON_L, STEEL, STEEL_D, STEEL_L, BRASS, BRASS_D, BRASS_L, GLASS,
                             WICKER, WICKER_D, WICKER_L, BURLAP, BURLAP_D, BURLAP_L, LINEN, LINEN_D, LEATHER, LEATHER_D,
                             PAPER, PAPER_D, APPLE, APPLE_L, LEAF, LEAF_D, BREAD, BREAD_L, BREAD_D, CORD)

# ================================================================== a little geometry

def euler_from_axes(x_to, y_to, z_to=None):
    """A part rotation (degrees, ZYX as the game turns parts) taking the part's own x and y axes to [x_to] and [y_to]
    (made orthonormal; z follows)."""
    x = np.asarray(x_to, float)
    x = x / np.linalg.norm(x)
    y = np.asarray(y_to, float)
    y = y - x * float(np.dot(x, y))
    y = y / np.linalg.norm(y)
    z = np.cross(x, y)
    R = np.stack([x, y, z], axis=1)
    b = -math.asin(max(-1.0, min(1.0, R[2, 0])))
    a = math.atan2(R[2, 1], R[2, 2])
    c = math.atan2(R[1, 0], R[0, 0])
    return tuple(round(math.degrees(t), 3) for t in (a, b, c))


def _rot_x(deg, v):
    t = math.radians(deg)
    x, y, z = v
    return (x, y * math.cos(t) - z * math.sin(t), y * math.sin(t) + z * math.cos(t))

# ================================================================== palette

CRUST = (176, 112, 52); CRUST_D = (122, 70, 30); CRUST_L = (220, 162, 92)
STEW = (132, 76, 40); STEW_D = (92, 48, 24); CARROT = (226, 122, 40); PEA = (110, 150, 60)
WATER = (52, 92, 170); WATER_L = (120, 168, 220); WATER_D = (34, 60, 120)
ROPE = (176, 146, 96); ROPE_D = (118, 92, 56); ROPE_L = (210, 184, 132)
SEED = (196, 170, 96); SEED_D = (120, 96, 50)
KRAFT = (178, 132, 84); KRAFT_D = (130, 92, 54); KRAFT_L = (206, 166, 116)
WAX = (166, 30, 34); WAX_D = (104, 16, 22); WAX_L = (214, 80, 70); INK = (52, 40, 40)
FLAME = [(214, 84, 20), (246, 140, 36), (255, 196, 80), (255, 236, 160), (255, 252, 230)]

# ================================================================== painters

def loaf_painter(key):
    """A round-ended loaf: a golden crust, darker where it baked hardest, three slashes across the top, a dusting of flour."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, CRUST, rng, 0.05)
        a[..., :3] = mix(a[..., :3], CRUST_L, np.clip(0.5 - np.abs(U - 0.5), 0, 0.5)[..., None] * 0.6)
        if face in ('front', 'back', 'right', 'left'):
            a[..., :3] = mix(a[..., :3], CRUST_D, np.clip(V - 0.7, 0, 0.3)[..., None] * 1.6)
            # the scored slashes, pale crumb showing in each
            for c in (0.28, 0.52, 0.76):
                cut = np.abs((V - c) - (U - 0.5) * 0.35) < max(0.05, 0.6 / h)
                a[..., :3] = np.where((cut & (np.abs(U - 0.5) < 0.38))[..., None], mix(a[..., :3], BREAD_L, 0.75), a[..., :3])
        else:
            r = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = mix(a[..., :3], CRUST_D, np.clip(r * 1.4 - 0.2, 0, 0.6)[..., None])
        flour = rng.random((h, w)) < 0.07
        a[..., :3] = np.where(flour[..., None], mix(a[..., :3], (246, 238, 220), 0.6), a[..., :3])
        return shade_edges(a, 0.22)
    return painter(key, fn)


def apple_painter(key):
    """A red apple: a highlight up on the left, a yellow blush, darker underneath; the top face dimpled round the stem."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, APPLE, rng, 0.04)
        blush = np.clip(np.sin(U * 9.0 + rng.uniform(0, 6)) * 0.5 + 0.5, 0, 1) * np.clip(V - 0.2, 0, 1)
        a[..., :3] = mix(a[..., :3], (214, 156, 60), (blush * 0.25)[..., None])
        if face == 'top':
            r = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = mix(a[..., :3], (120, 24, 24), np.clip(0.3 - r, 0, 0.3)[..., None] * 2.4)
        elif face == 'bottom':
            a[..., :3] *= 0.75
        else:
            hl = np.exp(-((U - 0.3) ** 2 + (V - 0.3) ** 2) * 16)
            a[..., :3] = mix(a[..., :3], APPLE_L, (hl * 0.85)[..., None])
            a[..., :3] = mix(a[..., :3], (110, 20, 20), np.clip(V - 0.65, 0, 0.35)[..., None] * 1.5)
        return shade_edges(a, 0.3)
    return painter(key, fn)


def stem_painter(key):
    def fn(face, w, h, rng):
        return noise_fill(w, h, (96, 66, 34), rng, 0.08)
    return painter(key, fn)


def leaf_painter(key):
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, LEAF, rng, 0.06)
        vein = np.abs(U - 0.5) < max(0.08, 0.5 / w)
        a[..., :3] = np.where(vein[..., None], mix(a[..., :3], LEAF_D, 0.6), a[..., :3])
        return a
    return painter(key, fn)


def bowl_painter(key):
    """A turned wooden bowl: grain running round it, a lathe ring under the rim; on top, a rim of wood round stew with
    bits of carrot and peas, and a sheen."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = noise_fill(w, h, STEW, rng, 0.08)
            bits = rng.random((h, w))
            a[..., :3] = np.where((bits < 0.1)[..., None], np.array(CARROT, float), a[..., :3])
            a[..., :3] = np.where(((bits > 0.1) & (bits < 0.15))[..., None], np.array(PEA, float), a[..., :3])
            sheen = np.exp(-((U - 0.35) ** 2 + (V - 0.35) ** 2) * 20)
            a[..., :3] = mix(a[..., :3], (200, 150, 100), (sheen * 0.4)[..., None])
            rim = (U < 0.14) | (U > 0.86) | (V < 0.14) | (V > 0.86)
            wood = grain(w, h, 'u', OAK_L, OAK, (200, 160, 110), rng, streak=0.3)
            a[..., :3] = np.where(rim[..., None], wood[..., :3], a[..., :3])
            inner = ((U < 0.2) | (U > 0.8) | (V < 0.2) | (V > 0.8)) & ~rim
            a[..., :3] = np.where(inner[..., None], a[..., :3] * 0.7, a[..., :3])
            return a
        if face == 'bottom':
            return shade_edges(grain(w, h, None, OAK, OAK_D, OAK_L, rng), 0.3)
        a = grain(w, h, 'u', OAK, OAK_D, OAK_L, rng, streak=0.35)
        ring = np.abs(V - 0.3) < max(0.06, 0.5 / h)
        a[..., :3] = np.where(ring[..., None], mix(a[..., :3], OAK_D, 0.6), a[..., :3])
        a[..., :3] = mix(a[..., :3], OAK_D, np.clip(V - 0.6, 0, 0.4)[..., None])
        return shade_edges(a, 0.2)
    return painter(key, fn)


def water_top(w, h, rng):
    U, V = uv(w, h)
    a = solid(w, h, WATER)
    ripple = np.sin((U * 3 + V * 2) * math.pi * 2 + rng.uniform(0, 6)) * 0.5 + 0.5
    a[..., :3] = mix(np.array(WATER_D, float), np.array(WATER_L, float), (0.25 + 0.5 * ripple)[..., None])
    hl = np.exp(-((U - 0.32) ** 2 + (V - 0.3) ** 2) * 30)
    a[..., :3] = mix(a[..., :3], (230, 244, 255), (hl * 0.7)[..., None])
    rim = (U < 0.12) | (U > 0.88) | (V < 0.12) | (V > 0.88)
    wood = grain(w, h, None, OAK, OAK_D, OAK_L, rng)
    a[..., :3] = np.where(rim[..., None], wood[..., :3], a[..., :3])
    return a


def iron_arch_painter(key, thick=0.16):
    """A bucket's bail: an iron half-hoop on a flat cube, clear inside and outside it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = metal(w, h, IRON, IRON_D, IRON_L, rng, bevel=False, wear=0.1)
        if face in ('front', 'back'):
            x = (U - 0.5) * 2
            y = 1 - V
            r = np.hypot(x, y)
            band = (r <= 1.0) & (r >= 1.0 - thick * 2.4) & (y >= 0)
            a[~band] = 0
        elif face in ('top', 'bottom'):
            band = np.abs(U - 0.5) < 0.18
            a[~band] = 0
        else:
            band = V > 0.7
            a[~band] = 0
        return a
    return painter(key, fn)


def rope_painter(key, along='x'):
    """Hemp rope: a twist of strands across it, darker in the hollows; its long faces along x (or y)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, ROPE, rng, 0.06)
        long_faces = ('front', 'back', 'top', 'bottom') if along == 'x' else ('front', 'back', 'left', 'right')
        if face in long_faces:
            n = max(w, h)
            A, B_ = (U, V) if along == 'x' else (V, U)
            tw = np.sin((A * n * 1.1 + B_ * 1.6) * math.pi) > 0.1
            a[..., :3] = np.where(tw[..., None], mix(a[..., :3], ROPE_D, 0.45), mix(a[..., :3], ROPE_L, 0.2))
        return a
    return painter(key, fn)


def seed_bag_painter(key):
    """A sower's bag: coarse sackcloth with a hem round its mouth; from above, the seed corn inside it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = noise_fill(w, h, SEED, rng, 0.12)
            dark = rng.random((h, w)) < 0.3
            a[..., :3] = np.where(dark[..., None], mix(a[..., :3], SEED_D, 0.6), a[..., :3])
            rim = (U < 0.15) | (U > 0.85) | (V < 0.15) | (V > 0.85)
            a[..., :3] = np.where(rim[..., None], np.array(BURLAP_D, float), a[..., :3])
            return a
        a = weave(w, h, BURLAP, BURLAP_D, BURLAP_L, rng, cell=max(1, min(w, h) // 6))
        a[..., :3] *= (1 + 0.1 * np.sin(U * 6.0 + rng.uniform(0, 6)) * np.sin(V * 5.0))[..., None]
        if face != 'bottom' and h >= 4:
            hem = V < max(0.16, 1.0 / h)
            a[..., :3] = np.where(hem[..., None], mix(a[..., :3], BURLAP_D, 0.5), a[..., :3])
        return shade_edges(a, 0.22)
    return painter(key, fn)


def strap_painter(key, base=(110, 74, 44), dark=(70, 44, 26), light=(150, 108, 70)):
    def fn(face, w, h, rng):
        a = noise_fill(w, h, base, rng, 0.05)
        U, V = uv(w, h)
        edge = (U < 0.15) | (U > 0.85)
        a[..., :3] = np.where(edge[..., None], mix(a[..., :3], dark, 0.5), mix(a[..., :3], light, 0.12))
        return a
    return painter(key, fn)


def scythe_blade_painter(key):
    """A scythe's blade: dark forged iron with a bright honed edge along one side (+y of the blade's own frame, its
    'bottom' rows on the long faces), a rib along the back."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = metal(w, h, (96, 98, 108), (54, 56, 64), (170, 176, 190), rng, bevel=False, wear=0.05)
        if face in ('front', 'back'):
            edge = V > 1 - max(0.22, 1.1 / h)
            rib = V < max(0.18, 0.9 / h)
            a[..., :3] = np.where(edge[..., None], mix(a[..., :3], STEEL_L, 0.8), a[..., :3])
            a[..., :3] = np.where(rib[..., None], mix(a[..., :3], (40, 42, 48), 0.5), a[..., :3])
        elif face == 'bottom':
            a[..., :3] = mix(a[..., :3], STEEL_L, 0.7)
        return a
    return painter(key, fn)


def axe_head_painter(key):
    """A splitting axe's head: blackened iron, the bit (+z end) ground bright, the eye's wedge showing on the sides."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = metal(w, h, (70, 72, 80), (38, 40, 46), (150, 156, 170), rng, wear=0.08)
        if face in ('right', 'left'):
            bit = U > 0.72 if face == 'right' else U < 0.28
            a[..., :3] = np.where(bit[..., None], mix(a[..., :3], STEEL_L, 0.7), a[..., :3])
            wedge = (np.abs(V - 0.5) < 0.22) & ((U < 0.3) if face == 'right' else (U > 0.7))
            a[..., :3] = np.where(wedge[..., None], a[..., :3] * 0.7, a[..., :3])
        elif face == 'back':
            a[..., :3] = mix(a[..., :3], STEEL_L, 0.8)
        return a
    return painter(key, fn)


def bell_painter(key, mouth=False):
    """A crier's brass handbell: warm polished brass, a bright band where it flares; underneath, the dark mouth."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'bottom' and mouth:
            a = solid(w, h, (40, 26, 12))
            r = np.maximum(np.abs(U - 0.5), np.abs(V - 0.5))
            a[..., :3] = np.where((r > 0.36)[..., None], np.array(BRASS_D, float), a[..., :3])
            return a
        a = metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=0.08)
        glint = np.exp(-((U - 0.3) ** 2 + (V - 0.4) ** 2) * 12)
        a[..., :3] = mix(a[..., :3], (255, 240, 190), (glint * 0.7)[..., None])
        return a
    return painter(key, fn)


def scroll_sheet_painter(key):
    """A proclamation: parchment with a red-capped heading and lines of writing on the side the reader holds towards
    themselves (+z, 'back'); the side the crowd sees is plain parchment, foxed at the edges."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, PAPER, rng, 0.03)
        a[..., :3] = mix(a[..., :3], PAPER_D, np.clip(np.maximum(np.abs(U - 0.5), np.abs(V - 0.5)) * 2 - 0.7, 0, 0.3)[..., None] * 2)
        if face == 'back' and h >= 6:
            rows = np.floor(V * max(6, h // 2)).astype(int)
            line = ((V * max(6, h // 2)) % 1.0 > 0.45) & (np.abs(U - 0.5) < 0.38) & (V > 0.12) & (V < 0.9)
            ragged = rng.random(max(6, h // 2) + 1)
            line &= U < 0.12 + 0.76 * (0.6 + 0.4 * ragged[np.clip(rows, 0, len(ragged) - 1)])
            head = (V > 0.04) & (V < 0.12) & (np.abs(U - 0.5) < 0.3)
            a[..., :3] = np.where(line[..., None], mix(a[..., :3], INK, 0.75), a[..., :3])
            a[..., :3] = np.where(head[..., None], np.array(WAX, float), a[..., :3])
        return a
    return painter(key, fn)


def wax_painter(key):
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, WAX, rng, 0.05)
        r = np.hypot(U - 0.5, V - 0.5)
        a[..., :3] = mix(a[..., :3], WAX_L, np.clip(0.25 - r, 0, 0.25)[..., None] * 2)
        a[r > 0.52] = 0 if face in ('front', 'back') else a[r > 0.52]
        return a
    return painter(key, fn)


def flame_painter(key):
    """A taper's flame, every pixel emissive (GLOW): a teardrop of fire pointing along +x (the pole's length), white at
    its root, clear round it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = np.zeros((h, w, 4), float)
        if face in ('right', 'left'):           # looking down the pole: a round glow
            r = np.hypot(U - 0.5, V - 0.5) * 2
            a[..., :3] = ramp(FLAME, 1 - r * 0.7)
            a[..., 3] = np.where(r <= 1.05, 255, 0)
            return a
        x = U if face in ('front', 'top') else 1 - U        # along the flame, root (0) to tip (1)
        half = 0.48 * np.clip(1 - x, 0, 1) ** 0.6 * np.clip(x * 3.5, 0, 1) ** 0.5
        inside = np.abs(V - 0.5) <= half + 0.02
        t = np.clip(1.0 - x * 0.8 - np.abs(V - 0.5) * 0.9, 0, 1)
        a[..., :3] = ramp(FLAME, t)
        a[..., 3] = np.where(inside, 255, 0)
        return a
    return painter(key, fn, light=False)


SOOT = (34, 28, 24)


def snuffer_painter(key, mouth=False):
    """The lamplighter's snuffer: a little brass bell, its mouth (the -y face, 'top': it faces out from the pole's
    side) black with soot inside a bright rim, the soot creeping up the sides from it; elsewhere polished brass with a
    glint."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top' and mouth:
            a = solid(w, h, SOOT)
            r = np.maximum(np.abs(U - 0.5), np.abs(V - 0.5))
            a[..., :3] = np.where((r > 0.34)[..., None], np.array(BRASS_L, float), a[..., :3])
            a[..., :3] = np.where((r <= 0.18)[..., None], np.array((14, 12, 10), float), a[..., :3])
            return a
        a = metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=0.08)
        glint = np.exp(-((U - 0.35) ** 2 + (V - 0.4) ** 2) * 12)
        a[..., :3] = mix(a[..., :3], (255, 240, 190), (glint * 0.6)[..., None])
        if mouth and face != 'bottom':
            # the sides lay y out down them from the mouth's edge (y0): V is how far a texel is from it
            a[..., :3] = mix(a[..., :3], SOOT, (np.clip(0.4 - V, 0, 1) * 1.6)[..., None])
        return a
    return painter(key, fn)


def basket_hip_painter(key):
    """A deep wicker basket for the hip: an over-under weave and a plaited rim; from above, the dark inside of it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = weave(w, h, WICKER_D, (80, 56, 28), WICKER, rng, cell=max(2, min(w, h) // 4))
            a[..., :3] *= 0.6
            rim = (U < 0.12) | (U > 0.88) | (V < 0.12) | (V > 0.88)
            a[..., :3] = np.where(rim[..., None], np.array(WICKER_L, float), a[..., :3])
            return a
        a = weave(w, h, WICKER, WICKER_D, WICKER_L, rng, cell=max(2, min(w, h) // 4))
        if face != 'bottom' and h >= 4:
            r = max(1, h // 6)
            a[:r, :, :3] = mix(a[:r, :, :3], WICKER_L, 0.4)
            a[r:r + 1, :, :3] = mix(a[r:r + 1, :, :3], WICKER_D, 0.5)
        return shade_edges(a, 0.2)
    return painter(key, fn)


def cloth_painter(key):
    """A cloth tucked over a basket of bread: a red and cream check, two loaves showing."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, (200, 60, 50), rng, 0.04)
        check = ((U * 4).astype(int) + (V * 4).astype(int)) % 2 == 0
        a[..., :3] = np.where(check[..., None], np.array((232, 220, 200), float), a[..., :3])
        if face == 'top':
            ys, xs = np.mgrid[0:h, 0:w]
            for cx, cy in ((0.34, 0.42), (0.64, 0.6)):
                d = np.hypot((xs + 0.5) / w - cx, ((ys + 0.5) / h - cy) * 1.3)
                loaf = d < 0.22
                a[..., :3] = np.where(loaf[..., None], mix(np.array(BREAD_L, float), np.array(BREAD_D, float), np.clip(d * 4.5, 0, 1)[..., None]), a[..., :3])
        return a
    return painter(key, fn)


def empty_sack_painter(key):
    """An emptied grain sack, hanging limp: the weave creased in folds, a pale dusting of flour."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, BURLAP, BURLAP_D, BURLAP_L, rng, cell=max(1, min(w, h) // 8))
        fold = np.sin(U * 9.0 + V * 2.0 + rng.uniform(0, 6))
        a[..., :3] *= (1 + 0.15 * fold)[..., None]
        dust = rng.random((h, w)) < 0.08
        a[..., :3] = np.where(dust[..., None], mix(a[..., :3], (240, 234, 220), 0.5), a[..., :3])
        return shade_edges(a, 0.25)
    return painter(key, fn)

# ================================================================== the props

def _loaf():
    # A loaf held upright in the fingers in front of the fist, tilted 45 degrees on the arm like the mug: upright with the
    # arm reaching 45 degrees forward. Its top end (part (0, -3.2, -3.0)) is what is bitten.
    return 'right_arm', Part('loaf', RIGHT_GRIP, (45, 0, 0), cubes=[
        C((-1.3, -3.2, -4.3), (2.6, 4.8, 2.6), 't_loaf', loaf_painter('t_loaf')),
    ])


def _apple():
    # An apple held the same way, its top at the same height as the loaf's (part y -2.9): the same clip eats either.
    return 'right_arm', Part('apple', RIGHT_GRIP, (45, 0, 0), cubes=[
        C((-1.3, -2.6, -4.3), (2.6, 2.6, 2.6), 't_apple', apple_painter('t_apple')),
        C((-0.2, -3.4, -3.2), (0.4, 0.9, 0.4), 't_apple_stem', stem_painter('t_apple_stem')),
        C((0.2, -3.2, -3.6), (1.1, 0.3, 0.9), 't_apple_leaf', leaf_painter('t_apple_leaf')),
    ])


# The bowl sits level on the left hand held out over a table, seated (sit_eat's pose: living_anims.py solved it, the arm
# reaching forward and a little down and in, its front down): the bowl's bottom 2 px over the grip, 1 px back.
BOWL_ROT = (-102.494, 0.0, -180.0)


def _bowl():
    # A bowl of stew held up on the left hand. The middle of its bottom at part (0, -2.0, 1.0), of the stew's top
    # (0, -3.9, 1.0); part -z points away from the eater.
    return 'left_arm', Part('bowl', LEFT_GRIP, BOWL_ROT, cubes=[
        C((-2.25, -3.9, -1.25), (4.5, 1.9, 4.5), 't_bowl', bowl_painter('t_bowl')),
    ])


def _spoon():
    # A wooden spoon held like a pen, at right angles to the arm (like the sword): with the arm hanging it points ahead.
    # The middle of its bowl at part (0, 5.0, -0.2).
    return 'right_arm', Part('spoon', RIGHT_GRIP, (-90, 0, 0), cubes=[
        C((-0.25, -1.5, -0.25), (0.5, 5.2, 0.5), 't_spoon_handle', wood_painter('t_spoon_handle', PALE, PALE_D, PALE_L, 'y')),
        C((-0.6, 3.7, -0.55), (1.2, 2.0, 0.7), 't_spoon_bowl', wood_painter('t_spoon_bowl', PALE_L, PALE, (236, 216, 170), 'y')),
    ])


def _bucket():
    # The well's bucket, hanging by its bail from the right fist (kept hanging upright: HANG). The bail's top is the grip;
    # the bucket's bottom at part (0, 6.4, 0).
    return 'right_arm', Part('bucket', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-2.2, 0.0, -0.15), (4.4, 2.4, 0.3), 't_bucket_bail', iron_arch_painter('t_bucket_bail')),
        C((-2.0, 2.0, -2.0), (4.0, 4.4, 4.0), 't_bucket', staves_painter('t_bucket', top=water_top)),
    ])


ROPE_LEN = 16.0         # the well's rope pieces are modelled this long along their +y; prop tracks stretch them


def _line(name, parent, pivot, thick=0.7):
    # A stretch of the well's rope, ROPE_LEN px along +y from its pivot: draw_water's prop tracks turn it to run where it
    # goes (from a fist over the curb, from the curb down the shaft) and stretch it to reach.
    h = thick / 2
    return parent, Part(name, pivot, (0, 0, 0), cubes=[
        C((-h, 0.0, -h), (thick, ROPE_LEN, thick), f't_{name}', rope_painter(f't_{name}', along='y')),
    ])


def _haul_rope_r():
    return _line('haul_rope_right', 'right_arm', RIGHT_GRIP)


def _haul_rope_l():
    return _line('haul_rope_left', 'left_arm', LEFT_GRIP)


def _tail_rope_r():
    return _line('tail_rope_right', 'right_arm', RIGHT_GRIP)


def _tail_rope_l():
    return _line('tail_rope_left', 'left_arm', LEFT_GRIP)


def _curb_rope():
    return _line('curb_rope', 'body', (0, 0, 0))


def _well_rope():
    return _line('well_rope', 'body', (0, 0, 0))


def _well_bucket():
    # The well's bucket on the end of its rope, coming up the shaft and over the curb: draw_water's prop track places it
    # by its bail's top (its pivot) in the clip's own space. The same bucket as the one carried off (bucket).
    return 'body', Part('well_bucket', (0, 0, 0), (0, 0, 0), cubes=[
        C((-2.2, 0.0, -0.15), (4.4, 2.4, 0.3), 't_well_bucket_bail', iron_arch_painter('t_well_bucket_bail')),
        C((-2.0, 2.0, -2.0), (4.0, 4.4, 4.0), 't_well_bucket', staves_painter('t_well_bucket', top=water_top)),
    ])


def _coin():
    # A coin pinched in the fingertips in front of the right fist, its face forward: part (0, 0.5, -3.0).
    return 'right_arm', Part('coin', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-0.7, -0.2, -3.2), (1.4, 1.4, 0.4), 't_coin', coin_painter('t_coin')),
    ])


def _seed_bag():
    # A sower's bag of seed at the front of the left hip, on a strap across the chest to the right shoulder. Its open
    # mouth (the top's middle) at body (3.6, 7.6, -3.6).
    strap = Part('seed_bag_strap', (-0.2, -1.4, 1.6), (0, 0, 41), cubes=[
        C((-0.5, -10.2, -0.2), (1.0, 10.2, 0.4), 't_seed_bag_strap', strap_painter('t_seed_bag_strap')),
    ])
    return 'body', Part('seed_bag', (3.6, 9.2, -3.6), (0, -12, 0), cubes=[
        C((-2.0, -1.6, -1.8), (4.0, 4.6, 3.6), 't_seed_bag', seed_bag_painter('t_seed_bag')),
    ], children=[strap])


# The scythe in the right fist as the mowing stance holds it (living_anims.py solves it: bent 35 degrees and sunk 3 px, the
# fist low in front of the right hip, the snath running back past it to the right and down to the blade, the blade flat on
# the ground; the scythe clip checks it).
SCYTHE_ROT = (109.127, -36.331, 34.678)


def _scythe():
    # A scythe, worked with both hands: the right fist on the snath's lower grip (the part's origin), the left on the top
    # end 11 px up it. The snath is the part's x (the blade's heel at x 17, the top end at -12); the blade runs off the
    # heel along -z, lying flat with the snath sloping 35 degrees up from the ground towards the hands, its edge (+y of
    # the blade, the cubes' bottom rows) leading.
    up = (math.sin(math.radians(35)), -math.cos(math.radians(35)), 0.0)     # the world's up, in the snath's frame, mowing
    L = (0.0, 0.0, -1.0)
    W = tuple(np.cross(up, L))
    blade = Part('scythe_blade', (17.0, 0.0, 0.0), euler_from_axes(L, W), cubes=[
        C((-0.5, -1.4, -0.3), (7.0, 2.6, 0.6), 't_scythe_blade_a', scythe_blade_painter('t_scythe_blade_a')),
        C((6.0, -1.9, -0.3), (6.0, 2.0, 0.6), 't_scythe_blade_b', scythe_blade_painter('t_scythe_blade_b')),
        C((11.5, -2.6, -0.3), (2.0, 1.4, 0.6), 't_scythe_blade_tip', scythe_blade_painter('t_scythe_blade_tip')),
    ])
    return 'right_arm', Part('scythe', RIGHT_GRIP, SCYTHE_ROT, cubes=[
        C((-12.0, -0.5, -0.5), (29.5, 1.0, 1.0), 't_scythe_snath', wood_painter('t_scythe_snath', WOOD, WOOD_D, WOOD_L, 'x', knots=1)),
        C((-0.6, -0.6, -2.4), (1.2, 1.2, 1.9), 't_scythe_nib', wood_painter('t_scythe_nib', OAK, OAK_D, OAK_L, 'z')),
        C((16.5, -0.75, -0.75), (1.5, 1.5, 1.5), 't_scythe_ferrule', iron_painter('t_scythe_ferrule')),
    ], children=[blade])


def _axe():
    # A splitting axe, the right fist on the handle and the left 3 px nearer its end. The handle continues the arm's line,
    # cocked 15 degrees forward (part x is the handle, out from the fist; the bit is +z of the part), so an overhead chop
    # brings the bit down edge first; the bit's edge at part (8.5, 0, 3.4).
    xa = _rot_x(-15, (0, 1, 0))
    za = _rot_x(-15, (0, 0, 1))
    ya = tuple(np.cross(za, xa))
    return 'right_arm', Part('axe', RIGHT_GRIP, euler_from_axes(xa, ya), cubes=[
        C((-4.5, -0.5, -0.5), (14.0, 1.0, 1.0), 't_axe_handle', wood_painter('t_axe_handle', PALE, PALE_D, PALE_L, 'x')),
        C((7.0, -0.75, -1.6), (3.0, 1.5, 5.0), 't_axe_head', axe_head_painter('t_axe_head')),
    ])


def _handbell():
    # A crier's handbell: the turned handle in the fist, the bell beyond it along the arm, cocked 10 degrees forward; the
    # mouth's middle at part (0, 5.4, 0).
    return 'right_arm', Part('handbell', RIGHT_GRIP, (-10, 0, 0), cubes=[
        C((-0.6, -1.6, -0.6), (1.2, 2.8, 1.2), 't_handbell_handle', wood_painter('t_handbell_handle', EBONY, EBONY_D, EBONY_L, 'y')),
        C((-1.1, 1.1, -1.1), (2.2, 1.2, 2.2), 't_handbell_crown', bell_painter('t_handbell_crown')),
        C((-1.7, 2.3, -1.7), (3.4, 2.2, 3.4), 't_handbell_body', bell_painter('t_handbell_body')),
        C((-2.1, 4.5, -2.1), (4.2, 0.8, 4.2), 't_handbell_lip', bell_painter('t_handbell_lip', mouth=True)),
    ])


# The scroll is held up to read in the left hand (by its top roller): SCROLL_ROT turns the sheet to hang straight with
# its writing towards the reader in the reading pose of the announce clip (living_anims.py solves that pose and checks
# the sheet hangs within a few degrees of upright).
SCROLL_ROT = (98.394, 2.697, -17.687)


def _scroll():
    return 'left_arm', Part('scroll', LEFT_GRIP, SCROLL_ROT, cubes=[
        C((-3.6, -0.6, -0.6), (7.2, 1.2, 1.2), 't_scroll_top', wood_painter('t_scroll_top', OAK_D, (60, 40, 24), OAK, 'x')),
        C((-3.0, 0.5, -0.15), (6.0, 8.4, 0.3), 't_scroll_sheet', scroll_sheet_painter('t_scroll_sheet')),
        C((-3.6, 8.8, -0.6), (7.2, 1.2, 1.2), 't_scroll_bottom', wood_painter('t_scroll_bottom', OAK_D, (60, 40, 24), OAK, 'x')),
        C((-0.7, 6.4, -0.5), (1.4, 1.4, 0.35), 't_scroll_seal', wax_painter('t_scroll_seal')),
    ])


LAMP_POLE_ROT = euler_from_axes((0.0, -math.cos(math.radians(25)), -math.sin(math.radians(25))), (1.0, 0.0, 0.0))


SNUFFER_X = 36.2                    # lamp_pole space: how far up the pole the snuffer's middle is (low enough to keep
                                    # under the lantern while the wick is in its door)
SNUFFER_MOUTH = (SNUFFER_X, -4.0, 0.0)    # the middle of its mouth
SNUFFER_AXIS = (0.0, -1.0, 0.0)     # the way its mouth faces: out from the pole's side


def _lamp_pole():
    # A lamplighter's pole, near three blocks of ash with a brass cap and a burning wick at the top: held low, near its
    # foot, in front of the fist along the arm like the watch's spear (the same pivot) but leaning 25 degrees forward of
    # it, so with the arm hanging it is carried at a slant, the wick 50 px up. The pole is the part's x (turned up the
    # arm, LAMP_POLE_ROT); the flame's root at part (40.8, 0, 0), its tip 2.4 px on. A little below the wick the
    # snuffer stands out from the pole on a short arm, on its side (part -y: out to the right of the pole as it is
    # carried and as a lamp is lit, clear of the lamp): a brass bell, its sooty mouth facing out (SNUFFER_MOUTH,
    # SNUFFER_AXIS), put over a lamp's flame to put it out of a morning (the pole turned a quarter in the hands to
    # bring it round to the lamp: snuff_lamp's track).
    flame = Part('lamp_flame', (40.8, 0.0, 0.0), (0, 0, 0), cubes=[
        C((0.0, -0.6, -0.6), (2.4, 1.2, 1.2), 't_lamp_flame', flame_painter('t_lamp_flame')),
    ])
    sx = SNUFFER_X
    return 'right_arm', Part('lamp_pole', (-1.0, 10.0, -2.6), LAMP_POLE_ROT, cubes=[
        C((-5.4, -0.5, -0.5), (45.0, 1.0, 1.0), 't_lamp_pole', wood_painter('t_lamp_pole', WOOD, WOOD_D, WOOD_L, 'x', knots=2)),
        C((39.0, -0.85, -0.85), (1.8, 1.7, 1.7), 't_lamp_pole_cap', brass_painter('t_lamp_pole_cap')),
        C((-5.7, -0.7, -0.7), (0.8, 1.4, 1.4), 't_lamp_pole_foot', iron_painter('t_lamp_pole_foot')),
        C((sx - 0.3, -1.5, -0.3), (0.6, 1.1, 0.6), 't_lamp_pole_arm', brass_painter('t_lamp_pole_arm')),
        C((sx - 0.6, -2.0, -0.6), (1.2, 0.6, 1.2), 't_lamp_pole_crown', snuffer_painter('t_lamp_pole_crown')),
        C((sx - 1.1, -4.0, -1.1), (2.2, 2.0, 2.2), 't_lamp_pole_snuffer', snuffer_painter('t_lamp_pole_snuffer', mouth=True)),
    ], children=[flame])


def _basket_hip():
    # A deep basket carried on the left hip, the left arm round its far side, the hand on its rim. Its open top's middle at
    # body (5.6, 7.4, -4.0); what is carried (the goods layer) sits in it there. The cloth over a load of bread shows when
    # nothing is carried.
    cloth = Part('basket_hip_cloth', (0.0, -2.1, 0.0), (0, 0, 0), cubes=[
        C((-2.7, -0.6, -2.4), (5.4, 0.9, 4.8), 't_basket_hip_cloth', cloth_painter('t_basket_hip_cloth')),
    ])
    return 'body', Part('basket_hip', (5.6, 9.5, -4.0), (0, -18, -6), cubes=[
        C((-3.0, -2.1, -2.6), (6.0, 4.6, 5.2), 't_basket_hip', basket_hip_painter('t_basket_hip')),
    ], children=[cloth])


def _sack_held():
    # The grain sack of carry_sack, taken in the right hand by its neck to be tipped out (pour_sack): the same sack as the
    # one on the shoulder, placed in the hand where the shoulder's sack is at the moment the hands take it.
    return 'right_arm', Part('sack_held', SACK_HELD_PIVOT, SACK_HELD_ROT, cubes=[
        C((-2.6, -3.0, -4.6), (5.2, 5, 9), 't_sack_held', sack_painter('t_sack_held')),
        C((-1.5, -2.6, -6.2), (3, 3, 1.6), 't_sack_held_neck', sack_painter('t_sack_held_neck')),
        C((-1.6, -2.7, -5.0), (3.2, 3.2, 0.6), 't_sack_held_cord', cord_painter('t_sack_held_cord')),
    ])


# Where the shoulder's sack is in the right arm's space at carry_sack's first frame, so the swap from the shoulder to
# the hand as pour_sack begins is not seen (living_anims.py checks it)
SACK_HELD_PIVOT = (-0.181, 1.834, -2.714)
SACK_HELD_ROT = (131.136, -0.487, 8.807)


def _sack_empty():
    # The sack once tipped out: hanging limp from the right fist by its neck (HANG).
    return 'right_arm', Part('sack_empty', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-1.0, -0.5, -1.0), (2.0, 1.6, 2.0), 't_sack_empty_neck', empty_sack_painter('t_sack_empty_neck')),
        C((-2.6, 1.0, -0.9), (5.2, 6.4, 1.8), 't_sack_empty', empty_sack_painter('t_sack_empty')),
    ])


CRANK_THROW = 3.0         # the crank's throw: the fist goes round the hub this far out


def _crank():
    # A grindstone's crank: the handle through the right fist (part z, the axle's way), the web from the handle's far end
    # along part +x to the hub on the axle, part (CRANK_THROW, 0, -2.9). The grind clip's prop track turns it about the
    # axle whatever way the arm is turned, so the web points from the fist to the hub as the hand goes round.
    r = CRANK_THROW
    return 'right_arm', Part('crank', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-0.5, -0.5, -2.3), (1.0, 1.0, 4.6), 't_crank_handle', wood_painter('t_crank_handle', OAK, OAK_D, OAK_L, 'z')),
        C((-0.6, -0.6, -3.1), (r + 1.2, 1.2, 0.8), 't_crank_web', iron_painter('t_crank_web')),
        C((r - 1.0, -1.0, -3.6), (2.0, 2.0, 1.4), 't_crank_hub', iron_painter('t_crank_hub')),
    ])


# ================================================================== the road's props (art/scripts/road_clips.py)

REIN = (92, 58, 34); REIN_D = (58, 36, 20); REIN_L = (136, 92, 58)      # harness leather (wagon.py's, the horse's reins)
CANVAS = (214, 204, 178); CANVAS_D = (160, 148, 120); CANVAS_L = (236, 230, 210)
OATS = (196, 168, 104); OATS_D = (140, 112, 62)
BAND_BLUE = (52, 80, 164)       # the band's chalk mark, as on the marked goods of its loot pile (wagon.py)


def rein_painter(key, along='y'):
    """A rein: a strap of dark harness leather, darker at its edges, the grain running along it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, REIN, rng, 0.05)
        across, down = (U, V) if along == 'y' else (V, U)
        a[..., :3] = mix(a[..., :3], REIN_D, np.clip(np.abs(across - 0.5) * 2 - 0.35, 0, 0.6)[..., None])
        streak = np.sin(down * max(w, h) * 0.9 + across * 3.0 + rng.uniform(0, 6)) > 0.75
        a[..., :3] = np.where(streak[..., None], mix(a[..., :3], REIN_L, 0.3), a[..., :3])
        return a
    return painter(key, fn)


def knife_blade_painter(key):
    """A working knife's blade: grey steel, its honed edge (the cube's +z face) bright, the spine dark, a speck of rust."""
    def fn(face, w, h, rng):
        a = metal(w, h, (150, 154, 164), (92, 96, 106), (214, 220, 232), rng, bevel=True, wear=0.08)
        if face == 'back':
            a[..., :3] = mix(a[..., :3], STEEL_L, 0.75)
        elif face == 'front':
            a[..., :3] = mix(a[..., :3], (70, 72, 80), 0.45)
        rust = rng.random((h, w)) < 0.04
        a[..., :3] = np.where(rust[..., None], mix(a[..., :3], (132, 72, 40), 0.5), a[..., :3])
        return a
    return painter(key, fn)


def loot_sack_painter(key, mark=True):
    """A sack stuffed with what was taken: lumpy burlap gathered at the neck, the band's blue chalk ring on its side."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, BURLAP, BURLAP_D, BURLAP_L, rng, cell=max(1, min(w, h) // 8))
        lump = np.sin(U * 4.6 + rng.uniform(0, 6)) * np.sin(V * 3.9 + rng.uniform(0, 6))
        a[..., :3] *= (1 + 0.14 * lump)[..., None]
        if face in ('front', 'back', 'right', 'left'):
            a[..., :3] = mix(a[..., :3], BURLAP_D, (np.clip(0.3 - V, 0, 0.3) * 1.6)[..., None])
            if mark and min(w, h) >= 6 and face in ('front', 'left'):
                x, y = (U - 0.5) * w / h, V - 0.56
                d = np.hypot(x, y)
                ring = (np.abs(d - 0.21) < 0.045) | ((np.abs(x + y * 0.3) < 0.04) & (d < 0.21))
                a[..., :3] = np.where(ring[..., None], mix(a[..., :3], BAND_BLUE, 0.85), a[..., :3])
        return shade_edges(a, 0.3, width=max(1, min(w, h) // 4))
    return painter(key, fn)


def nosebag_painter(key):
    """A horse's nosebag: a bucket of heavy canvas, a leather bottom and rim; seen from above, the oats in it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = noise_fill(w, h, OATS, rng, 0.12)
            husk = rng.random((h, w)) < 0.3
            a[..., :3] = np.where(husk[..., None], mix(a[..., :3], OATS_D, 0.6), a[..., :3])
            rim = (U < 0.16) | (U > 0.84) | (V < 0.16) | (V > 0.84)
            a[..., :3] = np.where(rim[..., None], np.array(REIN, float), a[..., :3])
            return a
        if face == 'bottom':
            return shade_edges(noise_fill(w, h, REIN, rng, 0.06), 0.25)
        a = weave(w, h, CANVAS, CANVAS_D, CANVAS_L, rng, cell=max(1, min(w, h) // 6))
        a[..., :3] *= 0.94
        a[..., :3] = np.where((V < 0.16)[..., None], noise_fill(w, h, REIN, rng, 0.06)[..., :3], a[..., :3])
        a[..., :3] = np.where((V > 0.82)[..., None], noise_fill(w, h, REIN_D, rng, 0.06)[..., :3], a[..., :3])
        return shade_edges(a, 0.2)
    return painter(key, fn)


def _driver_knife():
    # The driver's knife (cut_loose), a stout working blade held at right angles to the arm like the spoon, its edge (part
    # +z) down: with the arm hanging it points ahead. The blade's middle at part (0, 5.2, 0.2), its tip at (0, 7.7, -0.1).
    return 'right_arm', Part('driver_knife', RIGHT_GRIP, (-90, 0, 0), cubes=[
        C((-0.55, -1.7, -0.65), (1.1, 3.6, 1.3), 't_driver_knife_handle', wood_painter('t_driver_knife_handle', EBONY, EBONY_D, EBONY_L, 'y')),
        C((-0.7, 1.9, -0.8), (1.4, 0.6, 1.6), 't_driver_knife_bolster', brass_painter('t_driver_knife_bolster')),
        C((-0.2, 2.5, -0.55), (0.4, 4.2, 1.5), 't_driver_knife_blade', knife_blade_painter('t_driver_knife_blade')),
        C((-0.2, 6.7, -0.55), (0.4, 1.0, 0.9), 't_driver_knife_tip', knife_blade_painter('t_driver_knife_tip')),
    ])


# The reins: each in three pieces REIN_LEN px along +y from their pivots, which the driving clips' prop tracks lay from
# the fist (the right rein in the left fist while the right hand is on the brake) to the rein ring on the horse's cart
# saddle as it stands in the shafts, sagging, flicked and hauled taut. The horse's own reins (DraughtHorseMesh "rein_r",
# "rein_l") run on from the rings to the bit.
REIN_LEN = 16.0
REIN_PIECES = 3
REINS = [f'rein_{side}_{i}' for side in ('right', 'left') for i in range(1, REIN_PIECES + 1)]


def _rein(name):
    right = name.startswith('rein_right')
    return ('right_arm' if right else 'left_arm'), Part(name, RIGHT_GRIP if right else LEFT_GRIP, (0, 0, 0), cubes=[
        C((-0.45, 0.0, -0.2), (0.9, REIN_LEN, 0.4), f't_{name}', rein_painter(f't_{name}')),
    ])


_REIN_BUILDERS = [lambda name=name: _rein(name) for name in REINS]


# The watch's spear laid across the knees, riding the tailboard (ride_tail): held by the middle of its shaft in the right
# fist resting on the right thigh, its head out to the left. The clip's prop track keeps it lying level across the lap
# however the arm moves; SPEAR_KNEES_MID: how far up the shaft from where the watch holds its spear the fist is.
SPEAR_KNEES_MID = 7.0


def _spear_knees():
    m = SPEAR_KNEES_MID
    return 'right_arm', Part('spear_knees', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-0.5, -19.5 + m, -0.5), (1, 31, 1), 't_spear_knees_shaft', wood_painter('t_spear_knees_shaft', WOOD, WOOD_D, WOOD_L, 'y', knots=1)),
        C((-0.7, 11.0 + m, -0.7), (1.4, 1.0, 1.4), 't_spear_knees_butt', iron_painter('t_spear_knees_butt')),
        C((-0.75, -21.0 + m, -0.75), (1.5, 2.0, 1.5), 't_spear_knees_socket', iron_painter('t_spear_knees_socket', IRON, IRON_D, IRON_L)),
        C((-1.5, -26.0 + m, -0.25), (3, 5, 0.5), 't_spear_knees_head', tt.spear_head_painter('t_spear_knees_head')),
    ])


LOOT_SACK_BOTTOM = 9.0          # the loot sack's bottom, this far under the fist


def _loot_sack():
    # A waylayer's sack of loot held by its gathered neck in the right fist, hanging from it (HANG). LootSackMesh (the
    # same sack on its own, for the waylayers' fists: art/scripts/road_clips.py) is made from this.
    body = Part('loot_sack_body', (0.0, 2.2, 0.0), (0, 8, 0), cubes=[
        C((-2.9, 0.3, -2.4), (5.8, 5.9, 4.8), 't_loot_sack_body', loot_sack_painter('t_loot_sack_body')),
        C((-2.2, 6.0, -1.8), (4.4, 0.8, 3.6), 't_loot_sack_bottom', loot_sack_painter('t_loot_sack_bottom', mark=False)),
    ])
    return 'right_arm', Part('loot_sack', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-0.9, -0.6, -0.9), (1.8, 2.2, 1.8), 't_loot_sack_neck', loot_sack_painter('t_loot_sack_neck', mark=False)),
        C((-1.1, 0.9, -1.1), (2.2, 0.6, 2.2), 't_loot_sack_cord', cord_painter('t_loot_sack_cord')),
        C((-1.7, 1.4, -1.7), (3.4, 1.2, 3.4), 't_loot_sack_shoulder', loot_sack_painter('t_loot_sack_shoulder', mark=False)),
    ], children=[body])


def _nosebag_held():
    # The horse's nosebag carried to it by its strap in the left fist (HANG): the strap's loop over the fist, the bag
    # below it, its mouth at part (0, 3.4, 0). On the horse it's DraughtHorseMesh's own "nosebag".
    return 'left_arm', Part('nosebag_held', LEFT_GRIP, (0, 0, 0), cubes=[
        C((-1.9, -0.5, -0.25), (0.8, 4.0, 0.5), 't_nosebag_strap_a', rein_painter('t_nosebag_strap_a')),
        C((1.1, -0.5, -0.25), (0.8, 4.0, 0.5), 't_nosebag_strap_b', rein_painter('t_nosebag_strap_b')),
        C((-1.9, -0.9, -0.25), (3.8, 0.6, 0.5), 't_nosebag_strap_c', rein_painter('t_nosebag_strap_c', along='x')),
        C((-2.6, 3.4, -2.3), (5.2, 5.4, 4.6), 't_nosebag_bag', nosebag_painter('t_nosebag_bag')),
    ])


# The road's (M4): townsfolk.py packs their cubes after all the others here, so adding them moved no cube already on
# the texture.
ROAD_BUILDERS = [_driver_knife, *_REIN_BUILDERS, _spear_knees, _loot_sack, _nosebag_held]

BUILDERS = [_loaf, _apple, _bowl, _spoon, _bucket, _haul_rope_r, _haul_rope_l, _tail_rope_r, _tail_rope_l, _curb_rope,
            _well_rope, _well_bucket, _coin, _seed_bag, _scythe, _axe, _handbell, _scroll, _lamp_pole, _basket_hip,
            _sack_held, _sack_empty, _crank, *ROAD_BUILDERS]

GLOW_CUBES = {'t_lamp_flame'}

# Parts the game turns each frame to hang straight down from the hand (LivingProps.kt), unless a clip's prop track runs
# them (the parts it turns, places and stretches tick by tick: the crank, the well's ropes and bucket)
HANG = ('bucket', 'sack_empty', 'loot_sack', 'nosebag_held')

TOOL_NOTES = {
    'loaf': 'right hand, tilted 45 like the mug; the bitten top end at part (0, -3.2, -3.0)',
    'apple': 'right hand like the loaf; its top at part (0, -2.6, -3.0)',
    'bowl': 'left hand, sitting on it: level with the arm straight out in front; the top of the stew at part (0, -3.9, 1.0)',
    'spoon': 'right hand, at right angles to the arm like the sword; its bowl at part (0, 4.7, -0.2)',
    'bucket': 'right hand by the bail, hangs upright; bottom at part (0, 6.4, 0)',
    'haul_rope_right': 'right fist; a stretch of the well rope (prop track: from the fist to the curb\'s edge)',
    'haul_rope_left': 'left fist; a stretch of the well rope (prop track: from the fist to the curb\'s edge)',
    'tail_rope_right': 'right fist; the hauled-in rope (prop track: from the fist down to its coil on the ground)',
    'tail_rope_left': 'left fist; the hauled-in rope (prop track: from the fist down to its coil on the ground)',
    'curb_rope': 'body; the rope across the well\'s curb (prop track places it)',
    'well_rope': 'body; the rope down the shaft to the bucket (prop track places it)',
    'well_bucket': 'body; the bucket coming up the shaft and over the curb (prop track places it by its bail\'s top)',
    'coin': 'right fingertips; part (0, 0.5, -3.0)',
    'seed_bag': 'on the body at the front of the left hip, a strap to the right shoulder; mouth at body (3.6, 7.6, -3.6)',
    'scythe': 'right hand on the snath (part x), the left 11 px up it; the blade off the heel (x 17) along -z',
    'axe': 'right hand, the left 3 px nearer the end (part x, cocked 15 forward of the arm); the bit\'s edge at part (8.5, 0, 3.4)',
    'handbell': 'right hand, along the arm; the mouth at part (0, 5.4, 0)',
    'scroll': 'left hand by the top roller, the sheet hanging below it facing the reader in the announce pose',
    'lamp_pole': 'right hand near its foot, up the arm like the spear but leaning 25 forward of it; the wick (lamp_flame, glows) at part (42, 0, 0); the snuffer\'s mouth at part (36.2, -4, 0), facing -y (turned to the lamp in snuff_lamp by its track)',
    'basket_hip': 'on the body at the left hip; its top at body (5.6, 7.4, -4.0); basket_hip_cloth shows when nothing is carried',
    'sack_held': 'right hand by the neck: the shoulder sack of carry_sack, taken in hand to pour',
    'sack_empty': 'right hand, hangs limp',
    'crank': 'right hand; turned about the axle by its prop track so the web points to the hub, part (3, 0, -2.9)',
    'driver_knife': 'right hand, at right angles to the arm like the spoon, edge down; the blade\'s middle at part (0, 5.2, 0.2)',
    **{name: f'{"right" if "right" in name else "left"} fist; piece {name[-1]} of {REIN_PIECES} of a rein (prop tracks lay the '
             'pieces from the fist to the rein ring on the horse\'s saddle)' for name in REINS},
    'spear_knees': 'right fist on the right thigh, by the middle of the shaft; its prop track lays it level across the knees',
    'loot_sack': f'right fist by the neck, hangs; its bottom {LOOT_SACK_BOTTOM:g} px under the fist',
    'nosebag_held': 'left fist by its strap, hangs; its mouth at part (0, 3.4, 0)',
}

# ================================================================== the works' and the streets' props (M6)
# art/scripts/works_clips.py (the master builder's crew, the topping out and the opening, the watchtower) and
# street_clips.py (the alms table, the hard days, households coming and going) hold these. townsfolk.py packs their cubes
# last of all (works_cube_names()), so adding them moved no cube already on the texture. Most of them are turned, placed
# and stretched by their clips' prop tracks (the plumb line swings as a pendulum, the unveiling cloth flies off as a chain
# of three panels, the torch's flame stands up whichever way the torch leans): their rest rotations are where they sit
# when a clip shows them untracked.

MORTAR = (196, 190, 176); MORTAR_D = (150, 144, 132); MORTAR_L = (222, 218, 206)
PEWTER = (150, 154, 160); PEWTER_D = (96, 100, 108); PEWTER_L = (210, 214, 220)
WINE = (104, 18, 32); WINE_L = (168, 52, 64)
VELVET = (138, 20, 36); VELVET_D = (84, 10, 22); VELVET_L = (198, 58, 72)
GILT = (224, 180, 72); GILT_D = (150, 110, 40)
HOMESPUN = (66, 94, 142); HOMESPUN_D = (40, 58, 98); HOMESPUN_L = (128, 154, 192)
IVORY = (228, 216, 186); IVORY_D = (178, 158, 118); HORN_TIP = (70, 54, 42)
PITCH = (42, 32, 26); PITCH_L = (92, 70, 48); EMBER = (255, 136, 44)
BAND_RED = (186, 40, 36); BAND_WHITE = (234, 230, 218)
BEECH = (200, 160, 110); BEECH_D = (150, 110, 70); BEECH_L = (228, 194, 146)


def trowel_blade_painter(key):
    """A brick trowel's blade: thin bright steel, broad at its heel (the cube's -y end, under the handle) and drawn to a
    point at its tip (+y), a smear of mortar on its face near the heel; clear outside its outline."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = metal(w, h, STEEL, STEEL_D, STEEL_L, rng, bevel=False, wear=0.04)
        if face in ('front', 'back'):
            half = 0.5 * np.clip(1.0 - V, 0, 1) ** 0.75 + 0.03
            inside = np.abs(U - 0.5) <= half
            edge = np.abs(U - 0.5) > half - max(0.09, 1.0 / w)
            a[..., :3] = np.where(edge[..., None], mix(a[..., :3], (246, 248, 252), 0.55), a[..., :3])
            a[..., :3] *= (1.0 + 0.12 * (0.5 - V))[..., None]
            if face == 'front':
                smear = (V < 0.4) & (np.abs(U - 0.46) < 0.2) & (rng.random((h, w)) < 0.75)
                a[..., :3] = np.where(smear[..., None], mix(a[..., :3], MORTAR, 0.8), a[..., :3])
            a[~inside] = 0
        elif face == 'bottom':
            a[np.abs(U - 0.5) > 0.14] = 0
        elif face in ('right', 'left'):
            a[V > 0.14] = 0
        return a
    return painter(key, fn)


def larry_blade_painter(key):
    """A mortar hoe's blade: a broad iron plate with two holes through it (the mortar works through them as it is
    turned), caked grey with old mortar."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = metal(w, h, (90, 88, 86), (52, 50, 50), (154, 152, 148), rng, bevel=True, wear=0.05)
        cake = rng.random((h, w)) < 0.3 + 0.35 * np.abs(U - 0.5) * 2
        a[..., :3] = np.where(cake[..., None], mix(a[..., :3], mix(MORTAR, MORTAR_D, rng.random((h, w, 1))), 0.8), a[..., :3])
        if face in ('top', 'bottom'):
            for cx in (0.3, 0.7):
                d = np.hypot((U - cx) * w, (V - 0.5) * h)
                hole = d < min(w, h) * 0.2
                rim = d < min(w, h) * 0.3
                a[..., :3] = np.where((rim & ~hole)[..., None], mix(a[..., :3], (40, 38, 38), 0.5), a[..., :3])
                a[hole] = 0
        return a
    return painter(key, fn)


def mallet_painter(key):
    """A mason's round beech mallet head: the grain along its axis (the cube's y), turned round (its corners shaded
    deep), pocked and dark where it strikes round its middle; its ends end grain."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('top', 'bottom'):
            a = grain(w, h, None, BEECH, BEECH_D, BEECH_L, rng)
            r = np.hypot(U - 0.5, V - 0.5) * 2
            a[..., :3] *= (1.05 - 0.35 * np.clip(r - 0.55, 0, 1))[..., None]
            return a
        a = grain(w, h, 'v', BEECH, BEECH_D, BEECH_L, rng, streak=0.35)
        a[..., :3] *= (0.72 + 0.34 * np.sin(np.clip(U, 0, 1) * math.pi))[..., None]
        pock = (np.abs(V - 0.5) < 0.3) & (rng.random((h, w)) < 0.12)
        a[..., :3] = np.where(pock[..., None], mix(a[..., :3], BEECH_D, 0.7), a[..., :3])
        a[..., :3] = mix(a[..., :3], (120, 84, 54), (np.clip(0.3 - np.abs(V - 0.5), 0, 0.3) * 0.8)[..., None])
        return a
    return painter(key, fn)


def chisel_painter(key, part):
    """A mason's chisel: dark forged steel; its edge ([part] 'edge') ground bright, its struck head ('head') burred
    over and polished by the mallet."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = metal(w, h, (104, 106, 114), (58, 60, 66), (176, 180, 192), rng, wear=0.06)
        if part == 'edge':
            a[..., :3] = mix(a[..., :3], STEEL_L, (np.clip(V - 0.35, 0, 1) * 1.1)[..., None])
        elif part == 'head' and face == 'top':
            a[..., :3] = mix(a[..., :3], STEEL_L, 0.6)
        elif part == 'head':
            a[..., :3] = np.where((V < 0.45)[..., None], mix(a[..., :3], (150, 154, 166), 0.5), a[..., :3])
        return a
    return painter(key, fn)


def staff_painter(key):
    """A surveyor's ranging staff, modelled along x: ash painted in bands of red and white every 4 px (so a sight along it
    reads at a distance), the paint worn at the edges and dirty towards the foot (-x)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('right', 'left'):
            return shade_edges(grain(w, h, None, WOOD, WOOD_D, WOOD_L, rng), 0.2)
        a = grain(w, h, 'u', WOOD, WOOD_D, WOOD_L, rng, streak=0.3)
        bands = np.floor(U * 30.0 / 4.0).astype(int) % 2 == 0
        paint = np.where(bands[..., None], np.array(BAND_RED, float), np.array(BAND_WHITE, float))
        paint = paint * (1 + rng.normal(0, 0.03, (h, w, 1)))
        worn = rng.random((h, w)) < 0.05 + 0.25 * (np.abs(V - 0.5) > 0.38)
        a[..., :3] = np.where(worn[..., None], a[..., :3], paint)
        a[..., :3] = mix(a[..., :3], (96, 78, 56), (np.clip(0.18 - U, 0, 0.18) * 3.0)[..., None])
        return shade_edges(a, 0.14)
    return painter(key, fn)


def pennant_painter(key):
    """The staff's pennant, a flat cube in the staff's x-z plane: a little triangle of red over white cloth, its base along
    the staff (the cube's -z edge: v 1 on its broad faces) and its point out from it; clear round it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, BAND_WHITE, rng, 0.03)
        if face in ('top', 'bottom'):
            inside = np.abs(U - 0.5) <= 0.5 * V + 0.02
            a[..., :3] = np.where((U > 0.5)[..., None], np.array(BAND_RED, float), a[..., :3])
            a[~inside] = 0
        else:
            a[..., :3] = np.array(BAND_RED, float)
            if face in ('right', 'left', 'back'):
                a[...] = 0
        return a
    return painter(key, fn)


def beam_painter(key):
    """A squared oak beam, modelled along x: the grain running its length, the adze's shallow scallops across its faces,
    a knot or two, a split; its sawn ends end grain."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('right', 'left'):
            return shade_edges(grain(w, h, None, OAK, OAK_D, OAK_L, rng), 0.3)
        a = grain(w, h, 'u', OAK, OAK_D, OAK_L, rng, streak=0.5, knots=2)
        n = max(4, w // 5)
        scallop = np.sin((U * n + 0.3 * np.sin(V * 3.0 + rng.uniform(0, 6))) * math.pi) ** 2
        a[..., :3] *= (0.9 + 0.12 * scallop)[..., None]
        if face in ('front', 'top'):
            x0 = rng.uniform(0.2, 0.6)
            split = (U > x0) & (U < x0 + 0.18) & (np.abs(V - 0.5 - 0.15 * np.sin((U - x0) * 30)) < max(0.05, 0.6 / h))
            a[..., :3] = np.where(split[..., None], a[..., :3] * 0.55, a[..., :3])
        return shade_edges(a, 0.28, width=max(1, h // 4))
    return painter(key, fn)


def pewter_painter(key, wine=False):
    """Pewter, soft grey and a little worn, a bright glint and a dark line where it was turned; on top ([wine]) the wine
    in the cup inside a pewter rim."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top' and wine:
            a = noise_fill(w, h, WINE, rng, 0.05)
            hl = np.exp(-((U - 0.35) ** 2 + (V - 0.35) ** 2) * 16)
            a[..., :3] = mix(a[..., :3], WINE_L, (hl * 0.7)[..., None])
            rim = (U < 0.16) | (U > 0.84) | (V < 0.16) | (V > 0.84)
            a[..., :3] = np.where(rim[..., None], np.array(PEWTER_L, float), a[..., :3])
            return a
        a = metal(w, h, PEWTER, PEWTER_D, PEWTER_L, rng, wear=0.05)
        glint = np.exp(-((U - 0.3) ** 2 + (V - 0.38) ** 2) * 12)
        a[..., :3] = mix(a[..., :3], (240, 244, 248), (glint * 0.55)[..., None])
        if h >= 4 and face not in ('top', 'bottom'):
            ring = np.abs(V - 0.25) < max(0.05, 0.5 / h)
            a[..., :3] = np.where(ring[..., None], a[..., :3] * 0.75, a[..., :3])
        return a
    return painter(key, fn)


def ladle_bowl_painter(key, full):
    """A turned wooden ladle's bowl, the grain going round it; inside it (its top: it opens back towards the handle) the
    wet wood, or stew ([full]) up to its rim."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            if full:
                a = noise_fill(w, h, STEW, rng, 0.08)
                bits = rng.random((h, w))
                a[..., :3] = np.where((bits < 0.12)[..., None], np.array(CARROT, float), a[..., :3])
                a[..., :3] = mix(a[..., :3], (204, 150, 100), (np.exp(-((U - 0.35) ** 2 + (V - 0.4) ** 2) * 18) * 0.4)[..., None])
            else:
                a = grain(w, h, 'u', OAK_D, (70, 46, 28), OAK, rng, streak=0.3)
                r = np.hypot(U - 0.5, V - 0.5) * 2
                a[..., :3] *= (0.7 + 0.25 * r)[..., None]
                a[..., :3] = mix(a[..., :3], STEW_D, (rng.random((h, w, 1)) < 0.15) * 0.6)
            rim = (U < 0.16) | (U > 0.84) | (V < 0.16) | (V > 0.84)
            wood = grain(w, h, 'u', PALE_L, PALE, (236, 216, 170), rng, streak=0.3)
            a[..., :3] = np.where(rim[..., None], wood[..., :3], a[..., :3])
            return a
        a = grain(w, h, 'u' if face != 'bottom' else None, PALE, PALE_D, PALE_L, rng, streak=0.3)
        if face == 'bottom':
            return shade_edges(a, 0.3)
        a[..., :3] = mix(a[..., :3], PALE_D, np.clip(V - 0.5, 0, 0.5)[..., None])
        return shade_edges(a, 0.22)
    return painter(key, fn)


def stew_painter(key):
    """A stream of stew poured from the ladle: brown broth with bits of carrot in it, streaked along its fall (+y)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, STEW, rng, 0.06)
        streak = np.sin(U * 9.0 + rng.uniform(0, 6)) > 0.5
        a[..., :3] = np.where(streak[..., None], mix(a[..., :3], (176, 112, 66), 0.4), a[..., :3])
        bits = rng.random((h, w)) < 0.05
        a[..., :3] = np.where(bits[..., None], np.array(CARROT, float), a[..., :3])
        return a
    return painter(key, fn)


def empty_bowl_painter(key):
    """A turned wooden bowl, empty: the grain running round it and a lathe ring under the rim as the stew bowl's; inside,
    the bare wood deepening to its middle, a lathe ring round its floor."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = grain(w, h, 'u', OAK, OAK_D, OAK_L, rng, streak=0.3)
            r = np.maximum(np.abs(U - 0.5), np.abs(V - 0.5)) * 2
            a[..., :3] *= (0.62 + 0.32 * np.clip(r, 0, 1))[..., None]
            ring = np.abs(r - 0.45) < max(0.05, 0.6 / w)
            a[..., :3] = np.where(ring[..., None], a[..., :3] * 0.8, a[..., :3])
            wood = grain(w, h, 'u', OAK_L, OAK, (200, 160, 110), rng, streak=0.3)
            a[..., :3] = np.where((r > 0.72)[..., None], wood[..., :3], a[..., :3])
            return a
        if face == 'bottom':
            return shade_edges(grain(w, h, None, OAK, OAK_D, OAK_L, rng), 0.3)
        a = grain(w, h, 'u', OAK, OAK_D, OAK_L, rng, streak=0.35)
        ring = np.abs(V - 0.3) < max(0.06, 0.5 / h)
        a[..., :3] = np.where(ring[..., None], mix(a[..., :3], OAK_D, 0.6), a[..., :3])
        a[..., :3] = mix(a[..., :3], OAK_D, np.clip(V - 0.6, 0, 0.4)[..., None])
        return shade_edges(a, 0.2)
    return painter(key, fn)


def torch_head_painter(key):
    """A torch's head: rag soaked in pitch and bound round the stave in turns, charred black, glowing at its burning top
    (-y: the cube's top, and the top of its sides) where the flame stands."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = noise_fill(w, h, (60, 26, 14), rng, 0.2)
            hot = rng.random((h, w)) < 0.55
            a[..., :3] = np.where(hot[..., None], mix(np.array(EMBER, float), np.array((255, 220, 120), float), rng.random((h, w, 1))), a[..., :3])
            return a
        if face == 'bottom':
            return noise_fill(w, h, PITCH, rng, 0.1)
        a = noise_fill(w, h, PITCH, rng, 0.12)
        turns = np.sin((V * 5.0 + U * 0.8) * math.pi * 2) > 0.3
        a[..., :3] = np.where(turns[..., None], mix(a[..., :3], PITCH_L, 0.45), a[..., :3])
        char = rng.random((h, w)) < 0.2
        a[..., :3] = np.where(char[..., None], a[..., :3] * 0.5, a[..., :3])
        glow = (V < 0.3) & (rng.random((h, w)) < 0.35 * (1 - V / 0.3))
        a[..., :3] = np.where(glow[..., None], np.array(EMBER, float), a[..., :3])
        return a
    return painter(key, fn)


def upright_flame_painter(key, core=False):
    """A flame standing up from its root (the cube's +y end) to its tip (-y), every pixel emissive (GLOW): on each side a
    teardrop of fire, white-hot low in the middle, orange to red at its edges and tip ([core]: the small white-hot heart
    inside it); seen from above or below, a round glow. Clear round it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = np.zeros((h, w, 4), float)
        if face in ('top', 'bottom'):
            r = np.hypot(U - 0.5, V - 0.5) * 2
            a[..., :3] = ramp(FLAME, np.clip(1.0 - r * 0.8 + (0.3 if core else 0.0), 0, 1))
            a[..., 3] = np.where(r <= (0.7 if face == 'top' else 1.0), 255, 0)
            return a
        x = 1.0 - V                          # along the flame: 0 at its root, 1 at its tip
        wob = 0.04 * np.sin(x * 9.0 + (0.0 if face in ('front', 'back') else 1.7))
        half = 0.5 * np.clip(1.0 - x, 0, 1) ** 0.65 * np.clip(x * 4.0 + 0.25, 0, 1) ** 0.5
        inside = np.abs(U - 0.5 - wob * x) <= half
        t = np.clip(1.05 - x * (0.55 if core else 0.85) - np.abs(U - 0.5) * (0.6 if core else 1.0), 0, 1)
        a[..., :3] = ramp(FLAME, t)
        a[..., 3] = np.where(inside, 255, 0)
        return a
    return painter(key, fn, light=False)


def horn_painter(key, tip=0.0):
    """A cow's horn: polished, pale and warm, the growth rings faint round it, darkening ([tip], 0 to 1) towards its
    narrow end."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        base = mix(np.array(IVORY, float), np.array(HORN_TIP, float), tip)
        a = noise_fill(w, h, tuple(base), rng, 0.04)
        rings = np.sin(U * max(w, h) * 1.3) > 0.6
        a[..., :3] = np.where(rings[..., None], a[..., :3] * 0.9, a[..., :3])
        streak = np.sin(V * 7.0 + U * 2.0 + rng.uniform(0, 6)) > 0.7
        a[..., :3] = np.where(streak[..., None], mix(a[..., :3], IVORY_D, 0.35), a[..., :3])
        glint = np.exp(-((V - 0.3) ** 2) * 40)
        a[..., :3] = mix(a[..., :3], (255, 250, 236), (glint * 0.35)[..., None])
        return shade_edges(a, 0.22)
    return painter(key, fn)


def bundle_painter(key, knot=False):
    """A household's bundle: their bedding and clothes tied up in a blue check cloth, creased where it is gathered;
    [knot]: the knotted corners on top, the cloth twisted tight."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, HOMESPUN, rng, 0.05)
        n = max(3, round(max(w, h) / 3))
        check = (np.floor(U * n) + np.floor(V * n)) % 2 == 0
        stripe = ((U * n) % 1.0 < 0.25) | ((V * n) % 1.0 < 0.25)
        a[..., :3] = np.where(check[..., None], mix(a[..., :3], HOMESPUN_L, 0.45), a[..., :3])
        a[..., :3] = np.where(stripe[..., None], mix(a[..., :3], (230, 222, 200), 0.55), a[..., :3])
        if knot:
            tw = np.sin((U + V) * max(w, h) * 1.2) > 0.2
            a[..., :3] = np.where(tw[..., None], a[..., :3] * 0.75, a[..., :3])
        else:
            fold = np.sin(U * 7.0 + V * 3.0 + rng.uniform(0, 6)) * np.sin(V * 5.0 + rng.uniform(0, 6))
            a[..., :3] *= (1.0 + 0.16 * fold)[..., None]
            if face not in ('top', 'bottom'):
                a[..., :3] = mix(a[..., :3], HOMESPUN_D, (np.clip(0.25 - V, 0, 0.25) * 2.0)[..., None])
        return shade_edges(a, 0.3, width=max(1, min(w, h) // 4))
    return painter(key, fn)


def veil_painter(key, piece):
    """The cloth over the patron's plaque: crimson velvet with a soft sheen across its folds and a gilt border along its
    sides; the last [piece] (VEIL_PIECES) has a gilt fringe along its lower edge (+y)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        sheen = np.sin(U * 5.0 + piece * 1.3) * 0.5 + 0.5
        a = solid(w, h, VELVET)
        a[..., :3] = mix(np.array(VELVET_D, float), np.array(VELVET_L, float), (0.25 + 0.5 * sheen)[..., None]) * (1 + rng.normal(0, 0.03, (h, w, 1)))
        if face in ('front', 'back'):
            border = (U < 0.07) | (U > 0.93)
            a[..., :3] = np.where(border[..., None], mix(np.array(GILT, float), np.array(GILT_D, float), rng.random((h, w, 1)) * 0.5), a[..., :3])
            if piece == VEIL_PIECES:
                fringe = V > 0.8
                tassel = (np.floor(U * w * 0.8) % 2 == 0) | (V < 0.9)
                a[..., :3] = np.where(fringe[..., None], mix(np.array(GILT, float), np.array(GILT_D, float), V[..., None] * 0.6), a[..., :3])
                a[fringe & ~tassel] = 0
        return a
    return painter(key, fn)


def empty_basket_painter(key):
    """The shopper's basket, empty: the carried basket's weave and plaited rim (townsfolk_tools' basket_painter), and
    inside it (its top) only the bare weave, dark, and a few crumbs."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = weave(w, h, WICKER_D, (78, 54, 26), WICKER, rng, cell=max(2, min(w, h) // 4))
            a[..., :3] *= 0.62
            crumbs = rng.random((h, w)) < 0.05
            a[..., :3] = np.where(crumbs[..., None], np.array(BREAD_L, float), a[..., :3])
            rim = (U < 0.12) | (U > 0.88) | (V < 0.12) | (V > 0.88)
            a[..., :3] = np.where(rim[..., None], np.array(WICKER_D, float), a[..., :3])
            return a
        a = weave(w, h, WICKER, WICKER_D, WICKER_L, rng, cell=max(2, min(w, h) // 4))
        if face != 'bottom' and h >= 4:
            r = max(1, h // 6)
            a[:r, :, :3] = mix(a[:r, :, :3], WICKER_D, 0.5)
        return shade_edges(a, 0.2)
    return painter(key, fn)


TROWEL_ROT = (-16.0, 0.0, 0.0)
TROWEL_TIP = (0.0, 7.8, 2.15)            # trowel space: its point; the middle of its face at (0, 4.0, 2.0)


def _trowel():
    # A brick trowel in the right fist: the handle through the fist, the blade under it (part +z) on a cranked shank,
    # flat, its point ahead (part +y). Cocked TROWEL_ROT forward of the arm's line, so with the arm reaching 16 degrees
    # down ahead (laying a course a little below the belly, from a step back) the blade lies level.
    return 'right_arm', Part('trowel', RIGHT_GRIP, TROWEL_ROT, cubes=[
        C((-0.6, -2.4, -0.6), (1.2, 3.8, 1.2), 't_trowel_handle', wood_painter('t_trowel_handle', OAK_L, OAK, (196, 158, 108), 'y')),
        C((-0.7, 1.4, -0.7), (1.4, 0.6, 1.4), 't_trowel_ferrule', brass_painter('t_trowel_ferrule')),
        C((-0.25, 1.75, -0.25), (0.5, 0.5, 2.3), 't_trowel_shank', iron_painter('t_trowel_shank')),
        C((-1.9, 1.4, 2.0), (3.8, 6.4, 0.3), 't_trowel_blade', trowel_blade_painter('t_trowel_blade')),
    ])


def mortar_heap_painter(key):
    """A heap of fresh mortar: soft grey, lumpy, wet and lighter where the trowel has cut it, gritty with sand."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, MORTAR, rng, 0.06)
        lumps = np.sin(U * 7.0 + rng.random() * 6) * np.sin(V * 5.0 + rng.random() * 6)
        a[..., :3] *= (1.0 + 0.07 * lumps)[..., None]
        grit = rng.random((h, w)) < 0.12
        a[..., :3] = np.where(grit[..., None], mix(a[..., :3], MORTAR_D, 0.6), a[..., :3])
        if face == 'top':
            cut = np.abs(V - 0.35 - 0.1 * U) < max(0.06, 0.7 / h)
            a[..., :3] = np.where(cut[..., None], mix(a[..., :3], MORTAR_L, 0.7), a[..., :3])
        else:
            a[..., :3] *= (0.86 + 0.14 * (1.0 - V))[..., None]
        return shade_edges(a, 0.2)
    return painter(key, fn)


HAWK_ROT = (45.0, 0.0, 0.0)
HAWK_TOP = (0.0, -3.8, 0.0)              # hawk space: the middle of its board's top
HAWK_HEAP = (0.6, -5.2, 0.0)             # the top of the heap of mortar on it


def _hawk():
    # A mortar hawk in the left fist: a square board on a short round handle, a heap of mortar on it. The handle stands
    # up out of the fist (part -y), tipped HAWK_ROT back off the arm's line, so with the arm reaching 45 degrees down
    # ahead the board is level; TROWEL scoops off it.
    return 'left_arm', Part('hawk', LEFT_GRIP, HAWK_ROT, cubes=[
        C((-0.55, -3.2, -0.55), (1.1, 4.4, 1.1), 't_hawk_handle', wood_painter('t_hawk_handle', OAK, OAK_D, OAK_L, 'y')),
        C((-4.0, -3.8, -4.0), (8.0, 0.6, 8.0), 't_hawk_board', wood_painter('t_hawk_board', BEECH, BEECH_D, BEECH_L, 'x')),
        C((-1.6, -5.2, -2.2), (4.4, 1.4, 4.4), 't_hawk_mortar', mortar_heap_painter('t_hawk_mortar')),
    ])


MORTAR_HOE_ROT = (-60.0, 0.0, 0.0)
MORTAR_HOE_BLADE = (0.0, 17.5, 2.2)      # mortar hoe space: the middle of its blade
MORTAR_HOE_LEFT = 8.0                    # how far down the shaft from the right fist the left hand holds it


def _mortar_hoe():
    # A mortar hoe (a larry): a long ash shaft, the right fist at its end and the left MORTAR_HOE_LEFT px down it, and a
    # broad blade with two holes turned back towards the mixer (+z of the shaft) off its far end, as the draw hoe's. The
    # shaft leaves the fist MORTAR_HOE_ROT forward of the arm's line, pointing ahead and down into the tub.
    return 'right_arm', Part('mortar_hoe', RIGHT_GRIP, MORTAR_HOE_ROT, cubes=[
        C((-0.5, -2.0, -0.5), (1.0, 19.2, 1.0), 't_mortar_hoe_shaft', wood_painter('t_mortar_hoe_shaft', WOOD, WOOD_D, WOOD_L, 'y', knots=1)),
        C((-0.75, 15.8, -0.75), (1.5, 1.6, 1.5), 't_mortar_hoe_socket', iron_painter('t_mortar_hoe_socket', (80, 78, 82), (44, 42, 46), (150, 150, 156))),
        C((-2.75, 17.2, 0.3), (5.5, 0.6, 3.8), 't_mortar_hoe_blade', larry_blade_painter('t_mortar_hoe_blade')),
    ])


MALLET_ROT = (-20.0, 0.0, 0.0)
MALLET_HEAD = (0.0, 3.6, 0.0)            # mallet space: the middle of its head (1.8 px round)


def _mallet():
    # A mason's mallet: a short handle continuing the arm's line from the fist, cocked MALLET_ROT forward, and a round
    # beech head on its end, coaxial with it; it strikes with the head's side.
    return 'right_arm', Part('mallet', RIGHT_GRIP, MALLET_ROT, cubes=[
        C((-0.55, -1.6, -0.55), (1.1, 3.4, 1.1), 't_mallet_handle', wood_painter('t_mallet_handle', OAK, OAK_D, OAK_L, 'y')),
        C((-1.8, 1.8, -1.8), (3.6, 3.6, 3.6), 't_mallet_head', mallet_painter('t_mallet_head')),
        C((-1.35, 5.4, -1.35), (2.7, 0.4, 2.7), 't_mallet_cap', mallet_painter('t_mallet_cap')),
    ])


CHISEL_PIVOT = (1.0, 9.6, -2.4)          # left arm space: before the fist, where its fingers close round the chisel
CHISEL_ROT = (40.0, 0.0, 0.0)
CHISEL_HEAD = (0.0, -2.8, 0.0)           # chisel space: the struck end
CHISEL_EDGE = (0.0, 3.2, 0.0)            # its cutting edge


def _chisel():
    # A mason's chisel in the left fist, held about its middle before the fingers: its edge down (part +y), its burred
    # head up out of the top of the fist (part -y), tipped CHISEL_ROT back from the arm's line, so with the arm reaching
    # down and in to the stone it stands on its edge, leaning back to the mallet.
    return 'left_arm', Part('chisel', CHISEL_PIVOT, CHISEL_ROT, cubes=[
        C((-0.45, -2.8, -0.45), (0.9, 0.9, 0.9), 't_chisel_head', chisel_painter('t_chisel_head', 'head')),
        C((-0.35, -2.0, -0.35), (0.7, 4.4, 0.7), 't_chisel_shank', chisel_painter('t_chisel_shank', 'shank')),
        C((-0.6, 2.2, -0.22), (1.2, 1.0, 0.44), 't_chisel_edge', chisel_painter('t_chisel_edge', 'edge')),
    ])


PLUMB_PIVOT = (-1.0, 10.6, -2.6)         # right arm space: pinched in the fingertips before the fist
PLUMB_LEN = 13.9                         # the cord's top to the bob's point


def _plumb_line():
    # A plumb line pinched in the right fingertips before the fist: a toggle to hold it by, a cord and a brass bob on its
    # end, its point PLUMB_LEN px under the fingers. PLUMB's track hangs it and swings it as a pendulum as the hand moves;
    # where nothing runs it, it hangs (HANG).
    return 'right_arm', Part('plumb_line', PLUMB_PIVOT, (0, 0, 0), cubes=[
        C((-0.8, -0.5, -0.3), (1.6, 0.6, 0.6), 't_plumb_toggle', wood_painter('t_plumb_toggle', OAK, OAK_D, OAK_L, 'x')),
        C((-0.15, 0.0, -0.15), (0.3, 11.0, 0.3), 't_plumb_cord', cord_painter('t_plumb_cord')),
        C((-0.8, 11.0, -0.8), (1.6, 2.2, 1.6), 't_plumb_bob', brass_painter('t_plumb_bob')),
        C((-0.35, 13.2, -0.35), (0.7, 0.7, 0.7), 't_plumb_tip', brass_painter('t_plumb_tip')),
    ])


STAFF_UP_ROT = euler_from_axes((0.0, -1.0, 0.0), (1.0, 0.0, 0.0))     # staff x up the arm, staff y to the arm's +x
STAFF_FOOT = (-12.4, 0.0, 0.0)           # sighting staff space: its foot (on the ground with the arm hanging)
STAFF_TOP = (19.6, 0.0, 0.0)


def _sighting_staff():
    # A surveyor's ranging staff, two blocks of ash banded red and white every 4 px, an iron shoe at its foot, a brass cap
    # and a little red and white pennant at its head. Held upright in the left hand before the fist as the watch holds its
    # spear (staff x up the arm), its foot 12 px under the fist: on the ground with the arm hanging. Modelled along x (one
    # short strip of texture). SURVEY's track stands it upright on its foot as the hand works round it.
    return 'left_arm', Part('sighting_staff', (1.0, 10.0, -2.6), STAFF_UP_ROT, cubes=[
        C((-11.4, -0.6, -0.6), (30.0, 1.2, 1.2), 't_sighting_staff', staff_painter('t_sighting_staff')),
        C((-12.4, -0.75, -0.75), (1.0, 1.5, 1.5), 't_sighting_staff_shoe', iron_painter('t_sighting_staff_shoe')),
        C((18.6, -0.8, -0.8), (1.0, 1.6, 1.6), 't_sighting_staff_cap', brass_painter('t_sighting_staff_cap')),
        C((15.4, -0.15, 0.6), (3.0, 0.3, 3.0), 't_sighting_staff_pennant', pennant_painter('t_sighting_staff_pennant')),
    ])


BEAM_ROT = euler_from_axes((0.0, 0.0, 1.0), (0.0, 1.0, 0.0))         # beam x back along the body (model +z), y down
BEAM_SHOULDER = (-6.0, -0.2, 0.0)        # body space: the beam's pivot, the middle of its underside on the right shoulder
BEAM_FOLLOW = 32.0                       # the carrier behind, in step, this far back: the beam on their right shoulder
BEAM_AHEAD, BEAM_BEHIND = 16.0, BEAM_FOLLOW / 2   # each carrier's half: this far ahead of the shoulder, and back behind it


def _beam():
    # The beam two carry in step (carry_beam): a squared oak timber 3 px each way on the right shoulder. Each carrier shows
    # their half of it, BEAM_AHEAD px ahead of the shoulder to BEAM_BEHIND behind, so with the second BEAM_FOLLOW px behind
    # the first the halves meet between them as one beam (and one carrier alone carries a short one).
    return 'body', Part('beam', BEAM_SHOULDER, BEAM_ROT, cubes=[
        C((-BEAM_AHEAD, -3.0, -1.5), (BEAM_AHEAD + BEAM_BEHIND, 3.0, 3.0), 't_beam', beam_painter('t_beam')),
    ])


CUP_RIM = (0.0, -2.9, -3.0)              # cup space: the middle of its rim


def _cup():
    # A pewter goblet of wine for the toast, held by its stem in the fingers before the fist, tilted 45 degrees on the arm
    # as the mug is (upright with the arm reaching 45 degrees forward). TOAST's track keeps it upright as it is raised
    # high, and tips it to the lips.
    return 'right_arm', Part('cup', RIGHT_GRIP, (45, 0, 0), cubes=[
        C((-1.4, -2.9, -4.4), (2.8, 2.6, 2.8), 't_cup_bowl', pewter_painter('t_cup_bowl', wine=True)),
        C((-0.45, -0.3, -3.45), (0.9, 1.6, 0.9), 't_cup_stem', pewter_painter('t_cup_stem')),
        C((-1.1, 1.3, -4.1), (2.2, 0.5, 2.2), 't_cup_foot', pewter_painter('t_cup_foot')),
    ])


LADLE_BOWL = (0.0, 6.4, 0.9)             # ladle space: the middle of its bowl
LADLE_LIP = (0.0, 5.4, 2.2)              # the far edge of its mouth, where it pours from


def _ladle_part(name, full):
    # A turned wooden ladle in the right fist along the arm's line (ladle +y from the fist), its deep bowl on the end
    # opening back towards the hand (ladle -y), so it scoops held upright. LADLE's track turns it in the fist: dipped
    # upright into the pot, tipped over the bowl to pour.
    return 'right_arm', Part(name, RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-0.35, -1.2, -0.35), (0.7, 6.8, 0.7), f't_{name}_handle', wood_painter(f't_{name}_handle', PALE, PALE_D, PALE_L, 'y')),
        C((-1.3, 5.4, -0.4), (2.6, 2.0, 2.6), f't_{name}_bowl', ladle_bowl_painter(f't_{name}_bowl', full)),
    ])


def _ladle():
    return _ladle_part('ladle', False)


def _ladle_full():
    # the same ladle full of stew, in its place from the dip to the pour
    return _ladle_part('ladle_full', True)


def _soup_pour():
    # The stew pouring from the ladle into the bowl: a piece ROPE_LEN px along +y that LADLE's track aims from the ladle's
    # lip to the stew in the bowl and stretches to reach, for the few ticks it pours.
    return 'right_arm', Part('soup_pour', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-0.35, 0.0, -0.35), (0.7, ROPE_LEN, 0.7), 't_soup_pour', stew_painter('t_soup_pour')),
    ])


def _bowl_empty():
    # An empty wooden bowl from the alms table's stack: the stew bowl (bowl) before it is filled, the same size and in the
    # same place; LADLE's track holds it level on the left hand until the pour.
    return 'left_arm', Part('bowl_empty', LEFT_GRIP, BOWL_ROT, cubes=[
        C((-2.25, -3.9, -1.25), (4.5, 1.9, 4.5), 't_bowl_empty', empty_bowl_painter('t_bowl_empty')),
    ])


TORCH_PIVOT = (-1.0, 10.0, -2.6)         # right arm space: before the fist, as the spear is held
TORCH_TOP = (0.0, -9.0, 0.0)             # torch space: the top of its head, where the flame stands


def _torch():
    # A pitch torch, upright in the right hand before the fist as the watch holds its spear: an ash stave, an iron band and
    # a head of rag soaked in pitch, charred and glowing at its top. The flame is a part of its own (torch_flame), so it
    # can stand up whichever way the torch leans; LIGHT_BEACON's track tips the torch down into the brazier.
    return 'right_arm', Part('torch', TORCH_PIVOT, (0, 0, 0), cubes=[
        C((-0.55, -6.6, -0.55), (1.1, 9.0, 1.1), 't_torch_stave', wood_painter('t_torch_stave', WOOD, WOOD_D, WOOD_L, 'y')),
        C((-0.75, -6.5, -0.75), (1.5, 0.7, 1.5), 't_torch_band', iron_painter('t_torch_band', soot=0.3)),
        C((-1.05, -9.0, -1.05), (2.1, 2.8, 2.1), 't_torch_head', torch_head_painter('t_torch_head')),
    ])


def _torch_flame():
    # The torch's flame (it glows: GLOW_CUBES), standing on the top of its head: a teardrop of fire 5.2 px tall round a
    # white-hot core. LIGHT_BEACON's track keeps it standing up, leaning back from the way the torch moves, flickering.
    return 'right_arm', Part('torch_flame', tuple(float(v) for v in np.add(TORCH_PIVOT, TORCH_TOP)), (0, 0, 0), cubes=[
        C((-1.3, -5.2, -1.3), (2.6, 5.2, 2.6), 't_torch_flame', upright_flame_painter('t_torch_flame')),
        C((-0.65, -2.8, -0.65), (1.3, 2.8, 1.3), 't_torch_flame_core', upright_flame_painter('t_torch_flame_core', core=True)),
    ])


HORN_AT = (2.6, 10.6, -3.4)              # body space: the horn's pivot, slung at the front of the left hip
HORN_MOUTH = (5.2, 0.0, 0.0)             # horn space: the end of its mouthpiece


def _horn():
    # The watch's horn: a cow's horn, dark at its narrow end, with brass at the mouthpiece and round the bell, slung on a
    # baldric (horn_baldric) at the front of the left hip and lying across it, the mouthpiece out to the left (horn +x),
    # curving up to its bell towards the middle (the bell's mouth faces -x of horn_bell). sound_horn's track takes it up
    # to the lips.
    bell = Part('horn_bell', (-2.7, 0.0, 0.0), (0, 0, 20), cubes=[
        C((-2.5, -1.2, -1.2), (2.6, 2.4, 2.4), 't_horn_bell', horn_painter('t_horn_bell', tip=0.0)),
        C((-2.95, -1.5, -1.5), (0.5, 3.0, 3.0), 't_horn_rim', brass_painter('t_horn_rim')),
    ])
    bend = Part('horn_bend', (1.5, 0.0, 0.0), (0, 0, 20), cubes=[
        C((-2.8, -0.9, -0.9), (2.9, 1.8, 1.8), 't_horn_bend', horn_painter('t_horn_bend', tip=0.3)),
    ], children=[bell])
    return 'body', Part('horn', HORN_AT, (0, 0, 0), cubes=[
        C((4.1, -0.4, -0.4), (1.1, 0.8, 0.8), 't_horn_mouth', brass_painter('t_horn_mouth')),
        C((1.4, -0.65, -0.65), (2.8, 1.3, 1.3), 't_horn_tip', horn_painter('t_horn_tip', tip=0.75)),
    ], children=[bend])


def _horn_baldric():
    # The horn's baldric, a leather strap from the left hip across the chest to the right shoulder (shown with the horn,
    # and staying on the body when the horn is taken up).
    return 'body', Part('horn_baldric', (3.0, 9.8, -2.55), (0, 0, -34.5), cubes=[
        C((-0.5, -11.65, -0.2), (1.0, 11.65, 0.4), 't_horn_baldric', strap_painter('t_horn_baldric')),
    ])


def _bundle():
    # A household's bundle: their bedding and clothes tied up in a blue check cloth, its corners knotted on top. PACK's
    # track carries it between the hands and sets it on the cart; the middle of its underside is the part's origin (on the
    # body, where nothing shows it untracked).
    return 'body', Part('bundle', (0.0, 0.0, 0.0), (0, 0, 0), cubes=[
        C((-3.6, -5.6, -3.0), (7.2, 5.6, 6.0), 't_bundle', bundle_painter('t_bundle')),
        C((-2.5, -6.4, -2.1), (5.0, 0.8, 4.2), 't_bundle_top', bundle_painter('t_bundle_top')),
        C((-1.0, -7.6, -1.0), (2.0, 1.4, 2.0), 't_bundle_knot', bundle_painter('t_bundle_knot', knot=True)),
        C((-2.0, -7.3, -0.3), (4.0, 0.8, 0.6), 't_bundle_ears', bundle_painter('t_bundle_ears', knot=True)),
    ])


# The cloth over the patron's plaque: three panels VEIL_W across and VEIL_LEN long, each hanging from its top edge (its
# pivot), which UNVEIL's tracks lay one after another from the master's fist as the cloth is whipped off and flies.
VEIL_W, VEIL_LEN, VEIL_PIECES = 14.0, 4.2, 3
VEILS = [f'veil_{i}' for i in range(1, VEIL_PIECES + 1)]


def _veil(name):
    i = int(name[-1])
    return 'body', Part(name, (0.0, 0.0, 0.0), (0, 0, 0), cubes=[
        C((-VEIL_W / 2, 0.0, -0.15), (VEIL_W, VEIL_LEN, 0.3), f't_{name}', veil_painter(f't_{name}', i)),
    ])


_VEIL_BUILDERS = [lambda name=name: _veil(name) for name in VEILS]


def _basket_turned():
    # The shopper's basket (townsfolk_tools' basket, which the left hand carries about) as a prop of its own, empty, so
    # EMPTY_BASKET's track can turn it over and shake it: the same size and place, starting and ending where the carried
    # one hangs.
    return 'left_arm', Part('basket_turned', LEFT_GRIP, (0, 0, 0), cubes=[
        C((-0.25, -1.0, -2.5), (0.5, 3.5, 5), 't_basket_turned_handle', tt.handle_arch_painter('t_basket_turned_handle')),
        C((-2.0, 2.5, -3.0), (4, 3.5, 6), 't_basket_turned_body', empty_basket_painter('t_basket_turned_body')),
    ])


def flap_painter(key, rolled=False):
    """The stall's front flap: undyed canvas, seamed every so often down its length and hemmed dark along its lower edge
    (+y); [rolled]: the canvas rolled round its batten, the turns showing on its ends."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, CANVAS, rng, 0.04)
        if rolled:
            if face in ('left', 'right'):
                r = np.hypot(U - 0.5, V - 0.5)
                turns = np.sin(r * 26.0) > 0.0
                a[..., :3] = np.where(turns[..., None], mix(a[..., :3], CANVAS_D, 0.5), a[..., :3])
                a[..., :3] = np.where((r < 0.16)[..., None], np.array(WOOD_D, float), a[..., :3])
            else:
                a[..., :3] *= (1.0 - 0.18 * np.abs(np.sin(V * 9.0)))[..., None]
            return shade_edges(a, 0.3, width=max(1, min(w, h) // 3))
        seam = (np.floor(U * 4.0) != np.floor((U + 1.0 / max(w, 1)) * 4.0))
        a[..., :3] = np.where(seam[..., None], mix(a[..., :3], CANVAS_D, 0.55), a[..., :3])
        fold = np.sin(U * 21.0 + rng.uniform(0, 6))
        a[..., :3] *= (1.0 + 0.07 * fold)[..., None]
        if face in ('front', 'back'):
            hem = V > 0.82
            a[..., :3] = np.where(hem[..., None], mix(a[..., :3], CANVAS_D, 0.45), a[..., :3])
        return a
    return painter(key, fn)


# The stall's front flap (SHUT_STALL): a canvas blind on a rail under the awning, over the back edge of the counter,
# kept rolled up round its batten until the keeper pulls it down by its cord and hooks the batten on the counter. All
# three on the body, where nothing shows them untracked: SHUT_STALL's tracks place them in the clip's own space. The
# cloth is modelled FLAP_W across and FLAP_LEN long from its top edge (its pivot), and its track stretches it down to
# the batten, whose roll of canvas its track thins as the cloth comes off it.
FLAP_W, FLAP_LEN = 16.0, 4.0
FLAP_ROLL = 1.2                          # the rolled-up canvas round the batten: half its thickness


def _flap_rail():
    return 'body', Part('flap_rail', (0.0, 0.0, 0.0), (0, 0, 0), cubes=[
        C((-FLAP_W / 2 - 0.6, -0.6, -0.6), (FLAP_W + 1.2, 1.2, 1.2), 't_flap_rail',
          wood_painter('t_flap_rail', WOOD, WOOD_D, WOOD_L, 'x')),
    ])


def _flap_cloth():
    return 'body', Part('flap_cloth', (0.0, 0.0, 0.0), (0, 0, 0), cubes=[
        C((-FLAP_W / 2, 0.0, -0.15), (FLAP_W, FLAP_LEN, 0.3), 't_flap_cloth', flap_painter('t_flap_cloth')),
    ])


def _flap_batten():
    return 'body', Part('flap_batten', (0.0, 0.0, 0.0), (0, 0, 0), cubes=[
        C((-FLAP_W / 2, -FLAP_ROLL, -FLAP_ROLL), (FLAP_W, 2 * FLAP_ROLL, 2 * FLAP_ROLL), 't_flap_batten',
          flap_painter('t_flap_batten', rolled=True)),
    ])


FLAPS = ['flap_rail', 'flap_cloth', 'flap_batten']

# The works' and the streets' (M6): townsfolk.py packs their cubes after all the others here, so adding them moved no
# cube already on the texture.
WORKS_BUILDERS = [_trowel, _hawk, _mortar_hoe, _mallet, _chisel, _plumb_line, _sighting_staff, _beam, _cup, _ladle, _ladle_full,
                  _soup_pour, _bowl_empty, _torch, _torch_flame, _horn, _horn_baldric, _bundle, *_VEIL_BUILDERS,
                  _basket_turned, _flap_rail, _flap_cloth, _flap_batten]
BUILDERS.extend(WORKS_BUILDERS)
GLOW_CUBES.update({'t_torch_flame', 't_torch_flame_core'})
HANG = HANG + ('plumb_line',)
TOOL_NOTES.update({
    'trowel': 'right hand, the blade under the handle on its shank, cocked 16 forward of the arm (level with the arm 16 '
              'down ahead); its point at part (0, 7.8, 2.15)',
    'hawk': 'left hand, the mortar hawk: its board level with the arm 45 down ahead (the handle tipped 45 back off the arm); '
            'its board\'s top at part (0, -3.8, 0)',
    'mortar_hoe': f'right hand at the shaft\'s end, the left {MORTAR_HOE_LEFT:g} px down it; the shaft 60 forward of the arm; '
                  'the blade\'s middle at part (0, 17.5, 2.2)',
    'mallet': 'right hand, along the arm cocked 20 forward; its round head\'s middle at part (0, 3.6, 0), 1.8 px round',
    'chisel': 'left hand before the fist, its edge down at part (0, 3.2, 0), its struck head at (0, -2.8, 0), tipped 40 back '
              'off the arm',
    'plumb_line': f'right fingertips; hangs (HANG) or swings by its track; the bob\'s point {PLUMB_LEN:g} px under the fingers',
    'sighting_staff': 'left hand, upright before the fist like the spear (staff x up the arm); its foot at part (-12.4, 0, 0), '
                      'its head at (19.6, 0, 0); SURVEY\'s track stands it on its foot',
    'beam': f'body, on the right shoulder: each of the two carriers ({BEAM_FOLLOW:g} px apart, in step) shows their half, '
            f'{BEAM_AHEAD:g} px ahead of the shoulder to {BEAM_BEHIND:g} behind, the halves meeting between them',
    'cup': 'right hand by the stem, tilted 45 like the mug; its rim at part (0, -2.9, -3.0); TOAST\'s track keeps it upright',
    'ladle': 'right hand along the arm, its bowl opening back to the hand at part (0, 6.4, 0.9), its lip at (0, 5.4, 2.2); '
             'LADLE\'s track turns it',
    'ladle_full': 'the ladle full of stew, in its place from the dip to the pour',
    'soup_pour': 'right hand; the stew poured, aimed and stretched by LADLE\'s track from the ladle\'s lip to the bowl',
    'bowl_empty': 'left hand, as the stew bowl (bowl) before it is filled; LADLE\'s track holds it level',
    'torch': 'right hand, upright before the fist like the spear; the top of its head at part (0, -9, 0), where torch_flame '
             'stands; LIGHT_BEACON\'s track tips it into the brazier',
    'torch_flame': 'right hand, on the torch\'s head; glows; its track stands it up and flickers it',
    'horn': 'body, slung across the front of the left hip on horn_baldric, the mouthpiece out to the left at part (5.2, 0, 0); '
            'sound_horn\'s track takes it up to the lips',
    'horn_baldric': 'body, the horn\'s strap from the left hip to the right shoulder; shown with the horn',
    'bundle': 'body; PACK\'s track carries it in both hands and sets it on the cart (the middle of its underside its origin)',
    **{name: f'body; panel {name[-1]} of {VEIL_PIECES} of the cloth over the plaque (UNVEIL\'s tracks lay them from the fist '
             'as it flies off)' for name in VEILS},
    'basket_turned': 'left hand, the carried basket\'s size and place, empty; EMPTY_BASKET\'s track turns it over',
    'flap_rail': 'body; the rail under the awning the stall\'s canvas flap hangs from (SHUT_STALL\'s track places it)',
    'flap_cloth': f'body; the flap\'s canvas, {FLAP_W:g} across, hung from its top edge; SHUT_STALL\'s track stretches it '
                  'down to the batten',
    'flap_batten': 'body; the flap\'s batten in its roll of canvas, the roll thinning as the cloth comes off it; '
                   'SHUT_STALL\'s track drops it and hooks it on the counter',
})

# ================================================================== the cook's props (Hearthcraft M3)
# art/scripts/cook_clips.py (the seven kitchen clips) holds these. Both are held in the right hand the way the spoon is:
# at right angles to the arm, so with the arm hanging they point ahead, the part's +z down. townsfolk.py packs their
# cubes after every other group's (cook_cube_names()), so adding them moved no cube already on the texture. The
# ladle, spoon, bowl and peel the kitchen clips also use are the ones above (and the older tools') as they were.

TURNER_BLADE = (0.0, 7.2, 1.5)           # turner space: the middle of its blade's face
TURNER_EDGE = (0.0, 9.3, 1.5)            # the middle of its leading edge, which goes under the food
KNIFE_BLADE = (0.0, 6.05, 0.2)           # knife space: the middle of its blade
KNIFE_EDGE = (0.0, 6.05, 0.9)            # the middle of its cutting edge, which goes through the food
KNIFE_TIP = (0.0, 9.4, 0.9)              # its point


def turner_blade_painter(key):
    """A turner's blade: bright worn iron, its leading edge (the end that goes under the food) honed brighter still, three
    slots cut through it (clear, so they are holes, once the texture is fine enough to show them), its corners rounded."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = metal(w, h, (150, 154, 166), (88, 92, 104), (214, 220, 232), rng, bevel=False, wear=0.07)
        if face in ('front', 'back'):
            edge = V > 1.0 - max(0.12, 1.2 / h)
            a[..., :3] = np.where(edge[..., None], mix(a[..., :3], STEEL_L, 0.7), a[..., :3])
            a[..., :3] *= (1.0 + 0.10 * (0.5 - V))[..., None]
            if w >= 6 and h >= 6:
                for cx in (0.2, 0.5, 0.8):
                    slot = (np.abs(U - cx) < max(0.06, 0.45 / w)) & (V > 0.3) & (V < 0.76)
                    a[slot] = 0
            if w >= 6:
                corner = (V > 1.0 - 1.4 / h) & ((U < 1.2 / w) | (U > 1.0 - 1.2 / w))
                a[corner] = 0
        return a
    return painter(key, fn)


def cook_blade_painter(key):
    """A cook's knife blade: bright steel, honed along its edge (the cube's +z face, and a bevel on each flat), a dark spine
    along the other, the knife's own, slimmer than the driver's."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = metal(w, h, (166, 172, 184), (104, 108, 120), (226, 232, 244), rng, bevel=False, wear=0.05)
        if face == 'back':
            a[..., :3] = mix(a[..., :3], (246, 248, 252), 0.8)
        elif face == 'front':
            a[..., :3] = mix(a[..., :3], (56, 58, 66), 0.55)
        elif face in ('right', 'left') and w >= 2:
            # u runs from +z to -z across the right flat and from -z to +z across the left, so [across] is 0 at the edge
            across = U if face == 'right' else 1.0 - U
            k = max(1, int(round(w * 0.34)))
            texel = np.floor(across * w)
            a[..., :3] = np.where((texel < k)[..., None], mix(a[..., :3], (240, 244, 250), 0.65), a[..., :3])
            a[..., :3] = np.where((texel >= w - k)[..., None], mix(a[..., :3], (76, 78, 88), 0.5), a[..., :3])
        return a
    return painter(key, fn)


def _turner():
    # A cook's turner held like the spoon: the handle through the fist, the flat blade on a cranked shank under it
    # (part +z), slotted, its leading edge ahead. Fist to the leading edge: 9.3 px.
    return 'right_arm', Part('turner', RIGHT_GRIP, (-90, 0, 0), cubes=[
        C((-0.5, -1.8, -0.5), (1.0, 5.0, 1.0), 't_turner_handle', wood_painter('t_turner_handle', BEECH, BEECH_D, BEECH_L, 'y')),
        C((-0.6, 3.2, -0.6), (1.2, 0.5, 1.2), 't_turner_ferrule', iron_painter('t_turner_ferrule')),
        C((-0.25, 3.7, -0.25), (0.5, 1.6, 0.5), 't_turner_shank', iron_painter('t_turner_shank')),
        C((-0.25, 5.1, -0.2), (0.5, 0.5, 1.7), 't_turner_crank', iron_painter('t_turner_crank')),
        C((-1.8, 5.1, 1.3), (3.6, 4.2, 0.4), 't_turner_blade', turner_blade_painter('t_turner_blade')),
    ])


def _knife():
    # (Named cooks_knife, like its item: Kestrel's belt knife, a look piece, is already the part called knife.)
    # A cook's knife held like the spoon, its edge (part +z) down: with the arm hanging it points ahead. A slim blade
    # 6.7 px long that narrows to a point (the spine steps down toward it; the edge is one line), on a walnut handle with
    # a brass bolster: not the stout driver's knife.
    return 'right_arm', Part('cooks_knife', RIGHT_GRIP, (-90, 0, 0), cubes=[
        C((-0.55, -1.8, -0.7), (1.1, 4.0, 1.4), 't_knife_handle', wood_painter('t_knife_handle', (112, 72, 44), (72, 44, 26), (150, 104, 66), 'y')),
        C((-0.6, 2.2, -0.75), (1.2, 0.5, 1.5), 't_knife_bolster', brass_painter('t_knife_bolster')),
        C((-0.15, 2.7, -0.5), (0.3, 3.8, 1.4), 't_knife_blade', cook_blade_painter('t_knife_blade')),
        C((-0.15, 6.5, -0.2), (0.3, 1.9, 1.1), 't_knife_belly', cook_blade_painter('t_knife_belly')),
        C((-0.15, 8.4, 0.3), (0.3, 1.0, 0.6), 't_knife_point', cook_blade_painter('t_knife_point')),
    ])


COOK_BUILDERS = [_turner, _knife]
BUILDERS.extend(COOK_BUILDERS)
TOOL_NOTES.update({
    'turner': 'right hand, at right angles to the arm like the spoon, the flat of its blade (part +z, under the handle) '
              f'level with the arm hanging; the blade\'s middle at part {TURNER_BLADE}, its leading edge at {TURNER_EDGE}',
    'cooks_knife': 'right hand, at right angles to the arm like the spoon, its edge (part +z) down; the blade\'s middle at part '
             f'{KNIFE_BLADE}, its cutting edge at {KNIFE_EDGE}, its point at {KNIFE_TIP}',
})


def works_cube_names():
    """The works' and the streets' cubes among them (M6: townsfolk.py packs them last of all)."""
    return {c.name for b in WORKS_BUILDERS for p in b()[1].walk() for c in p.cubes}


def cook_cube_names():
    """The cook's cubes among them (Hearthcraft M3: townsfolk.py packs them last of all)."""
    return {c.name for b in COOK_BUILDERS for p in b()[1].walk() for c in p.cubes}


def cube_names():
    """Every cube these props add (townsfolk.py packs them last)."""
    return {c.name for b in BUILDERS for p in b()[1].walk() for c in p.cubes}


def road_cube_names():
    """The road's cubes among them (townsfolk.py packs them last of all)."""
    return {c.name for b in ROAD_BUILDERS for p in b()[1].walk() for c in p.cubes}


def part_names():
    """Every part these props add, children included."""
    return [p.name for b in BUILDERS for p in b()[1].walk()]


# ================================================================== the trade's props (art/scripts/trade_clips.py)

# The clerks', carters' and wardens' (trade X4): a seal stamp, the corner of a cart's canvas, a laden basket, a trace, a sack
# for the scale and a pack to rummage in. townsfolk.py packs their cubes after everyone else's (group 6), so adding them
# moved no cube already on the texture. Where a clip's track runs a prop (the basket, the canvas, the trace, the sack,
# the pack), the track places it in the clip's own space and its rest placement here only shows it untracked.

STAMP_FACE = (0.0, 4.6, 0.0)             # stamp space: the middle of the seal's working face


def seal_face_painter(key):
    """The seal's block: bevelled brass; its working face (the bottom) engraved, still red in a ring of wax."""
    def fn(face, w, h, rng):
        a = metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=0.1)
        if face == 'bottom' and min(w, h) >= 3:
            U, V = uv(w, h)
            r = np.maximum(np.abs(U - 0.5), np.abs(V - 0.5)) * 2
            ring = r > 0.62
            a[..., :3] = np.where(ring[..., None], mix(a[..., :3], WAX, 0.88), a[..., :3])
        return a
    return painter(key, fn)


def _stamp():
    # A seal stamp in the right fist: a turned beech knob over the fist, the shaft through it, a brass collar and neck
    # under it and the seal's block (4 px square) on the end, hanging along the arm. STAMP's track holds it pointing down
    # whichever way the arm reaches, and twists it on the wax.
    return 'right_arm', Part('stamp', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-1.3, -4.8, -1.3), (2.6, 1.2, 2.6), 't_stamp_knob', wood_painter('t_stamp_knob', OAK_L, OAK, (196, 158, 108), 'y')),
        C((-0.8, -3.6, -0.8), (1.6, 5.2, 1.6), 't_stamp_grip', wood_painter('t_stamp_grip', OAK, OAK_D, OAK_L, 'y')),
        C((-1.2, 1.6, -1.2), (2.4, 0.8, 2.4), 't_stamp_collar', brass_painter('t_stamp_collar')),
        C((-0.7, 2.4, -0.7), (1.4, 1.0, 1.4), 't_stamp_neck', brass_painter('t_stamp_neck')),
        C((-2.0, 3.4, -2.0), (4.0, 1.2, 4.0), 't_stamp_face', seal_face_painter('t_stamp_face')),
    ])


CANVAS_W, CANVAS_H = 11.0, 9.0


def _canvas_flap():
    # The corner of the cart's canvas that CHECK_LOAD lifts: a patch CANVAS_W across and CANVAS_H down, pinned at its top
    # corner (the part's pivot: the track swings the patch about that point), a ring in the opposite corner (where the
    # hand pinches it) and the cord tail of its tie.
    return 'body', Part('canvas_flap', (0.0, 0.0, 0.0), (0, 0, 0), cubes=[
        C((0.0, 0.0, -0.15), (CANVAS_W, CANVAS_H, 0.3), 't_canvas_flap', flap_painter('t_canvas_flap')),
        C((CANVAS_W - 1.9, CANVAS_H - 1.7, -0.6), (1.4, 1.4, 1.2), 't_canvas_ring', iron_painter('t_canvas_ring')),
        C((CANVAS_W - 1.4, CANVAS_H - 0.3, -0.4), (0.6, 2.2, 0.8), 't_canvas_tie', cord_painter('t_canvas_tie')),
    ])


PACK_BASKET_PIVOT, PACK_BASKET_ROT = (5.6, 9.5, -4.0), (0, -18, -6)       # basket_hip's place on the left hip
PACK_BASKET_BOX = ((-3.0, -2.1, -2.6), (3.0, 2.5, 2.6))                    # its basket: the mouth at y -2.1, the bottom at 2.5


def _pack_basket():
    # The laden basket of LOAD_PACK: basket_hip's own (the same size, place and weave: painted from basket_hip's key), so
    # nothing is seen to change as the clip takes over from carry_basket, but without its cloth. LOAD_PACK's track
    # carries it off the hip, heaves it and sets it on the wagon's tailboard; the carried goods sit in its mouth.
    lo, hi = PACK_BASKET_BOX
    return 'body', Part('pack_basket', PACK_BASKET_PIVOT, PACK_BASKET_ROT, cubes=[
        C(lo, tuple(b_ - a_ for a_, b_ in zip(lo, hi)), 't_pack_basket', basket_hip_painter('t_basket_hip')),
    ])


TRACE_LEN = 16.0         # trace_end: TRACE_LEN px along +y from its pivot, which HITCH's track stretches


def _trace_end():
    # The right trace's front end as HITCH draws it out and hooks it on the hame: the strap of the wagon's own traces
    # (wagon.py's 'trace': 1.1 x 0.6 px, 16 px along +y, stretched between two points) in their leather. Its track roots
    # it on the line the hitched trace runs and stretches it to the hand; on the hook it lies where the world's trace
    # then runs, so the one takes over from the other unseen.
    return 'body', Part('trace_end', (0.0, 0.0, 0.0), (0, 0, 0), cubes=[
        C((-0.55, 0.0, -0.3), (1.1, TRACE_LEN, 0.6), 't_trace_end',
          strap_painter('t_trace_end', (78, 46, 26), (50, 30, 18), (116, 76, 46))),
    ])


# The scale's sack (store_art's pan_part, the goods pan's 'sack'), in its own space: the middle of its bottom at the origin,
# y down, x as a clip's (the store's model x mirrored), so that set on the pan's floor with no turn it is the scale's.
WEIGH_SACK_CUBES = {'body': ((-1.4, -2.5, -1.3), (2.9, 2.5, 2.5)), 'lump': ((-1.9, -1.3, 0.5), (1.6, 1.3, 1.5)),
                    'neck': ((-0.8, -3.1, -0.7), (1.6, 0.6, 1.4)), 'tie': ((-1.0, -2.7, -1.0), (2.0, 0.35, 2.0))}
WEIGH_SACK_NECK = (0.0, -2.8, 0.0)       # where the fist has it


def sackcloth_painter(key):
    """The Store's sacks: pale undyed canvas (the scale's own sackcloth) in a tight weave, softly creased."""
    def fn(face, w, h, rng):
        a = weave(w, h, (204, 194, 168), (168, 158, 132), (224, 218, 196), rng, cell=1)
        U, V = uv(w, h)
        crease = np.sin(U * 4.7 + rng.uniform(0, 6)) * np.sin(V * 3.3 + rng.uniform(0, 6))
        a[..., :3] *= (1 + 0.07 * crease)[..., None]
        return shade_edges(a, 0.22)
    return painter(key, fn)


def _weigh_sack():
    # The sack WEIGH sets on the scale: the scale's own (store_art's goods pan 'sack': the same blocks, its pale canvas and
    # rope tie), so that when the game puts the scale's sack on the pan as the clip lets go of this one, nothing is seen to
    # change. WEIGH's track hangs it from the left fist by its neck and sets it on the pan's floor; untracked it hangs by
    # the left hand at rest.
    cubes = []
    for name, (lo, size) in WEIGH_SACK_CUBES.items():
        key = 't_weigh_sack_' + name
        cubes.append(C(lo, size, key, cord_painter(key) if name == 'tie' else sackcloth_painter(key)))
    return 'body', Part('weigh_sack', (6.0, 15.5, 0.0), (0, 0, 0), cubes=cubes)


RUMMAGE_PACK_BOX = ((-3.5, 0.0, -2.0), (7.0, 5.5, 4.0))      # the pack's body: its mouth's middle at the origin, open


def pack_painter(key):
    """A worn leather pack: stitched edges, scuffs; its top the open mouth, dark inside a lighter rim."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, (126, 86, 52), rng, 0.06)
        U, V = uv(w, h)
        if face == 'top':
            inside = (U * w > 1) & (U * w < w - 1) & (V * h > 1) & (V * h < h - 1)
            a[..., :3] = np.where(inside[..., None], mix(a[..., :3], (38, 28, 20), 0.85), mix(a[..., :3], (176, 132, 86), 0.4))
            return a
        edge = (U * w < 1) | (U * w > w - 1) | (V * h < 1) | (V * h > h - 1)
        a[..., :3] = np.where(edge[..., None], mix(a[..., :3], (86, 56, 32), 0.6), a[..., :3])
        scuff = rng.random((h, w)) > 0.93
        a[..., :3] = np.where(scuff[..., None], mix(a[..., :3], (170, 128, 84), 0.5), a[..., :3])
        return shade_edges(a, 0.16)
    return painter(key, fn)


def _rummage_pack():
    # The pack RUMMAGE goes through: a worn leather pack held open before the belly, its flap thrown back down its far
    # side and buckled to nothing. RUMMAGE's track holds it in the clip's space; the right hand goes in at its mouth.
    lo, size = RUMMAGE_PACK_BOX
    return 'body', Part('rummage_pack', (0.6, 7.0, -5.6), (0, 0, 0), cubes=[
        C(lo, size, 't_rummage_pack', pack_painter('t_rummage_pack')),
        C((-3.6, -0.5, -2.5), (7.2, 4.6, 0.5), 't_rummage_flap',
          leather_painter('t_rummage_flap', (118, 80, 48), (82, 54, 32), (168, 124, 80))),
        C((-0.7, 3.2, -2.85), (1.4, 1.1, 0.35), 't_rummage_buckle', brass_painter('t_rummage_buckle')),
    ])


TRADE_BUILDERS = [_stamp, _canvas_flap, _pack_basket, _trace_end, _weigh_sack, _rummage_pack]
BUILDERS.extend(TRADE_BUILDERS)
TOOL_NOTES.update({
    'stamp': 'right hand, a seal stamp hanging along the arm (STAMP\'s track holds it pointing down and twists it on the '
             'wax); the middle of the seal\'s face at part (0, 4.6, 0)',
    'canvas_flap': f'body; the corner of the cart\'s canvas, a patch {CANVAS_W:g} across and {CANVAS_H:g} down from its pinned '
                   'top corner (the pivot); CHECK_LOAD\'s track swings it about that corner and the ring in the opposite '
                   'corner is where the hand pinches it',
    'pack_basket': 'body, tracked (LOAD_PACK): basket_hip\'s basket without its cloth, at its place on the left hip untracked; '
                   'its mouth\'s middle at part (0, -2.1, 0), its bottom\'s at (0, 2.5, 0)',
    'trace_end': f'body, tracked (HITCH): the front end of the right trace, {TRACE_LEN:g} px along part +y from its root; the '
                 'track roots it on the line the hitched trace runs and stretches it to the hand',
    'weigh_sack': 'body, tracked (WEIGH): the scale\'s sack (store_art\'s goods pan), the middle of its bottom at the part\'s '
                  'origin; the fist has it by the neck at part (0, -2.8, 0)',
    'rummage_pack': 'body, tracked (RUMMAGE): a leather pack held open, its mouth\'s middle at the part\'s origin, its body '
                    '7 across, 5.5 deep and 4 thick under it',
})


def trade_cube_names():
    """The trade's cubes among them (trade X4: townsfolk.py packs them last of all)."""
    return {c.name for b in TRADE_BUILDERS for p in b()[1].walk() for c in p.cubes}


# ================================================================== the villages' props (art/scripts/village_clips.py)

# The villages' (V12a): a baby swaddled in a shawl, held in both arms (CRADLE, and CARRY played as carry_swaddle while
# someone walks with one), and the lit candle carried to a memorial and set on its ledge (LAY_CANDLE). townsfolk.py packs
# their cubes after the trade's (group 7), so adding them moved no cube already on the texture.

SWADDLE = (238, 230, 212); SWADDLE_D = (186, 174, 150); SWADDLE_L = (250, 246, 236)       # an undyed wool shawl
SWADDLE_BAND = (120, 146, 176)                                                            # its woven blue band
BABY = (240, 204, 182); BABY_D = (214, 160, 140); BABY_CHEEK = (236, 156, 142)
CANDLE = (222, 210, 178); CANDLE_D = (176, 160, 124); CANDLE_L = (240, 232, 208)          # the memorial's own wax (memorial_wax)
SWADDLE_AT = (0.0, 3.6, -8.8)            # body space: the bundle's middle, lying across the forearms before the chest
SWADDLE_TILT = (8.0, 40.0)               # its head end (+x, the holder's left) 8 up; its top turned 40 out, to the forearms' slope
SWADDLE_ROT = euler_from_axes((math.cos(math.radians(SWADDLE_TILT[0])), -math.sin(math.radians(SWADDLE_TILT[0])), 0.0),
                              (math.sin(math.radians(SWADDLE_TILT[0])) * math.cos(math.radians(SWADDLE_TILT[1])),
                               math.cos(math.radians(SWADDLE_TILT[0])) * math.cos(math.radians(SWADDLE_TILT[1])),
                               math.sin(math.radians(SWADDLE_TILT[1]))))
SWADDLE_FACE = (2.9, -2.05, 0.0)         # swaddle space: the middle of the baby's face, on the hood's top
SWADDLE_FEET = (-3.4, 0.0, 0.0)          # and the foot of the bundle
CANDLE_BASE = (0.0, 1.5, 0.0)            # candle space: the middle of its foot (the fist has it 1.5 above that)
CANDLE_WICK = (0.0, -3.5, 0.0)           # the wick's tip, where candle_flame stands


def swaddle_painter(key, part='body'):
    """A baby wrapped in an undyed wool shawl: the wraps crossing the body in soft diagonal folds, a woven blue band near the
    shawl's edge; on the hood ([part] 'head') the face looking up out of its round opening: pink, the eyes shut, rosy cheeks."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, SWADDLE, rng, 0.035)
        wrap = np.sin((U * 1.6 + V * (0.9 if face in ('top', 'bottom') else 0.6)) * math.pi * 2.6)
        a[..., :3] = mix(a[..., :3], SWADDLE_D, np.clip(-wrap, 0, 1)[..., None] * 0.45)
        a[..., :3] = mix(a[..., :3], SWADDLE_L, np.clip(wrap - 0.5, 0, 1)[..., None] * 0.7)
        if part == 'body' and face in ('front', 'back', 'top', 'bottom'):
            band = np.abs(U - 0.22 - 0.25 * V) < 0.06
            a[..., :3] = np.where(band[..., None], mix(a[..., :3], SWADDLE_BAND, 0.75), a[..., :3])
        if part == 'head' and face == 'top':
            # the face in the hood's opening (the face's u runs along the bundle, toward its head end)
            d = np.hypot((U - 0.42) * 1.15, (V - 0.5))
            skin = d < 0.33
            a[..., :3] = np.where((d < 0.42)[..., None] & ~skin[..., None], mix(a[..., :3], SWADDLE_D, 0.55), a[..., :3])
            a[..., :3] = np.where(skin[..., None], mix(np.array(BABY, float), np.array(BABY_D, float), np.clip(d * 2.2, 0, 1)[..., None] * 0.6), a[..., :3])
            for cv in (0.36, 0.64):
                eye = (np.abs(U - 0.48) < 0.06) & (np.abs(V - cv) < 0.08)
                a[..., :3] = np.where(eye[..., None], np.array((112, 72, 60), float), a[..., :3])
                cheek = np.hypot(U - 0.36, V - cv) < 0.09
                a[..., :3] = np.where(cheek[..., None], mix(a[..., :3], BABY_CHEEK, 0.5), a[..., :3])
        return shade_edges(a, 0.14)
    return painter(key, fn)


def candle_painter(key, part='wax'):
    """A plain candle of the memorial's cream wax, a drip run down one side from the lip; its wick black, singed."""
    def fn(face, w, h, rng):
        if part == 'wick':
            return noise_fill(w, h, (40, 32, 28), rng, 0.08)
        U, V = uv(w, h)
        a = noise_fill(w, h, CANDLE, rng, 0.03)
        if face in ('front', 'back', 'right', 'left'):
            a[..., :3] = mix(a[..., :3], CANDLE_L, np.clip(0.35 - np.abs(U - 0.4), 0, 0.35)[..., None])
            if face == 'front':
                drip = (np.abs(U - 0.7) < 0.14) & (V < 0.55)
                a[..., :3] = np.where(drip[..., None], mix(a[..., :3], CANDLE_L, 0.8), a[..., :3])
            a[..., :3] = mix(a[..., :3], CANDLE_D, np.clip(V - 0.75, 0, 0.25)[..., None] * 1.2)
        elif face == 'top':
            d = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = mix(a[..., :3], CANDLE_D, np.clip(0.3 - d, 0, 0.3)[..., None] * 1.5)
        return shade_edges(a, 0.12)
    return painter(key, fn)


def _swaddle():
    # The baby, lying across both forearms before the chest, its head at the holder's left: a bundle of wool 8 px long,
    # its hood a little fuller than the wrapped body, the face looking up out of it at whoever holds it (and, tipped out to
    # the forearms' slope, at whoever is in front). On the body, so it rocks with them; CRADLE's and carry_swaddle's arms
    # run under it (village_clips.py).
    return 'body', Part('swaddle', SWADDLE_AT, SWADDLE_ROT, cubes=[
        C((-4.2, -1.6, -2.0), (5.9, 3.2, 4.0), 'v_swaddle_body', swaddle_painter('v_swaddle_body')),
        C((1.6, -1.9, -2.2), (2.6, 3.8, 4.4), 'v_swaddle_head', swaddle_painter('v_swaddle_head', 'head')),
    ])


def _candle_lit():
    # The candle LAY_CANDLE carries to the memorial, lit, upright in the right fist (its track keeps it standing up
    # whichever way the forearm points); the memorial's own candle (Tales G's model, the same 2 x 4.1 of wax) shows in
    # its place once it is set down.
    return 'right_arm', Part('candle_lit', RIGHT_GRIP, (0, 0, 0), cubes=[
        C((-1.0, -2.6, -1.0), (2.0, 4.1, 2.0), 'v_candle_wax', candle_painter('v_candle_wax')),
        C((-0.25, -3.5, -0.25), (0.5, 0.9, 0.5), 'v_candle_wick', candle_painter('v_candle_wick', 'wick')),
    ])


def _candle_flame():
    # Its flame (it glows: GLOW_CUBES), standing on the wick: LAY_CANDLE's track keeps it upright, leaning back from the
    # way the hand moves and flickering, shrinking low behind the shielding hand as it is carried.
    return 'right_arm', Part('candle_flame', tuple(float(v) for v in np.add(RIGHT_GRIP, CANDLE_WICK)), (0, 0, 0), cubes=[
        C((-0.8, -3.0, -0.8), (1.6, 3.0, 1.6), 't_candle_flame', upright_flame_painter('t_candle_flame')),
        C((-0.35, -1.5, -0.35), (0.7, 1.4, 0.7), 't_candle_flame_core', upright_flame_painter('t_candle_flame_core', core=True)),
    ])


VILLAGE_BUILDERS = [_swaddle, _candle_lit, _candle_flame]
BUILDERS.extend(VILLAGE_BUILDERS)
GLOW_CUBES.update({'t_candle_flame', 't_candle_flame_core'})
TOOL_NOTES.update({
    'swaddle': f'body, a swaddled baby lying across the forearms before the chest, its head end at the holder\'s left; its face '
               f'at part {SWADDLE_FACE}, its foot at {SWADDLE_FEET}; the arms run under it (CRADLE, carry_swaddle)',
    'candle_lit': 'right hand, a candle upright in the fist (LAY_CANDLE\'s track stands it up); its foot at part (0, 1.5, 0), '
                  'the wick\'s tip at (0, -3.5, 0)',
    'candle_flame': 'right hand, the candle\'s flame (glows), tracked to stand on the wick',
})


def village_cube_names():
    """The villages' cubes among them (villages V12a: townsfolk.py packs them last of all)."""
    return {c.name for b in VILLAGE_BUILDERS for p in b()[1].walk() for c in p.cubes}


def install(g):
    """Adds the props to townsfolk_tools' tables ([g]: its globals)."""
    if g.get('_LIVING_PROPS'):
        return
    g['_LIVING_PROPS'] = True
    g['BUILDERS'].extend(BUILDERS)
    g['GLOW_CUBES'].update(GLOW_CUBES)
    g['TOOL_NOTES'].update(TOOL_NOTES)


install(vars(tt))
