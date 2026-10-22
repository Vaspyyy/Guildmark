"""
The three back-packs of the laden look (Trade, art stream X2): a blanket roll and a sack (25 Marks and over), an ash frame with two sacks, a
tin cup and a lit lantern on a pole (100), an oak ladder-frame that climbs past the shoulders with sacks, a small keg and two pots that clink
(250). Each is a model of its own, in the player's own space, with every loose part its own named part.

    python3 -B art/scripts/laden_pack.py [assets dir [kotlin dir [model dir [preview dir [game assets dir]]]]]

writes `textures/entity/trade/laden_pack.png` and `laden_pack_glow.png` (under [assets dir], default `src/main/resources/assets/guildmark`; shipped
at SCALE x the declared TEX_WIDTH x TEX_HEIGHT: one texture for the three packs, and the lit glass of the lantern on the same layout),
`client/trade/LadenPackMesh.kt` (GENERATED; under [kotlin dir], default `src/main/kotlin/io/github/vaspyyy/guildmark/client/trade`),
`laden_pack.bbmodel` (under [model dir], default `art/models`) and, with a [preview dir], the sheets and GIFs (the optional last argument is a game
`assets/minecraft` folder unpacked from the jar, read only: the player's own skin drawn under the packs). `-` stands for a default. A rerun gives
the same files. Before any file it checks its own numbers against this page (`check_contract`: the table below, the vanilla parts' names, the
pictures, the glow, that the packs go through nothing of the wearer over sampled poses and that nothing floats), and after writing it checks what
it wrote (`check_written`); it stops with the list if anything is off. Run it from the repository's root.

HOW IT IS DRAWN (for the layer that draws it) -----------------------------------------------------------------------------------
Model space as the player's own: px, y down, the origin at the neck, the player faces -z, his right is -x, the back of his body at z = 2. Each
level is a LayerDefinition of its own (`level1()`, `level2()`, `level3()`; the same texture for all three) that carries every part the vanilla
player model looks up by name (head, hat, body, jacket, the arms and sleeves, the legs and pants), empty but for `body`, so that
`PlayerModel(root, slim)` stands on it and its `setupAnim` poses the skeleton (the pack's own parts hang under `body` and follow its lean),
or a plain `Model` can draw it after `parentModel.body.translateAndRotate(poseStack)`. Under `body`: `straps_N` (a pad on the back, the chest
bands with a sternum strap and buckle, a hip belt with tabs back to the pack; level 1 has the pad and a rope sash across the chest) and
`pack_N` (the load; everything loose is its child). `setupAnim` resets every part, so pose the pack after it: `Rig(root, level).pose(...)`.

  level   Marks         parts                                   reach at rest: half width, top, bottom, front, back (px)
  ------  ------------  --------------------------------------  ---------------------------------------------------------
  1       25 to 99      a sack, a rolled blanket, a rope sash   3.5, 0.65, 10.2, 3.9, 10.32
  2       100 to 249    a frame, two sacks, a cup, a lantern    3.55, 0.8, 10.2, 3.85, 12.55
  3       250 and up    a tall frame, sacks, a keg, two pots    3.6, -6.45, 10.2, 3.85, 12.0

`levelFor(marks)` maps Marks to the level (`LEVEL_FROM` = 25, 100, 250). The level 2 lantern is lit: draw the model a second time, in the
same pose, with `laden_pack_glow.png` through `RenderTypes.eyes`, tinted `glowColour(age)` (white, alpha = the flame's flicker).

THE JAR'S NUMBERS IT STANDS ON (read with `javap -c`; px, model space) --------------------------------------------------------------
  PlayerModel / HumanoidModel (64 x 64): body box x +-4, y 0..12, z -2..2 about (0, 0, 0), the jacket 0.25 over it; the head 8 cubed, y -8..0,
                 the hat 0.5 over, a helmet 1.0 over; arms (+-5, 2, 0), 4 x 12 x 4, x 4..8, the sleeve 0.25 over (inner x 3.75), the arm armour
                 1.0 over (inner x 3.0); legs (+-1.9, 12, 0); the arms swing cos(pos * 0.6662) * speed, the legs 1.4 times that (80 degrees at a
                 sprint); a sneak leans the body 0.5 rad about its top and lowers it 3.2 px, the head 4.2, the legs 4 back.
  PlayerCapeModel: a 10 x 16 x 1 slab hung from (0, 0, 2) leaning back 6 + lean / 2 + flap degrees (1.1 further back and 0.85 higher under a chestplate).
  ArmorModelSet: the chestplate reaches z 3 behind and x +-5; leggings 0.5 over the legs.
  The pack's sizes are the fit: the pad lies 0.05 behind the jacket and reaches into the pack (z 2.3 to 5.3: the pack stands at z 3.9, so that
  it shows no gap as it sways), the frame is 0.15 to 0.25 clear of the sleeve, nothing solid reaches below y 10.2 (a thigh swung back lies at
  y 10.5 and more), the highest solid part is 0.65 to 0.8 (level 3's crown rises above the shoulders, behind the head, and clears a head
  turned 50 degrees or looking down).

THE MOVING PARTS: the contract with Trade D1, who draws them --------------------------------------------------------------------
Angles are degrees here (the Kotlin takes radians, as ModelPart does). `w` is the wearer's walk phase, `walkAnimationPos * 0.6662` (the limbs'
own), `g` the gait: the walk speed up to 1 times 1 + 0.35 * `sprint` (a sprint blend, 0 to 1), `age` the state's `ageInTicks`.

  part                      pivot (px, body's frame)       how it moves
  ------------------------  -----------------------------  -----------------------------------------------------------------------------
  straps_N                  (0, 0, 0)                      the worn kit: hugs the jacket (0.1 off it), does not sway; scaled with the pack
  pack_N                    (0, 1.5, 4.0) = PACK_PIVOT     the load, hung between the shoulder blades: rolls 1.6 * g about z, rocks 1.9 * g
                                                           about x, twists 1.4 * g about y and bobs 0.26 * g px with each step; breathes
                                                           0.35 deg at a stand; a sneak slides it 0.9 px down and 1 px back
  sash_1                    (-3.1, 0.2, -2.35) in straps   level 1's rope across the chest: fixed (rest turn -35.6 about z)
  sash_knot_1 > sash_tails  (3.2, 9.2, -2.4) in straps     the knot at the left hip lifts up to 9 deg about x; the two tails are pendulums
                                                           that ride up on the left thigh (`thigh`) and only ever swing outward
  sack_1                    (0, 3.2, 6.1)                  hangs by its neck: lags the pack, 2.6 deg about x, 1.3 about z
  neck_1 > tuft_1           (0, 3.2, 5.3), (0, 2.4, 5.3)   the sack's tied neck and its loose tuft: 3.5 and 17 deg about x, 2 and 11 about z
  roll_1 > tail_1_l, _r     (0, 2.7, 8.15), (+-2.35, 4.7, 9.9)   the blanket roll rocks 2.4 deg on its lashings; the lashings' ends are
                                                           pendulums, 23 deg about x, 13 about z, a wave running along them
  sack_2a, sack_2b          (0, 4.8, 6.6), (0, 1.6, 6.6)   level 2's sacks, each hung by its top, slumping like sack_1
  tail_2_a, _b              (1.8, 7.4, 9.32), (-1.8, 3.0, 9.32)    the lashings' ends, pendulums
  lantern_2                 (0, 5.35, 11.05) = LANTERN_PIVOT   swings backward only, 0 to 31 deg once a step (harder with speed; 2.6 deg
                                                           breathing at a stand), 10 deg to the side, rattles 2 deg in a sprint; turned
                                                           to hang plumb when the body leans (it rests on the load); its glass is lit
  cup_2                     (-2.5, 4.75, 9.7)              the tin cup on its peg: backward only, 0 to 36 deg against the lantern's beat
  sack_3a                   (0, 1.6, 6.6)                  level 3's sack hung in the frame
  crown_3                   (0, 0.8, 4.3)                  the frame's crown: leans back 9 deg at rest, folds 0.62 of the body's lean in a
                                                           sneak, flexes 1.5 deg on the stride; carries kick_3_l, _r (fixed, -55 deg)
  sack_3b > neck_3 > tuft_3 (0, -4.45, 8.6)                the sack standing on the plank among the crown (it counter-turns 0.7 of the
                                                           crown's angle); its neck and tuft as level 1's
  pot_3_l, pot_3_r          (+-1.9, 1.6, 10.3)             the clay pot (left) and the iron one (right): backward only, 0 to 21 deg; each
                                                           leans in and out 8 deg so that the two meet (a clink) and bounce apart
  lid_3                     (-1.9, 3.45, 11.8) in pot_3_r  the iron pot's lid lifts up to 11 deg with the swing and chatters in a sprint

`Rig.pose(walkPos, walkSpeed, age, sprint, appear, tilt, thigh, pitch, roll, bob, dangle)` poses all of it (level N poses only its own parts):
`appear` 0 to 1 brings the pack onto the back (`appearEase`: it comes down 2.4 px, scaled up from nothing, with a settle: run it over 12 ticks);
`tilt` is the wearer's body lean (radians) when the pack is drawn in the body's frame, so that a sneak or a swimmer's pitch reads right;
`thigh` the left thigh's xRot (read off the model's own `left_leg` by default); `pitch`, `roll` (radians), `bob` (px, down) and `dangle`
(radians, backward) are what the wearer's starts, stops, turns and landings add to the stride, from the layer's own springs, capped softly
(`c * tanh(x / c)`) where the legs, the back and the arms leave no room: pitch 7 deg back, 3 forward, roll 2 deg, bob 1.2 px down (0.3 when a
thigh is swung back 50 deg or more: the pack's foot is just above it) and 0.2 up, dangle 45 deg. `clinks(speed, sprint)` says whether the pots
meet at that speed and `clinkBeat(walkPos, speed, sprint)` steps up each time they do (once a step): the sound's cue. `flicker(age)` is the flame.

THE FIT: what moves must not go through the wearer ----------------------------------------------------------------------------------
Enforced over 126 poses (a stand, walks at three speeds and a sprint through the stride, the springs at their limits in every sign, a sneak
and a sneak-walk, the head turned 50 degrees and down 60, up 20 and, sneaking, 10), against the vanilla player: no cube of a pack goes more than
0.6 px (a third of a texel) further into the head, hat, body, jacket, arms, sleeves, legs or pants than it does at a stand. What is worn on the body
(pad, bands, belt, sash) is let overlap the head and hat where the neck bends (vanilla's own parts overlap there) and lie 0.7 under a sleeve; it is
not checked against the legs while sneaking. A second check keeps every loose part touching what carries it (the lantern its eye, a pot its peg,
a sack the frame, the crown its kick rails) in every pose: nothing floats; it is verified by moving a part off and watching the run stop.
Not enforced, and what to know of it:
  * Head up: the back of the head meets the frame's top rail: clear to 50 degrees (level 1), 20 (levels 2 and 3), 20 or more sneaking; in a
    helmet (1.0 over the head) the clear angle is smaller (the report prints it).
  * Armour: a chestplate reaches z 3 behind and hides the straps; the pack stands 0.9 px clear of it (and the layer a pixel further off it); the arm
    armour (inner x 3.0) meets the lower rails (x 3.5) as the arm swings back in a full sprint, about 1 px, as vanilla's own armour pieces overlap one
    another; the belt dips into leggings' tops for a moment in a sneak-walk.
  * The cape hangs where the pack is: it passes through the pad and frame at every lean; hide it while a pack shows (`state.showCape = false`).
  * A glider or elytra: draw no pack (the layer's rule). Swimming: the pack is drawn in the body's frame and so goes with the swimmer; pass `tilt`
    so that what hangs rests on the load.

THE LANTERN is lit, nothing else: its glass is the only thing in `laden_pack_glow.png` (`check_glow`); the lit pass runs through `RenderTypes.eyes`.
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
import townsfolk_tools as tt
from carrier_kit import bx, part, SCALE

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/trade')
MODELS = os.path.join(ROOT, 'art/models')

TEX_W = 128                               # declared texture size; the height is what the packing needs (see build)

# ================================================================== what the player fixes (the jar's numbers, px, the body's own frame)

# The player (PlayerModel / HumanoidModel, 64 x 64): the body box x +-4, y 0..12, z -2..2 about (0, 0, 0), the jacket 0.25 over it; the head
# 8 cubed over it, y -8..0 (the hat 0.5 over, a helmet 1.0 over); the arms (+-5, 2, 0), 4 x 12 x 4, x 4..8 beside the body; the legs
# (+-1.9, 12, 0) from y 12; the cape (PlayerCapeModel) a 10 x 16 x 1 slab at z 2..3, hung from (0, 0, 2) and leaning back 6 + lean / 2 + flap
# degrees (and 1.1 px further back and 0.85 higher under a chestplate); a chestplate (outer armour, 1.0 inflate) reaches z 3 behind, x +-5; the
# arms swing cos(pos * 0.6662) * speed (to +-1 rad), the legs 1.4 times that; a sneak leans the body 0.5 rad about its top and lowers it 3.2 px.
BODY_X, BODY_Z = 4.0, 2.0
ARM_INNER_X = 3.75                        # the arm's sleeve begins here: nothing of the pack below the shoulder goes wider, or the swinging arm meets it
LEG_TOP_Y = 12.0
PACK_X = 3.5                              # the widest the pack is below the shoulder (the frame's rails): 0.25 under the sleeve, for the sway
PACK_Z0 = 3.3                             # the pack's front plane: 0.3 behind a chestplate's back (z 3), clear of the body and of a cape at a stand
PACK_BOTTOM = 10.2                        # nothing solid lower: a leg swung back (80 degrees at a sprint) lies at y >= 10.5 behind the hip
TOP = 0.8                                 # the highest a pack's solid parts go: under a helmet's rim (y 1.0), so that the hat's lower back edge clears them as the head looks up a little
PACK_SHIFT_Z = 0.6                         # the whole pack stands this much further off the back than it is drawn (its part is moved: its front plane is at PACK_Z0 + this, 3.9):
#                                           clear of the hat's back (4.5) when the head looks up, and of a cape at a stand
PAD_EXTRA = 2.0                           # the pad (and the belt's tabs) reach this far past the pack's drawn front plane: into the pack, so that the pack, standing off
#                                           the back by the shift, the sway and a sneak's slide, never shows a gap
HEAD_BACK_Z = 5.0                         # a helmet's back: what rises above the shoulders stays behind this (and keeps 0.4 off it)
CARRY = (0.0, 1.5, 3.4)                   # where the pack hangs from: between the shoulder blades, just off the back; it sways about this

LEVELS = (25, 100, 250)                   # the marks at which the three packs begin

# ================================================================== colours the pack adds to the kit's

CREAM = (232, 222, 196); CREAM_D = (190, 176, 146); CREAM_L = (248, 242, 222)         # an undyed trade blanket
BAND_RED = (164, 48, 40); BAND_GOLD = (212, 168, 60); BAND_GREEN = (64, 110, 70); BAND_BLUE = (52, 66, 112)
OAK2 = (112, 76, 46); OAK2_D = (72, 46, 28); OAK2_L = (150, 108, 68)                   # the tall frame's darker oak
TIN = (176, 178, 184); TIN_D = (112, 114, 124); TIN_L = (232, 234, 240)
SOOT = (30, 30, 34)


# ================================================================== painters the pack adds

def bedroll_painter(key, radius):
    """The rolled trade blanket, its long axis x: cream wool with a set of bands (blue, red, gold, green) a hand in from each end, the
    twill fine; the ends show the roll: the layers in a spiral, a band's colour on every second turn. [radius]: the roll's, in px."""
    R = radius * SCALE

    def fn(face, w, h, rng):
        U, V = tt.uv(w, h)
        a = tt.solid(w, h, CREAM)
        if face in ('left', 'right'):
            x, y = np.abs((U - 0.5) * w), np.abs((V - 0.5) * h)
            d = np.maximum(np.maximum(x, y), (x + y) * 0.72) / R             # an octagon's distance: the roll's own outline
            ring = np.floor(d * 3.4).astype(int)
            cols = {0: CREAM_D, 1: BAND_BLUE, 2: CREAM_L, 3: BAND_RED}
            a[..., :3] = CREAM
            for k, col in cols.items():
                a[..., :3] = np.where((ring == k)[..., None], np.array(col, float), a[..., :3])
            a[..., :3] = np.where((d < 0.14)[..., None], np.array(CREAM_D, float) * 0.7, a[..., :3])
            a[..., :3] *= 1 + rng.normal(0, 0.02, (h, w, 1))
            return tt.shade_edges(a, 0.2)
        along = U                                                  # x runs across the picture on the long faces
        e = np.minimum(along, 1 - along) * 7.4 / 7.0              # px in from the nearer end, in tenths of the length
        stripes = [(0.50, 0.70, BAND_BLUE), (0.85, 1.05, BAND_RED), (1.12, 1.24, BAND_GOLD), (1.32, 1.52, BAND_GREEN)]
        for lo, hi, col in stripes:
            m = (e >= lo * 0.7) & (e < hi * 0.7)
            a[..., :3] = np.where(m[..., None], tt.mix(a[..., :3], col, 0.92), a[..., :3])
        yy, xx = np.mgrid[0:h, 0:w]
        tw = (((xx + yy) % 3) == 0)
        a[..., :3] *= np.where(tw, 0.95, 1.01)[..., None]
        a[..., :3] *= 1 + rng.normal(0, 0.014, (h, w, 1))
        # the layers' edge: a faint line where the blanket's end lies on the roll
        if face in ('front', 'back'):
            edge = np.abs(V - 0.33) * h < 0.7
            a[..., :3] = np.where(edge[..., None], a[..., :3] * 0.86, a[..., :3])
        return tt.shade_edges(a, 0.2, width=max(1, min(w, h) // 5))
    return tt.painter(key, fn)


def keg_painter(key, part='belly'):
    """A small cask lying with its long axis x: staves along the long faces with their dark seams and a bulge's shading, the heads
    (left, right) planked, rimmed by the chime and branded with the hall's wheel on the left head. [part] 'belly' or 'end'."""
    def fn(face, w, h, rng):
        U, V = tt.uv(w, h)
        if face in ('left', 'right'):
            a = tt.grain(w, h, 'v', P.OAK, P.OAK_D, P.OAK_L, rng, streak=0.5)
            seam = (np.abs(((U * 3.0) % 1.0) - 0.0) < max(0.04, 0.7 / w)) | (np.abs(U * 3.0 - 3.0) < 0.04)
            a[..., :3] = np.where(seam[..., None], a[..., :3] * 0.6, a[..., :3])
            r = np.maximum(np.abs(U - 0.5), np.abs(V - 0.5)) * 2
            a[..., :3] = np.where((r > 0.82)[..., None], np.array(P.OAK_D, float) * 0.9, a[..., :3])
            if face == 'left' and min(w, h) >= 6:
                x, y = (U - 0.5) * w, (V - 0.5) * h
                rr = np.hypot(x, y) / (min(w, h) * 0.3)
                ang = np.arctan2(y, x)
                spoke = (np.abs(((ang / (math.pi / 3) + 0.5) % 1.0) - 0.5) * rr * 1.6 < 0.17) & (rr < 1.0)
                mark = ((rr > 0.8) & (rr < 1.0)) | spoke | (rr < 0.2)
                a[..., :3] = np.where(mark[..., None], tt.mix(a[..., :3], P.STENCIL, 0.7), a[..., :3])
            return tt.shade_edges(a, 0.22)
        a = tt.grain(w, h, 'u', P.OAK, P.OAK_D, P.OAK_L, rng, streak=0.5)
        n = 4 if min(w, h) >= 6 else 2
        seam = ((V * n) % 1.0) < max(0.1, 0.8 / h)
        a[..., :3] = np.where(seam[..., None], tt.mix(a[..., :3], P.OAK_D, 0.6), a[..., :3])
        belly = np.clip(1.0 - np.abs(V - 0.5) * 1.6, 0, 1)
        a[..., :3] *= (0.84 + 0.2 * belly)[..., None]
        return tt.shade_edges(a, 0.15)
    return tt.painter(key, fn)


