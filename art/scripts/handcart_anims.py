"""
The townsfolk's clip for pulling a handcart (Activity.PULL_CART): leaning into it, both hands back on the shafts, a
heavier tread. Same format, skeleton and kit as art/scripts/townsfolk_anims.py, whose helpers this uses (importing it
takes about a minute: it solves its own clips as it loads), played by the game's CombatAnimator over the townsfolk
model. The hands close on the shafts exactly where art/scripts/handcart.py has the cart's renderer put them: 6 px out
to either side, 13 px over the ground and 2.5 px behind the feet, so the shafts end in the hands.

    python3 -B art/scripts/handcart_anims.py src/main/resources/assets/guildmark <preview-dir>

Writes assets/guildmark/animations/townsfolk/pull_cart.json and a contact sheet (townsfolk_pull_cart.png) of the clip
over vanilla's walk with the cart drawn behind on its shafts; prints where the hands are against where the shafts are.

An overlay (it plays while they walk), but unlike the others it keys the legs too: in 'add' mode, a little back from
the hips and a little apart on top of vanilla's walk, so the stride still shows but leans into the pull.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import townsfolk_anims as ta
from townsfolk_anims import F_, bend, key_frame, reach, ticks, seal, check, fk, qeuler, GRIP, INFO, L, R
import handcart as hc

LENGTH = 40

# Where the hands hold the shafts, in the puller's model space (feet on y = 24, facing -z): the cart's grip points
# (handcart.py) with its axle GRIP_BACK + its shafts' reach behind the feet.
GRIP_BACK = 2.5
HAND_Y = 24.0 - (hc.R - hc.RAIL_Y)
SHAFT_HOLD = {'right_arm': np.array([-hc.RAIL_X, HAND_Y, GRIP_BACK]), 'left_arm': np.array([hc.RAIL_X, HAND_Y, GRIP_BACK])}
AXLE = np.array([0.0, 24.0 - hc.R, GRIP_BACK - hc.GRIP_Z])      # the cart's axle, behind them

LEAN = 13.0             # degrees forward from the hips
LEG_BACK = 5.0          # the stride's middle a little behind the hips
LEG_OUT = 1.5           # and a little wider

c = ta.clip('pull_cart', LENGTH, True, look=('citizen', 2), overlay=True,
            traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
for t in ticks(0, LENGTH, 4):
    ph = 2 * math.pi * t / LENGTH
    # Bent into it, swaying a little from side to side and dipping with the effort
    # (the arms are nearly straight down to the shafts, so a deeper dip or roll would lift a hand off its shaft)
    fr = F_(qt=bend(LEAN + 1.2 * math.sin(2 * ph), 1.5 * math.sin(ph), 1.0 * math.sin(ph)),
            vt=(0.0, 0.1 * (1 - math.cos(2 * ph)) / 2, 0.0))
    key_frame(c, t, fr, root=False)
    # Both hands back on the shafts, wherever the sway takes the shoulders
    c.key('right_arm', t, reach('right_arm', fr, SHAFT_HOLD['right_arm'], palm=L, palm_w=0.3), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, SHAFT_HOLD['left_arm'], palm=R, palm_w=0.3), ease='inout')
    # The chin up to see past the lean; the legs back and apart on top of the walk
    c.key('head', t, qeuler(x=-6.0 - 1.0 * math.sin(2 * ph), z=-1.5 * math.sin(ph)), ease='inout')
    c.key('right_leg', t, qeuler(x=LEG_BACK, z=LEG_OUT), ease='inout')
    c.key('left_leg', t, qeuler(x=LEG_BACK, z=-LEG_OUT), ease='inout')
for bone in ('head', 'right_leg', 'left_leg'):
    c.mode(bone, 'add')
seal(c)
# The arms are solved per key; between keys they're eased, so check the hands every tick
for t in ticks(0, LENGTH, 1):
    for side in ('right_arm', 'left_arm'):
        check(c, f'{side} on its shaft (t={t:g})', fk(c, t, side, GRIP[side], walking=True), SHAFT_HOLD[side], tol=0.35)


def check_pull(c):
    """As townsfolk_anims' check for overlays, but the legs may add to the walk (mode 'add'); the root stays alone."""
    bad = []
    if 'root' in c.keys:
        bad.append('root is tracked')
    for leg in ('right_leg', 'left_leg'):
        if leg in c.keys and c.modes.get(leg) != 'add':
            bad.append(f'{leg} replaces the walk')
    return bad

