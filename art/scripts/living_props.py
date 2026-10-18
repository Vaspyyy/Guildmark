"""
The Living World's held props for the townsfolk (art/scripts/living_anims.py animates them): food to eat, a bowl and
spoon, the well's rope and bucket, coins, a seed bag, a scythe, an axe, a crier's handbell and scroll, a lamplighter's
pole, a basket on the hip, a sack to tip out and a grindstone's crank.

townsfolk_tools.py takes them in at its end (BUILDERS, GLOW_CUBES, TOOL_NOTES), so townsfolk.py builds them into the
model like every other tool, and art/scripts/townsfolk.py packs their cubes after everyone else's (CUBES), so adding
them never moves an older cube on the texture. Model space as in townsfolk_tools: pixels, y down, the character faces
-z, its right hand at -x; right grip (-1, 10, 0) in right_arm space, left grip (1, 10, 0) in left_arm space.

Long things (the rope, the poles, the scythe's snath) are modelled along x and turned into place by their part's
rotation: a cube's box UV takes 2 (w + d) x (h + d) texels, so a long thin cube lying along x needs one short strip.
The rope, the bucket and the empty sack hang straight down from the hand however the arm turns (HANG: the game turns
them each frame, as it does the fishing line and the lantern).
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


BUILDERS = [_loaf, _apple, _bowl, _spoon, _bucket, _haul_rope_r, _haul_rope_l, _tail_rope_r, _tail_rope_l, _curb_rope,
            _well_rope, _well_bucket, _coin, _seed_bag, _scythe, _axe, _handbell, _scroll, _lamp_pole, _basket_hip,
            _sack_held, _sack_empty, _crank]

GLOW_CUBES = {'t_lamp_flame'}

# Parts the game turns each frame to hang straight down from the hand (LivingProps.kt), unless a clip's prop track runs
# them (the parts it turns, places and stretches tick by tick: the crank, the well's ropes and bucket)
HANG = ('bucket', 'sack_empty')

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
}


def cube_names():
    """Every cube these props add (townsfolk.py packs them last)."""
    return {c.name for b in BUILDERS for p in b()[1].walk() for c in p.cubes}


def part_names():
    """Every part these props add, children included."""
    return [p.name for b in BUILDERS for p in b()[1].walk()]


def install(g):
    """Adds the props to townsfolk_tools' tables ([g]: its globals)."""
    if g.get('_LIVING_PROPS'):
        return
    g['_LIVING_PROPS'] = True
    g['BUILDERS'].extend(BUILDERS)
    g['GLOW_CUBES'].update(GLOW_CUBES)
    g['TOOL_NOTES'].update(TOOL_NOTES)


install(vars(tt))
