"""Hearthcraft's scenes for the previews (cook_prev.py): the stations and helpers placed as the game and the renderers place them, seen
without the game through `cook_view.py`. A station is its blockstate's shell plus the pieces its renderer draws (the pot lowered on its
chain, the pan, the oven's door and trays, the spit's rod and crank, the larder's door), each turned about the pivot the contract in
`cook_art.py` gives. Only for previews: nothing here is written into the mod.
"""
import hashlib
import math

import numpy as np
from PIL import Image, ImageDraw

import cook_view as V
from cook_geo import Rx, Ry
import cook_stewpot as SP
import cook_hob as HB
import cook_oven as OV
import cook_spit as SK
import cook_helpers as HL

CELL = np.array([8.0, 0.0, 8.0])                 # the middle of a block, about which its blockstate turns it


def tint_image(img, rgb):
    """A copy of an RGBA image with each colour multiplied by [rgb] (0..1 each)."""
    a = np.asarray(img.convert('RGBA'), float)
    a[..., :3] *= np.asarray(rgb, float)
    return Image.fromarray(np.clip(a + 0.5, 0, 255).astype(np.uint8), 'RGBA')


class Stage:
    """Parts, and boxes to outline, in one scene."""

    def __init__(self, assets, game=None):
        self.assets = assets
        self.game = game
        self.roots = V.Roots(assets, game)
        self.parts = []
        self.boxes = []                 # (lo, hi, colour, name): outlines, in the scene
        self.lines = []                 # (p0, p1, colour, width): pivots and axes
        self.sprites = []               # (image, point, size px, alpha): particles, drawn facing the camera
        self.labels = []                # (text, point, colour): under or beside a point
        self.dots = []                  # (point, colour, radius px): pivots

    # ---- placing
    def block(self, model, at=(0, 0, 0), facing=0):
        """A block's model as its blockstate places it: turned by [facing] (the blockstate's y: east 90, south 180, west 270) about the cell's middle,
        the cell [at] px from the scene's origin."""
        self.parts.append(V.Part(model, rot=Ry(-facing), pivot=CELL, at=at))

    def piece(self, model, at=(0, 0, 0), facing=0, turn=0.0, about=None, axis='y', slide=(0, 0, 0)):
        """A piece a renderer draws: turned by [turn] degrees about a vertical line through [about] (x, z) (axis 'y'), or about a line along x through
        [about] (y, z) (axis 'x'), moved by [slide], and then turned with its block by [facing]."""
        R1, h = np.eye(3), np.zeros(3)
        if turn:
            if axis == 'y':
                R1, h = Ry(turn), np.array([about[0], 0.0, about[1]])
            else:
                R1, h = Rx(turn), np.array([0.0, about[0], about[1]])
        R2 = Ry(-facing)
        move = R2 @ (h - R1 @ h + np.asarray(slide, float) - CELL) + CELL
        self.parts.append(V.Part(model, rot=R2 @ R1, pivot=(0, 0, 0), move=move, at=at))

    def _ref(self, texture, name):
        """A texture reference for the scene: an image is saved into the scratch folder (once for each distinct picture)."""
        if isinstance(texture, Image.Image):
            key = name or 'img_' + hashlib.md5(texture.convert('RGBA').tobytes() + bytes(texture.size)).hexdigest()[:16]
            return self.roots.scratch(key, texture)
        return texture

    def flat(self, texture, x, y, z, w=8.0, at=(0, 0, 0), facing=0, spin=0.0, name=None, slide=(0, 0, 0)):
        """An item's sprite laid flat (face up) at model px (x, y, z), [w] px across: how a dish lies on a plate or a tray. [texture] is an image, or a
        reference such as `guildmark:item/dish_stew`; [slide] moves it as a piece (a tray's load goes with the tray)."""
        el = {'from': [x - w / 2, y, z - w / 2], 'to': [x + w / 2, y + 0.05, z + w / 2],
              'faces': {'up': {'uv': [0, 0, 16, 16], 'texture': '#t'}}}
        self.piece({'textures': {'t': self._ref(texture, name)}, 'elements': [el]}, at=at, facing=facing, turn=spin, about=(x, z), slide=slide)

    def upright(self, texture, x, y, z, w=5.0, at=(0, 0, 0), facing=0, name=None, h=None):
        """An item's sprite standing up, its face toward the front (north), [w] px across and [h] (as much) high, centred on model px (x, y, z): a dish
        on a shelf, or writing on a slate."""
        h = w if h is None else h
        el = {'from': [x - w / 2, y - h / 2, z], 'to': [x + w / 2, y + h / 2, z + 0.05],
              'faces': {'north': {'uv': [0, 0, 16, 16], 'texture': '#t'}}}
        self.piece({'textures': {'t': self._ref(texture, name)}, 'elements': [el]}, at=at, facing=facing)

    def quad(self, image, name, lo, hi, at=(0, 0, 0), facing=0, turn=0.0, about=None, slide=(0, 0, 0)):
        """A flat horizontal quad of [image] over the px box [lo]..[hi] (its y is lo's): a liquid's surface."""
        ref = self._ref(image, name)
        el = {'from': [lo[0], lo[1], lo[2]], 'to': [hi[0], lo[1] + 0.02, hi[2]], 'faces': {'up': {'uv': [0, 0, 16, 16], 'texture': '#t'}}}
        self.piece({'textures': {'t': ref}, 'elements': [el]}, at=at, facing=facing, turn=turn, about=about, slide=slide)

    def _world(self, p, at, facing):
        return Ry(-facing) @ (np.array(p, float) - CELL) + CELL + np.asarray(at, float)

    def box(self, lo, hi, color, at=(0, 0, 0), facing=0, name=None):
        """An outline of a px box (authored facing north) in the cell [at], turned with the block."""
        pts = [self._world(p, at, facing) for p in (lo, hi)]
        self.boxes.append((np.minimum(pts[0], pts[1]), np.maximum(pts[0], pts[1]), color, name))

    def line(self, p0, p1, color, at=(0, 0, 0), facing=0, width=2):
        """A line between two model px points (a pivot's axis)."""
        self.lines.append((self._world(p0, at, facing), self._world(p1, at, facing), color, width))

    def sprite(self, image, p, size, at=(0, 0, 0), facing=0, alpha=1.0):
        """A particle: [image] facing the camera, [size] model px across, its middle at the model px point [p]."""
        self.sprites.append((image, self._world(p, at, facing), size, alpha))

    def dot(self, p, color, at=(0, 0, 0), facing=0, r=4):
        """A dot on a model px point (a pivot)."""
        self.dots.append((self._world(p, at, facing), color, r))

    def arc(self, centre, radius, a0, a1, y, color, at=(0, 0, 0), facing=0, width=2, n=18, heading=0.0):
        """The path of a point [radius] px from the vertical axis through [centre] (x, z), at height [y], as it turns from [a0] to [a1] degrees (as
        `Axis.YP` turns: a positive angle carries +x toward -z); it starts [heading] degrees round from +x."""
        pts = []
        for k in range(n + 1):
            a = math.radians(heading + a0 + (a1 - a0) * k / n)
            pts.append((centre[0] + radius * math.cos(a), y, centre[1] - radius * math.sin(a)))
        for p, q in zip(pts, pts[1:]):
            self.line(p, q, color, at, facing, width)

    def label(self, text, p, at=(0, 0, 0), facing=0, color=None):
        self.labels.append((text, self._world(p, at, facing), color))

    def floor(self, x0, z0, x1, z1, model='ground', y=0.0):
        """Whole cells of paving under a scene, from the cell (x0, z0) to (x1, z1) inclusive (cell numbers)."""
        for i in range(x0, x1 + 1):
            for k in range(z0, z1 + 1):
                el = {'from': [0, y - 0.4, 0], 'to': [16, y, 16], 'faces': {'up': {'uv': [0, 0, 16, 16], 'texture': '#g'}}}
                self.parts.append(V.Part({'textures': {'g': self.roots.ground() if model == 'ground' else model}, 'elements': [el]}, at=(16 * i, 0, 16 * k)))

    # ---- drawing
    def render(self, yaw=160.0, elev=28.0, scale=10.0, size=(640, 400), center=(8, 6, 8), mode=V.NOON, tick=0, ground=True, bloom=0.5, ss=3,
               box_width=2):
        im = V.render(self.parts, self.roots, yaw=yaw, elev=elev, scale=scale, size=size, tick=tick, center=center, boxes=(), ground=ground, mode=mode,
                      bloom=bloom, ss=ss)
        return self._overlay(im, yaw, elev, scale, size, center, box_width)

    def _overlay(self, im, yaw, elev, scale, size, center, box_width):
        """What is drawn over the picture: particles (far to near), outlines, axes, labels."""
        import cook_prevkit as P
        if not (self.sprites or self.boxes or self.lines or self.labels or self.dots):
            return im
        phi, th = math.radians(yaw), math.radians(elev)
        cam = np.array([math.cos(th) * math.sin(phi), math.sin(th), math.cos(th) * math.cos(phi)])
        out = im.convert('RGBA')
        for img, q, sz, alpha in sorted(self.sprites, key=lambda s: float(s[1] @ cam)):
            n = max(1, int(round(sz * scale)))
            s = img.convert('RGBA').resize((n, n), Image.NEAREST)
            if alpha < 1.0:
                a = np.asarray(s, float)
                a[..., 3] *= alpha
                s = Image.fromarray(np.clip(a + 0.5, 0, 255).astype(np.uint8), 'RGBA')
            x, y = V.project(q, yaw, elev, scale, size, center)
            out.alpha_composite(s, (int(round(x - n / 2)), int(round(y - n / 2))))
        d = ImageDraw.Draw(out)
        for lo, hi, col, name in self.boxes:
            pts = {(i, j, k): V.project((x, y, z), yaw, elev, scale, size, center) for i, x in enumerate((lo[0], hi[0])) for j, y in enumerate((lo[1], hi[1]))
                   for k, z in enumerate((lo[2], hi[2]))}
            for a in pts:
                for b in pts:
                    if sum(abs(a[t] - b[t]) for t in range(3)) == 1 and a < b:
                        d.line([pts[a], pts[b]], fill=tuple(col) + (255,), width=box_width)
        for p0, p1, col, w in self.lines:
            d.line([V.project(p0, yaw, elev, scale, size, center), V.project(p1, yaw, elev, scale, size, center)], fill=tuple(col) + (255,), width=w)
        for q, col, r in self.dots:
            x, y = V.project(q, yaw, elev, scale, size, center)
            d.ellipse([x - r - 1, y - r - 1, x + r + 1, y + r + 1], fill=(20, 20, 24, 255))
            d.ellipse([x - r, y - r, x + r, y + r], fill=tuple(col) + (255,))
        for text, q, col in self.labels:
            x, y = V.project(q, yaw, elev, scale, size, center)
            P.text(d, (x, y), text, col or P.INK, 12, 'ma')
        return out.convert('RGB')

    def project(self, p, yaw, elev, scale, size, center):
        return V.project(p, yaw, elev, scale, size, center)


