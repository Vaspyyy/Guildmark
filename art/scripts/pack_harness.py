"""
The Pack Harness on a donkey, a mule and a llama (Trade, art stream X2): the blanket in the owner's colour, two wicker panniers that
fill in four steps, the straps and a pack saddle, a brass bell on the breast strap (on a collar on the llama) whose pendulum and
clapper are parts of their own, a halter and a crupper. One fitted copy for the donkey and the mule (the mule is the donkey's mesh
at its own scale) and one for the llama.

    python3 -B art/scripts/pack_harness.py [assets dir [kotlin dir [model dir [preview dir [game assets dir]]]]]

writes `textures/entity/trade/pack_harness.png` (under [assets dir], default `src/main/resources/assets/guildmark`; shipped at
SCALE x the declared TEX_WIDTH x TEX_HEIGHT), `client/trade/PackHarnessMesh.kt` (GENERATED; under [kotlin dir], default
`src/main/kotlin/io/github/vaspyyy/guildmark/client/trade`), `pack_harness_donkey.bbmodel` and `pack_harness_llama.bbmodel` (under
[model dir], default `art/models`) and, with a [preview dir], the sheets and GIFs (the optional last argument is a game
`assets/minecraft` folder unpacked from the jar, read only: the vanilla animals' skins drawn under the harness). `-` stands for a
default. A rerun gives the same files. Before any file it checks its own numbers against this page (`check_contract`: the table
below, the vanilla parts' names, the pictures, that no moving part goes through the animal, the straps or the blanket over sampled poses
(the layer's springs pushed to their limits among them), that the animal's own neck goes no further into the still shells than 2 px over a
sweep of the head's look, and that nothing floats), and after writing
it checks what it wrote (`check_written`); it stops with the list if anything is off. Run it from the repository's root.

HOW IT IS DRAWN (for the layer that draws it) -----------------------------------------------------------------------------------
The vanilla way, as the game dresses a saddled horse: a second model of the same shape as the animal's own, drawn over it with the
same pose. Each layer carries every part the vanilla model looks up by name, empty where the harness has nothing, so that
`DonkeyModel(root)` and `LlamaModel(root)` stand on it unchanged and their `setupAnim` poses it as it poses the animal (the
harness follows the legs, the head, a rearing body). Only the harness's own parts carry cubes.

  layer          vanilla class to stand on it        scale              texture          tint
  -------------  ----------------------------------  -----------------  ---------------  ------------------------------------
  donkey()       DonkeyModel                         DONKEY_SCALE 0.87  pack_harness     none: leather, wicker, rope, sacks, brass
  mule()         DonkeyModel                         MULE_SCALE 0.92    pack_harness     none
  llama()        LlamaModel                          1                  pack_harness     none
  donkeyWool()   DonkeyModel                         DONKEY_SCALE       pack_harness     the owner's colour: blanket, tassels
  muleWool()     DonkeyModel                         MULE_SCALE         pack_harness     the owner's colour
  llamaWool()    LlamaModel                          1                  pack_harness     the owner's colour

The wool is a layer of its own because the game tints a whole model, never a part: draw it with the saved colour as the ARGB tint
(the cloth is painted neutral, so any colour shows true; the six booker colours are `COLOURS`). `setupAnim` runs when the frame is
drawn and resets every part, so pose the harness's own parts after it (override `setupAnim` in a subclass, call `super` first):
`Rig(root, llama)` and `WoolRig(root, llama)` do it from the render state's numbers. The Rig also shows the panniers' wrappers
(the vanilla chests' names) whether or not the animal has its chest.

THE JAR'S NUMBERS IT STANDS ON (read with `javap -c`; px, model space; the root scale applies to everything) --------------------
  donkey, mule   AbstractEquineModel / DonkeyModel: body box x +-5, y 3..13, z -12..10 (inflated 0.05) about (0, 11, 5); the chests
                 8 x 8 x 3 turned a quarter about y, x 5..8, y 3..11, z 1..9; head_parts (0, 4, -12), 30 degrees forward at rest and
                 125 grazing (lowered 7 px); neck x +-2, y -6..6 from there; tail (0, 6, 7); legs (+-4, 14, 7) and (+-4, 14, -10);
                 the front legs swing cos(walkPos * 0.6662) * 0.8 * speed (rad), the hind ones 0.5 * speed;
                 DONKEY_SCALE 0.87, MULE_SCALE 0.92 (public constants).
  llama          LlamaModel: body box x +-6, y 2..12, z -8..10; chests x 5.5..8.5, y 3..11, z -2..6; head and neck pivot (0, 7, -6),
                 neck x +-4, z -12..-6; legs (+-3.5, 10, 6) and (+-3.5, 10, -5); the legs swing cos(walkPos * 0.6662) * 1.4 * speed.
  The sizes are the harness's fit: a pannier encloses the vanilla chest, the girth and breast strap are shells round the body, the
  blanket lies on its back, the halter hugs the head. `check_contract` asserts the fits.

THE MOVING PARTS: the contract with Trade D1, who draws them -----------------------------------------------------------------------
Angles are degrees here (the Kotlin takes radians, as ModelPart does). `w` is the animal's walk phase, `walkAnimationPos * 0.6662`
(the legs' own), `s` its `walkAnimationSpeed`, `age` the state's `ageInTicks`; `fill` runs 0 to 3.

  part (donkey / mule)           pivot (px, parent's frame)         how it moves
  -----------------------------  ---------------------------------  ---------------------------------------------------------------
  left_chest, right_chest        (+-6.05, 2.3, 5.0) in body         the panniers' hanging points: the pair sways as one about z (+-2.6
                                                                    deg walking, less when full) and pitches about x (1.2 deg, twice a
                                                                    stride); everything of a pannier is a child
  pannier_l, pannier_r           the same                           the wicker basket: x scale 1 + BELLY * fill / 3 about it
  fill1_l .. fill3_r             (+-1.82, 0.05, 0) in the chest     the heap in three tiers, each grown from its base: tier k shows
                                                                    t = smooth(clamp(fill - (k - 1), 0, 1)); y scale t, x and z scale
                                                                    0.6 + 0.4 t; hidden under t = 0.02; settles 0.12 px with each step
  breast > hanger                (0, 7.1, -12.4)                    the arm the bell hangs from; rides along the strap up to 5 px to
                                                                    the left when the neck comes down in front of it (the head lowered
                                                                    over 0.3..2.2 px) or stands up (pitch under 15 degrees)
  bell                           (0, 7.9, -14.25) = BELL_DONKEY     swings fore and aft about x, forward only (the chest is behind
                                                                    it): 0 to -24 deg (the llama's -17) by walk speed, twice a stride;
                                                                    breathes 2 deg
  bell_clapper                   (0, 1.1, 0) in the bell            lags the bell and runs round an ellipse, x 22 and z 14 deg at most
  halter_ring                    (0, -5.7, -5.3) in head_parts      hangs plumb below the chin whatever the head does; swings 9 deg
                                                                    with the stride
  crupper_ring                   (0, 0, 0) in tail                  the dock loop: the tail's pose carries it
  flap_l, flap_r (wool)          (+-5.45, 2.7, 1.4) in the blanket  lift off the flank up to 4 deg with speed; breathe
  tassel_l0 .. tassel_r4 (wool)  (0, 4.75, z) in the flap           5 a side, each a pendulum, a wave running back along the hem
  llama                          the same names; panniers at (+-7.05, 1.35, 2.0) in the root; the bell on a collar round the neck's
                                 foot: head > collar > bell (0, 6.9, -15.0 when the head is level = BELL_LLAMA), counter-turned by
                                 the head's pitch so that it hangs plumb; 4 tassels a side; no halter, tail or hanger

  fill   what the baskets show                                         HEAP_TOP (px over the rim)
  -----  ------------------------------------------------------------  ---------------------------
  0      empty: a straw lining, open                                   0.0
  1      a quarter: a bundle of linen tied with cord                   1.1
  2      a half: two burlap sacks side by side                         2.4
  3      full: a big sack lashed over them                             4.5

Up to three goods show on top as small items (`GOODS`: x, z from the opening's middle, yaw, tilt) standing `heapTop(fill)` over the
rim at `OPENING` in the chest's frame: draw them as children of the chest's pose so that they sway with the basket. Ease the fill
over 6 ticks (`fillEase`; `fillStep` maps used slots to the four looks). The bell strikes twice a stride, at the top of its swing
(`bellBeat` steps up there): the sound is the caller's.

THE LAYER'S SPRINGS: what starts, stops, turns, a knock and a step taken add to the stride --------------------------------------------
Optional arguments of `Rig.pose(fill, walkPos, walkSpeed, age, trot, pitch, roll, knock, pop)` and `WoolRig.pose(walkPos, walkSpeed, age,
trot, dangle)` (radians, as the layer's own springs give them; all 0 gives the stride alone, exactly). Each is eased off by `fit` (a tanh,
as the stride's own swing passes untouched and only what is added beyond it is held) where the animal leaves the load no room; the limits
are the room measured over the fit poses (see `check_fit`, which pushes every spring past its limit in every sign, at a trot, with the
head lowered, raised and rearing, and stops the run if anything goes further into the animal, the straps or the blanket than that).

  argument  what it adds                                                    held to (degrees)
  --------  --------------------------------------------------------------  ----------------------------------------------------------
  trot      0 to 1: the stride's sway, lift and settle up to 1.5 times      the baskets' roll stops growing at 1.0, the tassels' at 1.05
  pitch     the pair of baskets lags a start and leads a stop (x 1.2)       2.4 back (the tail), 3.5 forward (the girth ring, the tassels)
  roll      the pair swings to the animal's right (x 1.4): the basket       3.2 in (the flank is that close), 14 out (nothing there)
            that way is pressed to the flank, the other swings free
  dangle    the tassels trail backward (x 0.5)                              22 forward, 26 back
  knock     0 or more: the bell swings further forward, its clapper rattles 24 more (the llama: none at a full gait; her legs swing
                                                                            through where it hangs)
  pop       0 to 1: a step taken squashes a heap (x and z up 4%, y down 3%) -

The donkey's bell fades to a tenth of its swing as the head's pitch (30 at rest) goes from 75 to 95 degrees (the muzzle comes down in front
of it); the llama's bell swings at most 17 degrees. The two things the fit poses leave over are listed in `CLIP_ALLOW`: the donkey's crupper
ring sits half inside the rump as the tail wags, the llama's bell lip touches the top of a front leg by 0.7 px for a couple of frames at a
sprint.

THE HEAD'S SWING: the animal's own neck goes into what stands on its back ----------------------------------------------------------------
The vanilla neck is a box that turns about the base of the neck, and the blanket, the saddle's front rail and the girth stand just behind it, so
a neck tipped back (a llama looking up at a player on a slope) or forward (a donkey looking down at one below it) goes into their front edges,
as it goes into the vanilla body. `check_fit` holds the still cubes of the harness against the part of the animal that turns away from them
(`neck_table`: the neck against the shells, and the halter's and the collar's cubes, which turn with the head, against the body) to
`NECK_LIMIT` (2.0 px beyond what the pair does at rest) over the look pitch `HEAD_CLEAR`, in steps of 5 degrees, standing and walking: the
donkey from 45 degrees up to 60 down, the llama from 20 degrees up to 60 down (the pitch is the state's xRot: negative looks up). Over those
ranges the worst is the donkey's blanket going 1.4 px into the neck at 45 degrees down and the llama's going 1.8 px at 20 degrees up (her
saddle's front rail 1.6, her collar 1.0 into the body). Past them the neck goes further in and the check does not hold it: the llama looking
up 35 degrees, 3.5 px into the saddle's front rail, up to 4.6 px at 60 degrees (her blanket, pad and girth); the donkey stays under 1.3 px
as far as 60 degrees up, and looking 70 degrees or more down puts its bell's tiers up to 1 px into the ring that hangs under the chin and,
from 75, its lip up to 1.2 px into the muzzle. What goes into the neck is hidden inside it (the cloth and the rail read as tucked under the
neck; the llama's neck is 6 px deep, the donkey's 7, and nothing reaches a far face).
"""
import copy
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import numpy as np
from PIL import Image

import carrier_kit as K
import carrier_paint as P
from carrier_kit import bx, part, SCALE

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/trade')
MODELS = os.path.join(ROOT, 'art/models')

# ================================================================== what the animals fix (the jar's numbers, px, model space)

# The donkey and the mule (AbstractEquineModel.createBodyMesh + DonkeyModel.modifyMesh): the body box x +-5, y 3..13, z -12..10 (inflated
# 0.05); the chests, 8 x 8 x 3 turned a quarter about y at (+-6, 3, 5), cover x 5..8, y 3..11, z 1..9; head_parts at (0, 4, -12) leans 30
# degrees; the tail at (0, 6, 7); the legs at (+-4, 14, 7) and (+-4, 14, -10). Everything is scaled by DONKEY_SCALE / MULE_SCALE about
# the ground.
DONKEY_BODY = (5.05, 2.95, 13.05, -12.05, 10.05)         # half width, top, bottom, front, back
DONKEY_CHEST = (5.0, 8.0, 3.0, 11.0, 1.0, 9.0)           # the left chest: x0, x1, y0, y1, z0, z1 (the right one mirrored)
# The llama (LlamaModel.createBodyLayer): the body box (turned 90 deg: x +-6, y 2..12, z -8..10), the chests at x 5.5..8.5, y 3..11,
# z -2..6, the neck x +-4, z -12..-6 from y -9 to 9 about (0, 7, -6), the legs at (+-3.5, 10, 6) and (+-3.5, 10, -5).
LLAMA_BODY = (6.0, 2.0, 12.0, -8.0, 10.0)
LLAMA_CHEST = (5.5, 8.5, 3.0, 11.0, -2.0, 6.0)

# The pannier kit, the same size on every animal (so that one set of pictures dresses them all)
PN_W, PN_H, PN_D = 5.5, 9.8, 9.4          # the basket's body: across the flank, deep, along the flank
PN_RAIL = 0.75                            # the rim stands this far over the body
PN_LINING = 0.4                           # the straw lies this far under the rim's top... below the body's top
BELLY = 0.14                              # the basket swells by this much (of its width) from empty to full

COLOURS = {                               # the six booker colours (the pennants, the blanket): ARGB
    'red': 0xFFC43430, 'blue': 0xFF3864BE, 'green': 0xFF3E964C, 'amber': 0xFFD69628, 'violet': 0xFF8448BE, 'teal': 0xFF2C9692,
}

TEX_W = 128                               # declared texture size; the height is what the packing needs (see build)


# ================================================================== a side of the animal

class Side:
    """The left (+x) or the right (-x) side: a cube written for the left is made for the right as its mirrored copy, drawn
    from the left's picture (the same place in the texture), so that a pair costs one picture. Names get '_l' / '_r'."""

    def __init__(self, sign):
        self.s = 1 if sign > 0 else -1
        self.sfx = '_l' if self.s > 0 else '_r'
        self.word = 'left' if self.s > 0 else 'right'

    def n(self, base):
        return base + self.sfx

    def x(self, v):
        return self.s * v

    def cube(self, base, x0, y0, z0, x1, y1, z1, paint=None, faces=None, group=None):
        """The cube written for the left side, as this side has it. [group] names the picture it is drawn from (cubes of
        the same size can share one); the default is the left cube's own name."""
        g = group or base + '_l'
        if self.s > 0:
            return bx(x0, y0, z0, x1, y1, z1, base + '_l', paint=paint, faces=faces, share=g)
        mf = None if faces is None else tuple({'left': 'right', 'right': 'left'}.get(f, f) for f in faces)
        return bx(-x1, y0, z0, -x0, y1, z1, base + '_r', faces=mf, share=g, mirror=True)