def hoop_painter(key):
    """An iron hoop round the cask (or a frame's strap): dark iron, a light edge, rivets in a row."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, P.IRON, P.IRON_D, P.IRON_L, rng, wear=0.08)
        if min(w, h) >= 4 and face not in ('left', 'right'):
            U, V = tt.uv(w, h)
            rv = ((U * w) % 6.0 < 1.0) & (np.abs(V - 0.5) * h < 0.8)
            a[..., :3] = np.where(rv[..., None], np.array(P.IRON_L, float) * 1.1, a[..., :3])
        return a
    return tt.painter(key, fn)


def billy_painter(key, part='body'):
    """A cooking pot of iron, blackened by the fire: the body's walls with a lighter rim and a dull sheen on the lit side, the lid with
    its steam hole, [part] 'body', 'lid' or 'knob'."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, (64, 62, 68), (30, 30, 36), (120, 120, 130), rng, bevel=False, wear=0.05)
        U, V = tt.uv(w, h)
        a[..., :3] = tt.mix(a[..., :3], SOOT, np.clip(V * 0.5 if face not in ('top', 'bottom') else 0.3, 0, 0.6)[..., None])
        if face == 'top':
            if part == 'body':                                       # the open mouth seen from above: dark inside a rim
                r = np.maximum(np.abs(U - 0.5), np.abs(V - 0.5)) * 2
                a[..., :3] = np.where((r < 0.74)[..., None], np.array((16, 14, 16), float), a[..., :3])
            elif part == 'lid':
                a[..., :3] = tt.mix(a[..., :3], P.IRON_L, 0.18)
                hole = (np.hypot(U - 0.5, V - 0.5) < 0.1)
                a[..., :3] = np.where(hole[..., None], np.array((12, 12, 14), float), a[..., :3])
            return tt.shade_edges(a, 0.2)
        lit = np.exp(-((U - 0.3) / 0.18) ** 2)
        a[..., :3] = tt.mix(a[..., :3], P.IRON_L, (lit * 0.38)[..., None])
        if part == 'body':
            rim = V < 0.14
            a[..., :3] = np.where(rim[..., None], tt.mix(a[..., :3], P.IRON_L, 0.3), a[..., :3])
            seam = (np.abs(V - 0.68) * h < 0.7)
            a[..., :3] = np.where(seam[..., None], a[..., :3] * 0.78, a[..., :3])
        return tt.shade_edges(a, 0.18)
    return tt.painter(key, fn)


def tag_painter(key):
    """A brass tag on a cord, stamped with the hall's wheel."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, P.BRASS, P.BRASS_D, P.BRASS_L, rng, wear=0.1)
        if face in ('front', 'back') and min(w, h) >= 6:
            U, V = tt.uv(w, h)
            x, y = (U - 0.5) * w, (V - 0.58) * h
            rr = np.hypot(x, y) / (min(w, h) * 0.28)
            ang = np.arctan2(y, x)
            spoke = (np.abs(((ang / (math.pi / 3) + 0.5) % 1.0) - 0.5) * rr * 1.6 < 0.18) & (rr < 1.0)
            mark = ((rr > 0.78) & (rr < 1.0)) | spoke | (rr < 0.2)
            a[..., :3] = np.where(mark[..., None], np.array(P.BRASS_D, float) * 0.8, a[..., :3])
            hole = np.hypot((U - 0.5) * w, (V - 0.14) * h) < 0.9
            a[..., :3] = np.where(hole[..., None], np.array((40, 28, 14), float), a[..., :3])
        return a
    return tt.painter(key, fn)


def cup_painter(key):
    """A tin cup: bright, dented, a rolled rim."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, TIN, TIN_D, TIN_L, rng, wear=0.12)
        U, V = tt.uv(w, h)
        if face == 'top':
            r = np.hypot(U - 0.5, V - 0.5) * 2
            a[..., :3] = np.where((r < 0.72)[..., None], np.array((60, 62, 70), float), a[..., :3])
            return a
        lit = np.exp(-((U - 0.3) / 0.16) ** 2)
        a[..., :3] = tt.mix(a[..., :3], TIN_L, (lit * 0.5)[..., None])
        a[..., :3] = np.where((V < 0.16)[..., None], tt.mix(a[..., :3], TIN_L, 0.35), a[..., :3])
        return a
    return tt.painter(key, fn)