# ================================================================ the stations

def stewpot(st, at=(0, 0, 0), style='camp', lit=False, burnt=False, notch=1, swing=0.0, soot=0, level=None, tint=None, crust=False, facing=0):
    """The stewpot: the shell of its style and state, the soot a burnt one wears, the pot hung at [notch] (on the crane's arm, swung [swing] degrees,
    in the hearth style), the soot on the pot, and - if [level] (0 a ladleful, 1 full) - the liquid's surface, tinted [tint], or the crust of a
    burnt pot."""
    st.block('stewpot_%s%s' % (style, '_lit' if lit else ''), at, facing)
    if burnt:
        st.block('stewpot_%s_burnt' % style, at, facing)
    dy = -SP.NOTCH_STEP * notch
    kw = dict(turn=swing, about=SP.CRANE_POST) if style == 'hearth' else {}
    if style == 'hearth':
        st.piece('cook_stewpot_crane', at, facing, **kw)
    st.piece('cook_stewpot_pot', at, facing, slide=(0, dy, 0), **kw)
    if soot:
        st.piece('cook_stewpot_soot_%d' % soot, at, facing, slide=(0, dy, 0), **kw)
    if level is not None:
        import cook_render
        y = SP.SURFACE_Y[0] + (SP.SURFACE_Y[1] - SP.SURFACE_Y[0]) * level
        if crust:
            img, name = cook_render.crust(), 'crust'
        else:
            img = cook_render.surface()
            if tint is not None:
                img = tint_image(img, tint)
            name = 'surface_%s' % '_'.join('%02x' % int(255 * c) for c in (tint or (1, 1, 1)))
        r = SP.SURFACE_R
        st.quad(img, name, (8 - r, y, 8 - r), (8 + r, y, 8 + r), at, facing, slide=(0, dy, 0), **kw)
    return dy, kw