# ================================================================== the contact sheet, with the cart


def cart_quads(cart, load, arr, spin):
    """The cart on its shafts behind them, in their model space, as the rasterizer wants it."""
    light = np.array([-0.45, -0.8, -0.4])
    light /= np.linalg.norm(light)
    goods = [('sack', 0), ('flour', 1), ('sack', 2), ('crate', 3), ('sack', 4), ('flour', 5)]
    faces = hc.model_faces(cart, np.eye(3), AXLE, extra={'wheel_r': spin, 'wheel_l': spin})
    for kind, slot in goods:
        faces += hc.piece_faces(load, kind, slot, T=AXLE)
    out = []
    for A, Bq, Dq, rect, face, n in faces:
        k = 0.55 + 0.5 * max(0.0, float(np.dot(n, -light))) + 0.1 * max(0.0, float(-n[1]))
        out.append((A, Bq, Dq, rect, min(1.15, k), arr))
    return out


def sheet(c, cart, load, arr, views=((90, 4), (35, 22), (145, 26)), cell=(260, 230), zoom=3.4):
    info = INFO[c.name]
    look = ta.look_for(info['look'])
    times = [0, 5, 10, 15, 20, 25, 30, 35]
    W, H = cell
    out = Image.new('RGB', (W * len(times), H * len(views) + 20), (30, 32, 40))
    d = ImageDraw.Draw(out)
    d.text((4, 4), f'{c.name}  {c.length}t loop, over the walk, the cart on its shafts behind  [{look.role} {look.n}]', fill=(235, 235, 235))
    ground = []
    for i in range(-3, 4):
        for j in range(-2, 6):
            A = np.array([i * 8.0, 24.0, j * 8.0 - 4])
            ground.append((A, A + [8, 0, 0], A + [0, 0, 8], (0, 0, 1, 1), 0.92 if (i + j) % 2 else 1.0, ta.solid_tex(ta.GROUND)))
    for i, t in enumerate(times):
        base = ta.walk_base(t, speed=0.42, age=t)
        pose = ta.pose_at(c, t, base)
        mats = ta.part_matrices(look, pose)
        quads = ground + ta.model_quads(look, mats, info['tools']) + cart_quads(cart, load, arr, spin=t * 9.0)
        for j, (yaw, pitch) in enumerate(views):
            project = ta.ortho(yaw, pitch, zoom, (W, H), 10.0)
            shift = (lambda base_: (lambda p: base_(np.asarray(p, float) - np.array([0.0, 0.0, 12.0]))))(project)
            img = ta.raster(quads, shift, (W, H), bg=(54, 60, 74))
            out.paste(img, (i * W, 20 + j * H))
        d.text((i * W + 4, 23), f't={t:g}', fill=(255, 255, 255))
        d.line([(i * W, 20), (i * W, out.height)], fill=(20, 20, 26))
    return out


def main(argv):
    assets, previews = argv[1], argv[2]
    os.makedirs(previews, exist_ok=True)
    bad = check_pull(c) + ta.check_loop(c)
    if bad:
        print(f'!! {c.name}: ' + '; '.join(bad))
    path = c.write(assets, ta.GROUP)
    print(f'wrote {path}')
    worst = 0.0
    for label, got, want, tol in INFO[c.name]['checks']:
        worst = max(worst, float(np.linalg.norm(got - want)))
    print(f'  hands off the shafts by {worst:.3f} px at worst over the loop (each tick, walking)')
    lines = ta.report(c)
    for line in lines[:2] + [x for x in lines[2:] if '!! OFF' in x]:
        print(line)
    print(f'  the shafts held at x +-{hc.RAIL_X:g}, {24.0 - HAND_Y:g} px over the ground, {GRIP_BACK:g} px behind the feet; '
          f'the axle {AXLE[2]:g} px behind them')
    cart, load = hc.build()
    tex = hc.texture(cart, load)
    arr = np.array(tex.convert('RGBA'), float)
    p = os.path.join(previews, f'townsfolk_{c.name}.png')
    sheet(c, cart, load, arr).save(p)
    print('preview', p)


if __name__ == '__main__':
    main(sys.argv)