def arch_painter(key, base=P.IRON, dark=P.IRON_D, light=P.IRON_L):
    """A wire bail seen from the front or back: an arch, clear inside and above (a flat card)."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, base, dark, light, rng, bevel=False, wear=0.05)
        if face in ('front', 'back') and min(w, h) >= 4:
            U, V = tt.uv(w, h)
            r = np.hypot((U - 0.5) * w, (V - 1.0) * h * 1.0) / (w / 2.0)
            ring = (r <= 1.0) & (r >= 0.62)
            a[~ring] = 0
        return a
    return tt.painter(key, fn, light=False)



# ================================================================== a pair of cubes, the right one the left's mirror

def one(name, x0, y0, z0, x1, y1, z1, paint=None, group=None, faces=None):
    """A cube on the middle line (or off it, a single one)."""
    return bx(x0, y0, z0, x1, y1, z1, name, paint=paint, share=group, faces=faces)


def pair(name, x0, y0, z0, x1, y1, z1, paint=None, group=None, faces=None):
    """Two cubes, `name_l` (as written, +x is the player's left) and `name_r` (its mirror: drawn from the left's picture, so a pair costs one
    picture). Returns the list of both."""
    g = group or name + '_l'
    mf = None if faces is None else tuple({'left': 'right', 'right': 'left'}.get(f, f) for f in faces)
    return [bx(x0, y0, z0, x1, y1, z1, name + '_l', paint=paint, share=g, faces=faces),
            bx(-x1, y0, z0, -x0, y1, z1, name + '_r', share=g, mirror=True, faces=mf)]


FACE_NAMES = ('top', 'bottom', 'front', 'back', 'left', 'right')
AROUND_Y = {f: 'u' for f in FACE_NAMES}                                           # a band round a standing sack: the rope runs across x or z
AROUND_X = {'top': 'v', 'bottom': 'v', 'front': 'v', 'back': 'v', 'left': None, 'right': None}   # a band round a lying roll (axis x)


def rope(key, axis='x'):
    return P.rope_painter(key, axis)


def ash(key, axis):
    return P.wood_painter(key, axis, P.ASH, P.ASH_D, P.ASH_L)


def oak(key, axis):
    return P.wood_painter(key, axis, OAK2, OAK2_D, OAK2_L)


def leather(key, axis='x'):
    return P.leather_painter(key, axis)


def sack_cubes(tag, x, y0, y1, z0, z1, mark=True, foot=True, swell=True, tint=1.0):
    """A burlap sack as stacked boxes (a body, a narrower swell on top and a narrower foot under it, so its corners are round):
    [x] half width, [y0] top and [y1] bottom of the body, [z0], [z1] front and back."""
    ins = 0.45
    out = [one(f'{tag}_body', -x, y0, z0, x, y1, z1, P.burlap_painter(f'{tag}_body', 'body', mark=mark, tint=tint))]
    if swell:
        out.append(one(f'{tag}_swell', -x + ins, y0 - 0.65, z0 + ins, x - ins, y0, z1 - ins, P.burlap_painter(f'{tag}_swell', 'top', mark=False, tint=tint)))
    if foot:
        out.append(one(f'{tag}_foot', -x + ins, y1, z0 + ins, x - ins, y1 + 0.3, z1 - ins, P.burlap_painter(f'{tag}_foot', 'top', mark=False, tint=tint)))
    return out


def tail_cubes(tag, x, y, z, length=2.1):
    """A loose rope end hanging from (x, y, z): the cord and a knot at its end, thin in z (it lies along a sack's back)."""
    g = f'tail_cord_{int(round(length * 10))}'
    return [one(f'{tag}_cord', x - 0.2, y, z - 0.2, x + 0.2, y + length, z + 0.2, rope(g, 'y'), group=g),
            one(f'{tag}_knot', x - 0.42, y + length - 0.15, z - 0.42, x + 0.42, y + length + 0.5, z + 0.42, rope('tail_knot', 'x'), group='tail_knot')]


# ================================================================== the skeleton: every part the vanilla model looks up, empty

SKELETON = (('head', (0, 0, 0)), ('hat', (0, 0, 0)), ('body', (0, 0, 0)), ('jacket', (0, 0, 0)),
            ('right_arm', (-5, 2, 0)), ('left_arm', (5, 2, 0)), ('right_sleeve', (-5, 2, 0)), ('left_sleeve', (5, 2, 0)),
            ('right_leg', (-1.9, 12, 0)), ('left_leg', (1.9, 12, 0)), ('right_pants', (-1.9, 12, 0)), ('left_pants', (1.9, 12, 0)))


def skeleton():
    """The player's parts as PlayerModel finds them, at the vanilla pivots: only `body` carries anything."""
    return {n: part(n, p) for n, p in SKELETON}


# ================================================================== what every level wears: a pad on the back, a belt and the chest straps

def set_back(pack):
    """Stand the whole pack PACK_SHIFT_Z further off the back (its cubes and children are relative to its part, so the part moves them all)."""
    x, y, z = pack.pivot
    pack.pivot = (x, y, z + PACK_SHIFT_Z)
    return pack


BELT_Y0, BELT_Y1 = 8.8, 10.1                                  # the hip belt's top and bottom


def worn_kit(body, level, straps=True):
    """The straps part of a level (a child of the body, so that it does not sway with the pack): a leather pad between the back and the pack,
    and, with [straps], the two chest bands with their sternum strap and buckle and the hip belt with its buckle and the two tabs back to the
    pack's foot. Everything in the front lies 0.1 px outside the jacket (z -2.25) and a chestplate (z -3) hides it."""
    k = f's{level}_'
    cubes = [one(k + 'pad', -3.1, TOP, 2.3, 3.1, 9.6, PACK_Z0 + PAD_EXTRA, P.pad_painter(k + 'pad'))]
    if straps:
        cubes += pair(k + 'band', 1.5, 0.0, -2.42, 3.0, BELT_Y0, -2.28, leather(k + 'band', 'y'))
        cubes += [one(k + 'sternum', -1.5, 3.4, -2.42, 1.5, 4.0, -2.28, leather(k + 'sternum', 'x')),
                  one(k + 'sternum_buckle', -0.55, 3.25, -2.56, 0.55, 4.15, -2.42, P.buckle_painter(k + 'sternum_buckle')),
                  one(k + 'belt_front', -4.4, BELT_Y0, -2.42, 4.4, BELT_Y1, -2.28, leather(k + 'belt', 'x')),
                  one(k + 'belt_buckle', -0.8, BELT_Y0 - 0.1, -2.56, 0.8, BELT_Y1 + 0.1, -2.42, P.buckle_painter(k + 'belt_buckle'))]
        cubes += pair(k + 'belt_side', 4.28, BELT_Y0, -2.28, 4.42, BELT_Y1, 2.28, leather(k + 'belt_side', 'z'))
        cubes += [one(k + 'belt_back', -4.4, BELT_Y0, 2.28, 4.4, BELT_Y1, 2.42, leather(k + 'belt_back', 'x'))]
        cubes += pair(k + 'tab', 2.5, 9.55, 2.42, 3.3, 10.15, PACK_Z0 + PAD_EXTRA, leather(k + 'tab', 'z'))
    return part(f'straps_{level}', (0, 0, 0), cubes, parent=body)


# ================================================================== level 1: a rolled blanket and a sack

def level1(body):
    """25 to 99 Marks: a burlap sack with a tied neck on the back, a trade blanket rolled and lashed on top of it behind the neck, a rope sash across
    the chest. The sack hangs from its neck (so it slumps with each step), the neck's tuft is loose, the lashings' ends dangle."""
    straps = worn_kit(body, 1, straps=False)
    # the sash: a rope from the right shoulder to the left hip across the chest, its tails hanging from the knot at the hip
    L = 10.82
    part('sash_1', (-3.1, 0.2, -2.35), [one('s1_sash', -3.7, 0.2, -2.42, -2.5, 0.2 + L, -2.28, rope('s1_sash', 'y'))], parent=straps, rot=(0, 0, -35.6))
    knot = part('sash_knot_1', (3.2, 9.2, -2.4), [one('s1_knot', 2.7, 8.6, -2.62, 3.7, 9.6, -2.4, rope('s1_knot', 'x'))], parent=straps)
    part('sash_tails_1', (3.2, 9.5, -2.4), [one('s1_tail_a', 2.8, 9.5, -2.58, 3.2, 11.5, -2.4, rope('s1_tail_a', 'y')),
                                            one('s1_tail_b', 3.2, 9.5, -2.58, 3.6, 11.1, -2.4, rope('s1_tail_b', 'y'))], parent=knot)
    pack = set_back(part('pack_1', CARRY, [], parent=body))
    # the sack, hung from its neck
    sack = part('sack_1', (0, 3.2, 5.5), sack_cubes('l1_sack', 2.9, 3.9, 9.9, PACK_Z0, 7.7), parent=pack)
    neck = part('neck_1', (0, 3.2, 4.7), [one('l1_neck', -1.0, 2.4, 4.0, 1.0, 3.2, 5.4, P.burlap_painter('l1_neck', 'neck', mark=False)),
                                           one('l1_tie', -1.22, 2.55, 3.78, 1.22, 2.88, 5.62, rope('l1_tie', AROUND_Y))], parent=sack)
    part('tuft_1', (0, 2.4, 4.7), [one('l1_tuft', -0.9, 1.25, 4.0, 0.9, 2.4, 5.4, P.burlap_painter('l1_tuft', 'neck', mark=False))], parent=neck)
    # the blanket rolled: two boxes crossed (an octagon), the long axis x, a lashing round each end
    rc = (2.7, 7.55)
    r, c = 1.85, 0.55
    roll = part('roll_1', (0, rc[0], rc[1]), [
        one('l1_roll_a', -3.5, rc[0] - r, rc[1] - r + c, 3.5, rc[0] + r, rc[1] + r - c, bedroll_painter('l1_roll_a', r)),
        one('l1_roll_b', -3.5, rc[0] - r + c, rc[1] - r, 3.5, rc[0] + r - c, rc[1] + r, bedroll_painter('l1_roll_b', r)),
    ] + pair('l1_lash', 2.0, rc[0] - r - 0.2, rc[1] - r - 0.2, 2.7, rc[0] + r + 0.2, rc[1] + r + 0.2, rope('l1_lash', AROUND_X)), parent=pack)
    for s, name in ((1, 'l'), (-1, 'r')):
        x, y, z = s * 2.35, rc[0] + r + 0.15, rc[1] + r - 0.1
        cubes = tail_cubes(f'l1_tail_{name}', x, y, z, 2.3)
        part(f'tail_1_{name}', (x, y, z), cubes, parent=roll)
    return {'straps': 'straps_1', 'pack': 'pack_1'}


# ================================================================== level 2: a pack-frame with two sacks and a lantern hooked on it

ZF0, ZF1, ZR0, ZR1 = PACK_Z0, PACK_Z0 + 0.8, 7.9, 8.7        # the front rails' z, the rear rails'
MID_Y = (4.2, 4.8)                                           # the middle bars (and the pole run through them)
LANTERN_PIVOT = (0.0, 5.35, 10.45)                           # the eye the lantern hangs from
GLOW_CUBES = {'l2_lantern_glass'}


def lantern_cubes(tag, px, py, pz):
    """The carried lantern about its pivot (px, py, pz) (the bail's top), in absolute px: a flat bail, a cap, four iron posts round warm glass (the
    glow texture's), a base. 5.4 px from the bail's top to the base's foot."""
    gl = f'{tag}_glass'
    cubes = [one(f'{tag}_bail', px - 0.9, py - 0.55, pz - 0.2, px + 0.9, py + 1.2, pz + 0.2, arch_painter(f'{tag}_bail'), faces=('front', 'back')),
             one(f'{tag}_cap', px - 1.5, py + 1.0, pz - 1.5, px + 1.5, py + 1.7, pz + 1.5, tt.lantern_iron_painter(f'{tag}_cap', rivets=True)),
             one(gl, px - 1.1, py + 1.7, pz - 1.1, px + 1.1, py + 4.2, pz + 1.1, tt.lantern_glass_painter(gl)),
             one(f'{tag}_base', px - 1.5, py + 4.2, pz - 1.5, px + 1.5, py + 4.8, pz + 1.5, tt.lantern_iron_painter(f'{tag}_base', rivets=True))]
    for i, (dx, dz) in enumerate(((-1.35, -1.35), (0.95, -1.35), (-1.35, 0.95), (0.95, 0.95))):
        cubes.append(one(f'{tag}_post{i}', px + dx, py + 1.7, pz + dz, px + dx + 0.4, py + 4.2, pz + dz + 0.4, tt.lantern_iron_painter(f'{tag}_post', rivets=False),
                         group=f'{tag}_post'))
    return cubes


def level2(body):
    """100 to 249 Marks: an ash frame (a rectangle of rails and bars standing off the back by the pad) with two sacks lashed in it, a pole run through
    its middle bars with an iron eye in its end and a lantern hung from the eye, a tin cup on a peg. The lantern swings (backward only), the cup
    swings, the sacks slump, the lashings' ends dangle; the glass is lit (the glow texture)."""
    worn_kit(body, 2)
    k = 'l2_'
    y0 = TOP
    cubes = pair(k + 'rail_f', 2.7, y0, ZF0, 3.5, 10.2, ZF1, ash(k + 'rail_f', 'y'))
    cubes += pair(k + 'rail_r', 2.7, y0, ZR0, 3.5, 10.2, ZR1, ash(k + 'rail_r', 'y'))
    cubes += [one(k + 'top', -3.5, y0, ZF1, 3.5, y0 + 0.8, ZR0, ash(k + 'top', 'z')),
              one(k + 'shelf', -3.5, 9.4, ZF1, 3.5, 10.2, ZR0, ash(k + 'shelf', 'z')),
              one(k + 'bar_top_f', -2.7, y0, ZF0, 2.7, y0 + 0.8, ZF1, ash(k + 'bar_f', 'x'), group=k + 'bar_f'),
              one(k + 'bar_low_f', -2.7, 9.4, ZF0, 2.7, 10.2, ZF1, ash(k + 'bar_f', 'x'), group=k + 'bar_f'),
              one(k + 'bar_mid_f', -2.7, MID_Y[0], ZF0, 2.7, MID_Y[1], ZF1, ash(k + 'bar_m', 'x'), group=k + 'bar_m'),
              one(k + 'bar_mid_r', -2.7, MID_Y[0], ZR0, 2.7, MID_Y[1], ZR1, ash(k + 'bar_m', 'x'), group=k + 'bar_m'),
              one(k + 'bar_top_r', -2.7, y0, ZR0, 2.7, y0 + 0.8, ZR1, ash(k + 'bar_f', 'x'), group=k + 'bar_f'),
              one(k + 'bar_low_r', -2.7, 9.4, ZR0, 2.7, 10.2, ZR1, ash(k + 'bar_f', 'x'), group=k + 'bar_f'),
              one(k + 'pole', -0.25, MID_Y[0] + 0.05, ZR1, 0.25, MID_Y[1] - 0.05, LANTERN_PIVOT[2] + 0.35, ash(k + 'pole', 'z')),
              one(k + 'eye_stem', -0.15, MID_Y[1] - 0.05, LANTERN_PIVOT[2] - 0.15, 0.15, LANTERN_PIVOT[1] - 0.2, LANTERN_PIVOT[2] + 0.15, P.iron_painter(k + 'eye_stem')),
              one(k + 'eye', -0.12, LANTERN_PIVOT[1] - 0.5, LANTERN_PIVOT[2] - 0.7, 0.12, LANTERN_PIVOT[1] + 0.5, LANTERN_PIVOT[2] + 0.7,
                  P.ring_painter(k + 'eye', P.IRON, P.IRON_D, P.IRON_L), faces=('left', 'right')),
              one(k + 'peg', -2.65, MID_Y[0] + 0.05, ZR1, -2.35, MID_Y[1] - 0.05, 9.45, ash(k + 'peg', 'z'))]
    for n, y in (('hi', y0 + 0.25), ('lo', 9.05)):                             # iron bands round the corners of the rectangle
        cubes += pair(f'{k}band_f_{n}', 2.65, y, ZF0 - 0.05, 3.55, y + 0.7, ZF1 + 0.05, P.iron_painter(k + 'band'), group=k + 'band_f')
        cubes += pair(f'{k}band_r_{n}', 2.65, y, ZR0 - 0.05, 3.55, y + 0.7, ZR1 + 0.05, P.iron_painter(k + 'band'), group=k + 'band_f')
    pack = set_back(part('pack_2', CARRY, cubes, parent=body))
    # the two sacks, each hung from its top (they slump about it); a lashing round each and a loose end from it
    zb0, zb1 = ZF0 + 0.2, ZR1 - 0.2                                           # the sacks are fuller than the opening: they bulge between the bars
    ya = y0 + 0.8
    sa = sack_cubes('l2_sack_a', 2.65, MID_Y[1], 9.4, zb0, zb1, foot=False, swell=False) + [one('l2_band_a', -2.8, 7.0, zb0 - 0.12, 2.8, 7.5, zb1 + 0.12, rope('l2_band', AROUND_Y), group='l2_band')]
    sb = sack_cubes('l2_sack_b', 2.65, ya, MID_Y[0], zb0, zb1, foot=False, swell=False, tint=0.94) + [one('l2_band_b', -2.8, 2.65, zb0 - 0.12, 2.8, 3.15, zb1 + 0.12, rope('l2_band', AROUND_Y), group='l2_band')]
    part('sack_2a', (0, MID_Y[1], 6.0), sa, parent=pack)
    part('sack_2b', (0, ya, 6.0), sb, parent=pack)
    part('tail_2_a', (1.8, 7.4, ZR1 + 0.02), tail_cubes('l2_tail_a', 1.8, 7.4, ZR1 + 0.02, 2.1), parent=pack)
    part('tail_2_b', (-1.8, 3.0, ZR1 + 0.02), tail_cubes('l2_tail_b', -1.8, 3.0, ZR1 + 0.02, 1.9), parent=pack)
    # the lantern, hung from the eye
    part('lantern_2', LANTERN_PIVOT, lantern_cubes('l2_lantern', *LANTERN_PIVOT), parent=pack)
    # the cup on its peg
    cp = (-2.5, MID_Y[1] - 0.05, 9.1)
    part('cup_2', cp, [one('l2_cup_cord', -2.65, cp[1], 8.95, -2.35, cp[1] + 1.0, 9.25, rope('l2_cord', 'y')),
                       one('l2_cup', -3.15, cp[1] + 0.95, 8.45, -1.85, cp[1] + 2.45, 9.75, cup_painter('l2_cup'))], parent=pack)
    return {'straps': 'straps_2', 'pack': 'pack_2'}


# ================================================================== level 3: a tall frame with sacks, a small barrel and pots tied on that clink

KICK_PIVOT = (0.0, TOP, 3.7)                                 # where the crown leaves the frame: it flexes about here (the sneak)
KICK_ANGLE = -55.0                                           # the kick rails lean back this much (about x)
PLANK_Z1 = 10.5                                              # the top plank's rear end: it carries the crown's sack and, under it, the pegs the pots hang from
POT_PEGS = ((1.9, TOP + 0.8, 9.7), (-1.9, TOP + 0.8, 9.7))   # where the pots hang from: the clay pot on the player's left, the iron one on the right
POT_HALF = (1.7, 1.5)                                        # half the belly's width, clay and iron


def level3(body):
    """250 Marks and over: an oak ladder-frame bound with iron that climbs past the shoulders behind the head (a kick back, then the crown), a plank
    over its top and a shelf under it; a small keg lashed on the shelf, a sack hung in the frame and another stood on the plank among the crown, and two
    pots tied under the plank's end: a clay one and an iron one with a lid. The pots swing and meet (they clink); the sacks slump, their necks' tufts
    flop, the crown flexes back when the wearer sneaks, the lid lifts with the stride."""
    worn_kit(body, 3)
    k = 'l3_'
    y0 = TOP
    cubes = pair(k + 'rail_f', 2.7, y0, ZF0, 3.5, 10.2, ZF1, oak(k + 'rail_f', 'y'))
    cubes += [one(k + 'top', -3.5, y0, ZF1, 3.5, y0 + 0.8, PLANK_Z1, oak(k + 'top', 'z')),
              one(k + 'shelf', -3.5, 9.4, ZF1, 3.5, 10.2, ZR1, oak(k + 'shelf', 'z')),
              one(k + 'bar_top_f', -2.7, y0, ZF0, 2.7, y0 + 0.8, ZF1, oak(k + 'bar_f', 'x'), group=k + 'bar_f'),
              one(k + 'bar_low_f', -2.7, 9.4, ZF0, 2.7, 10.2, ZF1, oak(k + 'bar_f', 'x'), group=k + 'bar_f'),
              one(k + 'bar_mid_f', -2.7, MID_Y[0], ZF0, 2.7, MID_Y[1], ZF1, oak(k + 'bar_m', 'x'), group=k + 'bar_m')]
    for i, (px, py, pz) in enumerate(POT_PEGS):
        cubes.append(one(f'{k}peg_{i}', px - 0.2, py - 0.1, pz - 0.2, px + 0.2, py + 0.3, pz + 0.2, P.iron_painter(k + 'peg'), group=k + 'peg'))
    for n, y in (('hi', y0 + 0.25), ('lo', 9.05)):
        cubes += pair(f'{k}band_f_{n}', 2.65, y, ZF0 - 0.05, 3.55, y + 0.7, ZF1 + 0.05, P.iron_painter(k + 'band'), group=k + 'band_f')
    cubes += pair(k + 'band_s', 2.65, 9.05, ZR1 - 0.9, 3.55, 9.75, ZR1 + 0.05, P.iron_painter(k + 'band'))   # the shelf's iron edge
    # the keg on the shelf, its long axis x: a belly and two ends, hoops, a bung
    kc = []
    kc += [one(k + 'keg_belly', -1.9, 5.4, 4.0, 1.9, 9.4, 8.0, keg_painter(k + 'keg_belly', 'belly'))]
    kc += pair(k + 'keg_end', 1.9, 5.6, 4.2, 3.2, 9.2, 7.8, keg_painter(k + 'keg_end', 'end'))
    kc += pair(k + 'keg_hoop', 1.2, 5.3, 3.9, 1.6, 9.5, 8.1, hoop_painter(k + 'keg_hoop'), faces=('top', 'bottom', 'front', 'back'))
    kc += pair(k + 'keg_rim', 2.8, 5.5, 4.1, 3.3, 9.3, 7.9, hoop_painter(k + 'keg_hoop'), group=k + 'keg_hoop_rim', faces=('top', 'bottom', 'front', 'back'))
    kc += [one(k + 'keg_bung', -0.4, 5.2, 5.6, 0.4, 5.4, 6.4, tt.cork_painter(k + 'keg_bung'))]
    cubes += kc
    pack = set_back(part('pack_3', CARRY, cubes, parent=body))
    # the sack hung in the frame from the plank, lashed to the middle bar
    ya = y0 + 0.8
    sa = sack_cubes('l3_sack_a', 2.65, ya, 5.4, ZF1, ZR0, foot=False, swell=False, tint=0.96) + [one('l3_band_a', -2.8, 3.3, ZF1, 2.8, 3.8, ZR0, rope('l3_band', AROUND_Y), group='l3_band')]
    part('sack_3a', (0, ya, 6.0), sa, parent=pack)
    # the crown: the frame's kick back and its rails above the shoulders, the carrying bar, and the second sack on the plank among them
    cc = pair(k + 'crown_rail', 2.7, y0 - 6.8, 5.3, 3.5, y0 - 1.25, 6.1, oak(k + 'crown_rail', 'y'))
    cc += [one(k + 'crown_bar', -3.5, y0 - 6.8, 5.3, 3.5, y0 - 6.1, 6.1, oak(k + 'crown_bar', 'x'))]
    crown = part('crown_3', KICK_PIVOT, cc, parent=pack)
    for s_, n in ((1, 'l'), (-1, 'r')):
        x0, x1 = (2.7, 3.5) if s_ > 0 else (-3.5, -2.7)
        part(f'kick_3_{n}', (s_ * 3.1, KICK_PIVOT[1], KICK_PIVOT[2]),
             [bx(x0, KICK_PIVOT[1] - 2.55, KICK_PIVOT[2] - 0.4, x1, KICK_PIVOT[1], KICK_PIVOT[2] + 0.4, f'l3_kick_{n}', paint=oak('l3_kick', 'y'), share='l3_kick', mirror=s_ < 0)],
             parent=crown, rot=(KICK_ANGLE, 0, 0))
    sb = sack_cubes('l3_sack_b', 2.6, y0 - 4.6, y0, 6.3, 9.7, foot=False, tint=1.04)
    sack_b = part('sack_3b', (0, y0 - 5.25, 8.0), sb, parent=crown)
    neck = part('neck_3', (0, y0 - 5.25, 8.0), [one('l3_neck', -0.95, y0 - 6.1, 7.3, 0.95, y0 - 5.25, 8.7, P.burlap_painter('l3_neck', 'neck', mark=False)),
                                                 one('l3_tie', -1.15, y0 - 5.9, 7.1, 1.15, y0 - 5.55, 8.9, rope('l3_tie', AROUND_Y))], parent=sack_b)
    part('tuft_3', (0, y0 - 6.1, 8.0), [one('l3_tuft', -0.85, y0 - 7.25, 7.3, 0.85, y0 - 6.1, 8.7, P.burlap_painter('l3_tuft', 'neck', mark=False))], parent=neck)
    # the pots under the plank's end
    (cx, cy, cz), (ix, iy, iz) = POT_PEGS
    clay = [one(k + 'potc_cord', cx - 0.22, cy, cz - 0.22, cx + 0.22, cy + 1.45, cz + 0.22, rope(k + 'potc_cord', 'y')),
            one(k + 'potc_rim', cx - 1.4, cy + 1.35, cz - 1.4, cx + 1.4, cy + 1.85, cz + 1.4, P.pot_painter(k + 'potc_rim', 'rim')),
            one(k + 'potc_neck', cx - 0.9, cy + 1.85, cz - 0.9, cx + 0.9, cy + 2.55, cz + 0.9, P.pot_painter(k + 'potc_neck', 'neck')),
            one(k + 'potc_cordband', cx - 1.08, cy + 1.95, cz - 1.08, cx + 1.08, cy + 2.25, cz + 1.08, rope(k + 'potc_band', AROUND_Y)),
            one(k + 'potc_belly', cx - POT_HALF[0], cy + 2.55, cz - POT_HALF[0], cx + POT_HALF[0], cy + 4.55, cz + POT_HALF[0], P.pot_painter(k + 'potc_belly', 'body')),
            one(k + 'potc_base', cx - 1.1, cy + 4.55, cz - 1.1, cx + 1.1, cy + 4.95, cz + 1.1, P.pot_painter(k + 'potc_base', 'rim'))]
    part('pot_3_l', (cx, cy, cz), clay, parent=pack)
    iron = [one(k + 'poti_bail', ix - 1.5, iy, iz - 0.15, ix + 1.5, iy + 1.85, iz + 0.15, arch_painter(k + 'poti_bail'), faces=('front', 'back')),
            one(k + 'poti_body', ix - POT_HALF[1], iy + 1.85, iz - POT_HALF[1], ix + POT_HALF[1], iy + 4.85, iz + POT_HALF[1], billy_painter(k + 'poti_body', 'body'))]
    pot_i = part('pot_3_r', (ix, iy, iz), iron, parent=pack)
    part('lid_3', (ix, iy + 1.85, iz + POT_HALF[1]),
         [one(k + 'poti_lid', ix - 1.62, iy + 1.55, iz - 1.62, ix + 1.62, iy + 1.9, iz + 1.62, billy_painter(k + 'poti_lid', 'lid')),
          one(k + 'poti_knob', ix - 0.35, iy + 1.2, iz - 0.35, ix + 0.35, iy + 1.55, iz + 0.35, billy_painter(k + 'poti_knob', 'knob'))], parent=pot_i)
    return {'straps': 'straps_3', 'pack': 'pack_3'}



# ================================================================== packing and painting: one texture, one mesh, all three levels

LEVEL_BUILDERS = (level1, level2, level3)


def build():
    """The mesh (the player's skeleton, the three packs and their straps under `body`), its HD texture, the lit-glass texture and the declared
    height: (Model, texture, glow, height)."""
    sk = skeleton()
    for f in LEVEL_BUILDERS:
        f(sk['body'])
    model = K.Model('laden_pack', TEX_W, 0, [sk[n] for n, _ in SKELETON])
    cubes = list(model.all_cubes())
    used = K.pack(cubes, TEX_W, 1024, 'laden_pack')
    height = (used + 7) // 8 * 8
    model.tex_h = height
    tex = K.paint_texture(cubes, TEX_W, height)
    glow = K.paint_texture([c for c in cubes if c.name in GLOW_CUBES], TEX_W, height)
    return model, tex, glow, height



# ================================================================== the rig: how the parts move (`Rig` in the Kotlin is this, line by line)

DEG = math.pi / 180.0

# Degrees unless said. w is the walk phase (walkAnimationPos * 0.6662, the limbs' own), g the gait (the walk speed up to 1, times GAIT_SPRINT
# when sprinting: the game's own speed saturates at 1, so a sprint needs the flag), age the state's ageInTicks.
GAIT_SPRINT = 1.35
ROLL, ROCK, BOB, IDLE = 1.6, 1.9, 0.26, 0.35        # the pack: sways about z with the stride, rocks about x and bobs (px) with each step, breathes
TWIST = 1.4                                           # and twists a little about y with the shoulders
SLUMP, SLUMP_LAG = 2.6, 0.8                           # a sack hung by its neck: swings behind the pack's own rock
TUFT_X, TUFT_Z = 17.0, 11.0                           # the loose neck of a sack
TAIL_X, TAIL_Z = 23.0, 13.0                           # the lashings' loose ends
SASH_X, SASH_Z = 9.0, 8.0                             # the sash's knot at the hip
SASH_TAIL_X, SASH_TAIL_Z = 20.0, 10.0                 # its two tails
KNOT_REST, TAIL_REST = 2.5, 3.0                       # they hang this far (degrees) clear of the jacket at a stand and only ever swing outward from it
THIGH_PUSH = 0.95                                     # the tails ride up on the left thigh as it comes forward: this much of its angle
ROLL_ROCK = 2.4                                       # the blanket roll rocks on its lashings
LANT_MAX, LANT_SIDE, LANT_IDLE, LANT_PHASE = 31.0, 10.0, 2.6, 1.1      # the lantern: backward only, 0 to LANT_MAX; side sway; at a stand it breathes
CUP_MAX, CUP_SIDE, CUP_PHASE = 36.0, 14.0, 2.9        # the cup on its peg: backward only
POT_MAX, POT_SIDE = 21.0, 8.0                         # the pots: backward only; the sideways sway that makes them meet
POT_PHASE_FA = (0.4, 1.3)                             # the pots' fore-aft phases (clay, iron)
POT_PHASE_SIDE = (0.0, 0.9)
POT_CONTACT = 6.5                                     # how far (degrees, the two leaning toward each other added) the pots can lean in before they meet
LID_MAX = 11.0
CROWN_REST = -9.0                                     # the crown leans back off the head at rest (degrees about x; negative: its top goes back)
CROWN_WOBBLE = 1.5                                    # the tall frame's crown flexes on the stride
SNEAK_FLEX = 0.62                                     # the crown folds back by this much of the body's lean (sneak: 0.5 rad)
SNEAK_LEAN = 28.65                                    # the body's lean sneaking (0.5 rad), degrees: what the slide below is scaled to
SNEAK_SLIDE = (0.9, 1.0)                              # px (down, back) the pack rides on the back at a full sneak, clear of the head that has dipped
LEAN_HANG = 38.0                                      # a hanging thing counter-turns the body's lean up to this much (degrees) to hang plumb
RATTLE = 2.0                                          # a sprint's rattle: what a hanging thing adds (degrees)
APPEAR_FALL = 2.4                                     # px: the pack comes down onto the back as it appears
FLICKER_MEAN = 0.9
# What the wearer's own motion adds on top of the stride (the layer's springs: starts, stops, turns, landings), softly capped (c * tanh(x / c)) so that it
# can never push the pack through the back, a thigh or a swinging arm: the pitch (degrees; the bottom of the pack swinging back / forward), the roll (degrees
# each way), the bob (px, down / up: the foot of the pack is just above a thigh swung back, so down is only free while the legs are near upright), the dangle
# (degrees, what hangs on the pack swinging back more).
SPRING_PITCH = (7.0, 3.0)
SPRING_ROLL = 2.0
SPRING_BOB = (1.2, 0.2)
BOB_TIGHT, BOB_TIGHT_LEG = 0.3, 50.0                  # a thigh swung back this far (degrees) leaves the pack this much to go down
SPRING_DANGLE = 45.0
# the springs at the most the layer's own hinges reach (pitch 0.42 rad, roll 0.36, bob 2.5 px, dangle 0.9 rad), in every sign: what the checks put the rig through
SPRING_LIMITS = tuple((sp * 0.42, sr * 0.36, sb * 2.5, dg * 0.9) for sp in (1, -1) for sr in (1, -1) for sb in (1, -1) for dg in (0.0, 1.0))


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def gait(walk_speed, sprinting=False):
    """The gait the rig works from: the walk speed to 1, a sprint's GAIT_SPRINT times it ([sprinting]: a flag, or how far into the sprint, 0 to 1)."""
    return clamp(walk_speed, 0.0, 1.0) * (1.0 + (GAIT_SPRINT - 1.0) * clamp(float(sprinting), 0.0, 1.0))


def soft(x, c):
    """[x] kept inside plus or minus [c] without a corner: c * tanh(x / c)."""
    return c * math.tanh(x / c)


def soft2(x, up, down):
    """[x] softly capped at [up] above and [down] below zero."""
    return soft(x, up) if x > 0.0 else soft(x, down)


def spring_in(spring, leg=0.0):
    """The layer's springs (pitch rad, roll rad, bob px, dangle rad) as the rig takes them, capped: (pitch deg, roll deg, bob px, dangle deg). [leg]: the left
    thigh's turn about x, degrees (the stride's other thigh turns the opposite way by as much)."""
    pitch, roll, bob, dangle = spring
    down = SPRING_BOB[0] + (BOB_TIGHT - SPRING_BOB[0]) * clamp(abs(leg) / BOB_TIGHT_LEG, 0.0, 1.0)
    return (soft2(pitch / DEG, *SPRING_PITCH), soft(roll / DEG, SPRING_ROLL), soft2(bob, down, SPRING_BOB[1]), soft(dangle / DEG, SPRING_DANGLE))


def rattle(g):
    """0 at a walk, 1 flat out: how much the load rattles."""
    return clamp((g - 1.0) / (GAIT_SPRINT - 1.0), 0.0, 1.0)


def appear_ease(t):
    """A pack appearing as [t] runs 0 to 1: it comes down onto the back, eased out, with a slight overshoot that settles."""
    if t >= 1.0:
        return 1.0
    c = t - 1.0
    return 1.0 + 1.9 * c * c * c + 0.9 * c * c


def flicker(age):
    """The lantern's flame: a brightness (the glow layer's alpha), 0.78 to 1, three sines and no two alike."""
    return FLICKER_MEAN + 0.055 * math.sin(age * 0.93) + 0.03 * math.sin(age * 2.31 + 1.0) + 0.02 * math.sin(age * 5.13 + 2.0)


def hang_lean(lean):
    """What a thing hung from the pack turns to hang plumb: the body's lean (degrees, sneaking, swimming) taken off, to LEAN_HANG."""
    return -clamp(lean, -LEAN_HANG, LEAN_HANG)


def pend(w, g, age, a_x, a_z, lag, k, idle=2.0):
    """A loose end hung from its top: a pendulum about x and z, a wave with [lag] behind its neighbours, a flutter at a stand. [k] tells
    it from the others. Returns (x, z) in degrees."""
    ph = 2.0 * w - lag
    x = a_x * g * math.sin(ph + 0.4 * k) + idle * math.sin(age * 0.09 + k * 0.8)
    z = a_z * g * math.sin(w - 0.5 * lag + 1.3 * k) + 0.6 * idle * math.sin(age * 0.063 + k * 1.3)
    return x, z


def pack_sway(w, g, age):
    """The pack on its straps: (rock about x, roll about z, bob in px, twist about y)."""
    roll = ROLL * g * math.sin(w + 0.35) + IDLE * math.sin(age * 0.071)
    rock = ROCK * g * math.sin(2.0 * w + 0.6) + 0.6 * IDLE * math.sin(age * 0.053 + 1.2)
    bob = BOB * g * math.sin(2.0 * w + 0.9)
    twist = TWIST * g * math.sin(w + 1.9)
    return rock, roll, bob, twist


def slump(w, g, age, k):
    """A sack hung from its neck lags the pack: (x, z) degrees, relative to the pack."""
    x = SLUMP * g * math.sin(2.0 * w + 0.6 - SLUMP_LAG - 0.5 * k) + 0.5 * math.sin(age * 0.047 + k)
    z = 0.5 * SLUMP * g * math.sin(w + 0.9 + k) + 0.3 * math.sin(age * 0.039 + 2.0 * k)
    return x, z


def lantern_angles(w, g, age, lean, dangle=0.0):
    """The lantern: backward only, 0 to LANT_MAX once a step, harder with speed (a stand breathes it); sways to the side; rattles when
    sprinting; turned to hang plumb; [dangle] (degrees) is what the wearer's starts and stops add to the backward swing: (about x, about z) in degrees."""
    gl = clamp(g / 0.7, 0.0, 1.0)
    u = 0.5 + 0.5 * math.sin(2.0 * w + LANT_PHASE)
    breathe = 0.5 + 0.5 * math.sin(age * 0.083)
    r = rattle(g)
    swing = LANT_IDLE * (1.0 - gl) * breathe + LANT_MAX * gl * u + RATTLE * r * math.sin(age * 1.9)
    side = LANT_SIDE * gl * math.sin(w + LANT_PHASE + 0.8) + 1.4 * (1.0 - gl) * math.sin(age * 0.057 + 0.5) + 0.5 * RATTLE * r * math.sin(age * 2.3)
    return max(0.0, swing + dangle + hang_lean(lean)), side


def cup_angles(w, g, age, lean, dangle=0.0):
    """The cup on its peg: backward only, against the lantern's beat; swings to the side; turned to hang plumb (it rests on the load when the body leans)."""
    gl = clamp(g / 0.7, 0.0, 1.0)
    u = 0.5 + 0.5 * math.sin(2.0 * w + CUP_PHASE)
    swing = 3.0 * (1.0 - gl) * (0.5 + 0.5 * math.sin(age * 0.11 + 1.0)) + CUP_MAX * gl * u + RATTLE * rattle(g) * math.sin(age * 2.1 + 1.0)
    side = CUP_SIDE * gl * math.sin(w + CUP_PHASE + 1.0) + 1.6 * (1.0 - gl) * math.sin(age * 0.067 + 2.0)
    return max(0.0, swing + dangle + hang_lean(lean)), side


def pot_angles(w, g, age, lean, dangle=0.0):
    """The two pots (clay on the left, iron on the right): each swings backward only (the load is behind its back); each leans in and out
    sideways, and when the two lean in (their sum past POT_CONTACT) they meet and bounce apart again as two equal masses would
    (the pair's closing distance reflected about the contact, the common motion kept). Returns ((x, z) of the clay pot, (x, z) of the
    iron one, how far in the pair got before the bounce, in degrees), z positive turning the pot's foot to the player's right."""
    gl = clamp(g / 0.7, 0.0, 1.0)
    r = rattle(g)
    xs, ins = [], []
    for i in range(2):
        u = 0.5 + 0.5 * math.sin(2.0 * w + POT_PHASE_FA[i])
        x = 1.6 * (1.0 - gl) * (0.5 + 0.5 * math.sin(age * 0.09 + 1.7 * i)) + POT_MAX * gl * u + RATTLE * r * math.sin(age * (1.7 + 0.4 * i) + i)
        xs.append(max(0.0, x + dangle + hang_lean(lean)))
        ins.append(POT_SIDE * gl * math.sin(2.0 * w + POT_PHASE_SIDE[i]) + 1.2 * (1.0 - gl) * math.sin(age * 0.061 + 2.3 * i) + 0.5 * RATTLE * r * math.sin(age * 2.7 + i))
    both = ins[0] + ins[1]
    if both > POT_CONTACT:
        over = both - POT_CONTACT
        ins = [ins[0] - over, ins[1] - over]
    return (xs[0], ins[0]), (xs[1], -ins[1]), min(both, POT_CONTACT)


def pots_reach(g):
    """How far the two pots lean toward each other at most at gait [g] (degrees, added): they meet when it is past POT_CONTACT."""
    return POT_SIDE * clamp(g / 0.7, 0.0, 1.0) * 2.0 * math.cos(0.5 * (POT_PHASE_SIDE[0] - POT_PHASE_SIDE[1]))


def clinks(g):
    """Whether the pots meet at gait [g] at all (below it they only sway)."""
    return pots_reach(g) > POT_CONTACT


def clink_beat(walk_pos, g):
    """Which beat the pots are on: it steps up each time the two meet (once a step), the cue for the clink's sound; only while `clinks(g)`."""
    amp = pots_reach(g)
    if amp <= POT_CONTACT:
        return 0
    mid = 0.5 * (POT_PHASE_SIDE[0] + POT_PHASE_SIDE[1])
    return math.floor((2.0 * walk_pos * 0.6662 + mid - math.asin(POT_CONTACT / amp)) / (2.0 * math.pi))


def lid_angle(w, g, age):
    """The iron pot's lid lifts with the pot's swing and chatters when sprinting: how far its front stands up, degrees (turn about x by minus this)."""
    gl = clamp(g / 0.7, 0.0, 1.0)
    lift = LID_MAX * gl * max(0.0, math.sin(2.0 * w + POT_PHASE_FA[1] + 0.9)) ** 1.5
    chatter = 3.0 * rattle(g) * max(0.0, math.sin(age * 2.9))
    return lift + chatter + 0.6 * math.sin(age * 0.2) * (1.0 - gl)


def crown_angle(w, g, age, lean):
    """The tall frame's crown (about x, degrees): it leans back off the head at rest, folds back further as the body leans (a sneak), and flexes a little on the stride."""
    return CROWN_REST - SNEAK_FLEX * clamp(lean, 0.0, 90.0) - CROWN_WOBBLE * g * math.sin(2.0 * w + 1.7) - 0.4 * math.sin(age * 0.043)


KICK = KICK_ANGLE                                      # the kick rails' own turn


def laden_pose(level, walk_pos=0.0, walk_speed=0.0, age=0.0, sprinting=False, lean=0.0, appear=1.0, leg=0.0, spring=(0.0, 0.0, 0.0, 0.0)):
    """The pose of the pack at [level] (0 none, 1 to 3): {part name: pose} as `K.faces_of` takes it. [lean]: how far the body leans from
    upright, degrees (the sneak's 28.6 and a swimmer's tilt), which the hanging things take off to hang plumb. [appear] 0 to 1: the pack
    coming onto the back. [leg]: the left thigh's turn about x, degrees (the vanilla model's own, negative forward), which the sash's tails ride on.
    [spring]: what the wearer's starts, stops, turns and landings add on top of the stride (the layer's own springs, in its units): (pitch, roll) in radians,
    bob in px (down), dangle in radians (what the hanging things swing back more by); capped as `spring_in` caps them."""
    w = walk_pos * 0.6662
    g = gait(walk_speed, sprinting)
    out = {}
    for n in (1, 2, 3):
        if n != level or appear <= 0.02:
            out[f'pack_{n}'] = {'hide': True}
            out[f'straps_{n}'] = {'hide': True}
    if level < 1 or appear <= 0.02:
        return out
    e = appear_ease(appear)
    rock, roll, bob, twist = pack_sway(w, g, age)
    s_pitch, s_roll, s_bob, dang = spring_in(spring, leg)
    drop = -(1.0 - e) * APPEAR_FALL
    sl = clamp(lean / SNEAK_LEAN, 0.0, 1.0)
    out[f'pack_{level}'] = {'rot': (rock + s_pitch, twist, roll + s_roll),
                            'move': (0.0, bob + s_bob + drop + SNEAK_SLIDE[0] * sl, SNEAK_SLIDE[1] * sl), 'scale': max(e, 0.001)}
    out[f'straps_{level}'] = {'scale': max(e, 0.001)}
    if level == 1:
        x, z = slump(w, g, age, 0.0)
        out['sack_1'] = {'rot': (x, 0.0, z)}
        out['neck_1'] = {'rot': (3.5 * g * math.sin(2.0 * w + 1.0), 0.0, 2.0 * g * math.sin(w + 2.0))}
        out['tuft_1'] = {'rot': (TUFT_X * g * math.sin(2.0 * w + 2.0) + 3.0 * math.sin(age * 0.13), 0.0, TUFT_Z * g * math.sin(w + 0.5) + 2.0 * math.sin(age * 0.11))}
        out['roll_1'] = {'rot': (ROLL_ROCK * g * math.sin(2.0 * w + 1.5) + 0.4 * math.sin(age * 0.057), 0.0, 0.0)}
        for k, (sg, n) in enumerate(((1, 'l'), (-1, 'r'))):
            x, z = pend(w, g, age, TAIL_X, TAIL_Z, 0.5 * k, k + 1)
            out[f'tail_1_{n}'] = {'rot': (x, 0.0, -sg * 2.0 + z)}
        sx = SASH_X * g * math.sin(w + 0.3) + 2.0 * math.sin(age * 0.081)
        sz = SASH_Z * g * math.sin(2.0 * w + 0.6) + 1.2 * math.sin(age * 0.067 + 1.0)
        out['sash_knot_1'] = {'rot': (-max(0.0, KNOT_REST + sx), 0.0, sz)}
        tx, tz = pend(w, g, age, SASH_TAIL_X, SASH_TAIL_Z, 0.5, 7)
        out['sash_tails_1'] = {'rot': (-max(0.0, TAIL_REST + tx + THIGH_PUSH * max(0.0, -leg)), 0.0, tz)}
    elif level == 2:
        for k, n in enumerate(('a', 'b')):
            x, z = slump(w, g, age, 0.6 + 1.1 * k)
            out[f'sack_2{n}'] = {'rot': (x, 0.0, z)}
            tx, tz = pend(w, g, age, TAIL_X, TAIL_Z, 0.5 * k, k + 3)
            out[f'tail_2_{n}'] = {'rot': (tx, 0.0, tz)}
        lx, lz = lantern_angles(w, g, age, lean, dang)
        out['lantern_2'] = {'rot': (lx, 0.0, lz)}
        cx, cz = cup_angles(w, g, age, lean, dang)
        out['cup_2'] = {'rot': (cx, 0.0, cz)}
    else:
        x, z = slump(w, g, age, 1.0)
        out['sack_3a'] = {'rot': (x, 0.0, z)}
        ca = crown_angle(w, g, age, lean)
        out['crown_3'] = {'rot': (ca, 0.0, 0.0)}
        out['kick_3_l'] = {'rot': (KICK, 0.0, 0.0)}
        out['kick_3_r'] = {'rot': (KICK, 0.0, 0.0)}
        x, z = slump(w, g, age, 2.0)
        out['sack_3b'] = {'rot': (x - 0.7 * ca, 0.0, z)}
        out['neck_3'] = {'rot': (3.5 * g * math.sin(2.0 * w + 1.0), 0.0, 2.0 * g * math.sin(w + 2.0))}
        out['tuft_3'] = {'rot': (TUFT_X * g * math.sin(2.0 * w + 2.6) + 3.0 * math.sin(age * 0.13 + 1.0), 0.0, TUFT_Z * g * math.sin(w + 1.1) + 2.0 * math.sin(age * 0.11 + 1.0))}
        (cxr, czr), (ixr, izr), _ = pot_angles(w, g, age, lean, dang)
        out['pot_3_l'] = {'rot': (cxr, 0.0, czr)}
        out['pot_3_r'] = {'rot': (ixr, 0.0, izr)}
        out['lid_3'] = {'rot': (-lid_angle(w, g, age), 0.0, 0.0)}
    return out


def dressed_pose(level, walk_pos=0.0, walk_speed=0.0, age=0.0, sprinting=False, crouch=False, appear=1.0, tilt=0.0, bob=True, spring=(0.0, 0.0, 0.0, 0.0)):
    """The player's own pose (`K.humanoid_pose`) and the pack's, together: what one frame of the player wearing the pack poses."""
    pose = K.humanoid_pose(walk_pos, walk_speed, crouch, age, bob)
    lean = (pose['body']['rot'][0] if crouch else 0.0) + tilt
    pose.update(laden_pose(level, walk_pos, walk_speed, age, sprinting, lean, appear, leg=pose['left_leg']['rot'][0], spring=spring))
    return pose



# ================================================================== the numbers read off the built mesh (so the contract cannot drift from the cubes)

def chain_pivot(model, name):
    """A part's pivot in the body's frame (the pivots of its chain added: none of its parents is turned at rest)."""
    def go(p, acc):
        here = tuple(a + b for a, b in zip(acc, p.pivot))
        if p.name == name:
            return here
        for c in p.children:
            r = go(c, here)
            if r is not None:
                return r
        return None
    for p in model.parts:
        r = go(p, (0.0, 0.0, 0.0))
        if r is not None:
            return r
    raise KeyError(name)


def measure(model):
    """The numbers the renderer works from, read off the built mesh: where the packs hang, what each level reaches from the body (half
    width, top y, bottom y, front z, back z: the pack's own parts at rest, its straps not counted), where the lantern's eye is."""
    hide = {f'{k}_{n}': {'hide': True} for n in (1, 2, 3) for k in ('pack', 'straps')}
    extent = {}
    for lv in (1, 2, 3):
        pose = {k: v for k, v in hide.items() if not k.endswith(f'_{lv}')}
        names = {q.name for q in K.find(model, f'pack_{lv}').walk()}
        boxes = [b for b in K.boxes_of(model, pose) if b.part in names]
        lo = np.min([b.lo for b in boxes], axis=0)
        hi = np.max([b.hi for b in boxes], axis=0)
        extent[lv] = tuple(round(float(v), 3) for v in (max(abs(lo[0]), abs(hi[0])), lo[1], hi[1], lo[2], hi[2]))
    return {
        'pivot': tuple(round(v, 4) for v in chain_pivot(model, 'pack_1')),
        'lantern': tuple(round(v, 4) for v in chain_pivot(model, 'lantern_2')),
        'cup': tuple(round(v, 4) for v in chain_pivot(model, 'cup_2')),
        'pegs': (tuple(round(v, 4) for v in chain_pivot(model, 'pot_3_l')), tuple(round(v, 4) for v in chain_pivot(model, 'pot_3_r'))),
        'crown': tuple(round(v, 4) for v in chain_pivot(model, 'crown_3')),
        'extent': extent,
    }


def kt_array(vals):
    return 'floatArrayOf(' + K.kt_list(vals) + ')'


def mesh_function(doc, name, model):
    return (f'    /** {doc} */\n    @Suppress("LocalVariableName", "UNUSED_VARIABLE")\n    private fun {name}(): MeshDefinition {{\n'
            + K.mesh_body(model) + '\n    }\n')


def prune(model, lv):
    """The layer for one level: the model as built, but for the other two levels' packs and straps."""
    drop = {f'{k}_{n}' for n in (1, 2, 3) if n != lv for k in ('straps', 'pack')}

    def clone(p):
        q = K.Part(p.name, p.pivot, p.rot, cubes=p.cubes, children=[clone(c) for c in p.children if c.name not in drop])
        q.abs = getattr(p, 'abs', None)
        return q
    return K.Model(f'laden_pack_{lv}', model.tex_w, model.tex_h, [clone(p) for p in model.parts])


MESH_FUNCTIONS = {1: 'levelOneMesh', 2: 'levelTwoMesh', 3: 'levelThreeMesh'}


# The rig's constants, by the names the Kotlin carries them under (the values are this script's, above).
KT_CONSTS = (
    ('GAIT_SPRINT', GAIT_SPRINT), ('ROLL', ROLL), ('ROCK', ROCK), ('BOB', BOB), ('IDLE', IDLE), ('TWIST', TWIST),
    ('SLUMP', SLUMP), ('SLUMP_LAG', SLUMP_LAG), ('TUFT_X', TUFT_X), ('TUFT_Z', TUFT_Z), ('TAIL_X', TAIL_X), ('TAIL_Z', TAIL_Z),
    ('SASH_X', SASH_X), ('SASH_Z', SASH_Z), ('SASH_TAIL_X', SASH_TAIL_X), ('SASH_TAIL_Z', SASH_TAIL_Z), ('KNOT_REST', KNOT_REST), ('TAIL_REST', TAIL_REST),
    ('THIGH_PUSH', THIGH_PUSH), ('ROLL_ROCK', ROLL_ROCK),
    ('LANT_MAX', LANT_MAX), ('LANT_SIDE', LANT_SIDE), ('LANT_IDLE', LANT_IDLE), ('LANT_PHASE', LANT_PHASE),
    ('CUP_MAX', CUP_MAX), ('CUP_SIDE', CUP_SIDE), ('CUP_PHASE', CUP_PHASE),
    ('POT_MAX', POT_MAX), ('POT_SIDE', POT_SIDE), ('POT_PHASE_FA_L', POT_PHASE_FA[0]), ('POT_PHASE_FA_R', POT_PHASE_FA[1]),
    ('POT_PHASE_SIDE_L', POT_PHASE_SIDE[0]), ('POT_PHASE_SIDE_R', POT_PHASE_SIDE[1]), ('POT_CONTACT', POT_CONTACT), ('LID_MAX', LID_MAX),
    ('CROWN_REST', CROWN_REST), ('CROWN_WOBBLE', CROWN_WOBBLE), ('SNEAK_FLEX', SNEAK_FLEX), ('SNEAK_LEAN', SNEAK_LEAN),
    ('SNEAK_SLIDE_Y', SNEAK_SLIDE[0]), ('SNEAK_SLIDE_Z', SNEAK_SLIDE[1]), ('LEAN_HANG', LEAN_HANG), ('RATTLE', RATTLE), ('APPEAR_FALL', APPEAR_FALL),
    ('SPRING_PITCH_BACK', SPRING_PITCH[0]), ('SPRING_PITCH_FORWARD', SPRING_PITCH[1]), ('SPRING_ROLL', SPRING_ROLL),
    ('SPRING_BOB_DOWN', SPRING_BOB[0]), ('SPRING_BOB_UP', SPRING_BOB[1]), ('BOB_TIGHT', BOB_TIGHT), ('BOB_TIGHT_LEG', BOB_TIGHT_LEG), ('SPRING_DANGLE', SPRING_DANGLE),
)


KOTLIN_HEADER = '''// generated by art/scripts/laden_pack.py, edit the script, not this file
package io.github.vaspyyy.guildmark.client.trade

import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.core.Direction
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
'''

KOTLIN_OBJECT = '''
/**
 * The three back-packs of the laden look (art/scripts/laden_pack.py), box UV on the one [TEX_WIDTH] x [TEX_HEIGHT] texture
 * (textures/entity/trade/laden_pack.png, shipped at 2x that and painted at the higher resolution, the same png for all three packs; the lantern's
 * lit glass is laden_pack_glow.png on the same layout). Model space as the player's own: pixels, y down, the origin at the neck, the player faces
 * -z, his right is -x, the back of his body at z = 2.
 *
 * One layer for each pack: [level1] (25 Marks: a burlap sack with a tied neck, a striped trade blanket rolled and lashed on top of it, a rope sash
 * across the chest), [level2] (100 Marks: an ash frame with two sacks, a pole with an iron eye and a lit lantern, a tin cup on a peg) and [level3]
 * (250 Marks: an oak ladder-frame that climbs past the shoulders in a crown, a plank and a shelf, a sack hung in it and another on the plank, a
 * small keg on the shelf, a clay pot and an iron pot with a lid that clink). Each layer carries the player's own part names, empty but for
 * "body" (so `PlayerModel(root, slim)` can stand on one and pose it as the vanilla armour layers do, or a plain `Model` can draw it after
 * `body.translateAndRotate`), and under "body" the level's "straps_N" (a pad on the back, the chest bands, the belt: they hug the body, 0.1 px
 * outside the jacket, and do not sway; level 1 has only the pad and the sash) and "pack_N" (hangs from [PACK_PIVOT], sways on it; its loose parts
 * are its children). [Rig] shows the pack and poses every loose part: call `pose` after the model's own pose (`setupAnim` resets every part).
 *
 * Draw a level 2 pack twice: the model with `laden_pack.png`, then the same model, in the same pose, with `laden_pack_glow.png` through
 * `RenderTypes.eyes`, tinted [glowColour] (the lantern's flame). See guildmark-notes/living-world/trader/guide/tra_x2.md.
 */
object LadenPackMesh {
    const val TEX_WIDTH = 128
    const val TEX_HEIGHT = @TEX_H@

    /** The rolled blanket and the sack (25 to 99 Marks). */
    fun level1(): LayerDefinition = LayerDefinition.create(levelOneMesh(), TEX_WIDTH, TEX_HEIGHT)

    /** The frame with two sacks and the lantern (100 to 249 Marks). */
    fun level2(): LayerDefinition = LayerDefinition.create(levelTwoMesh(), TEX_WIDTH, TEX_HEIGHT)

    /** The tall frame with sacks, a small barrel and pots (250 Marks and over). */
    fun level3(): LayerDefinition = LayerDefinition.create(levelThreeMesh(), TEX_WIDTH, TEX_HEIGHT)

    /** The layer of [level] (1 to 3). */
    fun layer(level: Int): LayerDefinition = when (level) {
        1 -> level1()
        2 -> level2()
        else -> level3()
    }

@MESHES@

    // ---- the numbers the renderer works from (px, in the body's frame) ----

    /** The Marks at which levels 1, 2 and 3 begin (the laden value at or over each); under the first there is no pack. */
    val LEVEL_FROM: IntArray = intArrayOf(@LEVELS@)

    /** The level for a laden value in Marks: 0 (none) to 3. */
    fun levelFor(marks: Int): Int = if (marks >= LEVEL_FROM[2]) 3 else if (marks >= LEVEL_FROM[1]) 2 else if (marks >= LEVEL_FROM[0]) 1 else 0

    /** Where the packs hang from and sway about: the pivot of every "pack_N" (between the shoulder blades, just off the back). */
    val PACK_PIVOT: FloatArray = @PIVOT@

    /** What each level reaches at rest, as (half width, top y, bottom y, front z, back z), for levels 1, 2 and 3 (the pack's own parts, not its straps). */
    val EXTENT: Array<FloatArray> = arrayOf(
@EXTENT@
    )

    /** The level 2 lantern's pivot (the iron eye it hangs from): the flame is about 3 px under it, the glass 1.7 to 4.2 px under. */
    val LANTERN_PIVOT: FloatArray = @LANTERN@

    /** The lantern flame's mean brightness: [flicker] runs about this much, 0.8 to 1. */
    const val FLICKER_MEAN = @FLICKER_MEAN@

    // ---- the motion: the same numbers and formulas as the script's own previews ----

    private const val DEG = 0.017453292f      // PI / 180
    private const val TWO_PI = 6.2831855f

@CONSTS@

    /**
     * The gait the rig works from: the walk speed up to 1 (the game's own saturates there), [GAIT_SPRINT] times it in a full sprint. [sprint]
     * is how far into the sprint the wearer is, 0 to 1 (a blend, so that the stride does not jump when a sprint starts or stops).
     */
    fun gait(walkSpeed: Float, sprint: Float): Float = walkSpeed.coerceIn(0f, 1f) * (1f + (GAIT_SPRINT - 1f) * sprint.coerceIn(0f, 1f))

    private fun rattle(g: Float): Float = ((g - 1f) / (GAIT_SPRINT - 1f)).coerceIn(0f, 1f)

    /** [x] kept inside plus or minus [c] without a corner. */
    private fun soft(x: Float, c: Float): Float = c * tanh(x / c)

    /** [x] softly capped at [pos] above zero and [neg] below. */
    private fun soft2(x: Float, pos: Float, neg: Float): Float = if (x > 0f) soft(x, pos) else soft(x, neg)

    /** A pack appearing as [t] runs 0 to 1 (over 12 ticks): it comes down onto the back, eased out, with a slight overshoot that settles. */
    fun appearEase(t: Float): Float {
        if (t >= 1f) return 1f
        val c = t - 1f
        return 1f + 1.9f * c * c * c + 0.9f * c * c
    }

    /** The lantern flame's brightness at [age] (ticks, partial included), about 0.8 to 1: three sines and no two alike. */
    fun flicker(age: Float): Float = FLICKER_MEAN + 0.055f * sin(age * 0.93f) + 0.03f * sin(age * 2.31f + 1f) + 0.02f * sin(age * 5.13f + 2f)

    /** The ARGB tint for the glow pass at [age]: white, the flame's brightness ([flicker]) as its alpha. */
    fun glowColour(age: Float): Int = ((flicker(age).coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or 0xFFFFFF

    private fun potsReach(g: Float): Float = POT_SIDE * (g / 0.7f).coerceIn(0f, 1f) * 2f * cos(0.5f * (POT_PHASE_SIDE_L - POT_PHASE_SIDE_R))

    /** Whether the two pots of level 3 meet at all at this speed (below it they only sway). */
    fun clinks(walkSpeed: Float, sprint: Float): Boolean = potsReach(gait(walkSpeed, sprint)) > POT_CONTACT

    /**
     * Which beat the pots are on at [walkPos]: it steps up each time the two meet (once a step), the cue for the clink's sound; zero
     * while they do not ([clinks]). Compare it with the last tick's.
     */
    fun clinkBeat(walkPos: Float, walkSpeed: Float, sprint: Float): Int {
        val amp = potsReach(gait(walkSpeed, sprint))
        if (amp <= POT_CONTACT) return 0
        val mid = 0.5f * (POT_PHASE_SIDE_L + POT_PHASE_SIDE_R)
        return floor((2f * walkPos * 0.6662f + mid - asin(POT_CONTACT / amp)) / TWO_PI).toInt()
    }

    private fun hangLean(lean: Float): Float = -lean.coerceIn(-LEAN_HANG, LEAN_HANG)

    private fun pendX(w: Float, g: Float, age: Float, ax: Float, lag: Float, k: Float): Float =
        ax * g * sin(2f * w - lag + 0.4f * k) + 2f * sin(age * 0.09f + k * 0.8f)

    private fun pendZ(w: Float, g: Float, age: Float, az: Float, lag: Float, k: Float): Float =
        az * g * sin(w - 0.5f * lag + 1.3f * k) + 1.2f * sin(age * 0.063f + k * 1.3f)

    private fun slumpX(w: Float, g: Float, age: Float, k: Float): Float =
        SLUMP * g * sin(2f * w + 0.6f - SLUMP_LAG - 0.5f * k) + 0.5f * sin(age * 0.047f + k)

    private fun slumpZ(w: Float, g: Float, age: Float, k: Float): Float =
        0.5f * SLUMP * g * sin(w + 0.9f + k) + 0.3f * sin(age * 0.039f + 2f * k)

    /** Level 1's loose parts. */
    private class Level1(pack: ModelPart, straps: ModelPart) {
        val sack: ModelPart = pack.getChild("sack_1")
        val neck: ModelPart = sack.getChild("neck_1")
        val tuft: ModelPart = neck.getChild("tuft_1")
        val roll: ModelPart = pack.getChild("roll_1")
        val tails: Array<ModelPart> = arrayOf(roll.getChild("tail_1_l"), roll.getChild("tail_1_r"))
        val knot: ModelPart = straps.getChild("sash_knot_1")
        val sashTails: ModelPart = knot.getChild("sash_tails_1")
    }

    /** Level 2's loose parts. */
    private class Level2(pack: ModelPart) {
        val sacks: Array<ModelPart> = arrayOf(pack.getChild("sack_2a"), pack.getChild("sack_2b"))
        val tails: Array<ModelPart> = arrayOf(pack.getChild("tail_2_a"), pack.getChild("tail_2_b"))
        val lantern: ModelPart = pack.getChild("lantern_2")
        val cup: ModelPart = pack.getChild("cup_2")
    }

    /** Level 3's loose parts. */
    private class Level3(pack: ModelPart) {
        val sackA: ModelPart = pack.getChild("sack_3a")
        val crown: ModelPart = pack.getChild("crown_3")
        val sackB: ModelPart = crown.getChild("sack_3b")
        val neck: ModelPart = sackB.getChild("neck_3")
        val tuft: ModelPart = neck.getChild("tuft_3")
        val potClay: ModelPart = pack.getChild("pot_3_l")
        val potIron: ModelPart = pack.getChild("pot_3_r")
        val lid: ModelPart = potIron.getChild("lid_3")
    }

    /**
     * Poses one level's pack on its baked [layer] ([level] 1 to 3), after the model's own pose has been set (`setupAnim` resets every part): hold one
     * per baked model. [pose] takes [walkPos], [walkSpeed] and [age], the render state's `walkAnimationPos`, `walkAnimationSpeed` and `ageInTicks`;
     * [sprint] (0 to 1) is how far into a sprint the wearer is: it adds the sprint's stride and rattle (the game's walk speed saturates at 1);
     * [appear] 0 to 1 brings the pack onto the back (eased by [appearEase]: run it over 12 ticks, or leave it 1 and scale the whole model instead);
     * [tilt] (radians) is how far the wearer's body is turned from upright when this model's own `body` is not (a pack drawn after
     * `parentModel.body.translateAndRotate` has no lean of its own: pass the body's `xRot`; the sneak's lean slides the pack down and back on the
     * back, folds level 3's crown and lets the hanging things rest on the load instead of swinging through it); [thigh] (radians) is the left
     * thigh's `xRot` (level 1's sash tails ride up on it as it comes forward, and the pack has less room to bounce down when a thigh is swung back;
     * it is read off this model's own `left_leg` unless the pack is drawn in the body's place, where it is the parent model's).
     * [pitch], [roll] (radians), [bob] (px, down) and [dangle] (radians, backward) are what the wearer's starts, stops, turns and landings add on
     * top of the stride, from the caller's own springs: the pack leans, rolls and lifts by them, each softly capped where the legs, the back and the
     * swinging arms leave no room ([SPRING_PITCH_BACK] and the like: past the cap they ease off, they do not stop), and what hangs on it swings
     * back by [dangle] (it can only swing back: forward it rests on the load).
     * The pack sways on its straps (a roll with the stride, a rock, a twist and a bob with each step); its sacks slump behind it; every loose end
     * is a pendulum, a wave running along it; the lantern and the cup swing backward only, the lantern sways and rattles; the pots swing and,
     * leaning in, meet and bounce apart; the lid lifts; the crown leans back off the head, folds as the body leans and flexes on the stride.
     */
    class Rig(root: ModelPart, private val level: Int) {
        private val body: ModelPart = root.getChild("body")
        private val straps: ModelPart = body.getChild("straps_$level")
        private val pack: ModelPart = body.getChild("pack_$level")
        private val packY: Float = pack.initialPose.y
        private val packZ: Float = pack.initialPose.z
        private val legL: ModelPart = root.getChild("left_leg")
        private val one: Level1? = if (level == 1) Level1(pack, straps) else null
        private val two: Level2? = if (level == 2) Level2(pack) else null
        private val three: Level3? = if (level == 3) Level3(pack) else null

        fun pose(
            walkPos: Float, walkSpeed: Float, age: Float, sprint: Float = 0f, appear: Float = 1f, tilt: Float = 0f, thigh: Float = legL.xRot,
            pitch: Float = 0f, roll: Float = 0f, bob: Float = 0f, dangle: Float = 0f,
        ) {
            val show = appear > 0.02f
            straps.visible = show
            pack.visible = show
            if (!show) return
            val w = walkPos * 0.6662f
            val g = gait(walkSpeed, sprint)
            val gl = (g / 0.7f).coerceIn(0f, 1f)
            val r = rattle(g)
            val lean = (body.xRot + tilt) / DEG
            val e = appearEase(appear)
            val s = max(e, 0.001f)
            val sl = (lean / SNEAK_LEAN).coerceIn(0f, 1f)
            val down = SPRING_BOB_DOWN + (BOB_TIGHT - SPRING_BOB_DOWN) * (abs(thigh) / DEG / BOB_TIGHT_LEG).coerceIn(0f, 1f)
            val rock = ROCK * g * sin(2f * w + 0.6f) + 0.6f * IDLE * sin(age * 0.053f + 1.2f) + soft2(pitch / DEG, SPRING_PITCH_BACK, SPRING_PITCH_FORWARD)
            val sway = ROLL * g * sin(w + 0.35f) + IDLE * sin(age * 0.071f) + soft(roll / DEG, SPRING_ROLL)
            val lift = BOB * g * sin(2f * w + 0.9f) + soft2(bob, down, SPRING_BOB_UP)
            val turn = TWIST * g * sin(w + 1.9f)
            val dang = soft(dangle / DEG, SPRING_DANGLE)
            pack.xRot = rock * DEG
            pack.yRot = turn * DEG
            pack.zRot = sway * DEG
            pack.y = packY + lift - (1f - e) * APPEAR_FALL + SNEAK_SLIDE_Y * sl
            pack.z = packZ + SNEAK_SLIDE_Z * sl
            pack.xScale = s
            pack.yScale = s
            pack.zScale = s
            straps.xScale = s
            straps.yScale = s
            straps.zScale = s
            one?.let { poseOne(it, w, g, age, thigh) }
            two?.let { poseTwo(it, w, g, gl, r, age, lean, dang) }
            three?.let { poseThree(it, w, g, gl, r, age, lean, dang) }
        }

        private fun poseOne(p: Level1, w: Float, g: Float, age: Float, thigh: Float) {
            p.sack.xRot = slumpX(w, g, age, 0f) * DEG
            p.sack.zRot = slumpZ(w, g, age, 0f) * DEG
            p.neck.xRot = 3.5f * g * sin(2f * w + 1f) * DEG
            p.neck.zRot = 2f * g * sin(w + 2f) * DEG
            p.tuft.xRot = (TUFT_X * g * sin(2f * w + 2f) + 3f * sin(age * 0.13f)) * DEG
            p.tuft.zRot = (TUFT_Z * g * sin(w + 0.5f) + 2f * sin(age * 0.11f)) * DEG
            p.roll.xRot = (ROLL_ROCK * g * sin(2f * w + 1.5f) + 0.4f * sin(age * 0.057f)) * DEG
            for (i in 0..1) {
                val sg = if (i == 0) 1f else -1f
                p.tails[i].xRot = pendX(w, g, age, TAIL_X, 0.5f * i, i + 1f) * DEG
                p.tails[i].zRot = (-sg * 2f + pendZ(w, g, age, TAIL_Z, 0.5f * i, i + 1f)) * DEG
            }
            p.knot.xRot = -max(0f, KNOT_REST + SASH_X * g * sin(w + 0.3f) + 2f * sin(age * 0.081f)) * DEG
            p.knot.zRot = (SASH_Z * g * sin(2f * w + 0.6f) + 1.2f * sin(age * 0.067f + 1f)) * DEG
            p.sashTails.xRot = -max(0f, TAIL_REST + pendX(w, g, age, SASH_TAIL_X, 0.5f, 7f) + THIGH_PUSH * max(0f, -thigh / DEG)) * DEG
            p.sashTails.zRot = pendZ(w, g, age, SASH_TAIL_Z, 0.5f, 7f) * DEG
        }

        private fun poseTwo(p: Level2, w: Float, g: Float, gl: Float, r: Float, age: Float, lean: Float, dangle: Float) {
            for (i in 0..1) {
                val k = 0.6f + 1.1f * i
                p.sacks[i].xRot = slumpX(w, g, age, k) * DEG
                p.sacks[i].zRot = slumpZ(w, g, age, k) * DEG
                p.tails[i].xRot = pendX(w, g, age, TAIL_X, 0.5f * i, i + 3f) * DEG
                p.tails[i].zRot = pendZ(w, g, age, TAIL_Z, 0.5f * i, i + 3f) * DEG
            }
            val u = 0.5f + 0.5f * sin(2f * w + LANT_PHASE)
            val breathe = 0.5f + 0.5f * sin(age * 0.083f)
            val swing = LANT_IDLE * (1f - gl) * breathe + LANT_MAX * gl * u + RATTLE * r * sin(age * 1.9f)
            p.lantern.xRot = max(0f, swing + dangle + hangLean(lean)) * DEG
            p.lantern.zRot = (LANT_SIDE * gl * sin(w + LANT_PHASE + 0.8f) + 1.4f * (1f - gl) * sin(age * 0.057f + 0.5f) + 0.5f * RATTLE * r * sin(age * 2.3f)) * DEG
            val cu = 0.5f + 0.5f * sin(2f * w + CUP_PHASE)
            val cswing = 3f * (1f - gl) * (0.5f + 0.5f * sin(age * 0.11f + 1f)) + CUP_MAX * gl * cu + RATTLE * r * sin(age * 2.1f + 1f)
            p.cup.xRot = max(0f, cswing + dangle + hangLean(lean)) * DEG
            p.cup.zRot = (CUP_SIDE * gl * sin(w + CUP_PHASE + 1f) + 1.6f * (1f - gl) * sin(age * 0.067f + 2f)) * DEG
        }

        private fun poseThree(p: Level3, w: Float, g: Float, gl: Float, r: Float, age: Float, lean: Float, dangle: Float) {
            p.sackA.xRot = slumpX(w, g, age, 1f) * DEG
            p.sackA.zRot = slumpZ(w, g, age, 1f) * DEG
            val ca = CROWN_REST - SNEAK_FLEX * lean.coerceIn(0f, 90f) - CROWN_WOBBLE * g * sin(2f * w + 1.7f) - 0.4f * sin(age * 0.043f)
            p.crown.xRot = ca * DEG
            p.sackB.xRot = (slumpX(w, g, age, 2f) - 0.7f * ca) * DEG
            p.sackB.zRot = slumpZ(w, g, age, 2f) * DEG
            p.neck.xRot = 3.5f * g * sin(2f * w + 1f) * DEG
            p.neck.zRot = 2f * g * sin(w + 2f) * DEG
            p.tuft.xRot = (TUFT_X * g * sin(2f * w + 2.6f) + 3f * sin(age * 0.13f + 1f)) * DEG
            p.tuft.zRot = (TUFT_Z * g * sin(w + 1.1f) + 2f * sin(age * 0.11f + 1f)) * DEG
            val ux = 0.5f + 0.5f * sin(2f * w + POT_PHASE_FA_L)
            val ix = 0.5f + 0.5f * sin(2f * w + POT_PHASE_FA_R)
            val xl = 1.6f * (1f - gl) * (0.5f + 0.5f * sin(age * 0.09f)) + POT_MAX * gl * ux + RATTLE * r * sin(age * 1.7f)
            val xr = 1.6f * (1f - gl) * (0.5f + 0.5f * sin(age * 0.09f + 1.7f)) + POT_MAX * gl * ix + RATTLE * r * sin(age * 2.1f + 1f)
            var inL = POT_SIDE * gl * sin(2f * w + POT_PHASE_SIDE_L) + 1.2f * (1f - gl) * sin(age * 0.061f) + 0.5f * RATTLE * r * sin(age * 2.7f)
            var inR = POT_SIDE * gl * sin(2f * w + POT_PHASE_SIDE_R) + 1.2f * (1f - gl) * sin(age * 0.061f + 2.3f) + 0.5f * RATTLE * r * sin(age * 2.7f + 1f)
            if (inL + inR > POT_CONTACT) {
                val over = inL + inR - POT_CONTACT
                inL -= over
                inR -= over
            }
            p.potClay.xRot = max(0f, xl + dangle + hangLean(lean)) * DEG
            p.potClay.zRot = inL * DEG
            p.potIron.xRot = max(0f, xr + dangle + hangLean(lean)) * DEG
            p.potIron.zRot = -inR * DEG
            val lf = max(0f, sin(2f * w + POT_PHASE_FA_R + 0.9f))
            val chatter = 3f * r * max(0f, sin(age * 2.9f))
            p.lid.xRot = -(LID_MAX * gl * lf * sqrt(lf) + chatter + 0.6f * sin(age * 0.2f) * (1f - gl)) * DEG
        }
    }
}
'''


def kotlin_source(model, height):
    """LadenPackMesh.kt, from the built mesh and the rig's numbers."""
    m = measure(model)
    ex = ',\n'.join('        ' + kt_array(m['extent'][lv]) for lv in (1, 2, 3)) + ','
    docs = {1: 'Level 1: the rolled blanket and the sack, the sash.', 2: 'Level 2: the frame with two sacks, the lantern and the cup.',
            3: 'Level 3: the tall frame with sacks, the barrel and the pots.'}
    meshes = '\n'.join(mesh_function(docs[lv], MESH_FUNCTIONS[lv], prune(model, lv)) for lv in (1, 2, 3)).rstrip('\n')
    tok = {
        'TEX_H': str(height), 'MESHES': meshes,
        'LEVELS': ', '.join(str(v) for v in LEVELS),
        'PIVOT': kt_array(m['pivot']), 'EXTENT': ex, 'LANTERN': kt_array(m['lantern']),
        'FLICKER_MEAN': K.kt_float(FLICKER_MEAN),
        'CONSTS': '\n'.join(f'    private const val {n} = {K.kt_float(v)}' for n, v in KT_CONSTS),
    }
    body = KOTLIN_OBJECT
    for k, v in tok.items():
        body = body.replace('@' + k + '@', v)
    assert '@' not in body.replace('@Suppress', ''), 'a token is left in the template'
    return KOTLIN_HEADER + body



# ================================================================== the fit and clip check: what moves must not go through the wearer

# The wearer's armour, as the game builds it (ArmorModelSet: outer 1.0 on the head, body and arms of a helmet and a chestplate, 0.5 on the legs
# of leggings), as stand-ins for the clip check; and the cape (PlayerCapeModel: a 10 x 16 x 1 slab hung from (0, 0, 2) on the body, leaning
# 6 + lean / 2 + flap degrees; 0.85 px higher and 1.1 px further back under a chestplate).
def armour_stand_in():
    return K.Model('armour', 64, 64, [
        K._vpart('head', (0, 0, 0), [K._vbox('helmet', (0, 0), -4, -8, -4, 8, 8, 8, 1.0)]),
        K._vpart('body', (0, 0, 0), [K._vbox('chestplate', (16, 16), -4, 0, -2, 8, 12, 4, 1.0)]),
        K._vpart('right_arm', (-5, 2, 0), [K._vbox('chest_arm_r', (40, 16), -3, -2, -2, 4, 12, 4, 1.0)]),
        K._vpart('left_arm', (5, 2, 0), [K._vbox('chest_arm_l', (32, 48), -1, -2, -2, 4, 12, 4, 1.0)]),
        K._vpart('right_leg', (-1.9, 12, 0), [K._vbox('leggings_r', (0, 16), -2, 0, -2, 4, 12, 4, 0.5)]),
        K._vpart('left_leg', (1.9, 12, 0), [K._vbox('leggings_l', (16, 48), -2, 0, -2, 4, 12, 4, 0.5)]),
    ])


def cape_stand_in(angle, chestplate=False):
    """The cape hung from the body (so it follows the body's lean), leaning [angle] degrees."""
    body = K._vpart('body', (0, 0, 0))
    z0, y0 = (3.1, -0.85) if chestplate else (2.0, 0.0)
    K._vpart('cape', (0, y0, z0), [K._vbox('cape', (0, 0), -5, 0, 0, 10, 16, 1)], rot=(angle, 0, 0), parent=body)
    return K.Model('cape', 64, 64, [body])


CAPE_ANGLES = (6.0, 14.0, 30.0)                        # a stand; a walk; a run


def fit_poses(level):
    """The poses the clip check samples (label, dressed_pose's arguments + head): standing, walking and sprinting through the stride, sneaking
    (standing and walking), the head up, down and turned as far as a mob's look goes, the body leaned over (a bow, a swimmer's tilt)."""
    out = [('stand', dict())]
    for i in range(10):
        out.append((f'walk {i}', dict(walk_pos=i * 1.1, walk_speed=0.55, age=i * 7.0)))
        out.append((f'brisk {i}', dict(walk_pos=i * 1.3 + 0.5, walk_speed=0.9, age=i * 5.0)))
        out.append((f'sprint {i}', dict(walk_pos=i * 1.4 + 0.2, walk_speed=1.0, sprinting=True, age=i * 4.0)))
    out.append(('sneak', dict(crouch=True)))
    for i in range(6):
        out.append((f'sneak walk {i}', dict(crouch=True, walk_pos=i * 1.7, walk_speed=0.3, age=i * 6.0)))
    for h in (-60.0, -30.0, 30.0, 60.0, 90.0):
        out.append((f'head pitch {h:g}', dict(head_pitch=h)))
        out.append((f'sneak head pitch {h:g}', dict(crouch=True, head_pitch=h)))
    for y in (-50.0, 50.0):
        out.append((f'head yaw {y:g}', dict(head_yaw=y)))
    return out


def full_pose(level, args):
    a = dict(args)
    pitch, yaw = a.pop('head_pitch', 0.0), a.pop('head_yaw', 0.0)
    pose = dressed_pose(level, **a)
    for n in ('head', 'hat'):
        pose[n] = {**pose[n], 'rot': (pitch, yaw, 0.0)}
    return pose


def clip_depths(pack_boxes, other_boxes, skip=()):
    out = {}
    for a in pack_boxes:
        for b in other_boxes:
            if np.any(a.lo > b.hi) or np.any(b.lo > a.hi):
                continue
            d = K.box_depth(a, b)
            if d > 0.0 and not (K.box_inside(a, b) or K.box_inside(b, a)):
                key = (a.cube, b.cube)
                if key not in skip:
                    out[key] = d
    return out


def clip_table(model, level, poses=None, who='player'):
    """The pack's cubes against the wearer's cubes ([who]: 'player' the vanilla player's, 'armour' the armour stand-in, 'cape' the cape at a
    stand's, a walk's and a run's lean, with and without a chestplate): {(pack cube, other cube): (the deepest overlap beyond what the same
    pair does standing, px; the pose)}."""
    others = {'player': [K.vanilla_player()], 'armour': [armour_stand_in()],
              'cape': [cape_stand_in(a, c) for a in CAPE_ANGLES for c in (False, True)]}[who]
    worst, rest = {}, {}
    for label, args in poses or fit_poses(level):
        pose = full_pose(level, args)
        pack_boxes = [b for b in K.boxes_of(model, pose) if b.cube != 'none']
        for k, other in enumerate(others):
            op = dict(pose)
            ob = K.boxes_of(other, op)
            if who == 'cape':                                           # the cape's own pose: the body's lean carries it; its angle is its model's
                ob = K.boxes_of(other, {kk: vv for kk, vv in op.items() if kk in ('body',)})
            if k not in rest:
                rest[k] = clip_depths([b for b in K.boxes_of(model, full_pose(level, {})) if b.cube != 'none'],
                                      K.boxes_of(other, {kk: vv for kk, vv in full_pose(level, {}).items() if who != 'cape' or kk == 'body'}))
            for key, d in clip_depths(pack_boxes, ob).items():
                extra = d - rest[k].get(key, 0.0)
                if extra > worst.get(key, (0.0, ''))[0]:
                    worst[key] = (extra, label + (f' (cape {CAPE_ANGLES[k // 2]:g}{", chestplate" if k % 2 else ""})' if who == 'cape' else ''))
    return worst



# ================================================================== the checks the run enforces

CLIP_LIMIT = 0.6        # px a part of the pack may go into the wearer beyond what it does standing: a fraction of a texel (the HD texture is 2 texels to the px)
SLEEVE_LAP = 0.7        # a garment (belt, bands) lies under a sleeve the way the jacket does: the vanilla sleeve overlaps the jacket by 0.5 px and the belt stands 0.17 outside it
GAP_LIMIT = 0.15        # px: a part that touches nothing of the rest of the pack by less than this is floating
GARMENT = ('straps_1', 'straps_2', 'straps_3', 'sash_1', 'sash_knot_1', 'sash_tails_1')       # what is worn on the body, hugging it
WORN_ON = ('head', 'hat', 'body', 'jacket')
WORN_ON_ARMOUR = WORN_ON + ('helmet', 'chestplate')
EXTENT_CONTRACT = {1: (3.5, 0.65, 10.2, 3.9, 10.32), 2: (3.55, 0.8, 10.2, 3.85, 12.55), 3: (3.6, -6.45, 10.2, 3.85, 12.0)}      # (half width, top, bottom, front, back) of each pack at rest
SLEEVES = ('right_arm', 'left_arm', 'right_sleeve', 'left_sleeve')


def cube_owners(model):
    """{cube name: the name of the part that carries it}."""
    return {c.name: p.name for p in model.all_parts() for c in p.cubes}


def enforced_poses():
    """The poses the enforced check samples: standing; walking, brisk and sprinting through the stride (the sprint's rattle and the thigh at 80
    degrees); sneaking and sneak-walking; the head up 20 degrees (10 sneaking) and down 60, and turned 50 either way."""
    out = [('stand', {})]
    for i in range(10):
        out.append((f'walk {i}', dict(walk_pos=i * 1.1, walk_speed=0.55, age=i * 7.0)))
        out.append((f'brisk {i}', dict(walk_pos=i * 1.3 + 0.5, walk_speed=0.9, age=i * 5.0)))
        out.append((f'sprint {i}', dict(walk_pos=i * 1.4 + 0.2, walk_speed=1.0, sprinting=True, age=i * 4.0)))
    out.append(('sneak', dict(crouch=True)))
    for i in range(6):
        out.append((f'sneak walk {i}', dict(crouch=True, walk_pos=i * 1.7, walk_speed=0.3, age=i * 6.0)))
    for hp in (-20.0, 30.0, 60.0):
        out.append((f'head pitch {hp:g}', dict(head_pitch=hp)))
    for hp in (-10.0, 30.0, 60.0):
        out.append((f'sneak head pitch {hp:g}', dict(crouch=True, head_pitch=hp)))
    for y in (-50.0, 50.0):
        out.append((f'head yaw {y:g}', dict(head_yaw=y)))
    for i, spring in enumerate(SPRING_LIMITS):                                        # the layer's springs at their limits, at three points of a sprint's stride and a sneak
        for k in range(3):
            out.append((f'spring {i} sprint {k}', dict(walk_pos=i * 0.9 + k * 2.1, walk_speed=1.0, sprinting=True, age=i * 3.0 + k * 11.0, spring=spring)))
        out.append((f'spring {i} stand', dict(spring=spring)))
        out.append((f'sneak spring {i}', dict(crouch=True, walk_pos=i * 1.3, walk_speed=0.3, age=i * 6.0, spring=spring)))
    return out


def limit_for(owner, other, label):
    """The depth beyond which [owner]'s cube going into the wearer's [other] cube in the pose [label] is a fault; None: not checked.
    The pack proper (frame, sacks, lantern, pots ...) is held to CLIP_LIMIT everywhere. What is worn on the body is not checked against the parts it is
    worn on (the head dips into the pad and the straps wherever the neck bends: the vanilla parts overlap there as well), is let lie under a sleeve
    (SLEEVE_LAP), and is not checked against the legs while sneaking (the thighs then stand forward of the leaning jacket: vanilla's pants and jacket
    interpenetrate there too, and the belt goes into the thigh with them)."""
    if owner not in GARMENT:
        return CLIP_LIMIT
    if other in WORN_ON:
        return None
    if other in SLEEVES:
        return SLEEVE_LAP
    if label.startswith('sneak'):
        return None
    return CLIP_LIMIT


def check_fit(model):
    """The clip check of the three levels against the vanilla player over `enforced_poses`: (the problems, {level: {pair: (depth, pose)}})."""
    owner = cube_owners(model)
    problems, tables = [], {}
    for lv in (1, 2, 3):
        worst = clip_table(model, lv, poses=enforced_poses(), who='player')
        tables[lv] = worst
        for (a, b), (d, label) in sorted(worst.items(), key=lambda kv: -kv[1][0]):
            lim = limit_for(owner[a], b, label)
            if lim is not None and d > lim:
                problems.append(f'level {lv}: {a} ({owner[a]}) goes {d:.2f} px into the wearer\'s {b} beyond what it does standing, over {lim:g} (pose "{label}")')
    return problems, tables


# What each part must keep touching (within GAP_LIMIT, in every pose sampled): the part that carries it. A loose end that has come away from what it hangs
# from is floating, however much else it touches. Every part of a pack and of its straps has a row; a part added without one stops the run.
SUPPORT = {
    'sack_1': ('straps_1',), 'neck_1': ('sack_1',), 'tuft_1': ('neck_1',), 'roll_1': ('sack_1',), 'tail_1_l': ('roll_1',), 'tail_1_r': ('roll_1',),
    'sash_1': ('body',), 'sash_knot_1': ('sash_1',), 'sash_tails_1': ('sash_knot_1',),
    'sack_2a': ('pack_2',), 'sack_2b': ('pack_2',), 'tail_2_a': ('sack_2a',), 'tail_2_b': ('sack_2b',), 'lantern_2': ('l2_eye',), 'cup_2': ('l2_peg',),
    'sack_3a': ('pack_3',), 'crown_3': ('kick_3_l', 'kick_3_r'), 'kick_3_l': ('l3_rail_f_l',), 'kick_3_r': ('l3_rail_f_r',), 'sack_3b': ('crown_3', 'pack_3'), 'neck_3': ('sack_3b',),
    'tuft_3': ('neck_3',), 'pot_3_l': ('l3_peg_0',), 'pot_3_r': ('l3_peg_1',), 'lid_3': ('pot_3_r',),
}
# and the packs and the straps on the body: the pad lies against the jacket, the pack's frame against the pad
ANCHORS = {'pack_1': ('straps_1',), 'straps_1': ('body',), 'straps_2': ('body',), 'straps_3': ('body',), 'pack_2': ('straps_2',), 'pack_3': ('straps_3',)}


def attached_poses():
    """The poses the attachment check samples: rest, the stride at its widest (sprinting), sneaking, the springs at their limits."""
    out = [('rest', {})]
    out += [(f'sprint {i}', dict(walk_pos=i * 1.4 + 0.2, walk_speed=1.0, sprinting=True, age=i * 4.0)) for i in range(0, 10, 2)]
    out += [(f'walk {i}', dict(walk_pos=i * 1.1, walk_speed=0.55, age=i * 7.0)) for i in range(0, 10, 3)]
    out += [('sneak', dict(crouch=True)), ('sneak walk', dict(crouch=True, walk_pos=1.7, walk_speed=0.3, age=6.0))]
    out += [(f'spring {i}', dict(walk_pos=i * 1.7, walk_speed=0.8, age=i * 9.0, sprinting=i % 2 == 0, spring=SPRING_LIMITS[i % len(SPRING_LIMITS)])) for i in range(len(SPRING_LIMITS))]
    return out


def check_attached(model):
    """Nothing floats: over the sampled poses every part of a pack and of its straps stays within GAP_LIMIT px of a cube of the part that carries it
    (`SUPPORT`, `ANCHORS`). Returns the problems."""
    problems = []
    player = K.vanilla_player()
    for lv in (1, 2, 3):
        mine = {q.name for n in (f'pack_{lv}', f'straps_{lv}') for q in K.find(model, n).walk()}
        rows = {n: s for n, s in {**SUPPORT, **ANCHORS}.items() if n in mine}
        for n in sorted(mine - set(rows)):
            problems.append(f'level {lv}: the part {n} has no row in SUPPORT (what carries it)')
        for label, args in attached_poses():
            pose = full_pose(lv, args)
            by_part, by_cube = {}, {}
            for b in K.boxes_of(model, pose):
                by_part.setdefault(b.part, []).append(b)
                by_cube.setdefault(b.cube, []).append(b)
            for b in K.boxes_of(player, pose):
                if b.part in ('body', 'jacket'):
                    by_part.setdefault('body', []).append(b)
            for name, carriers in sorted(rows.items()):
                best = 1e9
                for a in by_part.get(name, ()):
                    for carrier in carriers:                                          # a carrier is a part, or one cube (a peg, an eye, a rail)
                        for b in by_part.get(carrier) or by_cube[carrier]:
                            if np.any(a.lo - b.hi > GAP_LIMIT) or np.any(b.lo - a.hi > GAP_LIMIT):
                                continue                                              # the boxes' bounds are apart already
                            best = min(best, -K.box_depth(a, b))
                if name in by_part and best > GAP_LIMIT:
                    how = 'touches none of it' if best > 1e8 else f'{best:.2f} px clear'
                    problems.append(f'level {lv}: the part {name} comes away from {"/".join(carriers)} ({how}) in the pose "{label}"')
    return problems


def head_up_clear(model, level, crouch, helmet=False):
    """How far the head can look up (degrees, 5 at a time) before the pack proper (not what is worn) goes more than CLIP_LIMIT into the head or hat (or,
    with [helmet], into the stand-in helmet)."""
    names = {q.name for q in K.find(model, f'pack_{level}').walk()}
    head = armour_stand_in() if helmet else K.vanilla_player()
    heads = ('helmet',) if helmet else ('head', 'hat')
    last = 0.0
    for hp in range(0, 91, 5):
        pose = full_pose(level, dict(head_pitch=-float(hp), crouch=crouch))
        pack = [b for b in K.boxes_of(model, pose) if b.part in names]
        other = [b for b in K.boxes_of(head, pose) if b.cube in heads]
        base_pose = full_pose(level, dict(crouch=crouch))
        base = clip_depths([b for b in K.boxes_of(model, base_pose) if b.part in names], [b for b in K.boxes_of(head, base_pose) if b.cube in heads])
        worst = max([d - base.get(k, 0.0) for k, d in clip_depths(pack, other).items()] or [0.0])
        if worst > CLIP_LIMIT:
            return last
        last = float(hp)
    return last


def fit_report(model):
    """Lines for the page: per level, the head-up angle that is clear, the worst of the armour and the cape stand-ins."""
    lines = []
    owner = cube_owners(model)
    for lv in (1, 2, 3):
        parts = [f'level {lv}: head up clear to {head_up_clear(model, lv, False):g} degrees standing, {head_up_clear(model, lv, True):g} sneaking, '
                 f'{head_up_clear(model, lv, False, helmet=True):g} standing in a helmet']
        for who in ('armour',):
            worst = clip_table(model, lv, poses=enforced_poses(), who=who)
            top = sorted(((d, a, b, lab) for (a, b), (d, lab) in worst.items() if owner[a] not in GARMENT or b not in WORN_ON_ARMOUR), reverse=True)[:1]
            if top:
                d, a, b, lab = top[0]
                parts.append(f'{who}: worst {d:.2f} px ({a} into {b}, {lab})')
            else:
                parts.append(f'{who}: clear')
        lines.append('; '.join(parts))
    return lines


# ================================================================== the self-check: the numbers in the page above against the cubes, and the files against the cubes

VANILLA_PARTS = {'head', 'hat', 'body', 'jacket', 'right_arm', 'left_arm', 'right_sleeve', 'left_sleeve', 'right_leg', 'left_leg', 'right_pants', 'left_pants'}
RIG_PARTS = {'straps_1', 'straps_2', 'straps_3', 'pack_1', 'pack_2', 'pack_3', 'sack_1', 'neck_1', 'tuft_1', 'roll_1', 'tail_1_l', 'tail_1_r', 'sash_1', 'sash_knot_1',
             'sash_tails_1', 'sack_2a', 'sack_2b', 'tail_2_a', 'tail_2_b', 'lantern_2', 'cup_2', 'sack_3a', 'crown_3', 'kick_3_l', 'kick_3_r', 'sack_3b', 'neck_3', 'tuft_3',
             'pot_3_l', 'pot_3_r', 'lid_3'}
# what the Kotlin Rig poses each frame: the rest of RIG_PARTS is looked up by level (pack_N, straps_N) or stands at its own rest turn (the sash, the kick rails)
RIG_POSED = RIG_PARTS - {'straps_1', 'straps_2', 'straps_3', 'pack_1', 'pack_2', 'pack_3', 'sash_1', 'kick_3_l', 'kick_3_r'}


def check_glow(model, glow, height):
    """The lit-glass texture paints the lantern's glass and nothing else, on all of its faces."""
    problems = []
    a = np.array(glow.convert('RGBA'))[..., 3] > 8
    covered = np.zeros_like(a)
    for c in model.all_cubes():
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w <= 0 or h <= 0 or not c.shows(face):
                continue
            if c.name in GLOW_CUBES:
                covered[y:y + h, x:x + w] = True
                if not a[y:y + h, x:x + w].any():
                    problems.append(f'the glow texture has nothing on {c.name}.{face}')
    if (a & ~covered).any():
        problems.append('the glow texture paints something other than the lantern glass')
    return problems


def check_contract(model, tex, glow, height):
    """The contract table in this file's docstring, and the part names the vanilla model and the rig look up, against the built mesh; a change to
    the cubes without the page (or the page without the cubes) stops the run. Also the pictures (every face of every cube has paint, none paints over
    another, nothing is outside the texture), the glow texture, the fit and that nothing floats."""
    m = measure(model)
    assert m['pivot'] == (0.0, 1.5, 4.0), m['pivot']
    assert m['lantern'] == (0.0, 5.35, 11.05), m['lantern']
    assert m['cup'] == (-2.5, 4.75, 9.7), m['cup']
    assert m['pegs'] == ((1.9, 1.6, 10.3), (-1.9, 1.6, 10.3)), m['pegs']
    assert m['crown'] == (0.0, 0.8, 4.3), m['crown']
    assert m['extent'] == EXTENT_CONTRACT, m['extent']
    have = set(K.all_names(model))
    assert VANILLA_PARTS <= have, f'the layer lacks the vanilla parts {sorted(VANILLA_PARTS - have)}'
    assert RIG_PARTS <= have, f'the layer lacks the rig parts {sorted(RIG_PARTS - have)}'
    for n in VANILLA_PARTS:                                                       # only the body carries anything of the player's own
        if n != 'body':
            p = K.find(model, n)
            assert not p.cubes and not p.children, f'the vanilla part {n} must stay empty'
    problems = K.check_model(model, tex, (TEX_W, height), who='laden_pack')
    problems += check_glow(model, glow, height)
    problems += check_fit(model)[0]
    problems += check_attached(model)
    K.must_be_clean(problems, 'laden_pack')


def write_all(assets=ASSETS, kotlin_dir=KOTLIN, model_dir=MODELS):
    """Everything the script owns: the two textures, the Kotlin table and the Blockbench file. Returns (model, texture, glow, height)."""
    model, tex, glow, height = build()
    check_contract(model, tex, glow, height)
    tdir = os.path.join(assets, 'textures', 'entity', 'trade')
    os.makedirs(tdir, exist_ok=True)
    tex.save(os.path.join(tdir, 'laden_pack.png'))
    glow.save(os.path.join(tdir, 'laden_pack_glow.png'))
    os.makedirs(kotlin_dir, exist_ok=True)
    with open(os.path.join(kotlin_dir, 'LadenPackMesh.kt'), 'w') as f:
        f.write(kotlin_source(model, height))
    os.makedirs(model_dir, exist_ok=True)
    small = tex.resize((TEX_W, height), Image.LANCZOS)
    with open(os.path.join(model_dir, 'laden_pack.bbmodel'), 'w') as f:
        json.dump(model.bbmodel(small, 'laden_pack'), f)
    check_written(model, tex, glow, height, assets, kotlin_dir, model_dir)
    return model, tex, glow, height


def check_written(model, tex, glow, height, assets, kotlin_dir, model_dir):
    """What was written, read back: the textures' sizes, the Kotlin's mesh function carrying exactly the parts (and the texture offsets) of the model,
    every part the rig looks up, the numbers in its constants, the Blockbench file's cube count."""
    problems = []
    for name in ('laden_pack.png', 'laden_pack_glow.png'):
        png = Image.open(os.path.join(assets, 'textures', 'entity', 'trade', name))
        if png.size != (TEX_W * SCALE, height * SCALE) or png.mode != 'RGBA':
            problems.append(f'{name} is {png.size} {png.mode}')
    src = open(os.path.join(kotlin_dir, 'LadenPackMesh.kt')).read()
    if f'const val TEX_HEIGHT = {height}' not in src or f'const val TEX_WIDTH = {TEX_W}' not in src:
        problems.append('the Kotlin names another texture size')
    cubes = [c for p in model.all_parts() for c in p.cubes]
    want = set(K.all_names(model))
    for lv, fn in MESH_FUNCTIONS.items():                                       # each level's layer: exactly its own parts and cubes, at the model's texture offsets
        i = src.index(f'private fun {fn}()')
        body = src[i:src.index('return mesh', i)]
        level_model = prune(model, lv)
        names = re.findall(r'addOrReplaceChild\("([a-z_0-9]+)"', body)
        if sorted(names) != sorted(K.all_names(level_model)):
            problems.append(f'{fn} has other parts than the model\'s level {lv}: {sorted(set(names) ^ set(K.all_names(level_model)))}')
        offs = re.findall(r'texOffs\((\d+), (\d+)\)', body)
        level_cubes = [c for p in level_model.all_parts() for c in p.cubes]
        if len(offs) != len(level_cubes):
            problems.append(f'{fn} has {len(offs)} cubes, the model\'s level {lv} {len(level_cubes)}')
        elif sorted((int(a), int(b)) for a, b in offs) != sorted(tuple(c.uv) for c in level_cubes):
            problems.append(f'{fn} puts its cubes at other texture offsets than the model')
        j = src.index(f'private class Level{lv}(')
        k = src.find('/**', j)
        held = set(re.findall(r'getChild\("([a-z_0-9]+)"\)', src[j:k if k > 0 else len(src)]))
        if not held <= set(names):
            problems.append(f'the Rig\'s Level{lv} looks up parts that level {lv}\'s layer lacks: {sorted(held - set(names))}')
    looked_up = set(re.findall(r'getChild\("([a-z_0-9]+)"\)', src))
    if not looked_up <= want:
        problems.append(f'the Rig looks up parts the model lacks: {sorted(looked_up - want)}')
    if 'getChild("straps_$level")' not in src or 'getChild("pack_$level")' not in src:
        problems.append('the Rig does not look up the level\'s straps and pack')
    if not RIG_POSED <= looked_up:
        problems.append(f'the Rig leaves parts alone that the page names: {sorted(RIG_POSED - looked_up)}')
    for const, val in KT_CONSTS:
        if f'private const val {const} = {K.kt_float(val)}' not in src:
            problems.append(f'the Kotlin does not carry {const} = {K.kt_float(val)}')
    for lv, e in enumerate(measure(model)['extent'].values()):
        if kt_array(e) not in src:
            problems.append(f'the Kotlin does not carry level {lv + 1}\'s extent {e}')
    bb = json.load(open(os.path.join(model_dir, 'laden_pack.bbmodel')))
    if len(bb['elements']) != len(cubes):
        problems.append(f'laden_pack.bbmodel has {len(bb["elements"])} cubes, the model {len(cubes)}')
    K.must_be_clean(problems, 'laden_pack (written)')


def report(model, height):
    m = measure(model)
    cubes = len(list(model.all_cubes()))
    parts = len(list(model.all_parts()))
    lines = [f'laden_pack: texture {TEX_W} x {height} declared ({TEX_W * SCALE} x {height * SCALE} shipped); {cubes} cubes in {parts} parts (the player\'s skeleton and the three packs)',
             f'  packs hang from {m["pivot"]}; lantern eye at {m["lantern"]}; reach (half width, top, bottom, front, back): ' + ', '.join(f'L{k} {v}' for k, v in m['extent'].items())]
    return '\n'.join(lines + ['  ' + s for s in fit_report(model)])


# ================================================================== the previews (only with a preview dir): sheets and GIFs, the packs on a vanilla player

VIEWS = {'back': (180.0, -8.0), '3/4 back': (145.0, -18.0), 'side': (-90.0, -6.0), '3/4 front': (-35.0, -14.0), 'above': (180.0, -80.0)}
FRAMING = {1: ((0.0, 6.0, 4.0), 12.0), 2: ((0.0, 6.0, 4.0), 12.0), 3: ((0.0, 4.5, 4.0), 11.0)}      # what the camera looks at, and at what px per px, per level
LEVEL_NAME = {1: 'level 1, a blanket roll and a sack (25 Marks)', 2: 'level 2, a frame with a lantern (100 Marks)', 3: 'level 3, a tall frame with pots (250 Marks)'}
STRIDE_WALK = dict(walk_speed=0.86)
STRIDE_SPRINT = dict(walk_speed=1.0, sprinting=True)


def flat(colour):
    return np.full((64, 64, 4), tuple(float(c) for c in colour) + (255.0,))


class Stage:
    """What a preview draws with: the pack's model and textures, the vanilla player with his skin, the stand-ins for armour and cape."""

    def __init__(self, model, tex, glow, game_assets=None):
        self.model, self.tex, self.glow = model, tex, glow
        self.steve, self.steve_tex = K.vanilla_player(), K.Vanilla(game_assets).steve()
        self.armour = armour_stand_in()

    def textures(self):
        return {'steve': self.steve_tex, 'pack': self.tex, 'glow': self.glow, 'armour': flat((150, 152, 170)), 'cape': flat((170, 40, 50))}


def scene(st, players, view, size=(300, 380), scale=12.0, centre=(0.0, 7.5, 4.0)):
    """Players in one picture: each {'level': 0 to 3, 'args': dressed_pose's arguments (+ head_pitch, head_yaw), 'x': px aside, 'armour': stand-in armour on,
    'cape': (angle, with a chestplate) a cape stand-in on}. Level 0 is the bare player; the lantern's glass is lit (the flame's flicker at the pose's age)."""
    faces = []
    for p in players:
        lv, args, x = p.get('level', 0), dict(p.get('args', {})), p.get('x', 0.0)
        T = np.array([x, 0.0, 0.0])
        pose = full_pose(max(lv, 1), args)
        faces += K.faces_of(st.steve, 'steve', s=1, pose=pose, T=T)
        if lv:
            faces += K.faces_of(st.model, 'pack', s=2, pose=pose, T=T)
        if lv == 2:
            faces += K.faces_of(st.model, 'glow', s=2, pose=pose, T=T, glow=True, only=('lantern_2',), tint=(255 * flicker(args.get('age', 0.0)),) * 3)
        if p.get('armour'):
            faces += K.faces_of(st.armour, 'armour', s=1, pose=pose, T=T)
        if p.get('cape'):
            faces += K.faces_of(cape_stand_in(*p['cape']), 'cape', s=1, pose={k: v for k, v in pose.items() if k == 'body'}, T=T)
    yaw, pitch = view if not isinstance(view, str) else VIEWS[view]
    return K.render(faces, st.textures(), K.View(yaw=yaw, pitch=pitch, scale=scale, center=centre, size=size))


def pic(st, level, view, args=None, size=(300, 380), zoom=1.0, **extra):
    """One player wearing [level], seen from [view], framed for that level."""
    centre, scale = FRAMING[level or 1]
    return scene(st, [dict(level=level, args=args or {}, **extra)], view, size=size, scale=scale * zoom, centre=centre)


def sheet_views(st, level):
    tiles = [K.caption(pic(st, level, v, size=(260, 340)), f'{n}') for n, v in VIEWS.items()]
    return K.sheet(tiles, 5, title=f'{LEVEL_NAME[level]}: from the back, the three-quarter, the side, the three-quarter front and above')


def sheet_beside(st):
    """The three packs and the bare player: height, width and depth against the player."""
    tiles = []
    for view in ('3/4 back', 'side'):
        for lv in range(4):
            tiles.append(K.caption(pic(st, lv, view, size=(280, 360), zoom=0.95), 'the vanilla player' if lv == 0 else f'level {lv}, {view}'))
    return K.sheet(tiles, 4, title='The vanilla player (the same skin) and the three packs, the same size')


def sheet_stride(st, level):
    """Six phases of a walk and of a sprint, from the three-quarter back."""
    tiles = []
    for name, stride, k in (('walk', STRIDE_WALK, 0.95), ('sprint', STRIDE_SPRINT, 1.15)):
        for i in range(6):
            args = dict(stride, walk_pos=i * 1.5, age=float(i * 5))
            tiles.append(K.caption(pic(st, level, '3/4 back', args, size=(220, 330), zoom=0.9), f'{name} {i + 1}'))
    return K.sheet(tiles, 6, title=f'{LEVEL_NAME[level]}: a walk (top) and a sprint (below), six points of the stride')


def sheet_sneak(st):
    tiles = []
    for lv in (1, 2, 3):
        for name, view, args in (('sneaking', 'side', dict(crouch=True)), ('sneaking', '3/4 back', dict(crouch=True)),
                                 ('sneak-walking', '3/4 back', dict(crouch=True, walk_speed=0.3, walk_pos=2.0, age=9.0)),
                                 ('sneak-walking', 'side', dict(crouch=True, walk_speed=0.3, walk_pos=4.5, age=17.0))):
            tiles.append(K.caption(pic(st, lv, view, args, size=(240, 330), zoom=0.9), f'L{lv} {name}, {view}'))
    return K.sheet(tiles, 4, title='Sneaking: the pack slides down the back, the tall frame folds its crown, the lantern, the cup and the pots rest on the load')


def sheet_armour(st):
    tiles = []
    cases = (('standing', '3/4 back', dict()), ('sprinting', 'side', dict(STRIDE_SPRINT, walk_pos=3.0, age=11.0)), ('sneaking', 'side', dict(crouch=True)),
             ('looking up 20', 'side', dict(head_pitch=-20.0)))
    for lv in (1, 2, 3):
        for name, view, args in cases:
            tiles.append(K.caption(pic(st, lv, view, args, size=(250, 330), zoom=0.9, armour=True), f'L{lv} armoured, {name}'))
    return K.sheet(tiles, 4, title='In a full set of iron-coloured armour (helmet, chestplate, leggings: stand-ins at the game\'s sizes), nothing of the pack goes into it')


def sheet_cape(st):
    tiles = []
    for lv in (1, 2, 3):
        for ang, chest in ((6.0, False), (14.0, False), (30.0, False), (14.0, True)):
            tiles.append(K.caption(pic(st, lv, 'side', dict(walk_speed=0.5), size=(250, 330), zoom=0.9, cape=(ang, chest)),
                                   f'L{lv}, cape {ang:g} deg' + (' + chestplate' if chest else '')))
    return K.sheet(tiles, 4, title='A cape (stand-in) hangs through the pack: hide the cape while a pack shows (the pack is on the back where the cape hangs)')


def sheet_head(st):
    tiles = []
    clear = {lv: head_up_clear(st.model, lv, False) for lv in (1, 2, 3)}
    for lv in (1, 2, 3):
        for hp in (-20.0, -40.0, -60.0):
            tiles.append(K.caption(pic(st, lv, 'side', dict(head_pitch=hp), size=(250, 330), zoom=0.9), f'L{lv}, head up {-hp:g} deg'))
    return K.sheet(tiles, 3, title='Looking up: the back of the head meets the frame\'s top rail; clear to ' + ', '.join(f'{clear[lv]:g} at level {lv}' for lv in (1, 2, 3)) + ' degrees')


def sheet_springs(st):
    """Level 2 and 3 with the layer's springs driven as hard as its hinges go (pitch 0.42 rad, roll 0.36, bob 2.5 px, dangle 0.9 rad): the rig caps each."""
    tiles = []
    cases = (('pitched back (cap 7 deg)', (0.42, 0.0, 0.0, 0.0)), ('pitched forward (cap 3 deg)', (-0.42, 0.0, 0.0, 0.0)), ('rolled (cap 2 deg)', (0.0, 0.36, 0.0, 0.0)),
             ('bounced down (cap 1.2 px)', (0.0, 0.0, 2.5, 0.0)), ('lifted (cap 0.2 px)', (0.0, 0.0, -2.5, 0.0)), ('dangling (cap 45 deg)', (0.0, 0.0, 0.0, 0.9)))
    for lv in (2, 3):
        for name, spring in cases:
            tiles.append(K.caption(pic(st, lv, 'side', dict(spring=spring), size=(250, 330), zoom=0.9), f'L{lv} {name}'))
    return K.sheet(tiles, 6, title='What the wearer\'s starts, stops and landings may add (the layer\'s springs driven to their hinges\' limits, then capped by the rig)')


def sheet_texture(tex, glow):
    big = lambda im: im.resize((im.width * 3, im.height * 3), Image.NEAREST)
    t, g = big(tex), big(glow)
    bg = Image.new('RGB', (t.width, t.height * 2 + 30), (44, 46, 54))
    bg.paste(t.convert('RGB'), (0, 18), t.split()[3])
    bg.paste(g.convert('RGB'), (0, t.height + 30), g.split()[3])
    return K.caption(bg, f'laden_pack.png (above) and laden_pack_glow.png (below, the lit glass), {tex.width} x {tex.height} (declared {TEX_W} x {tex.height // SCALE}), shown 3x', h=14)


# ---- the GIFs

def gif_walk(st, level, path, speed=0.86, frames=44, view='3/4 back', sprint=False):
    """The pack on a player walking in place (the camera fixed): the stride's sway, the sacks slumping, the loose ends in a wave, the lantern swinging out and back."""
    out = []
    for t in range(frames):
        args = dict(walk_speed=speed, walk_pos=speed * t, age=float(t) + 30.0, sprinting=sprint)
        out.append(pic(st, level, view, args, size=(300, 380), zoom=1.0))
    return K.save_gif(out, path, ms=50)


def gif_together(st, path, frames=88, view=(160.0, -16.0)):
    """The three packs side by side: a walk, then a sprint, then a walk again."""
    out = []
    pos = 0.0
    for t in range(frames):
        sprint = 26 <= t < 62
        sp = 1.0 if sprint else 0.86
        pos += sp
        args = dict(walk_speed=sp, walk_pos=pos, age=float(t) + 30.0, sprinting=sprint)
        out.append(scene(st, [dict(level=lv, args=args, x=(lv - 2) * 21.0) for lv in (1, 2, 3)], view, size=(600, 380), scale=9.4, centre=(0.0, 5.5, 4.0)))
    return K.save_gif(out, path, ms=50)


def gif_sneak(st, path, view=(150.0, -16.0)):
    """Stand, sneak (the body leans, the pack slides, the crown folds, what hangs rests on the load), sneak-walk, stand: all three packs."""
    out = []
    phases = [(14, dict()), (30, dict(crouch=True)), (34, dict(crouch=True, walk_speed=0.3)), (14, dict())]
    pos = 0.0
    t = 0
    for n, a in phases:
        for _ in range(n):
            pos += a.get('walk_speed', 0.0)
            args = dict(a, walk_pos=pos, age=float(t) + 10.0)
            out.append(scene(st, [dict(level=lv, args=args, x=(lv - 2) * 21.0) for lv in (1, 2, 3)], view, size=(600, 380), scale=9.4, centre=(0.0, 5.5, 4.0)))
            t += 1
    return K.save_gif(out, path, ms=50)


def gif_lantern(st, path, frames=64):
    """The level 2 lantern close up from behind: it sways at a stand, swings out and back with the stride (backward only), rattles in a sprint, and its flame flickers."""
    out = []
    pos = 0.0
    for t in range(frames):
        sp = 0.0 if t < 12 else (0.6 if t < 36 else (1.0 if t < 56 else 0.0))
        pos += sp
        args = dict(walk_speed=sp, walk_pos=pos, age=float(t) + 12.0, sprinting=36 <= t < 56)
        out.append(scene(st, [dict(level=2, args=args)], (122.0, -10.0), size=(340, 340), scale=26.0, centre=(0.0, 7.0, 9.5)))
    return K.save_gif(out, path, ms=50)


def gif_pots(st, path, frames=60):
    """The level 3 pots close up: they sway out of step and, from a brisk walk on, lean in and meet (the clink); in a sprint the iron pot's lid chatters."""
    out = []
    pos = 0.0
    for t in range(frames):
        sp = 0.0 if t < 8 else (0.7 if t < 32 else (1.0 if t < 52 else 0.0))
        pos += sp
        args = dict(walk_speed=sp, walk_pos=pos, age=float(t) + 12.0, sprinting=32 <= t < 52)
        out.append(scene(st, [dict(level=3, args=args)], (122.0, -12.0), size=(340, 340), scale=26.0, centre=(0.0, 4.0, 8.5)))
    return K.save_gif(out, path, ms=50)


def gif_appear(st, path):
    """A pack coming onto the back (eased, with a settle), another size popping on from nearly full, and the last going down off it."""
    out = []
    t = 0
    seq = [(1, 0.0, 1.0, 12), (1, 1.0, 1.0, 14), (2, 0.55, 1.0, 8), (2, 1.0, 1.0, 12), (3, 0.55, 1.0, 8), (3, 1.0, 1.0, 14), (3, 1.0, 0.0, 12), (3, 0.0, 0.0, 4)]
    pos = 0.0
    for lv, a0, a1, n in seq:
        for i in range(n):
            ap = a0 + (a1 - a0) * (i + 1) / n
            pos += 0.45
            args = dict(walk_speed=0.5, walk_pos=pos, age=float(t) + 10.0, appear=ap)
            out.append(pic(st, lv, '3/4 back', args, size=(300, 380)))
            t += 1
    return K.save_gif(out, path, ms=50)


def previews(model, tex, glow, out_dir, game_assets=None):
    """Every sheet and GIF into [out_dir] (made if need be); returns the paths. [game_assets]: an unpacked game `assets/minecraft` folder, for the player's
    own skin (a plain stand-in colour where there is none)."""
    st = Stage(model, tex, glow, game_assets)
    os.makedirs(out_dir, exist_ok=True)
    out = []

    def save(img, name):
        p = os.path.join(out_dir, name)
        img.save(p)
        out.append(p)

    for lv in (1, 2, 3):
        save(sheet_views(st, lv), f'laden_pack_level{lv}_views.png')
        save(sheet_stride(st, lv), f'laden_pack_level{lv}_stride.png')
    save(sheet_beside(st), 'laden_pack_beside_vanilla.png')
    save(sheet_sneak(st), 'laden_pack_sneak.png')
    save(sheet_armour(st), 'laden_pack_armour.png')
    save(sheet_cape(st), 'laden_pack_cape.png')
    save(sheet_head(st), 'laden_pack_head_up.png')
    save(sheet_springs(st), 'laden_pack_springs.png')
    save(sheet_texture(tex, glow), 'laden_pack_texture.png')
    for lv in (1, 2, 3):
        out.append(gif_walk(st, lv, os.path.join(out_dir, f'laden_pack_level{lv}_walk.gif')))
    out.append(gif_together(st, os.path.join(out_dir, 'laden_pack_all_walk_sprint.gif')))
    out.append(gif_sneak(st, os.path.join(out_dir, 'laden_pack_sneak.gif')))
    out.append(gif_lantern(st, os.path.join(out_dir, 'laden_pack_lantern_closeup.gif')))
    out.append(gif_pots(st, os.path.join(out_dir, 'laden_pack_pots_closeup.gif')))
    out.append(gif_appear(st, os.path.join(out_dir, 'laden_pack_appear_and_swap.gif')))
    return out


def main(argv):
    assets = argv[1] if len(argv) > 1 and argv[1] != '-' else ASSETS
    kotlin_dir = argv[2] if len(argv) > 2 and argv[2] != '-' else KOTLIN
    model_dir = argv[3] if len(argv) > 3 and argv[3] != '-' else MODELS
    model, tex, glow, height = write_all(assets, kotlin_dir, model_dir)
    print(report(model, height))
    if len(argv) > 4 and argv[4] != '-':
        for p in previews(model, tex, glow, argv[4], argv[5] if len(argv) > 5 and argv[5] != '-' else None):
            print('preview', p)


if __name__ == '__main__':
    main(sys.argv)