def hob(st, at=(0, 0, 0), lit=False, notch=1, burnt=False, facing=0, pieces=()):
    """The hob: shell, damper plate, the pan, [pieces] (sprites) lying in the pan at its slots."""
    st.block('hob_lit' if lit else 'hob', at, facing)
    st.block('hob_damper_%d' % notch, at, facing)
    st.piece('cook_hob_pan', at, facing)
    for img, (x, z) in zip(pieces, HB.PAN_SLOTS):
        st.flat(img, x, HB.PAN_FLOOR_Y + 0.05, z, 3.6, at, facing)
    if burnt:
        st.piece('cook_hob_burnt', at, facing)


def oven(st, at=(0, 0, 0), lit=False, notch=1, door=0.0, trays=2, out=0.0, burnt=False, facing=0, lower=(), upper=()):
    """The bake oven: shell and damper, the door swung [door] degrees (the glowing one while it is hot and shut), [trays] trays (1 lower, 2 both),
    slid [out] px toward the door, [lower] and [upper] (sprites) lying on them at the tray slots."""
    st.block('bake_oven_lit' if lit else 'bake_oven', at, facing)
    st.block('bake_oven_damper_%d' % notch, at, facing)
    st.piece('cook_oven_door_hot' if (lit and door == 0) else 'cook_oven_door', at, facing, turn=door, about=OV.HINGE)
    if trays >= 1:
        st.piece('cook_oven_tray', at, facing, slide=(0, 0, -out))
        for img, (x, z) in zip(lower, OV.TRAY_SLOTS):
            st.flat(img, x, OV.TRAY_Y[0] + 0.3, z, 2.8, at, facing, slide=(0, 0, -out))
    if trays >= 2:
        st.piece('cook_oven_tray', at, facing, slide=(0, OV.TRAY_Y[1] - OV.TRAY_Y[0], -out))
        for img, (x, z) in zip(upper, OV.TRAY_SLOTS):
            st.flat(img, x, OV.TRAY_Y[1] + 0.3, z, 2.8, at, facing, slide=(0, 0, -out))
    if burnt:
        st.piece('cook_oven_burnt', at, facing)