def one(name, x0, y0, z0, x1, y1, z1, paint=None, faces=None, group=None):
    """A cube on the middle line (a single one, not a pair)."""
    return bx(x0, y0, z0, x1, y1, z1, name, paint=paint, faces=faces, share=group)


# ================================================================== the skeletons: every part the vanilla model looks up, empty

def equine_skeleton():
    """The donkey's and mule's parts as AbstractEquineModel finds them: body (with its tail and the two chests), head_parts and
    the four legs, at the vanilla pivots. Returns the parts by name; the chests are made by the pannier kit."""
    body = part('body', (0, 11, 5))
    tail = part('tail', (0, 6, 7), parent=body, rot=(30, 0, 0))
    hp = part('head_parts', (0, 4, -12), rot=(30, 0, 0))
    legs = [part(n, (x, 14, z)) for n, x, z in (('right_hind_leg', -4, 7), ('left_hind_leg', 4, 7),
                                                  ('right_front_leg', -4, -10), ('left_front_leg', 4, -10))]
    return {'body': body, 'tail': tail, 'head_parts': hp, 'legs': legs}


def llama_skeleton():
    """The llama's parts as LlamaModel finds them: head (the neck's pivot), body, the legs; the chests are made by the pannier
    kit. (The body is turned a quarter about x in the vanilla layer, nothing of the harness hangs from it.)"""
    head = part('head', (0, 7, -6))
    body = part('body', (0, 5, 2), rot=(90, 0, 0))
    legs = [part(n, (x, 10, z)) for n, x, z in (('right_hind_leg', -3.5, 6), ('left_hind_leg', 3.5, 6),
                                                  ('right_front_leg', -3.5, -5), ('left_front_leg', 3.5, -5))]
    return {'head': head, 'body': body, 'legs': legs}


# ================================================================== the pannier kit (the same pictures dress every animal)

# The hanging point sits this far in from the basket's inner face (x), up from its body's top (y)
HANG_DX, HANG_DY = 0.93, -0.45
# The opening's middle, from the hanging point (x, y of the heap's base, z): the heap is built about it
TIER_BASE_DY = (-PN_LINING) - HANG_DY            # the lining's top, from the hanging point's y
OPEN_DX = 2.75 - HANG_DX


def pannier_side(s, xi, yt, zc, parent):
    """One pannier of an animal: the wrapper (named as the vanilla chest, `left_chest` / `right_chest`: the animal's model
    shows and hides it with the chest), hung at (xi + HANG_DX, yt + HANG_DY, zc), the basket `pannier_l` / `_r` on it
    and the three tiers of the heap `fill1_l` .. `fill3_r`. [xi] is the basket's inner face, [yt] the top of its body, [zc]
    the middle along the flank; (the right side is the left's mirror). Returns the wrapper."""
    hx, hy = xi + HANG_DX, yt + HANG_DY
    wrapper = part(f'{s.word}_chest', (s.x(hx), hy, zc), parent=parent)
    W, H, D = PN_W, PN_H, PN_D
    zf, zb = zc - D / 2, zc + D / 2
    basket = [
        s.cube('pn_body', xi, yt, zf, xi + W, yt + H, zb, P.wicker_painter('pn_body')),
        s.cube('pn_rail_f', xi, yt - PN_RAIL, zf, xi + W, yt, zf + 0.7, P.plait_painter('pn_rail_x', 'x'), group='pn_rail_x'),
        s.cube('pn_rail_b', xi, yt - PN_RAIL, zb - 0.7, xi + W, yt, zb, P.plait_painter('pn_rail_x', 'x'), group='pn_rail_x'),
        s.cube('pn_rail_i', xi, yt - PN_RAIL, zf + 0.7, xi + 0.7, yt, zb - 0.7, P.plait_painter('pn_rail_z', 'z'), group='pn_rail_z'),
        s.cube('pn_rail_o', xi + W - 0.7, yt - PN_RAIL, zf + 0.7, xi + W, yt, zb - 0.7, P.plait_painter('pn_rail_z', 'z'), group='pn_rail_z'),
        s.cube('pn_lining', xi + 0.7, yt - PN_LINING, zf + 0.7, xi + W - 0.7, yt, zb - 0.7, P.straw_painter('pn_lining')),
        # leather: two straps down the outer face, a band round the basket's middle (over them)
        s.cube('pn_sv_f', xi + W, yt - PN_RAIL - 0.05, zc - 2.9, xi + W + 0.22, yt + H - 0.6, zc - 2.1, P.leather_painter('pn_sv', 'y'), group='pn_sv'),
        s.cube('pn_sv_b', xi + W, yt - PN_RAIL - 0.05, zc + 2.1, xi + W + 0.22, yt + H - 0.6, zc + 2.9, P.leather_painter('pn_sv', 'y'), group='pn_sv'),
        s.cube('pn_band_o', xi + W, yt + 3.5, zf, xi + W + 0.3, yt + 4.4, zb, P.leather_painter('pn_band_o', 'z')),
        s.cube('pn_band_f', xi, yt + 3.5, zf - 0.22, xi + W + 0.3, yt + 4.4, zf, P.leather_painter('pn_band_e', 'x'), group='pn_band_e'),
        s.cube('pn_band_b', xi, yt + 3.5, zb, xi + W + 0.3, yt + 4.4, zb + 0.22, P.leather_painter('pn_band_e', 'x'), group='pn_band_e'),
    ]
    part(s.n('pannier'), (s.x(hx), hy, zc), basket, parent=wrapper)
    tier_parts(s, wrapper, xi, yt, zc)
    return wrapper


def tier_parts(s, wrapper, xi, yt, zc):
    """The heap: three tiers, each a part of its own standing on the straw (y = yt - PN_LINING) at the opening's middle, so that
    the animal's fill can grow them one after another: (1) a bundle of linen tied with cord, (2) two burlap sacks side by side,
    (3) a big sack lashed over them. Returns {name: part}."""
    yb = yt - PN_LINING
    cx = xi + 2.75
    rc = P.rope_painter('t_cord', P.RING_Y)
    out = {}
    # tier 1
    t1 = [
        s.cube('t1_bundle', xi + 1.0, yb - 1.45, zc - 3.4, xi + 4.5, yb, zc + 3.4, P.linen_painter('t1_bundle')),
        s.cube('t1_cord_a', xi + 0.88, yb - 1.55, zc - 1.95, xi + 4.62, yb, zc - 1.4, rc, group='t1_cord'),
        s.cube('t1_cord_b', xi + 0.88, yb - 1.55, zc + 1.4, xi + 4.62, yb, zc + 1.95, rc, group='t1_cord'),
        s.cube('t1_knot', xi + 2.3, yb - 1.65, zc - 1.95, xi + 3.2, yb - 1.4, zc - 1.4, P.rope_painter('t_knot', 'x')),
    ]
    out['fill1'] = part(s.n('fill1'), (s.x(cx), yb, zc), t1, parent=wrapper)
    # tier 2: two sacks
    t2 = []
    for tag, z0, z1 in (('a', zc - 3.95, zc - 0.15), ('b', zc + 0.15, zc + 3.7)):
        ins = 0.45
        t2 += [
            s.cube(f't2{tag}_body', xi + 0.8, yb - 2.0, z0, xi + 4.7, yb, z1, P.burlap_painter(f't2{tag}_body', 'body')),
            s.cube(f't2{tag}_top', xi + 0.8 + ins, yb - 2.75, z0 + ins, xi + 4.7 - ins, yb - 2.0, z1 - ins, P.burlap_painter(f't2{tag}_top', 'top', mark=False)),
            s.cube(f't2{tag}_tie', xi + 0.8 + ins - 0.18, yb - 2.2, z0 + ins - 0.18, xi + 4.7 - ins + 0.18, yb - 1.9, z1 - ins + 0.18, P.rope_painter('t_tie', P.RING_Y)),
        ]
    out['fill2'] = part(s.n('fill2'), (s.x(cx), yb, zc), t2, parent=wrapper)
    # tier 3: the big sack and two lashings
    t3 = [
        s.cube('t3_body', xi + 0.65, yb - 4.35, zc - 3.5, xi + 4.85, yb - 2.3, zc + 3.5, P.burlap_painter('t3_body', 'body')),
        s.cube('t3_top', xi + 1.15, yb - 4.85, zc - 2.95, xi + 4.35, yb - 4.35, zc + 2.95, P.burlap_painter('t3_top', 'top', mark=False)),
        s.cube('t3_rope_a', xi + 0.5, yb - 4.55, zc - 1.85, xi + 5.0, yb - 2.1, zc - 1.3, rc, group='t3_rope'),
        s.cube('t3_rope_b', xi + 0.5, yb - 4.55, zc + 1.3, xi + 5.0, yb - 2.1, zc + 1.85, rc, group='t3_rope'),
    ]
    out['fill3'] = part(s.n('fill3'), (s.x(cx), yb, zc), t3, parent=wrapper)
    return out



# ================================================================== the bell, the same on every animal

BELL_STEPS = (                      # (name, top and bottom y from the pivot, half width): a cast bell stacked from boxes, waisted and flared at the lip
    ('bl_loop', -0.4, 0.4, 0.5),
    ('bl_cap', 0.4, 1.0, 0.9),
    ('bl_shoulder', 1.0, 1.7, 1.2),
    ('bl_waist', 1.7, 2.7, 1.4),
    ('bl_flare', 2.7, 3.3, 1.75),
    ('bl_lip', 3.3, 3.9, 2.2),
)
CLAPPER_DY = 1.1                    # the clapper hangs from this far under the bell's pivot (inside the cap)
BELL_BOTTOM = 3.9


def bell_parts(parent, py, pz, local):
    """The bell and its clapper at pivot (0, py, pz) in [parent]'s frame: the bell is brass from a loop to a lip, the clapper an iron
    rod and ball hung inside it, its ball showing under the lip."""
    cubes = [one(n, -hw, py + y0, pz - hw, hw, py + y1, pz + hw, P.brass_painter(n) if n == 'bl_loop' else P.bell_painter(n, n[3:]), group=n)
             for n, y0, y1, hw in BELL_STEPS]
    bell = part('bell', (0, py, pz), cubes, parent=parent, local=local)
    # the clapper in the bell's own frame: its pivot a little under the bell's, inside the cap
    clapper = [one('cl_rod', -0.2, CLAPPER_DY, -0.2, 0.2, CLAPPER_DY + 2.8, 0.2, P.iron_painter('cl_rod'), group='cl_rod'),
               one('cl_ball', -0.5, CLAPPER_DY + 2.6, -0.5, 0.5, CLAPPER_DY + 3.6, 0.5, P.iron_painter('cl_ball'), group='cl_ball')]
    part('bell_clapper', (0, CLAPPER_DY, 0), clapper, parent=bell, local=True)
    return bell


# ================================================================== the donkey's and the mule's harness

L, R = Side(+1), Side(-1)
TASSEL_Z = (-7.7, -6.4, -5.1, -1.4, -0.2)               # along the flap, clear of the girth (z -3.45..-2.05) and the pannier (from z 0.3)
TASSEL_Z_LLAMA = (-6.7, -5.5, 7.4, 8.3)


def equine_trees():
    """The donkey's and mule's harness as two layers' parts: (the leather one: saddle, straps, bell, halter, crupper, the panniers
    and their heaps; the wool one: the blanket and its tassels). Each is the list of root parts of a full skeleton."""
    # ---------------- leather, wicker, rope, brass
    sk = equine_skeleton()
    body, tail, hp = sk['body'], sk['tail'], sk['head_parts']
    cubes = []
    for s in (L, R):
        cubes += [s.cube('sd_bar', 2.5, 1.35, -1.8, 4.3, 2.3, 8.6, P.wood_painter('sd_bar', 'z')),
                  s.cube('sd_pomp', 2.5, 0.1, -1.8, 4.3, 1.35, -0.9, P.wood_painter('sd_post', 'y')),
                  s.cube('sd_cant', 2.5, 0.35, 7.7, 4.3, 1.35, 8.6, P.wood_painter('sd_post', 'y'))]
    cubes += [one('sd_pomp_rail', -4.3, 0.1, -1.8, 4.3, 0.85, -0.9, P.wood_painter('sd_rail', 'x')),
              one('sd_cant_rail', -4.3, 0.35, 7.7, 4.3, 1.05, 8.6, P.wood_painter('sd_rail', 'x')),
              one('sd_pad', -2.5, 1.45, -0.9, 2.5, 2.3, 7.7, P.pad_painter('sd_pad'))]
    part('saddle', (0, 2.3, 3.4), cubes, parent=body)
    part('hang', (0, 2.0, 5.0), [s.cube('hg_top', 3.0, 1.7, 4.5, 6.2, 2.3, 5.5, P.leather_painter('hg_top', 'x')) for s in (L, R)], parent=body)
    part('girth', (0, 8.0, -2.75), [one('gi_ring', -5.88, 1.9, -3.45, 5.88, 13.4, -2.05, P.leather_painter('gi_ring', P.RING_Y)),
                                    L.cube('gi_buckle', 5.88, 7.9, -3.3, 6.25, 9.5, -2.2, P.buckle_painter('gi_buckle'))], parent=body)
    breast = part('breast', (0, 6.5, -9.0), [one('br_band', -5.45, 5.7, -12.4, 5.45, 7.3, -7.5, P.leather_painter('br_band', P.BAND_Z))], parent=body)
    hanger = part('hanger', (0, 7.1, -12.4), [one('br_arm', -0.5, 6.7, -14.5, 0.5, 7.5, -12.4, P.leather_painter('br_arm', 'z'))], parent=breast)
    bell_parts(hanger, 7.9, -14.25, local=False)
    part('crupper', (0, 3.0, 9.0), [one('cr_top', -0.55, 2.35, 8.4, 0.55, 3.05, 10.6, P.leather_painter('cr_top', 'z')),
                                    one('cr_rear', -0.55, 2.9, 10.1, 0.55, 6.0, 10.5, P.leather_painter('cr_rear', 'y'))], parent=body)
    # the dock loop turns with the tail: written in the tail's own frame
    part('crupper_ring', (0, 0, 0), [one('cr_loop', -1.9, 0.6, -0.4, 1.9, 1.6, 4.4, P.leather_painter('cr_loop', P.RING_Y)),
                                      one('cr_ring', 1.9, 0.2, 1.0, 2.25, 2.0, 2.8, P.ring_painter('cr_ring'))], parent=tail, local=True)
    ha = [one('ha_nose', -2.3, -11.3, -5.9, 2.3, -5.7, -4.7, P.leather_painter('ha_nose', P.RING_Y)),
          one('ha_head', -3.3, -11.3, 1.2, 3.3, -5.7, 2.2, P.leather_painter('ha_head', P.RING_Y))]
    for s in (L, R):
        ha += [s.cube('ha_cheek_a', 1.98, -9.1, -5.9, 2.2, -8.3, -2.3, P.leather_painter('ha_cheek_a', 'z')),
               s.cube('ha_cheek_b', 2.0, -9.1, -2.3, 3.3, -8.3, 1.2, P.leather_painter('ha_cheek_b', 'z')),
               s.cube('ha_ring', 2.3, -9.3, -6.1, 2.8, -7.8, -4.6, P.ring_painter('ha_ring'))]
    halter = part('halter', (0, 0, 0), ha, parent=hp, local=True)
    part('halter_ring', (0, -5.7, -5.3), [one('hr_card', -0.25, -5.7, -6.05, 0.25, -4.2, -4.55, P.ring_painter('hr_card'))], parent=halter, local=True)
    for s in (L, R):
        pannier_side(s, 5.12, 2.75, 5.0, body)
    leather = [body, hp] + sk['legs']

    # ---------------- wool
    sk = equine_skeleton()
    body = sk['body']
    for n, x in (('left_chest', 6.0), ('right_chest', -6.0)):                     # (vanilla looks them up; nothing of the wool hangs here)
        part(n, (x, 3.0, 5.0), parent=body)
    blanket = part('blanket', (0, 2.6, 1.4), [one('bl_slab', -5.78, 2.3, -8.6, 5.78, 2.95, 9.2, P.wool_painter('bl_slab', 'top'))], parent=body)
    for s in (L, R):
        flap = part(s.n('flap'), (s.x(5.45), 2.7, 1.4), [s.cube('bl_flap', 5.18, 2.6, -8.2, 5.72, 7.6, 8.8, P.wool_painter('bl_flap', 'flap'))], parent=blanket)
        for i, z in enumerate(TASSEL_Z):
            part(f'tassel{s.sfx}{i}', (s.x(5.45), 7.45, z),
                 [s.cube(f'ts{i}', 5.1, 7.45, z - 0.35, 5.8, 9.35, z + 0.35, P.tassel_painter('ts'), group='ts')], parent=flap)
    wool = [body, sk['head_parts']] + sk['legs']
    return leather, wool


# ================================================================== the llama's harness

def llama_trees():
    """The llama's harness (the leather layer, the wool layer): the same pieces on the llama's wider, flatter back, the bell on a
    collar round the neck's foot instead of a breast strap. The body is not posed by the model, so the parts hang from the root."""
    sk = llama_skeleton()
    head = sk['head']
    roots = []
    dy, dz = -0.95, -3.0                    # the saddle: the donkey's, on the llama's lower back, centred on its panniers
    cubes = []
    for s in (L, R):
        cubes += [s.cube('sd_bar', 3.0, 1.35 + dy, -1.8 + dz, 4.8, 2.3 + dy, 8.6 + dz, P.wood_painter('sd_bar', 'z')),
                  s.cube('sd_pomp', 3.0, 0.1 + dy, -1.8 + dz, 4.8, 1.35 + dy, -0.9 + dz, P.wood_painter('sd_post', 'y')),
                  s.cube('sd_cant', 3.0, 0.35 + dy, 7.7 + dz, 4.8, 1.35 + dy, 8.6 + dz, P.wood_painter('sd_post', 'y'))]
    cubes += [one('sd_pomp_rail_w', -4.8, 0.1 + dy, -1.8 + dz, 4.8, 0.85 + dy, -0.9 + dz, P.wood_painter('sd_rail', 'x')),
              one('sd_cant_rail_w', -4.8, 0.35 + dy, 7.7 + dz, 4.8, 1.05 + dy, 8.6 + dz, P.wood_painter('sd_rail', 'x')),
              one('sd_pad_w', -3.0, 1.45 + dy, -0.9 + dz, 3.0, 2.3 + dy, 7.7 + dz, P.pad_painter('sd_pad'))]
    roots.append(part('saddle', (0, 2.3 + dy, 3.4 + dz), cubes))
    roots.append(part('hang', (0, 1.05, 2.0), [s.cube('hg_top_w', 3.5, 0.75, 1.5, 6.9, 1.35, 2.5, P.leather_painter('hg_top', 'x')) for s in (L, R)]))
    roots.append(part('girth', (0, 7.0, -3.6), [one('gi_ring_w', -6.88, 1.1, -4.3, 6.88, 12.45, -2.9, P.leather_painter('gi_ring', P.RING_Y)),
                                                  L.cube('gi_buckle', 6.88, 8.0, -4.2, 7.25, 9.6, -3.1, P.buckle_painter('gi_buckle'))]))
    # the collar round the neck's foot and the bell from it (in the head's own frame: the neck leans and turns with it)
    co = part('collar', (0, 0, 0), [one('co_ring', -4.35, -3.0, -6.35, 4.35, -1.2, 0.35, P.pad_painter('co_ring')),
                                     one('co_boss', -0.9, -2.9, -6.8, 0.9, -1.3, -6.35, P.brass_painter('co_boss')),
                                     one('co_arm', -0.5, -1.4, -9.5, 0.5, -0.6, -6.8, P.leather_painter('co_arm', 'z'))], parent=head, local=True)
    bell_parts(co, -0.1, -9.0, local=True)
    for s in (L, R):
        roots.append(pannier_side(s, 6.12, 1.8, 2.0, None))
    leather = [head, sk['body']] + roots + sk['legs']

    sk = llama_skeleton()
    chests = [part(n, (x, 3.0, 2.0)) for n, x in (('left_chest', 7.0), ('right_chest', -7.0))]
    blanket = part('blanket', (0, 1.7, 1.4), [one('bl_slab_w', -6.38, 1.35, -7.5, 6.38, 2.0, 9.2, P.wool_painter('bl_slab_w', 'top'))])
    for s in (L, R):
        flap = part(s.n('flap'), (s.x(6.35), 1.8, 1.4), [s.cube('bl_flap_w', 6.08, 1.7, -7.2, 6.62, 7.1, 8.8, P.wool_painter('bl_flap_w', 'flap'))], parent=blanket)
        for i, z in enumerate(TASSEL_Z_LLAMA):
            part(f'tassel{s.sfx}{i}', (s.x(6.35), 6.95, z),
                 [s.cube(f'ts{i}', 6.0, 6.95, z - 0.35, 6.7, 8.85, z + 0.35, P.tassel_painter('ts'), group='ts')], parent=flap)
    wool = [sk['head'], sk['body'], blanket] + chests + sk['legs']
    return leather, wool



# ================================================================== packing and painting: one texture for all six layers

LAYERS = ('donkey', 'donkey_wool', 'llama', 'llama_wool')           # (the mule is the donkey's mesh at its own scale)


def build():
    """The four meshes, packed into one texture and painted: ({layer: Model}, the HD texture, its declared height)."""
    d_leather, d_wool = equine_trees()
    l_leather, l_wool = llama_trees()
    models = {
        'donkey': K.Model('pack_harness_donkey', TEX_W, 0, d_leather),
        'donkey_wool': K.Model('pack_harness_donkey_wool', TEX_W, 0, d_wool),
        'llama': K.Model('pack_harness_llama', TEX_W, 0, l_leather),
        'llama_wool': K.Model('pack_harness_llama_wool', TEX_W, 0, l_wool),
    }
    cubes = [c for m in models.values() for c in m.all_cubes()]
    used = K.pack(cubes, TEX_W, 512, 'pack_harness')
    height = (used + 7) // 8 * 8
    for m in models.values():
        m.tex_h = height
    return models, K.paint_texture(cubes, TEX_W, height), height



# ================================================================== the rig: how the parts move (`Rig` in the Kotlin is this, line by line)

DEG = math.pi / 180.0

# Degrees unless said: the panniers sway about z and pitch about x with the stride (and breathe at a stand); a full one sways less
ROLL, PITCH, IDLE_ROLL, LOAD_DAMP = 2.6, 1.2, 0.35, 0.35
BOB = 0.12                                  # px: the heap settles this far with each step
BELL_MAX, BELL_IDLE, BELL_PHASE = 24.0, 2.2, 0.9
BELL_MAX_LLAMA = 17.0                       # the llama's bell hangs where the front legs swing: it may swing this far forward at most ...
KNOCK_DAMP_LLAMA = 1.0                      # ... and a knock adds that much less of its swing the faster she walks (a full gait takes it all: the legs are in the way)
BELL_FADE = (75.0, 95.0, 0.1)               # the donkey's bell swing fades to a tenth over the head's pitch (degrees, 30 at rest) from 75 to 95: the muzzle comes down in front of it
CLAPPER_X, CLAPPER_Z, CLAPPER_LAG = 22.0, 14.0, 1.3
FLAP_LIFT = 4.0
TASSEL_BASE, TASSEL_SPEED, TASSEL_LAG = 3.0, 16.0, 0.55
RING_SWING = 9.0
SLIDE_X = 5.0                               # px: how far the bell rides along the breast strap (toward the animal's left) to clear the neck
SLIDE_DIP = (0.3, 2.2)                      # ... over this much of the head's dip (px: the eat animation lowers it by up to 7) ...
SLIDE_UP = (15.0, 3.0)                      # ... or as the head's pitch (degrees; 30 at rest) goes down through this: the neck standing up
FILL_STEPS = 3                              # fill runs 0 (empty) to 3 (full): four looks

# The layer's own springs (Trade D1's frame: what starts, stops, turns, a knock and a step taken add to the stride) come in as optional
# arguments, each kept where the animal leaves the load room (`fit`): up to what the stride itself swings a part it passes untouched, beyond
# that it eases off toward the limit and never past it. The limits are the room measured over the fit poses (`check_fit` holds them).
TROT_GAIN = 0.5                             # `trot` 0 to 1: the stride's sway, lift and settle are up to this much bigger
ROLL_SP_MAX = 1.0                           # ... but a basket's sideways sway stops growing here (the flank is that close) ...
TASSEL_SP_MAX = 1.05                        # ... and the tassels' swing here
GAIN_PITCH, GAIN_ROLL, GAIN_DANGLE = 1.2, 1.4, 0.5      # how much of a spring's angle a basket takes (fore-aft, sideways) and a tassel (the layer's weights)
PITCH_LIMIT = (-3.5, 2.4)                   # a basket's fore-aft swing about its hanging point, degrees (negative: forward): the girth ring and the tail
ROLL_IN, ROLL_OUT = 3.2, 14.0               # its sideways swing, degrees: in toward the flank (the basket on that side presses on it), out (free)
TASSEL_LIMIT = (-22.0, 26.0)                # a tassel's swing, degrees (negative: forward): the girth ring and the pannier behind it
KNOCK_MAX = 24.0                            # a knock swings the bell this much further forward at most, degrees
POP_X, POP_Y = 0.04, 0.03                   # a step taken squashes a heap: x and z grow by POP_X, y shrinks by POP_Y


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def fit(x, lo, knee_lo, knee_hi, hi):
    """[x] kept between [lo] and [hi] (lo < knee_lo <= 0 <= knee_hi < hi): as it is between the knees (what the stride alone swings), beyond
    them eased toward the limit by tanh, never past it."""
    if x > knee_hi:
        return knee_hi + (hi - knee_hi) * math.tanh((x - knee_hi) / (hi - knee_hi))
    if x < knee_lo:
        return knee_lo - (knee_lo - lo) * math.tanh((knee_lo - x) / (knee_lo - lo))
    return x


def tier_t(fill, k):
    """How much of tier [k] (1..3) shows at [fill]: 0 to 1, eased."""
    return K.smooth(clamp(fill - (k - 1), 0.0, 1.0))


def pannier_pose(fill, walk_pos, walk_speed, age, trot=0.0, pitch=0.0, roll=0.0, pop=0.0):
    """The pose of both panniers (their wrappers, baskets and the three tiers of each heap): {part name: pose} as `K.faces_of`
    takes it. The pair sways as one about the hanging points (a roll the same way on both sides), pitches a little with each
    step, swells with the load, and the heap grows tier by tier from its base and settles with each step. The springs: [trot]
    (0 to 1) lengthens the stride; [pitch] and [roll] (degrees, as the layer's own springs give them: positive, the baskets lag
    backward, and swing to the animal's right) add to the pair's sway, the basket on the side the swing carries in toward the flank
    pressing on it (it gets the little room there is, `ROLL_IN`) and the other swinging free (`ROLL_OUT`); [pop] (0 to 1) squashes
    the heap, as a step taken does."""
    w = walk_pos * 0.6662
    sp = min(walk_speed, 1.0) * (1.0 + TROT_GAIN * trot)
    load = clamp(fill / FILL_STEPS, 0.0, 1.0)
    damp = 1.0 - LOAD_DAMP * load
    spr = min(sp, ROLL_SP_MAX)
    stride_roll = -(ROLL * spr * math.sin(w) + IDLE_ROLL * math.sin(age * 0.071)) * damp
    knee_roll = (ROLL * spr + IDLE_ROLL) * damp
    knee_pitch = (PITCH * sp + 0.15) * damp
    out = {}
    for sg, side in ((1, 'l'), (-1, 'r')):
        stride_pitch = (PITCH * sp * math.sin(2 * w + 0.7 + (0.0 if sg > 0 else 0.5 * math.pi)) + 0.15 * math.sin(age * 0.053 + sg * 1.1)) * damp
        p = fit(stride_pitch + GAIN_PITCH * pitch, PITCH_LIMIT[0], -knee_pitch, knee_pitch, PITCH_LIMIT[1])
        # a turn about z carries the left basket's foot in toward the flank when it is positive, the right one's when it is negative
        inward = fit(sg * (stride_roll + GAIN_ROLL * roll), -ROLL_OUT, -knee_roll, knee_roll, ROLL_IN)
        out['left_chest' if sg > 0 else 'right_chest'] = {'rot': (p, 0.0, sg * inward)}
        out['pannier_' + side] = {'scale': (1.0 + BELLY * load, 1.0, 1.0)}
        for k in (1, 2, 3):
            t = tier_t(fill, k)
            if t < 0.02:
                out[f'fill{k}_{side}'] = {'hide': True}
            else:
                bob = -BOB * sp * math.sin(2 * w + 0.7 + 0.35 * k) * t
                out[f'fill{k}_{side}'] = {'scale': ((0.6 + 0.4 * t) * (1.0 + POP_X * pop), t * (1.0 - POP_Y * pop), (0.6 + 0.4 * t) * (1.0 + POP_X * pop)),
                                          'move': (0.0, bob, 0.0)}
    return out


def bell_fade(head_x):
    """How much of its swing the donkey's bell keeps with the head at [head_x] (degrees, 30 at rest): all of it, until the muzzle comes
    down in front of the chest, then a tenth."""
    return 1.0 - (1.0 - BELL_FADE[2]) * K.smooth((head_x - BELL_FADE[0]) / (BELL_FADE[1] - BELL_FADE[0]))


def bell_pose(walk_pos, walk_speed, age, hung_from_x=0.0, knock=0.0, max_swing=BELL_MAX, fade=1.0, knock_damp=0.0):
    """The bell and its clapper. The bell swings forward only (the chest is behind it): from 0 to -[max_swing] degrees about x, once a
    step, harder with speed; at a stand it breathes forward a little. The clapper hangs inside it, a pendulum that lags the bell
    and runs round an ellipse, never past what the lip allows. [hung_from_x]: the pitch (degrees) of the part the bell hangs
    from when that turns (the llama's head), taken off so that the bell hangs plumb. [knock] (degrees, forward, 0 or more: a
    bell knocked by a start or a stop) swings it further forward, softly up to `KNOCK_MAX` more, and sets the clapper going.
    [fade] (0 to 1, [bell_fade]) is how much of the swing the head's pose leaves it; [knock_damp] how much of a knock the stride takes away
    at a full gait."""
    w = walk_pos * 0.6662
    g = clamp(walk_speed / 0.7, 0.0, 1.0)
    u = 0.5 + 0.5 * math.sin(2 * w + BELL_PHASE)
    breathe = 0.5 + 0.5 * math.sin(age * 0.083)
    extra = KNOCK_MAX * math.tanh(max(knock, 0.0) / KNOCK_MAX) * (1.0 - knock_damp * g)
    k = extra / KNOCK_MAX
    swing = -((BELL_IDLE * (1 - g) * breathe + max_swing * g * u) + extra) * fade
    ph = 2 * w + BELL_PHASE - CLAPPER_LAG
    cx = clamp(CLAPPER_X * g * math.sin(ph) + 3.0 * (1 - g) * math.sin(age * 0.21) + 0.8 * CLAPPER_X * k * math.sin(age * 0.9), -CLAPPER_X, CLAPPER_X)
    cz = clamp(CLAPPER_Z * g * math.cos(ph) + 2.0 * (1 - g) * math.sin(age * 0.17 + 1.0) + 0.8 * CLAPPER_Z * k * math.cos(age * 0.9 + 0.5), -CLAPPER_Z, CLAPPER_Z)
    return {'bell': {'rot': (swing - hung_from_x, 0.0, 0.0)}, 'bell_clapper': {'rot': (cx, 0.0, cz)}}