def spit(st, at=(0, 0, 0), lit=False, notch=1, angle=0.0, burnt=False, facing=0):
    st.block('spit_lit' if lit else 'spit', at, facing)
    kw = dict(turn=angle, about=(SK.AXIS_Y, SK.AXIS_Z), axis='x', slide=(0, SK.REST_DY[notch], 0))
    st.piece('cook_spit_rod', at, facing, **kw)
    st.piece('cook_spit_crank', at, facing, **kw)
    if burnt:
        st.piece('cook_spit_burnt', at, facing)


def larder(st, at=(0, 0, 0), open_=False, door=0.0, facing=0, dishes=()):
    """The larder: shut (the door part of its shell) or open with the door swung [door] degrees; [dishes] (sprites) stand on its shelves."""
    st.block('larder_open' if open_ else 'larder', at, facing)
    if open_:
        st.piece('cook_larder_door', at, facing, turn=door, about=HL.LARDER_DOOR_HINGE)
    for img, (x, y, z) in zip(dishes, HL.LARDER_DISHES):
        st.upright(img, x, y + 1.0, z, 4.4, at, facing)


def plate(st, at=(0, 0, 0), dish=None, facing=0):
    st.block('plate', at, facing)
    if dish is not None:
        x, y, z = HL.PLATE_AT
        st.flat(dish, x, y + 0.02, z, 7.4, at, facing)


def prep_board(st, at=(0, 0, 0), heap=(), facing=0, seed=3):
    """The Prep Board with a heap of [heap] (sprites) lying where the heap box is, lower ones first."""
    st.block('prep_board', at, facing)
    rng = np.random.default_rng(seed)
    x0, y0, z0, x1, y1, z1 = HL.HEAP_BOX
    for i, img in enumerate(heap):
        layer = i // 4
        x = rng.uniform(x0 + 1.9, x1 - 1.9)
        z = rng.uniform(z0 + 1.9, z1 - 1.9)
        st.flat(img, x, y0 + 0.1 + 0.8 * layer, z, 3.8, at, facing, spin=float(rng.uniform(0, 360)))


def menu_board(st, at=(0, 0, 0), wall=False, facing=0, chalk=None):
    """The Menu Board (standing, or the wall variant) with [chalk] (an RGBA picture of the writing) on its slate."""
    st.block('menu_board_wall' if wall else 'menu_board', at, facing)
    if chalk is not None:
        x0, y0, x1, y1 = HL.WALL_FACE if wall else HL.SLATE_FACE
        z = (HL.WALL_Z if wall else HL.SLATE_Z) - 0.03
        st.upright(chalk, (x0 + x1) / 2, (y0 + y1) / 2, z, x1 - x0, at, facing, h=y1 - y0)


def vanilla(st, name, at=(0, 0, 0), facing=0):
    st.block('minecraft:block/' + name, at, facing)