def bell_beat(walk_pos):
    """Which beat of the bell it is: it steps up each time the bell reaches the top of its swing (twice a stride), the sound's cue."""
    return math.floor((2 * walk_pos * 0.6662 + BELL_PHASE - 0.5 * math.pi) / (2 * math.pi))


def bell_slide(head_x, dip):
    """How far the bell rides along the breast strap (px, toward the animal's left) so that the neck does not go through it: the
    neck comes down over the chest when the head grazes ([dip]: how far the head's pivot is lowered, px, 0 to 7) and stands up
    in front of it when the head is raised ([head_x]: the head's pitch, degrees, 30 at rest). Either slips the bell aside
    along the strap, and it comes back as the head comes back."""
    down = K.smooth((dip - SLIDE_DIP[0]) / (SLIDE_DIP[1] - SLIDE_DIP[0]))
    up = K.smooth((SLIDE_UP[0] - head_x) / (SLIDE_UP[0] - SLIDE_UP[1]))
    return SLIDE_X * max(down, up)


def halter_pose(walk_pos, walk_speed, age, head_x):
    """The halter's chin ring hangs plumb below the chin whatever the head does ([head_x]: the head's pitch, degrees) and swings
    with the stride."""
    w = walk_pos * 0.6662
    swing = RING_SWING * min(walk_speed, 1.0) * math.sin(w + 0.4) + 3.0 * math.sin(age * 0.13)
    return {'halter_ring': {'rot': (clamp(-head_x + swing, -40.0, 40.0), 0.0, 0.0)}}


def wool_pose(walk_pos, walk_speed, age, tassels, trot=0.0, dangle=0.0):
    """The blanket's two flaps (they lift off the flank with speed and breathe) and their [tassels] (how many each): pendulums,
    a wave running back along the hem. The springs: [trot] (0 to 1) lengthens the stride; [dangle] (degrees, positive: they trail
    backward) is what a start or a stop adds to the tassels' swing, as much as the girth ring in front of them and the pannier behind
    them leave (`TASSEL_LIMIT`)."""
    w = walk_pos * 0.6662
    sp = min(walk_speed, 1.0) * (1.0 + TROT_GAIN * trot)
    lift = FLAP_LIFT * sp * (0.5 + 0.5 * math.sin(1.7 * w + 0.3)) + 0.5 * math.sin(age * 0.049)
    amp = TASSEL_BASE + TASSEL_SPEED * min(sp, TASSEL_SP_MAX)
    knee = amp + 1.6
    out = {}
    for sg, side in ((1, 'l'), (-1, 'r')):
        out['flap_' + side] = {'rot': (0.0, 0.0, -sg * lift)}
        for i in range(tassels):
            ph = 2 * w - TASSEL_LAG * i
            xr = fit(amp * math.sin(ph + 0.4 * sg) + 1.6 * math.sin(age * 0.09 + i * 0.8) + GAIN_DANGLE * dangle, TASSEL_LIMIT[0], -knee, knee, TASSEL_LIMIT[1])
            zr = -sg * (2.0 + 0.35 * amp * (0.5 + 0.5 * math.sin(ph + 1.3)))
            out[f'tassel_{side}{i}'] = {'rot': (xr, 0.0, zr)}
    return out


def harness_pose(kind, fill, walk_pos=0.0, walk_speed=0.0, age=0.0, trot=0.0, pitch=0.0, roll=0.0, dangle=0.0, knock=0.0, pop=0.0, **vanilla):
    """The pose of the whole dressed animal ([kind] 'donkey' or 'llama'): the vanilla model's own (`K.equine_pose` or `K.llama_pose`,
    with [vanilla]'s head and rear-up arguments) and the harness's, for every layer of that kind at once. [trot], [pitch], [roll],
    [dangle], [knock] and [pop] are the layer's springs (see `pannier_pose`, `bell_pose` and `wool_pose`); all 0 gives the stride alone."""
    if kind == 'donkey':
        pose = K.equine_pose(walk_pos, walk_speed, age, **vanilla)
        head_x = pose['head_parts']['rot'][0]
        pose['hanger'] = {'move': (bell_slide(head_x, pose['head_parts']['move'][1]), 0.0, 0.0)}
        pose.update(bell_pose(walk_pos, walk_speed, age, knock=knock, fade=bell_fade(head_x)))
        pose.update(halter_pose(walk_pos, walk_speed, age, head_x))
        pose.update(wool_pose(walk_pos, walk_speed, age, len(TASSEL_Z), trot, dangle))
    else:
        pose = K.llama_pose(walk_pos, walk_speed, **vanilla)
        head_x = pose['head']['rot'][0]
        pose.update(bell_pose(walk_pos, walk_speed, age, head_x, knock, max_swing=BELL_MAX_LLAMA, knock_damp=KNOCK_DAMP_LLAMA))
        pose.update(wool_pose(walk_pos, walk_speed, age, len(TASSEL_Z_LLAMA), trot, dangle))
    pose.update(pannier_pose(fill, walk_pos, walk_speed, age, trot, pitch, roll, pop))
    return pose


def fill_ease(a, b, t):
    """The fill going from [a] to [b] as [t] runs 0 to 1 (over 6 ticks): eased out, with a slight overshoot that settles."""
    c = t - 1.0
    e = 1.0 + 1.9 * c * c * c + 0.9 * c * c
    return a + (b - a) * (e if t < 1.0 else 1.0)


def fill_step(used, total):
    """How full the baskets look for [used] of [total] slots taken: 0 empty, 1 a quarter (under a third), 2 a half, 3 full."""
    if total <= 0 or used <= 0:
        return 0
    f = used / total
    return 1 if f <= 0.34 else 2 if f <= 0.67 else 3



# ================================================================== the numbers read off the built meshes (so the contract cannot drift from the cubes)

def measure(models):
    """The numbers the renderer works from, measured on the built donkey and llama harness: the hanging points, where the heap's
    top stands over the rim at each fill, the opening's middle, the bell's pivot."""
    out = {}
    for kind in ('donkey', 'llama'):
        m = models[kind]
        wrap = K.find(m, 'left_chest')
        basket = K.find(m, 'pannier_l')
        rim = min(basket.abs[1] + c.origin[1] for c in basket.cubes if c.name.startswith('pn_rail'))
        lin = next(c for c in basket.cubes if c.name == 'pn_lining_l')
        flat = {1: ('t1_bundle_l',), 2: ('t2a_top_l', 't2b_top_l'), 3: ('t3_top_l',)}       # the flat tops the goods rest on (not the knot or the ties)
        tops = [min(K.find(m, f'fill{k}_l').abs[1] + c.origin[1] for c in K.find(m, f'fill{k}_l').cubes if c.name in flat[k]) for k in (1, 2, 3)]
        out[kind] = {
            'hang': tuple(round(v, 4) for v in wrap.abs),
            'rim': round(rim, 4),
            'heap': (0.0,) + tuple(round(rim - t, 4) for t in tops),
            'opening': (round(basket.abs[0] + lin.origin[0] + lin.size[0] / 2 - wrap.abs[0], 4), round(rim - wrap.abs[1], 4),
                        round(basket.abs[2] + lin.origin[2] + lin.size[2] / 2 - wrap.abs[2], 4)),
            'bell': tuple(round(v, 4) for v in K.find(m, 'bell').abs),
            'clapper': tuple(round(v, 4) for v in K.find(m, 'bell_clapper').abs),
        }
    return out


# Up to three goods show on top of a heap, at these places (x and z from the opening's middle, px; yaw and tilt, degrees), for the
# left pannier: the right one's are the mirror (x and yaw negated). A good is drawn this big (of a block).
GOODS = ((0.35, -2.5, 25.0, 6.0), (-0.45, 0.1, -40.0, -5.0), (0.2, 2.5, 70.0, 4.0))
GOODS_SIZE = 0.22


def kt_array(vals):
    return 'floatArrayOf(' + K.kt_list(vals) + ')'


KOTLIN_HEADER = '''// generated by art/scripts/pack_harness.py, edit the script, not this file
package io.github.vaspyyy.guildmark.client.trade

import net.minecraft.client.model.animal.equine.DonkeyModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.MeshTransformer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
'''

KOTLIN_OBJECT = '''
/**
 * The Pack Harness on a donkey, a mule and a llama (art/scripts/pack_harness.py), box UV on the one [TEX_WIDTH] x [TEX_HEIGHT]
 * texture (textures/entity/trade/pack_harness.png, shipped at 2x that and painted at the higher resolution). Model space as
 * the animals' own: pixels, y down, the animal faces -z, its right is -x.
 *
 * Drawn the vanilla way, as the game dresses a saddled horse: a second model of the animal's own shape on top of it, posed
 * as it is. Each layer carries every part the vanilla model looks up by name (empty where the harness has nothing), so
 * `DonkeyModel(root)` and `LlamaModel(root)` stand on it and their `setupAnim` poses it; only the harness's own parts carry
 * cubes. [Rig] and [WoolRig] pose those parts: call `pose` after the vanilla `setupAnim` (which resets every part).
 * The six layers: [donkey], [mule] and [llama] (leather, wicker, rope, sacks and brass, untinted) and [donkeyWool],
 * [muleWool] and [llamaWool] (the blanket and its tassels, painted neutral: draw them with the owner's colour as the tint).
 * See guildmark-notes/living-world/trader/guide/tra_x2.md.
 */
object PackHarnessMesh {
    const val TEX_WIDTH = 128
    const val TEX_HEIGHT = @TEX_H@

    /**
     * The donkey's harness: "body" carries "saddle" and "hang" (the saddle's bars and arches, the leather over them to the
     * panniers), "girth" (with its buckle), "breast" (the breast strap) with "hanger" (the arm the bell hangs from: it rides
     * along the strap, to the left, when the neck comes down or stands up in front of it) and that "bell" (turns about x at
     * its loop: swing it, forward only) with its child "bell_clapper" (turns about x and z at the middle of the bell),
     * "crupper" and, on "tail", "crupper_ring"; "left_chest" and "right_chest" are the panniers' hanging points (the
     * vanilla chests' names: [Rig] shows them whether or not the animal has its chest) with "pannier_l" / "pannier_r" (the
     * wicker basket: scales in x with the load) and the heap, "fill1_l" .. "fill3_r" (three tiers, each standing on the straw:
     * scale each about its base, hide it when it is under 2% grown); "head_parts" carries "halter", and that "halter_ring"
     * (hangs from the chin).
     */
    fun donkey(): LayerDefinition = LayerDefinition.create(equineHarnessMesh(), TEX_WIDTH, TEX_HEIGHT).apply(MeshTransformer.scaling(DonkeyModel.DONKEY_SCALE))

    /** The mule's: the donkey's mesh at the mule's own scale. */
    fun mule(): LayerDefinition = LayerDefinition.create(equineHarnessMesh(), TEX_WIDTH, TEX_HEIGHT).apply(MeshTransformer.scaling(DonkeyModel.MULE_SCALE))

    /**
     * The donkey's blanket: "body" carries "blanket", that "flap_l" and "flap_r" (turn about z at the top edge: they lift off the
     * flank), and each flap [TASSELS_DONKEY] tassels "tassel_l0" .. "tassel_r4" (pendulums, turn about x and z at the hem).
     * Draw it tinted by the owner's colour.
     */
    fun donkeyWool(): LayerDefinition = LayerDefinition.create(equineWoolMesh(), TEX_WIDTH, TEX_HEIGHT).apply(MeshTransformer.scaling(DonkeyModel.DONKEY_SCALE))

    /** The mule's blanket. */
    fun muleWool(): LayerDefinition = LayerDefinition.create(equineWoolMesh(), TEX_WIDTH, TEX_HEIGHT).apply(MeshTransformer.scaling(DonkeyModel.MULE_SCALE))

    /**
     * The llama's harness: the same parts, but "saddle", "hang", "girth", "left_chest" and "right_chest" hang from the root
     * (the llama's body is not posed), and the bell is on a collar round the neck's foot: "head" carries "collar" and that "bell"
     * (and its "bell_clapper"); the bell counter-turns the head's pitch so that it hangs plumb. There is no tail and no halter.
     */
    fun llama(): LayerDefinition = LayerDefinition.create(llamaHarnessMesh(), TEX_WIDTH, TEX_HEIGHT)

    /** The llama's blanket: "blanket" at the root, with "flap_l" / "flap_r" and [TASSELS_LLAMA] tassels on each. Draw it tinted. */
    fun llamaWool(): LayerDefinition = LayerDefinition.create(llamaWoolMesh(), TEX_WIDTH, TEX_HEIGHT)

@MESHES@

    // ---- the numbers the renderer works from (px, model space; the right side is the left's mirror) ----

    /** The panniers' hanging points (the "left_chest" part's pivot; the right one's x is mirrored) on the donkey and on the llama. */
    val HANG_DONKEY: FloatArray = @HANG_DONKEY@
    val HANG_LLAMA: FloatArray = @HANG_LLAMA@

    /** The bell's pivot (its loop) on the donkey (in the body's frame) and on the llama (in the root's: the collar moves with the head). */
    val BELL_DONKEY: FloatArray = @BELL_DONKEY@
    val BELL_LLAMA: FloatArray = @BELL_LLAMA@

    /** Fill runs from 0 (empty) to [FILL_STEPS] (full): four looks, and the baskets swell by [BELLY] of their width from one end to the other. */
    const val FILL_STEPS = 3
    const val BELLY = @BELLY@

    /** How far a heap's top stands over the basket's rim (px) at fill 0, 1, 2 and 3: put the goods this high; [heapTop] gives it for any fill in between. */
    val HEAP_TOP: FloatArray = @HEAP_TOP@

    /** The opening's middle at the rim's top, from the hanging point, in the left chest part's frame (negate x for the right). The heap and its goods stand here. */
    val OPENING: FloatArray = @OPENING@

    /**
     * A place for one good on top of a heap: [x] and [z] from the opening's middle (px, in the left chest part's frame: for the
     * right one negate x and yaw), turned [yaw] degrees about the vertical and tilted [tilt] degrees.
     */
    class Goods(val x: Float, val z: Float, val yaw: Float, val tilt: Float)

    /** The three places, in the order the goods fill them (the first three of the chest's goods); each is drawn this big (of a block). */
    val GOODS: List<Goods> = listOf(
@GOODS@
    )
    const val GOODS_SIZE = @GOODS_SIZE@

    /** The six booker colours as ARGB, for the wool layers' tint: red, blue, green, amber, violet, teal. */
    val COLOURS: IntArray = intArrayOf(@COLOURS@)

    /** The tassels hung on each flap, donkey and llama. */
    const val TASSELS_DONKEY = @TASSELS_D@
    const val TASSELS_LLAMA = @TASSELS_L@

    // ---- the motion: the same numbers and formulas as the script's own previews ----

    private const val DEG = 0.017453292f      // PI / 180
    private const val HALF_PI = 1.5707964f

    private const val ROLL = @ROLL@
    private const val PITCH = @PITCH@
    private const val IDLE_ROLL = @IDLE_ROLL@
    private const val LOAD_DAMP = @LOAD_DAMP@
    private const val BOB = @BOB@
    private const val BELL_MAX = @BELL_MAX@
    private const val BELL_IDLE = @BELL_IDLE@
    private const val BELL_PHASE = @BELL_PHASE@
    private const val CLAPPER_X = @CLAPPER_X@
    private const val CLAPPER_Z = @CLAPPER_Z@
    private const val CLAPPER_LAG = @CLAPPER_LAG@
    private const val FLAP_LIFT = @FLAP_LIFT@
    private const val TASSEL_BASE = @TASSEL_BASE@
    private const val TASSEL_SPEED = @TASSEL_SPEED@
    private const val TASSEL_LAG = @TASSEL_LAG@
    private const val RING_SWING = @RING_SWING@
    private const val SLIDE_X = @SLIDE_X@
    private const val SLIDE_DIP_FROM = @SLIDE_DIP_FROM@
    private const val SLIDE_DIP_TO = @SLIDE_DIP_TO@
    private const val SLIDE_UP_FROM = @SLIDE_UP_FROM@
    private const val SLIDE_UP_TO = @SLIDE_UP_TO@

    // the layer's springs (Trade D1's frame): what starts, stops, turns, a knock and a step taken add to the stride, each eased off by [fit]
    private const val TROT_GAIN = @TROT_GAIN@
    private const val ROLL_SP_MAX = @ROLL_SP_MAX@
    private const val TASSEL_SP_MAX = @TASSEL_SP_MAX@
    private const val GAIN_PITCH = @GAIN_PITCH@
    private const val GAIN_ROLL = @GAIN_ROLL@
    private const val GAIN_DANGLE = @GAIN_DANGLE@
    private const val PITCH_FORWARD = @PITCH_FORWARD@
    private const val PITCH_BACK = @PITCH_BACK@
    private const val ROLL_IN = @ROLL_IN@
    private const val ROLL_OUT = @ROLL_OUT@
    private const val TASSEL_FORWARD = @TASSEL_FORWARD@
    private const val TASSEL_BACK = @TASSEL_BACK@
    private const val KNOCK_MAX = @KNOCK_MAX@
    private const val KNOCK_DAMP_LLAMA = @KNOCK_DAMP_LLAMA@
    private const val BELL_MAX_LLAMA = @BELL_MAX_LLAMA@
    private const val BELL_FADE_FROM = @BELL_FADE_FROM@
    private const val BELL_FADE_TO = @BELL_FADE_TO@
    private const val BELL_FADE_MIN = @BELL_FADE_MIN@
    private const val POP_X = @POP_X@
    private const val POP_Y = @POP_Y@

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /**
     * [x] kept between [lo] and [hi] ([lo] < [kneeLo] <= 0 <= [kneeHi] < [hi]): as it is between the knees (what the stride alone swings), eased off
     * toward the limit beyond them (tanh), never past it.
     */
    private fun fit(x: Float, lo: Float, kneeLo: Float, kneeHi: Float, hi: Float): Float = when {
        x > kneeHi -> kneeHi + (hi - kneeHi) * tanh((x - kneeHi) / (hi - kneeHi))
        x < kneeLo -> kneeLo - (kneeLo - lo) * tanh((kneeLo - x) / (kneeLo - lo))
        else -> x
    }

    /** How much of tier [k] (1 to 3) of a heap shows at [fill]: 0 to 1, eased. */
    fun tierT(fill: Float, k: Int): Float = smooth(fill - (k - 1))

    /** How far a heap's top stands over the rim (px) at [fill] (0 to 3), between the four looks' [HEAP_TOP]s. */
    fun heapTop(fill: Float): Float {
        val f = fill.coerceIn(0f, FILL_STEPS.toFloat())
        val i = min(f.toInt(), FILL_STEPS - 1)
        return HEAP_TOP[i] + (HEAP_TOP[i + 1] - HEAP_TOP[i]) * smooth(f - i)
    }

    /** The fill to show for [used] of [total] slots taken: 0 empty, 1 a quarter (up to a third), 2 a half (up to two thirds), 3 full. */
    fun fillStep(used: Int, total: Int): Int {
        if (total <= 0 || used <= 0) return 0
        val f = used.toFloat() / total
        return if (f <= 0.34f) 1 else if (f <= 0.67f) 2 else 3
    }

    /** The fill going from [from] to [to] as [t] runs 0 to 1 (over six ticks): eased out, with a slight overshoot that settles. */
    fun fillEase(from: Float, to: Float, t: Float): Float {
        if (t >= 1f) return to
        val c = t - 1f
        return from + (to - from) * (1f + 1.9f * c * c * c + 0.9f * c * c)
    }

    /** Which beat the bell is on at [walkPos]: it steps up each time the bell reaches the top of its swing (twice a stride), the cue for its sound. */
    fun bellBeat(walkPos: Float): Int = floor((2f * walkPos * 0.6662f + BELL_PHASE - HALF_PI) / (2f * Math.PI.toFloat())).toInt()

    /**
     * Poses the harness's own parts on a baked [donkey], [mule] or [llama] layer ([llama]: true for the llama's): hold one per baked
     * model and call [pose] after the vanilla model's `setupAnim` has run (it resets every part). The panniers sway as a pair about
     * their hanging points, pitch a little with each step and swell with [pose]'s `fill`; the heap grows tier by tier and settles
     * with each step; the bell swings forward only, harder with speed, its clapper lagging and running round an ellipse; the
     * halter's chin ring hangs plumb below the chin.
     *
     * The layer's own springs come in as optional arguments to [pose], each kept where the animal leaves the load room (the numbers are the
     * script's fit poses' limits): [pose]'s `trot` (0 to 1) lengthens the stride; `pitch` and `roll` (radians, as the layer's frame gives them:
     * the baskets lag backward when it is positive and swing to the animal's right) add to the pair's sway, the basket on the side the swing
     * carries in toward the flank getting the little room there is ([ROLL_IN] degrees) and the other swinging free ([ROLL_OUT]), and the fore-aft
     * swing kept between [PITCH_FORWARD] and [PITCH_BACK]; `knock` (radians, 0 or more) swings the bell further forward, up to [KNOCK_MAX]
     * degrees more, and sets its clapper going; `pop` (0 to 1) squashes the heap, as a step taken does. The bell's swing fades as the donkey's
     * head comes down in front of it, and the llama's is shorter ([BELL_MAX_LLAMA]) and takes no knock at a full gait (her legs swing through
     * where it hangs).
     */
    class Rig(root: ModelPart, private val llama: Boolean) {
        private val head: ModelPart = root.getChild(if (llama) "head" else "head_parts")
        private val host: ModelPart = if (llama) root else root.getChild("body")
        private val chest: Array<ModelPart> = arrayOf(host.getChild("left_chest"), host.getChild("right_chest"))
        private val basket: Array<ModelPart> = arrayOf(chest[0].getChild("pannier_l"), chest[1].getChild("pannier_r"))
        private val tier: Array<ModelPart> = arrayOf(
            chest[0].getChild("fill1_l"), chest[0].getChild("fill2_l"), chest[0].getChild("fill3_l"),
            chest[1].getChild("fill1_r"), chest[1].getChild("fill2_r"), chest[1].getChild("fill3_r"),
        )
        private val tierY: FloatArray = FloatArray(6) { tier[it].y }
        private val hanger: ModelPart? = if (llama) null else host.getChild("breast").getChild("hanger")
        private val bell: ModelPart = hanger?.getChild("bell") ?: head.getChild("collar").getChild("bell")
        private val hangerX: Float = hanger?.x ?: 0f
        private val headY: Float = head.initialPose.y
        private val clapper: ModelPart = bell.getChild("bell_clapper")
        private val ring: ModelPart? = if (llama) null else head.getChild("halter").getChild("halter_ring")

        /**
         * [fill] 0 to [FILL_STEPS] (the eased value, see [fillEase]); [walkPos], [walkSpeed] and [age] are the render state's `walkAnimationPos`,
         * `walkAnimationSpeed` and `ageInTicks`; [trot], [pitch], [roll], [knock] and [pop] are the springs described above (all 0: the stride alone).
         */
        fun pose(fill: Float, walkPos: Float, walkSpeed: Float, age: Float, trot: Float = 0f, pitch: Float = 0f, roll: Float = 0f, knock: Float = 0f, pop: Float = 0f) {
            val w = walkPos * 0.6662f
            val sp = min(walkSpeed, 1f) * (1f + TROT_GAIN * trot.coerceIn(0f, 1f))
            val load = (fill / FILL_STEPS).coerceIn(0f, 1f)
            val damp = 1f - LOAD_DAMP * load
            val spr = min(sp, ROLL_SP_MAX)
            val strideRoll = -(ROLL * spr * sin(w) + IDLE_ROLL * sin(age * 0.071f)) * damp
            val kneeRoll = (ROLL * spr + IDLE_ROLL) * damp
            val kneePitch = (PITCH * sp + 0.15f) * damp
            val squash = pop.coerceIn(0f, 1f)
            for (s in 0..1) {
                val sg = if (s == 0) 1f else -1f
                val stridePitch = (PITCH * sp * sin(2f * w + 0.7f + (if (s == 0) 0f else HALF_PI)) + 0.15f * sin(age * 0.053f + sg * 1.1f)) * damp
                val lag = fit(stridePitch + GAIN_PITCH * pitch / DEG, PITCH_FORWARD, -kneePitch, kneePitch, PITCH_BACK)
                // a turn about z carries the left basket's foot in toward the flank when it is positive, the right one's when it is negative
                val inward = fit(sg * (strideRoll + GAIN_ROLL * roll / DEG), -ROLL_OUT, -kneeRoll, kneeRoll, ROLL_IN)
                chest[s].visible = true
                chest[s].xRot = lag * DEG
                chest[s].zRot = sg * inward * DEG
                basket[s].xScale = 1f + BELLY * load
                for (k in 0..2) {
                    val t = tierT(fill, k + 1)
                    val p = tier[s * 3 + k]
                    if (t < 0.02f) {
                        p.visible = false
                    } else {
                        p.visible = true
                        p.xScale = (0.6f + 0.4f * t) * (1f + POP_X * squash)
                        p.yScale = t * (1f - POP_Y * squash)
                        p.zScale = (0.6f + 0.4f * t) * (1f + POP_X * squash)
                        p.y = tierY[s * 3 + k] - BOB * sp * sin(2f * w + 0.7f + 0.35f * (k + 1)) * t
                    }
                }
            }
            val headX = head.xRot / DEG
            hanger?.x = hangerX + SLIDE_X * max(
                smooth((head.y - headY - SLIDE_DIP_FROM) / (SLIDE_DIP_TO - SLIDE_DIP_FROM)),
                smooth((SLIDE_UP_FROM - headX) / (SLIDE_UP_FROM - SLIDE_UP_TO)),
            )
            val g = (walkSpeed / 0.7f).coerceIn(0f, 1f)
            val u = 0.5f + 0.5f * sin(2f * w + BELL_PHASE)
            val breathe = 0.5f + 0.5f * sin(age * 0.083f)
            val extra = KNOCK_MAX * tanh(max(knock / DEG, 0f) / KNOCK_MAX) * (1f - (if (llama) KNOCK_DAMP_LLAMA else 0f) * g)
            val k = extra / KNOCK_MAX
            val fade = if (llama) 1f else 1f - (1f - BELL_FADE_MIN) * smooth((headX - BELL_FADE_FROM) / (BELL_FADE_TO - BELL_FADE_FROM))
            val reach = if (llama) BELL_MAX_LLAMA else BELL_MAX
            val swing = -((BELL_IDLE * (1f - g) * breathe + reach * g * u) + extra) * fade
            bell.xRot = (swing - (if (llama) headX else 0f)) * DEG
            val ph = 2f * w + BELL_PHASE - CLAPPER_LAG
            clapper.xRot = (CLAPPER_X * g * sin(ph) + 3f * (1f - g) * sin(age * 0.21f) + 0.8f * CLAPPER_X * k * sin(age * 0.9f)).coerceIn(-CLAPPER_X, CLAPPER_X) * DEG
            clapper.zRot = (CLAPPER_Z * g * cos(ph) + 2f * (1f - g) * sin(age * 0.17f + 1f) + 0.8f * CLAPPER_Z * k * cos(age * 0.9f + 0.5f)).coerceIn(-CLAPPER_Z, CLAPPER_Z) * DEG
            ring?.xRot = (-headX + RING_SWING * sp * sin(w + 0.4f) + 3f * sin(age * 0.13f)).coerceIn(-40f, 40f) * DEG
        }
    }

    /**
     * Poses the blanket's moving parts on a baked [donkeyWool], [muleWool] or [llamaWool] layer, after the vanilla `setupAnim`: the two
     * flaps lift off the flank with speed and breathe, and every tassel is a pendulum, a wave running back along the hem. The springs, as
     * the [Rig]'s: `trot` (0 to 1) lengthens the stride; `dangle` (radians, positive: they trail backward) is what a start or a stop adds
     * to the tassels' swing, as far as the girth ring in front of them and the pannier behind them leave ([TASSEL_FORWARD] to [TASSEL_BACK] degrees).
     */
    class WoolRig(root: ModelPart, llama: Boolean) {
        private val n: Int = if (llama) TASSELS_LLAMA else TASSELS_DONKEY
        private val blanket: ModelPart = (if (llama) root else root.getChild("body")).getChild("blanket")
        private val flap: Array<ModelPart> = arrayOf(blanket.getChild("flap_l"), blanket.getChild("flap_r"))
        private val tassel: Array<Array<ModelPart>> = Array(2) { s -> Array(n) { i -> flap[s].getChild("tassel_" + (if (s == 0) "l" else "r") + i) } }

        fun pose(walkPos: Float, walkSpeed: Float, age: Float, trot: Float = 0f, dangle: Float = 0f) {
            val w = walkPos * 0.6662f
            val sp = min(walkSpeed, 1f) * (1f + TROT_GAIN * trot.coerceIn(0f, 1f))
            val lift = FLAP_LIFT * sp * (0.5f + 0.5f * sin(1.7f * w + 0.3f)) + 0.5f * sin(age * 0.049f)
            val amp = TASSEL_BASE + TASSEL_SPEED * min(sp, TASSEL_SP_MAX)
            val knee = amp + 1.6f
            val trail = GAIN_DANGLE * dangle / DEG
            for (s in 0..1) {
                val sg = if (s == 0) 1f else -1f
                flap[s].zRot = -sg * lift * DEG
                for (i in 0 until n) {
                    val ph = 2f * w - TASSEL_LAG * i
                    tassel[s][i].xRot = fit(amp * sin(ph + 0.4f * sg) + 1.6f * sin(age * 0.09f + i * 0.8f) + trail, TASSEL_FORWARD, -knee, knee, TASSEL_BACK) * DEG
                    tassel[s][i].zRot = -sg * (2f + 0.35f * amp * (0.5f + 0.5f * sin(ph + 1.3f))) * DEG
                }
            }
        }
    }
}
'''


def mesh_function(doc, name, model):
    return (f'    /** {doc} */\n    @Suppress("LocalVariableName", "UNUSED_VARIABLE")\n    private fun {name}(): MeshDefinition {{\n'
            + K.mesh_body(model) + '\n    }\n')


def kotlin_source(models, height):
    """PackHarnessMesh.kt, from the built meshes and the rig's numbers."""
    m = measure(models)
    meshes = '\n'.join([
        mesh_function('The donkey and mule harness: leather, wicker, rope, sacks and brass.', 'equineHarnessMesh', models['donkey']),
        mesh_function('The donkey and mule blanket and tassels.', 'equineWoolMesh', models['donkey_wool']),
        mesh_function('The llama harness.', 'llamaHarnessMesh', models['llama']),
        mesh_function('The llama blanket and tassels.', 'llamaWoolMesh', models['llama_wool']),
    ]).rstrip('\n')
    d, l = m['donkey'], m['llama']
    tok = {
        'TEX_H': str(height), 'MESHES': meshes,
        'HANG_DONKEY': kt_array(d['hang']), 'HANG_LLAMA': kt_array(l['hang']),
        'BELL_DONKEY': kt_array(d['bell']), 'BELL_LLAMA': kt_array(l['bell']),
        'BELLY': K.kt_float(BELLY), 'HEAP_TOP': kt_array(d['heap']), 'OPENING': kt_array(d['opening']),
        'GOODS': ',\n'.join(f'        Goods({K.kt_list(g[:1])}, {K.kt_list(g[1:2])}, {K.kt_list(g[2:3])}, {K.kt_list(g[3:4])})' for g in GOODS) + ',',
        'GOODS_SIZE': K.kt_float(GOODS_SIZE),
        'COLOURS': ', '.join('0x%08Xu.toInt()' % c for c in COLOURS.values()),
        'TASSELS_D': str(len(TASSEL_Z)), 'TASSELS_L': str(len(TASSEL_Z_LLAMA)),
        'ROLL': K.kt_float(ROLL), 'PITCH': K.kt_float(PITCH), 'IDLE_ROLL': K.kt_float(IDLE_ROLL), 'LOAD_DAMP': K.kt_float(LOAD_DAMP),
        'BOB': K.kt_float(BOB), 'BELL_MAX': K.kt_float(BELL_MAX), 'BELL_IDLE': K.kt_float(BELL_IDLE), 'BELL_PHASE': K.kt_float(BELL_PHASE),
        'CLAPPER_X': K.kt_float(CLAPPER_X), 'CLAPPER_Z': K.kt_float(CLAPPER_Z), 'CLAPPER_LAG': K.kt_float(CLAPPER_LAG),
        'FLAP_LIFT': K.kt_float(FLAP_LIFT), 'TASSEL_BASE': K.kt_float(TASSEL_BASE), 'TASSEL_SPEED': K.kt_float(TASSEL_SPEED),
        'TASSEL_LAG': K.kt_float(TASSEL_LAG), 'RING_SWING': K.kt_float(RING_SWING),
        'SLIDE_X': K.kt_float(SLIDE_X), 'SLIDE_DIP_FROM': K.kt_float(SLIDE_DIP[0]), 'SLIDE_DIP_TO': K.kt_float(SLIDE_DIP[1]),
        'SLIDE_UP_FROM': K.kt_float(SLIDE_UP[0]), 'SLIDE_UP_TO': K.kt_float(SLIDE_UP[1]),
        'TROT_GAIN': K.kt_float(TROT_GAIN), 'ROLL_SP_MAX': K.kt_float(ROLL_SP_MAX), 'TASSEL_SP_MAX': K.kt_float(TASSEL_SP_MAX),
        'GAIN_PITCH': K.kt_float(GAIN_PITCH), 'GAIN_ROLL': K.kt_float(GAIN_ROLL), 'GAIN_DANGLE': K.kt_float(GAIN_DANGLE),
        'PITCH_FORWARD': K.kt_float(PITCH_LIMIT[0]), 'PITCH_BACK': K.kt_float(PITCH_LIMIT[1]),
        'ROLL_IN': K.kt_float(ROLL_IN), 'ROLL_OUT': K.kt_float(ROLL_OUT),
        'TASSEL_FORWARD': K.kt_float(TASSEL_LIMIT[0]), 'TASSEL_BACK': K.kt_float(TASSEL_LIMIT[1]),
        'KNOCK_MAX': K.kt_float(KNOCK_MAX), 'KNOCK_DAMP_LLAMA': K.kt_float(KNOCK_DAMP_LLAMA), 'BELL_MAX_LLAMA': K.kt_float(BELL_MAX_LLAMA),
        'BELL_FADE_FROM': K.kt_float(BELL_FADE[0]), 'BELL_FADE_TO': K.kt_float(BELL_FADE[1]), 'BELL_FADE_MIN': K.kt_float(BELL_FADE[2]),
        'POP_X': K.kt_float(POP_X), 'POP_Y': K.kt_float(POP_Y),
    }
    body = KOTLIN_OBJECT
    for k, v in tok.items():
        body = body.replace('@' + k + '@', v)
    assert '@' not in body.replace('@Suppress', ''), 'a token is left in the template'
    return KOTLIN_HEADER + body



# ================================================================== the self-check: the numbers above against the cubes, and the files against the cubes

NEED_EQUINE = {'body', 'head_parts', 'right_hind_leg', 'left_hind_leg', 'right_front_leg', 'left_front_leg', 'tail', 'left_chest', 'right_chest'}
NEED_LLAMA = {'head', 'body', 'right_chest', 'left_chest', 'right_hind_leg', 'left_hind_leg', 'right_front_leg', 'left_front_leg'}


def check_contract(models, tex, height):
    """The contract table in this file's docstring, and the vanilla part names the models stand on, against the built meshes; a
    change to the cubes without the page (or the page without the cubes) stops the run. Also the pictures: every face of every cube
    has paint, none paints over another, nothing is outside the texture."""
    m = measure(models)
    d, l = m['donkey'], m['llama']
    assert d['hang'] == (6.05, 2.3, 5.0) and l['hang'] == (7.05, 1.35, 2.0), (d['hang'], l['hang'])
    assert d['bell'] == (0, 7.9, -14.25) and l['bell'] == (0, 6.9, -15.0), (d['bell'], l['bell'])
    assert d['clapper'] == (0, 9.0, -14.25) and l['clapper'] == (0, 8.0, -15.0), (d['clapper'], l['clapper'])
    assert d['heap'] == (0.0, 1.1, 2.4, 4.5) == l['heap'], (d['heap'], l['heap'])
    assert d['opening'] == (1.82, -0.3, 0.0) == l['opening'], (d['opening'], l['opening'])
    for key, need in (('donkey', NEED_EQUINE), ('donkey_wool', NEED_EQUINE), ('llama', NEED_LLAMA), ('llama_wool', NEED_LLAMA)):
        have = set(K.all_names(models[key]))
        assert need <= have, f'{key} lacks the vanilla parts {sorted(need - have)}'
    # the rig's parts, by the names the Kotlin looks them up with
    for key, names in (('donkey', ('body', 'saddle', 'hang', 'girth', 'breast', 'hanger', 'bell', 'bell_clapper', 'crupper', 'crupper_ring', 'halter', 'halter_ring',
                                   'left_chest', 'right_chest', 'pannier_l', 'pannier_r') + tuple(f'fill{k}_{s}' for k in (1, 2, 3) for s in 'lr')),
                       ('llama', ('head', 'collar', 'bell', 'bell_clapper', 'left_chest', 'right_chest', 'pannier_l', 'pannier_r', 'saddle', 'hang', 'girth')
                        + tuple(f'fill{k}_{s}' for k in (1, 2, 3) for s in 'lr')),
                       ('donkey_wool', ('blanket', 'flap_l', 'flap_r') + tuple(f'tassel_{s}{i}' for s in 'lr' for i in range(len(TASSEL_Z)))),
                       ('llama_wool', ('blanket', 'flap_l', 'flap_r') + tuple(f'tassel_{s}{i}' for s in 'lr' for i in range(len(TASSEL_Z_LLAMA))))):
        have = set(K.all_names(models[key]))
        assert set(names) <= have, f'{key} lacks {sorted(set(names) - have)}'
    # the fit the page promises: a pannier encloses the vanilla chest (x past the animal's own flank, y and z inside the basket's body)
    for kind, chest, body_x in (('donkey', DONKEY_CHEST, DONKEY_BODY[0]), ('llama', LLAMA_CHEST, LLAMA_BODY[0])):
        basket = K.find(models[kind], 'pannier_l')
        body = next(c for c in basket.cubes if c.name == 'pn_body_l')
        x0, y0, z0 = (basket.abs[i] + body.origin[i] for i in range(3))
        x1, y1, z1 = x0 + body.size[0], y0 + body.size[1], z0 + body.size[2]
        cx0, cx1, cy0, cy1, cz0, cz1 = chest
        assert x0 <= body_x + 0.2 and x1 >= cx1 + 1.0, (kind, 'x', x0, x1)
        assert y0 <= cy0 - 0.2 and y1 >= cy1 + 0.4, (kind, 'y', y0, y1)
        assert z0 <= cz0 - 0.4 and z1 >= cz1 + 0.4, (kind, 'z', z0, z1)
    problems = []
    for key, mdl in models.items():
        problems += K.check_model(mdl, tex, (TEX_W, height), who=key)
    # the union of the four: no two pictures on the same texels
    union = K.Model('union', TEX_W, height, [K.Part('all', cubes=[c for mdl in models.values() for c in mdl.all_cubes()])])
    problems += [p for p in K.check_model(union, tex, (TEX_W, height), who='all') if 'used twice' not in p]
    problems += check_fit(models)[0]
    K.must_be_clean(problems, 'pack_harness')


def merged(models, kind):
    """One model for Blockbench: the leather layer with the blanket's parts hung in it (as they hang on the animal)."""
    base, wool = models[kind], models[kind + '_wool']
    parts = []
    for p in base.parts:
        q = copy.copy(p)
        q.children = list(p.children)
        parts.append(q)
    top = {q.name: q for q in parts}
    for w in wool.parts:
        if w.name == 'blanket':
            parts.append(w)
        elif w.name == 'body' and kind == 'donkey':
            top['body'].children += [c for c in w.children if c.name == 'blanket']
    return K.Model('pack_harness_' + kind, TEX_W, base.tex_h, parts)


def write_all(assets=ASSETS, kotlin_dir=KOTLIN, model_dir=MODELS):
    """Everything the script owns: the texture, the Kotlin table and the two Blockbench files. Returns (models, texture, height)."""
    models, tex, height = build()
    check_contract(models, tex, height)
    tdir = os.path.join(assets, 'textures', 'entity', 'trade')
    os.makedirs(tdir, exist_ok=True)
    tex.save(os.path.join(tdir, 'pack_harness.png'))
    os.makedirs(kotlin_dir, exist_ok=True)
    with open(os.path.join(kotlin_dir, 'PackHarnessMesh.kt'), 'w') as f:
        f.write(kotlin_source(models, height))
    os.makedirs(model_dir, exist_ok=True)
    small = tex.resize((TEX_W, height), Image.LANCZOS)
    for kind in ('donkey', 'llama'):
        with open(os.path.join(model_dir, f'pack_harness_{kind}.bbmodel'), 'w') as f:
            f.write(K.steady_uuids(json.dumps(merged(models, kind).bbmodel(small, 'pack_harness')), 'pack_harness_' + kind))
    check_written(models, tex, height, assets, kotlin_dir, model_dir)
    return models, tex, height


def check_written(models, tex, height, assets, kotlin_dir, model_dir):
    """What was written, read back: the texture's size, the Kotlin's mesh functions carrying exactly the parts (and the texture
    offsets) of the models, the numbers in its constants, the Blockbench files' cube counts."""
    problems = []
    png = Image.open(os.path.join(assets, 'textures', 'entity', 'trade', 'pack_harness.png'))
    if png.size != (TEX_W * SCALE, height * SCALE) or png.mode != 'RGBA':
        problems.append(f'pack_harness.png is {png.size} {png.mode}')
    src = open(os.path.join(kotlin_dir, 'PackHarnessMesh.kt')).read()
    if f'const val TEX_HEIGHT = {height}' not in src or f'const val TEX_WIDTH = {TEX_W}' not in src:
        problems.append('the Kotlin names another texture size')
    for fn, key in (('equineHarnessMesh', 'donkey'), ('equineWoolMesh', 'donkey_wool'), ('llamaHarnessMesh', 'llama'), ('llamaWoolMesh', 'llama_wool')):
        i = src.index(f'private fun {fn}()')
        j = src.index('return mesh', i)
        body = src[i:j]
        names = re.findall(r'addOrReplaceChild\("([a-z_0-9]+)"', body)
        want = K.all_names(models[key])
        if sorted(names) != sorted(want):
            problems.append(f'{fn} has other parts than the model: {sorted(set(names) ^ set(want))}')
        offs = re.findall(r'texOffs\((\d+), (\d+)\)', body)
        cubes = [c for p in models[key].all_parts() for c in p.cubes]
        if len(offs) != len(cubes):
            problems.append(f'{fn} has {len(offs)} cubes, the model {len(cubes)}')
    for const, val in (('ROLL', ROLL), ('BELL_MAX', BELL_MAX), ('BELLY', BELLY), ('TASSEL_SPEED', TASSEL_SPEED), ('BELL_PHASE', BELL_PHASE),
                       ('ROLL_IN', ROLL_IN), ('ROLL_OUT', ROLL_OUT), ('KNOCK_MAX', KNOCK_MAX), ('BELL_MAX_LLAMA', BELL_MAX_LLAMA), ('POP_X', POP_X)):
        if f'{const} = {K.kt_float(val)}' not in src:
            problems.append(f'the Kotlin does not carry {const} = {K.kt_float(val)}')
    for kind in ('donkey', 'llama'):
        bb = json.load(open(os.path.join(model_dir, f'pack_harness_{kind}.bbmodel')))
        if len(bb['elements']) != len(list(merged(models, kind).all_cubes())):
            problems.append(f'pack_harness_{kind}.bbmodel has {len(bb["elements"])} cubes')
    K.must_be_clean(problems, 'pack_harness (written)')


def report(models, height):
    m = measure(models)
    d, l = m['donkey'], m['llama']
    cubes = {k: len(list(v.all_cubes())) for k, v in models.items()}
    parts = {k: len(list(v.all_parts())) for k, v in models.items()}
    return '\n'.join([
        f'pack_harness: texture {TEX_W} x {height} declared ({TEX_W * SCALE} x {height * SCALE} shipped); cubes {cubes}; parts {parts}',
        f'  donkey/mule: panniers hang at (+-{d["hang"][0]:g}, {d["hang"][1]:g}, {d["hang"][2]:g}), bell at {d["bell"]}, heap tops over the rim {d["heap"]}',
        f'  llama: panniers hang at (+-{l["hang"][0]:g}, {l["hang"][1]:g}, {l["hang"][2]:g}), bell at {l["bell"]} (on the collar, in the root frame)',
    ])





# ================================================================== the fit and clip check: what moves must not go through what stands

# The poses it samples (label, harness_pose's arguments): standing at every fill, walking and trotting through the stride, the head
# turned and raised and lowered as far as a mob's look goes, grazing (the head down on the ground), rearing, and the layer's springs
# pushed past their caps in every sign (a spring is eased off before it reaches a limit, so the largest push is the worst case).
SPRING_PUSH = dict(pitch=40.0, roll=40.0, dangle=60.0, knock=90.0, pop=1.0)


def fit_poses(kind):
    out = [(f'stand fill {f}', dict(fill=f)) for f in (0, 1, 2, 3)]
    for i in range(8):
        out.append((f'walk {i}', dict(fill=2, walk_pos=i * 1.2, walk_speed=0.5, age=i * 7.0)))
        out.append((f'trot {i}', dict(fill=3, walk_pos=i * 1.3, walk_speed=0.9, age=i * 5.0)))
    for h in (-35.0, 0.0, 45.0, 60.0):
        for y in (-20.0, 20.0):
            out.append((f'head pitch {h:g} yaw {y:g}', dict(fill=3, head_pitch=h, head_yaw=y)))
        # walking with it, the bell at the top of its swing (walk_pos 0.5, and a stride on)
        out += [(f'head pitch {h:g}, bell up {i}', dict(fill=3, head_pitch=h, walk_pos=0.503 + 4.716 * i, walk_speed=0.9, age=i * 6.0)) for i in range(2)]
    if kind == 'donkey':
        out += [(f'graze {e:g}', dict(fill=3, eat=e)) for e in (0.15, 0.3, 0.5, 0.7, 1.0)]
        out += [(f'rear {s:g}', dict(fill=3, stand=s, walk_pos=2.0)) for s in (0.5, 1.0)]
        out.append(('graze, walking', dict(fill=2, eat=1.0, walk_pos=3.0, walk_speed=0.8, age=11.0)))
    out += spring_poses(kind)
    return out


def spring_poses(kind):
    """The springs at their pushed limits: each alone in each sign, standing, at the empty and the full baskets; every one at once, in
    all eight combinations of signs, at a trot through the stride; with the head lowered, raised and rearing."""
    out = []
    for fill in (0, 3):
        for name, amount in SPRING_PUSH.items():
            for sg in ((1, -1) if name in ('pitch', 'roll', 'dangle') else (1,)):
                out.append((f'stand fill {fill}, {name} {sg * amount:+g}', dict(fill=fill, **{name: sg * amount})))
    for i in range(16):
        a, b, c = (1 if i & 1 else -1), (1 if i & 2 else -1), (1 if i & 4 else -1)
        out.append((f'trot {i} with springs {a:+d}{b:+d}{c:+d}', dict(
            fill=(1 if i & 8 else 3), walk_pos=i * 1.1, walk_speed=1.0, trot=1.0, age=i * 9.0,
            pitch=a * 40.0, roll=b * 40.0, dangle=c * 60.0, knock=90.0, pop=1.0)))
    for h in (-35.0, 60.0):
        out.append((f'head pitch {h:g}, springs', dict(fill=3, head_pitch=h, walk_pos=1.0, walk_speed=0.6, **SPRING_PUSH)))
    if kind == 'donkey':
        out.append(('graze, springs', dict(fill=3, eat=1.0, walk_pos=3.0, walk_speed=0.8, age=11.0, **SPRING_PUSH)))
        out.append(('rear, springs', dict(fill=3, stand=1.0, walk_pos=2.0, **SPRING_PUSH)))
    return out


MOVER_PARTS = ('bell', 'bell_clapper', 'hanger', 'halter_ring', 'crupper_ring', 'pannier_l', 'pannier_r')
MOVER_PREFIX = ('tassel_', 'flap_', 'fill')            # the heap's tiers, the flaps and their tassels
HIDDEN_CHESTS = ('left_chest', 'right_chest')          # the animal's own chests (when it has them they are inside the baskets)


def is_mover(part_name):
    return part_name in MOVER_PARTS or part_name.startswith(MOVER_PREFIX)


def group_of(part_name):
    """The parts that are built to lie against one another and move as one family: a basket with its heap, the bell with its clapper
    and the arm it hangs from, a flap with its tassels. A pair inside a family is a join, not a clip."""
    if part_name in ('pannier_l', 'pannier_r') or part_name.startswith('fill'):
        return 'basket_' + part_name[-1]
    if part_name in ('bell', 'bell_clapper', 'hanger'):
        return 'bell'
    if part_name.startswith('tassel_'):
        return 'flap_' + part_name[7]
    return part_name


def clip_table(models, kind='donkey', poses=None):
    """The moving parts of the harness ([is_mover]) against every cube of the vanilla animal (but its chests, which a basket
    covers), every still cube of the harness (the saddle, the girth, the straps, the blanket) and every moving cube of another
    family ([group_of]) over the sampled poses: {(moving cube, other cube): (the deepest extra overlap beyond the same pair standing
    at that fill, px; the pose label)}. A cube that lies wholly inside the other (a pin in a board, a flap lying on the flank) is a
    fit, not a clip, and is left out. Also returns the standing overlaps, {fill: {pair: depth}}, for the page."""
    harness = [models[kind], models[kind + '_wool']]
    van = K.vanilla_equine() if kind == 'donkey' else K.vanilla_llama()
    root = K.root_pose(K.DONKEY_SCALE) if kind == 'donkey' else K.root_pose(1.0)
    rest = {}

    def depths(pose):
        hb = [b for m in harness for b in K.boxes_of(m, pose, root=root)]
        moving = [b for b in hb if is_mover(b.part)]
        vpose = {k: v for k, v in pose.items() if k not in HIDDEN_CHESTS}
        others = [b for b in K.boxes_of(van, vpose, root=root) if b.cube not in HIDDEN_CHESTS] + [b for b in hb if not is_mover(b.part)]
        out = {}
        for i, a in enumerate(moving):
            for b in others + moving[i + 1:]:
                if np.any(a.lo > b.hi) or np.any(b.lo > a.hi):
                    continue
                if is_mover(b.part) and group_of(a.part) == group_of(b.part):
                    continue
                if a.cube.startswith('cr_') and b.cube.startswith('cr_'):       # the crupper strap and the ring it runs through
                    continue
                d = K.box_depth(a, b)
                if d > 0.0 and not (K.box_inside(a, b) or K.box_inside(b, a)):
                    out[(a.cube, b.cube)] = d
        return out

    worst = {}
    for label, args in poses or fit_poses(kind):
        args = dict(args)
        fill = args.pop('fill')
        if fill not in rest:
            rest[fill] = depths(harness_pose(kind, fill))
        for key, d in depths(harness_pose(kind, fill, **args)).items():
            extra = d - rest[fill].get(key, 0.0)
            if extra > worst.get(key, (0.0, ''))[0]:
                worst[key] = (extra, label)
    return worst, rest


# The animal's own head and neck swing through what stands on its back, as the vanilla neck swings through the vanilla body: the
# neck is a box that turns about the base of the neck, and the harness's still shells (the blanket, the saddle's rails, the girth)
# stand just behind it. So the other half of the check: the still cubes of the harness against the part of the animal that turns away
# from them (the head and neck against the body-borne shells; the halter's and the collar's cubes, which turn with the head, against the
# body), over a sweep of the head's look pitch (degrees, the state's xRot: negative looks up) that is as far as the look of a mob goes
# on a hill or at a player standing below it. Past the range the neck goes further in (the report prints how far) and is not held.
HEAD_VANILLA = {'donkey': ('head_parts', 'head', 'left_ear', 'right_ear', 'mane', 'upper_mouth'), 'llama': ('head',)}    # vanilla parts that turn with the head
HEAD_HARNESS = {'donkey': ('halter', 'halter_ring'), 'llama': ('collar',)}                                               # the harness's own
HEAD_CLEAR = {'donkey': (-45.0, 60.0), 'llama': (-20.0, 60.0)}
NECK_LIMIT = 2.0                          # px beyond what the pair does at rest: the neck is a thick box in a thin cloth or a rail, not a cut into a bar


def head_sweep(kind):
    """The look pitches of [kind]'s clear range in steps of 5 degrees, standing and walking (the neck also nods a little with the walk)."""
    lo, hi = HEAD_CLEAR[kind]
    out, h = [], lo
    while h <= hi + 1e-6:
        out.append((f'look {h:g}', dict(fill=3, head_pitch=h)))
        out.append((f'look {h:g}, walking', dict(fill=3, head_pitch=h, walk_pos=1.0 + h, walk_speed=0.8, age=h + 50.0)))
        h += 5.0
    return out


def neck_table(models, kind, poses=None):
    """The still cubes of the harness against the part of the animal that turns away from them: the vanilla head and neck against the
    harness's body-borne cubes that stand still, and the harness's head-borne cubes (the halter, the collar) against the vanilla body, legs
    and tail. {(harness cube, vanilla cube): (the deepest extra overlap beyond the same pair at rest, px; the pose)} over [poses]
    (default: [head_sweep]), for the fill 3. A cube that lies wholly inside the other is a fit, not a clip."""
    harness = [models[kind], models[kind + '_wool']]
    van = K.vanilla_equine() if kind == 'donkey' else K.vanilla_llama()
    root = K.root_pose(K.DONKEY_SCALE) if kind == 'donkey' else K.root_pose(1.0)
    vhead, hhead = HEAD_VANILLA[kind], HEAD_HARNESS[kind]

    def depths(pose):
        hb = [b for m in harness for b in K.boxes_of(m, pose, root=root) if not is_mover(b.part)]
        vb = [b for b in K.boxes_of(van, {k: v for k, v in pose.items() if k not in HIDDEN_CHESTS}, root=root) if b.cube not in HIDDEN_CHESTS]
        out = {}
        for a in hb:
            for b in vb:
                if (a.part in hhead) == (b.part in vhead):               # both turn with the head, or neither does: they keep their places
                    continue
                if np.any(a.lo > b.hi) or np.any(b.lo > a.hi):
                    continue
                d = K.box_depth(a, b)
                if d > 0.0 and not (K.box_inside(a, b) or K.box_inside(b, a)):
                    out[(a.cube, b.cube)] = d
        return out

    rest = depths(harness_pose(kind, 3))
    worst = {}
    for label, args in poses or head_sweep(kind):
        args = dict(args)
        for key, d in depths(harness_pose(kind, args.pop('fill'), **args)).items():
            extra = d - rest.get(key, 0.0)
            if extra > worst.get(key, (0.0, ''))[0]:
                worst[key] = (extra, label)
    return worst, rest


# How far a moving part may go into the animal beyond what it does standing (px): the tolerance is the model's own looseness, a
# fraction of a texel (the HD texture is 2 texels to the px). The pairs that go further are listed with the reason.
CLIP_LIMIT = 0.6
SOFT_LIMIT = 1.0                                        # cloth (the blanket, its flaps and tassels) gives under a basket or a ring: a pair with it may go this far
CLIP_ALLOW = {                                          # (animal, moving cube, other cube): how far it may go, and why
    ('donkey', 'cr_ring', 'body'): 1.6,                 # the crupper's own ring sits half inside the rump as the tail wags (nothing of it shows there)
    ('llama', 'bl_lip', 'right_front_leg'): 0.8,        # the vanilla llama's front legs swing 80 degrees at a full gait, through where the bell hangs: the lip's corner
    ('llama', 'bl_lip', 'left_front_leg'): 0.8,         # touches a leg's top by 0.7 px for a couple of frames of the sprint
}


def is_soft(cube_name):
    """Whether a cube is cloth: the blanket's slab, its flaps and their tassels."""
    return cube_name == 'bl_slab' or cube_name.startswith('bl_flap') or re.match(r'ts\d_', cube_name) is not None


def check_knees():
    """The springs' limits stand beyond what the stride alone swings at its largest (a trot, empty baskets): `fit` needs that room to
    ease into. The problems, as lines."""
    problems = []
    knee_roll = ROLL * ROLL_SP_MAX + IDLE_ROLL
    knee_pitch = PITCH * (1.0 + TROT_GAIN) + 0.15
    knee_tassel = TASSEL_BASE + TASSEL_SPEED * TASSEL_SP_MAX + 1.6
    if not knee_roll < ROLL_IN:
        problems.append(f'the stride alone rolls a basket {knee_roll:g} degrees toward the flank, ROLL_IN is {ROLL_IN:g}')
    if not PITCH_LIMIT[0] < -knee_pitch < knee_pitch < PITCH_LIMIT[1]:
        problems.append(f'the stride alone pitches a basket {knee_pitch:g} degrees, PITCH_LIMIT is {PITCH_LIMIT}')
    if not TASSEL_LIMIT[0] < -knee_tassel < knee_tassel < TASSEL_LIMIT[1]:
        problems.append(f'the stride alone swings a tassel {knee_tassel:g} degrees, TASSEL_LIMIT is {TASSEL_LIMIT}')
    if not (0.0 < BELL_FADE[2] <= 1.0 and BELL_FADE[0] < BELL_FADE[1]):
        problems.append(f'BELL_FADE {BELL_FADE} is not a fade')
    return problems


def check_fit(models):
    """The clip check of both animals: (the problems, {kind: worst}) for `check_contract` and the page."""
    problems, tables = check_knees(), {}
    for kind in ('donkey', 'llama'):
        worst, _ = clip_table(models, kind)
        tables[kind] = worst
        for (a, b), (d, label) in sorted(worst.items(), key=lambda kv: -kv[1][0]):
            if d > CLIP_ALLOW.get((kind, a, b), SOFT_LIMIT if is_soft(a) or is_soft(b) else CLIP_LIMIT):
                problems.append(f'{kind}: {a} goes {d:.2f} px further into {b} than it does standing (in the pose "{label}")')
        neck, _ = neck_table(models, kind)
        for (a, b), (d, label) in sorted(neck.items(), key=lambda kv: -kv[1][0]):
            if d > NECK_LIMIT:
                problems.append(f'{kind}: {a} and {b} (one turns with the head, the other does not) go {d:.2f} px further into each other than they do at rest (in the pose "{label}")')
        problems += check_attached(models, kind)
    return problems, tables


def check_attached(models, kind):
    """Nothing floats: over the sampled poses each basket still overlaps the strap it hangs from. The problems, as lines."""
    root = K.root_pose(K.DONKEY_SCALE) if kind == 'donkey' else K.root_pose(1.0)
    problems = []
    for label, args in fit_poses(kind):
        args = dict(args)
        pose = harness_pose(kind, args.pop('fill'), **args)
        boxes = K.boxes_of(models[kind], pose, root=root)
        for side in 'lr':
            straps = [b for b in boxes if b.cube.startswith('hg_top') and b.cube.endswith(side)]
            basket = [b for b in boxes if b.part == f'pannier_{side}']
            reach = max(K.box_depth(a, s) for a in basket for s in straps)
            if reach < -0.05:
                problems.append(f'{kind}: the {side} basket has come {-reach:.2f} px off its strap in the pose "{label}"')
    return problems



# ================================================================== the previews (only with a preview dir): sheets and GIFs, the harness drawn on the vanilla animals

RED = tuple(COLOURS['red'] >> s & 255 for s in (16, 8, 0))
VIEWS = {                                  # (yaw, pitch) in `K.View`'s terms: yaw 0 looks at the animal's face, 180 at its tail
    '3/4': (-38.0, -20.0), 'side': (-90.0, -8.0), 'front': (0.0, -12.0), 'above': (180.0, -88.0), 'back': (180.0, -14.0),
}
FOCUS = {'donkey': ((0.0, 12.0, 0.0), 11.0), 'llama': ((0.0, 10.0, -2.0), 9.0)}      # what the camera looks at, and at what px per px


def tint_of(name):
    c = COLOURS[name]
    return (c >> 16 & 255, c >> 8 & 255, c & 255)


def vanilla_of(kind, V, mule=False):
    return (K.vanilla_equine(), V.mule() if mule else V.donkey()) if kind == 'donkey' else (K.vanilla_llama(), V.llama())


def draw(models, tex, V, kind, view, dressed=True, fill=2, colour='red', mule=False, shift=(0.0, 0.0, 0.0), size=(320, 330), zoom=1.0,
         centre=None, **pose_args):
    """One animal in one view: the vanilla animal [dressed] in the harness (or bare), at [fill], in [colour], posed as the vanilla
    model poses it for [pose_args] (walk_pos, walk_speed, age, eat, stand, head_pitch ...), moved by [shift]."""
    van, vtex = vanilla_of(kind, V, mule)
    root = K.root_pose((K.MULE_SCALE if mule else K.DONKEY_SCALE) if kind == 'donkey' else 1.0)
    pose = harness_pose(kind, fill, **pose_args)
    T = np.asarray(shift, float)
    faces = K.faces_of(van, 'van', s=1, root=root, pose=pose, T=T, skip=HIDDEN_CHESTS)
    if dressed:
        faces += K.faces_of(models[kind], 'harness', s=2, root=root, pose=pose, T=T)
        faces += K.faces_of(models[kind + '_wool'], 'harness', s=2, root=root, pose=pose, T=T, tint=tint_of(colour))
    c0, sc0 = FOCUS[kind]
    c = np.array([0.0, root[1], 0.0]) + root[0] * np.asarray(centre if centre is not None else c0, float) + T
    yaw, pitch = VIEWS[view] if isinstance(view, str) else view
    return K.render(faces, {'van': vtex, 'harness': tex}, K.View(yaw=yaw, pitch=pitch, scale=sc0 * zoom, center=tuple(c), size=size))


def sheet_fills(models, tex, V, kind, **kw):
    tiles = []
    for view in ('3/4', 'side', 'front', 'above'):
        for fill in (0, 1, 2, 3):
            tiles.append(K.caption(draw(models, tex, V, kind, view, fill=fill, size=(290, 310), **kw), f'{kind} {view}, fill {fill}'))
    return K.sheet(tiles, 4, title=f'The harness on the {kind}: the four fills, from the three-quarter, the side, the front and above')


def sheet_plain_beside(models, tex, V, kind, **kw):
    tiles = []
    for view in ('3/4', 'side', 'front', 'above'):
        tiles.append(K.caption(draw(models, tex, V, kind, view, dressed=False, size=(330, 330), **kw), f'vanilla {kind}, {view}'))
        tiles.append(K.caption(draw(models, tex, V, kind, view, dressed=True, fill=2, size=(330, 330), **kw), f'with the harness (half full), {view}'))
    return K.sheet(tiles, 2, title=f'The vanilla {kind} and the harnessed one, at the same size')


def sheet_colours(models, tex, V):
    tiles = [K.caption(draw(models, tex, V, 'donkey', '3/4', fill=3, colour=c, size=(260, 290), zoom=1.0), c) for c in COLOURS]
    tiles += [K.caption(draw(models, tex, V, 'llama', '3/4', fill=1, colour=c, size=(260, 290)), 'llama ' + c) for c in ('blue', 'amber', 'violet')]
    return K.sheet(tiles, 5, title='The six booker colours (the wool layers tinted; the cloth is painted neutral)')


def lineup(models, tex, V):
    """The donkey, the mule and the llama harnessed in a row beside a player (Steve) for height and, if the wagon script is
    there to draw it, the caravans' wagon at the same scale."""
    P = K.vanilla_player()
    steve = V.steve()
    faces = []
    textures = {'van': V.donkey(), 'mule': V.mule(), 'llama': V.llama(), 'harness': tex, 'steve': steve}
    xs = {'donkey': -50.0, 'mule': -21.0, 'llama': 9.0, 'player': 34.0}
    for name, kind, mule in (('donkey', 'donkey', False), ('mule', 'donkey', True), ('llama', 'llama', False)):
        van, _ = vanilla_of(kind, V, mule)
        root = K.root_pose((K.MULE_SCALE if mule else K.DONKEY_SCALE) if kind == 'donkey' else 1.0)
        pose = harness_pose(kind, 2)
        T = np.array([xs[name], 0.0, 0.0])
        tn = {'donkey': 'van', 'mule': 'mule', 'llama': 'llama'}[name]
        faces += K.faces_of(van, tn, s=1, root=root, pose=pose, T=T, skip=HIDDEN_CHESTS)
        faces += K.faces_of(models[kind], 'harness', s=2, root=root, pose=pose, T=T)
        faces += K.faces_of(models[kind + '_wool'], 'harness', s=2, root=root, pose=pose, T=T, tint=tint_of('blue' if name == 'llama' else 'red'))
    faces += K.faces_of(P, 'steve', s=1, pose=K.humanoid_pose(), T=np.array([xs['player'], 0.0, 0.0]))
    view = (-26.0, -12.0)
    img = K.render(faces, textures, K.View(yaw=view[0], pitch=view[1], scale=6.0, center=(-8.0, 6.0, 0.0), size=(800, 330)))
    out = K.caption(img, 'donkey, mule and llama harnessed (half full), and a player: one view, one scale')
    try:
        import wagon as Wg
        wm, _ = Wg.build()
        wt = Wg.texture(wm)
        wimg = Wg.render(Wg.wagon_faces(wm), wt, view[0] - 22.0, view[1], 6.0, (800, 390), (0.0, -24.0, -16.0))
        wimg = K.caption(wimg, "the caravans' wagon at the same scale (art/scripts/wagon.py)")
        both = Image.new('RGB', (out.width, out.height + wimg.height), (30, 32, 40))
        both.paste(out, (0, 0))
        both.paste(wimg, (0, out.height))
        out = both
    except Exception as e:                                                       # the wagon is only for scale; its absence is no fault here
        print('no wagon in the line-up:', e)
    return out


def sheet_springs(models, tex, V, kind):
    """The layer's springs pushed past their limits, one at a time, standing: what the eased-off result looks like at its most (the
    pitch and roll of the baskets, the tassels trailing, the bell knocked forward, a heap squashed)."""
    d = kind == 'donkey'
    want = [
        ('standing', 'side', {}),
        ('pitch +40: held to 2.4 back', 'side', dict(pitch=40.0)),
        ('pitch -40: held to 3.5 forward', 'side', dict(pitch=-40.0)),
        ('dangle +60: tassels trail, held to 26', 'side', dict(dangle=60.0)),
        ('knock 90: the bell 24 further forward', '3/4', dict(knock=90.0)),
        ('roll +40 from behind: left basket in 3.2, right out 14', 'back', dict(roll=40.0)),
        ('roll -40 from behind', 'back', dict(roll=-40.0)),
        ('pop 1: a heap squashed (full)', '3/4', dict(pop=1.0)),
    ]
    tiles = []
    for label, view, kw in want:
        tiles.append(K.caption(draw(models, tex, V, kind, view, fill=3 if 'pop' in kw else 2, size=(300, 310), zoom=1.0, **kw), label))
    return K.sheet(tiles, 4, title=f'The {kind}: the layer\'s springs pushed past their limits, one at a time')


def sheet_head_pitch(models, tex, V):
    """The head's look pitch from far up to far down, from the side, full baskets: the neck turning into the front edge of the blanket and
    the saddle's rail. The pitches inside `HEAD_CLEAR` are the ones `check_fit` holds (to `NECK_LIMIT`); the others are marked."""
    tiles = []
    for kind, pitches, centre, zoom in (('donkey', (-60, -45, -25, 0, 25, 45, 75), (0.0, 12.0, -9.0), 0.78),
                                        ('llama', (-60, -45, -30, -20, 0, 30, 60), (0.0, 6.0, -8.0), 0.72)):
        lo, hi = HEAD_CLEAR[kind]
        for h in pitches:
            note = 'held' if lo <= h <= hi else 'past the held range'
            tiles.append(K.caption(draw(models, tex, V, kind, 'side', fill=3, size=(300, 330), zoom=zoom, centre=centre, head_pitch=float(h)),
                                   f'{kind}, look {h:+d} ({note})'))
    return K.sheet(tiles, 7, title='The head tipped up and down: the neck goes into the front edge of the blanket and the rail (held to 2 px over the marked range)')


def sheet_texture(tex):
    big = tex.resize((tex.width * 3, tex.height * 3), Image.NEAREST)
    bg = Image.new('RGB', big.size, (44, 46, 54))
    bg.paste(big.convert('RGB'), (0, 0), big.split()[3])
    return K.caption(bg, f'pack_harness.png, {tex.width} x {tex.height} (declared {TEX_W} x {tex.height // SCALE}), shown 3x')


# ---- the GIFs

def gif_walk(models, tex, V, kind, path, view='side', speed=0.6, frames=48, fill=2, zoom=0.82, size=(420, 330), centre=(0.0, 9.0, -6.0), **kw):
    """The animal walking (speed 0.6) or trotting (1.0) past, the camera following it: panniers swaying, the heap settling, the bell
    swinging, its clapper running round, the tassels in a wave, the flaps lifting."""
    v = 1.3 if speed < 0.8 else 3.4                                              # px per tick: a stride's length over its period
    out = []
    for t in range(frames):
        z = -v * t
        out.append(draw(models, tex, V, kind, view, fill=fill, walk_pos=speed * t, walk_speed=speed, age=float(t) + 40.0,
                        shift=(0.0, 0.0, z), size=size, zoom=zoom, centre=centre, **kw))
    return K.save_gif(out, path, ms=50)


def gif_fill(models, tex, V, kind, path, view='3/4', size=(330, 330)):
    """The baskets filling and emptying: 0, 1, 2, 3 and back, each step eased over six ticks (`fillEase`), with a rest between."""
    seq = [0, 1, 2, 3, 2, 1, 0]
    out = []
    for a, b in zip(seq, seq[1:]):
        for t in range(6):
            out.append(draw(models, tex, V, kind, view, fill=fill_ease(a, b, (t + 1) / 6.0), age=float(len(out)), size=size))
        out += [out[-1]] * 4
    return K.save_gif(out, path, ms=50)


def gif_graze(models, tex, V, path, size=(340, 340)):
    """The donkey lowering its head to graze and lifting it again: the bell slips aside along the strap and back."""
    out = []
    steps = list(range(0, 21)) + [20] * 14 + list(range(20, -1, -1)) + [0] * 6
    for t, k in enumerate(steps):
        e = K.smooth(k / 20.0)
        out.append(draw(models, tex, V, 'donkey', (35.0, -22.0), fill=2, eat=e, age=float(t) + 40.0, size=size, zoom=1.08, centre=(0.0, 8.0, -6.0)))
    return K.save_gif(out, path, ms=50)


def gif_bell(models, tex, V, kind, path, size=(300, 300)):
    """The bell close up as the animal goes from a stand to a trot: it breathes, then rings forward once a step with its clapper
    running round inside."""
    out = []
    pos = 0.0
    for t in range(60):
        sp = min(1.0, t / 24.0) if t < 48 else max(0.0, 1.0 - (t - 48) / 12.0)
        pos += sp * 0.9                                                         # the game's walkAnimationPos: it runs on by the speed each tick
        out.append(draw(models, tex, V, kind, (-60.0, -12.0), fill=2, walk_pos=pos, walk_speed=sp * 0.9, age=float(t),
                        size=size, zoom=2.6, centre=(0.0, 9.0, -14.0) if kind == 'donkey' else (0.0, 8.0, -14.0)))
    return K.save_gif(out, path, ms=50)


class Spring:
    """A damped spring a layer would keep (k, c per tick), driven each tick by an input u: the lag of a load that is dragged."""

    def __init__(self, k=0.35, c=0.28):
        self.k, self.c, self.x, self.v = k, c, 0.0, 0.0

    def step(self, u):
        self.v += (-self.k * self.x - self.c * self.v + u)
        self.x += self.v
        return self.x


def gif_start_stop(models, tex, V, kind, path, view='3/4', size=(400, 340), zoom=0.8, centre=(0.0, 9.0, -6.0)):
    """The animal standing, setting off, turning left, walking, and stopping, with the layer's springs driven by the speed's change and
    the turn's rate as a layer would (damped springs, their output well past the limits): the baskets lag a start and lead a stop,
    swing out in the turn, the bell is knocked and the tassels trail, all held by the rig. The camera follows the animal's body."""
    pitch, roll, knock, dangle = Spring(), Spring(0.3, 0.22), Spring(0.5, 0.2), Spring(0.4, 0.25)
    speed, heading, pos, z, out = 0.0, 0.0, 0.0, 0.0, []
    for t in range(96):
        target = 0.0 if t < 10 or t >= 62 else 0.75
        new = speed + clamp(target - speed, -0.05, 0.04)                    # a start is slower than a stop
        accel = new - speed
        speed = new
        turn = 0.0
        if 40 <= t < 54:
            turn = 0.05 * math.sin(math.pi * (t - 40) / 14.0)               # radians per tick: a left turn of about 25 degrees
        heading += turn
        pos += speed * 0.9
        p = pitch.step(2.6 * accel)
        r = roll.step(6.0 * speed * turn)
        k = knock.step(6.0 * abs(accel))
        a = dangle.step(5.0 * max(accel, 0.0))
        yaw, pit = VIEWS[view]
        out.append(draw(models, tex, V, kind, (yaw + math.degrees(heading), pit), fill=2, walk_pos=pos, walk_speed=speed, age=float(t) + 40.0,
                        pitch=math.degrees(p), roll=math.degrees(r), knock=math.degrees(abs(k)), dangle=math.degrees(a),
                        size=size, zoom=zoom, centre=centre))
    return K.save_gif(out, path, ms=50)


def previews(models, tex, out_dir, game_assets=None):
    """Every sheet and GIF into [out_dir] (made if need be); returns the paths. [game_assets]: an unpacked game `assets/minecraft`
    folder, for the animals' own skins (a plain stand-in colour where there is none)."""
    V = K.Vanilla(game_assets)
    os.makedirs(out_dir, exist_ok=True)
    out = []

    def save(img, name):
        p = os.path.join(out_dir, name)
        img.save(p)
        out.append(p)

    save(sheet_plain_beside(models, tex, V, 'donkey'), 'pack_harness_donkey_beside_vanilla.png')
    save(sheet_plain_beside(models, tex, V, 'llama'), 'pack_harness_llama_beside_vanilla.png')
    save(sheet_fills(models, tex, V, 'donkey'), 'pack_harness_donkey_fills.png')
    save(sheet_fills(models, tex, V, 'llama', colour='blue'), 'pack_harness_llama_fills.png')
    save(sheet_colours(models, tex, V), 'pack_harness_colours.png')
    save(lineup(models, tex, V), 'pack_harness_lineup_and_wagon.png')
    save(sheet_texture(tex), 'pack_harness_texture.png')
    save(sheet_springs(models, tex, V, 'donkey'), 'pack_harness_donkey_springs.png')
    save(sheet_springs(models, tex, V, 'llama'), 'pack_harness_llama_springs.png')
    save(sheet_head_pitch(models, tex, V), 'pack_harness_head_pitch.png')
    out.append(gif_walk(models, tex, V, 'donkey', os.path.join(out_dir, 'pack_harness_donkey_walk.gif')))
    out.append(gif_walk(models, tex, V, 'donkey', os.path.join(out_dir, 'pack_harness_donkey_trot.gif'), view='3/4', speed=1.0, frames=40, fill=3,
                        size=(380, 340), centre=(0.0, 9.0, -4.0)))
    out.append(gif_walk(models, tex, V, 'llama', os.path.join(out_dir, 'pack_harness_llama_walk.gif'), view='3/4', speed=0.6, frames=48, colour='blue',
                        size=(380, 360), centre=(0.0, 6.0, -3.0)))
    out.append(gif_fill(models, tex, V, 'donkey', os.path.join(out_dir, 'pack_harness_fill_steps.gif')))
    out.append(gif_graze(models, tex, V, os.path.join(out_dir, 'pack_harness_graze_bell_slides.gif')))
    out.append(gif_bell(models, tex, V, 'donkey', os.path.join(out_dir, 'pack_harness_bell_closeup.gif')))
    out.append(gif_start_stop(models, tex, V, 'donkey', os.path.join(out_dir, 'pack_harness_donkey_start_turn_stop.gif')))
    out.append(gif_start_stop(models, tex, V, 'llama', os.path.join(out_dir, 'pack_harness_llama_start_turn_stop.gif'), size=(380, 360), centre=(0.0, 6.0, -3.0)))
    return out



def main(argv):
    assets = argv[1] if len(argv) > 1 and argv[1] != '-' else ASSETS
    kotlin_dir = argv[2] if len(argv) > 2 and argv[2] != '-' else KOTLIN
    model_dir = argv[3] if len(argv) > 3 and argv[3] != '-' else MODELS
    models, tex, height = write_all(assets, kotlin_dir, model_dir)
    print(report(models, height))
    if len(argv) > 4 and argv[4] != '-':
        for p in previews(models, tex, argv[4], argv[5] if len(argv) > 5 and argv[5] != '-' else None):
            print('preview', p)


if __name__ == '__main__':
    main(sys.argv)

# @@END@@
