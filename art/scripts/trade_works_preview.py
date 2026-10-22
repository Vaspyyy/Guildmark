"""Previews of what store_art.py and house_art.py paint and model, drawn with the same numbers the generated tables carry (so a swing
looks here as it will in the game): contact sheets of the pictures, each fitting beside the works' own, at the size a room shows them,
and GIFs of the moving ones, every GIF's frames on one palette (trade_works_kit.save_gif). Software renders of block models and of
the meshes' parts through island_blockview, as the works' previews are; nothing here is part of the game.

    python3 -B art/scripts/store_art.py <assets> <kotlin dir> <models dir> <preview dir>
    python3 -B art/scripts/house_art.py <assets> <kotlin dir> <models dir> <preview dir>
"""
import copy
import json
import math
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import trade_works_kit as K  # noqa: E402
from trade_works_kit import np, Image  # noqa: E402

W = K.W


# ================================================================== a part, a turn, a place
def without(part, drop):
    """A copy of [part] without the children (at any depth) named in [drop], so a preview can leave the sack or a weight off."""
    q = copy.copy(part)
    q.children = [without(c, drop) for c in part.children if c.name not in drop]
    return q


def mp(rx=0.0, ry=0.0, rz=0.0):
    """A turn given in model space (degrees about x, y and z, applied z last) as the matrix to give Scene.mesh's `extra`, which acts in
    ModelPart space: a turn about z is the same in both, about x and y it is the other way."""
    return W.Rz(rz) @ W.Ry(-ry) @ W.Rx(-rx)


def draw(sc, mesh, part, at, extra=None, drop=(), scale=1.0, double=False, yaw=0.0, y=0, ref=None):
    """Part [part] of [mesh], standing at [at] in model px (turned [yaw] about up, the block turned [y] as its blockstate does)."""
    p = next(q for q in mesh.parts if q.name == part)
    q = without(p, set(drop)) if drop else p
    shim = type('M', (), {})()
    shim.parts, shim.model, shim.texture = [q], mesh.model, mesh.texture
    sc.mesh(shim, part, W.placer(at=at, yaw=yaw, y=y, extra=extra), scale=scale, double=double, ref=ref)


def preview_dir(out, *names):
    os.makedirs(out, exist_ok=True)
    return [os.path.join(out, n) for n in names]


def scratch_assets(assets, out, textures, meshes_):
    """A scratch assets folder (the works' textures, the trade's as written) and the trade's pictures regenerated into it, so a preview
    never depends on what an earlier run left."""
    pa = K.preview_assets(assets, out)
    td = os.path.join(pa, 'textures', 'block', 'trade')
    os.makedirs(td, exist_ok=True)
    for n, im in textures.items():
        im.save(os.path.join(td, n + '.png'))
    for m in meshes_:
        m.paint().save(os.path.join(td, m.texture + '.png'))
    return pa


def works_model(assets, name):
    with open(os.path.join(assets, 'models', 'block', 'works', name + '.json')) as f:
        return json.load(f)


def floor(sc, x0, z0, x1, z1, y=0.0):
    W.ground(sc, x0, z0, x1, z1, y)


def wall(sc, x0, x1, y0, y1, z=16.0, ref='guildmark:block/works/pine'):
    """A plain wall of the works' pine behind a fitting (its face toward -z at z), 16 px to a tile."""
    for x in np.arange(x0, x1, 16.0):
        for y in np.arange(y0, y1, 16.0):
            sc.quad((x, y, z), (x + 16, y, z), (x, y + 16, z), (0, 16, 16, 0), ref)


def shot(sc, yaw=190.0, elev=10.0, scale=10.0, size=(300, 340), center=(8, 14, 8), **kw):
    return sc.render(yaw=yaw, elev=elev, scale=scale, size=size, center=center, ss=2, **kw)


# ================================================================== the Store's
def store_previews(assets, out):
    import store_art as S
    ms = S.meshes()
    work = tempfile.mkdtemp(prefix='trade_store_')
    pa = scratch_assets(assets, work, S.block_textures(), ms)
    mods = S.models()
    scale_m, boards_m = ms
    done = []

    # ---- the scale at a weighing: the sack set down, three weights added one by one until it is level
    steps = [(24.0, S.BEAM_MAX * 0.85), (72.0, 8.5), (114.0, 3.2), (156.0, 0.0)]      # tick of the change, the tilt it settles to
    sack_from, weights_at = 24.0, (72.0, 114.0, 156.0)

    def state(t):
        """(the tilt before the last change, the tilt it settles to, the tick of the change): each change comes after the last has settled."""
        frm, target, tc = 0.0, 0.0, -1e9
        for tchg, to in steps:
            if t >= tchg:
                frm, target, tc = target, to, tchg
        return target, frm, tc

    def scale_frame(t, yaw=192.0, elev=9.0, size=(300, 340), scale=10.0):
        tilt_to, tilt_from, tc = state(t)
        age = t - tc
        tilt = S.settle(age, tilt_from, tilt_to) + (S.idle_tilt(t) if age >= S.SETTLE_TICKS else S.idle_tilt(t) * min(1.0, max(0.0, age) / S.SETTLE_TICKS))
        sc = K.Scene(pa)
        sc.model(mods['store_scale'])
        px, py, pz = S.SCALE_PIVOT
        draw(sc, scale_m, 'beam', (px, py, pz), extra=mp(rz=tilt))
        nw = sum(1 for w in weights_at if t >= w)
        for name, side in (('pan_goods', S.GOODS), ('pan_weights', S.WEIGHTS)):
            hx, hy = S.hook_at(side, tilt)
            # a pan swings on after each change: the age since the last change drives it
            sz = S.pan_sway(age, 0.0) * (1 if side == S.GOODS else -1)
            sx = S.pan_sway(age, 1.6)
            drop = []
            if side == S.GOODS and t < sack_from:
                drop = ['sack']
            if side == S.WEIGHTS:
                drop = [f'weight_{i}' for i in range(3) if i >= nw]
            draw(sc, scale_m, name, (hx, hy, 8.0), extra=mp(rx=sx, rz=sz), drop=drop)
        return shot(sc, yaw=yaw, elev=elev, scale=scale, size=size, center=(8, 15, 8))

    frames = [scale_frame(t) for t in np.arange(0.0, 200.0, 2.0)]
    p = K.save_gif(frames, os.path.join(out, 'store_scale_weighing.gif'), ms=100)
    done.append(p)

    # ---- the boards: the name board swinging in a breeze (and in rain), the door board turning shut and open again
    def boards_frame(t, door_shut):
        sc = K.Scene(pa)
        floor(sc, -16, -16, 64, 32)
        sc.model(mods['store_name_board'], at=(0, 0, 0))
        sc.model(mods['store_door_board'], at=(34, 0, 0))
        sw = S.board_swing(t, 0.7, wet=False)
        bx, by, bz = S.BOARD_PIVOT
        draw(sc, boards_m, 'board', (bx, by, bz), extra=mp(rx=sw))
        # the door board turns about up through the hook; it is told at tick 30 to go to SHUT and at 110 back to OPEN
        if t < 30:
            a = 0.0
        elif t < 110:
            a = S.door_turn(t - 30.0, 0.0, 180.0)
        else:
            a = S.door_turn(t - 110.0, 180.0, 360.0)
        dx, dy, dz = S.DOOR_PIVOT
        draw(sc, boards_m, 'door_board', (34 + dx, dy, dz), extra=mp(ry=a))
        return sc.render(yaw=188.0, elev=9.0, scale=9.0, size=(520, 300), center=(18, 9, 8), ss=2)

    frames = [boards_frame(t, False) for t in np.arange(0.0, 190.0, 2.0)]
    done.append(K.save_gif(frames, os.path.join(out, 'store_boards.gif'), ms=100))

    # ---- the pictures: every texture, large
    items = [(n, im) for n, im in S.block_textures().items()] + [(m.texture, m.paint(), 800) for m in ms]
    done.append(flow_sheet(items, os.path.join(out, 'store_pictures.png')))

    # ---- the scale and the boards at the size a room shows them: 3, 5 and 9 screen px to a model px
    imgs = []
    for sc_, sz in ((3.0, (210, 220)), (5.0, (300, 320)), (9.0, (420, 480))):
        sc = K.Scene(pa)
        floor(sc, -16, -16, 48, 48)
        wall(sc, -16, 48, 0, 48, z=16.0, ref='guildmark:block/works/oak_h')
        sc.model(mods['store_scale'])
        px, py, pz = S.SCALE_PIVOT
        draw(sc, scale_m, 'beam', (px, py, pz), extra=mp(rz=7.0))
        for name, side in (('pan_goods', S.GOODS), ('pan_weights', S.WEIGHTS)):
            hx, hy = S.hook_at(side, 7.0)
            draw(sc, scale_m, name, (hx, hy, 8.0), drop=['weight_1', 'weight_2'] if side == S.WEIGHTS else [])
        imgs.append(sc.render(yaw=186.0, elev=8.0, scale=sc_, size=sz, center=(8, 14, 8), ss=2))
    done.append(save_sheet([K.label(i, f'{s} px to a model px') for i, s in zip(imgs, (3, 5, 9))], os.path.join(out, 'store_scale_sizes.png'), cols=3))

    # ---- beside the works' own: the hoist's post, the "To let" board, the scale, the Store's name board
    sc = K.Scene(pa)
    floor(sc, -16, -16, 160, 48)
    for i, (mdl, at) in enumerate(((works_model(assets, 'to_let_board'), (0, 0, 0)), (mods['store_scale'], (24, 0, 0)),
                                   (mods['store_name_board'], (56, 0, 0)), (works_model(assets, 'works_plaque'), (92, 0, 0)))):
        sc.model(mdl, at=at)
    px, py, pz = S.SCALE_PIVOT
    draw(sc, scale_m, 'beam', (24 + px, py, pz))
    for name, side in (('pan_goods', S.GOODS), ('pan_weights', S.WEIGHTS)):
        hx, hy = S.hook_at(side, 0.0)
        draw(sc, scale_m, name, (24 + hx, hy, 8.0))
    bx, by, bz = S.BOARD_PIVOT
    draw(sc, boards_m, 'board', (56 + bx, by, bz))
    done.append(save_sheet([sc.render(yaw=190.0, elev=9.0, scale=6.0, size=(780, 300), center=(52, 12, 8), ss=2)],
                           os.path.join(out, 'store_beside_works.png'), cols=1))
    shutil.rmtree(work, ignore_errors=True)
    return done


def save_sheet(images, path, cols=None, title=None):
    cols = cols or min(4, len(images))
    w = max(i.width for i in images)
    h = max(i.height for i in images)
    padded = []
    for im in images:
        c = Image.new('RGB', (w, h), W.PREVIEW_BG)
        im = im.convert('RGBA')
        c.paste(im, ((w - im.width) // 2, (h - im.height) // 2), im)
        padded.append(c)
    W.tiles(padded, cols, title).save(path)
    return path


def flow_sheet(items, path, width=1180, target=330):
    """[items] (name, picture) laid out in rows on a dark ground, each enlarged by a whole number to about [target] px across (a small
    picture as far as 12 times), a name over it: the pictures' own pixels, for judging them."""
    pad, head = 10, 14
    x = y = row = 0
    placed = []
    for it in items:
        name, im = it[0], it[1].convert('RGBA')
        tg = it[2] if len(it) > 2 else target
        k = max(1, min(12, int(round(tg / im.width)))) if im.width < tg else max(1, tg // im.width)
        w, h = im.width * k, im.height * k
        w = min(w, width - 2 * pad)
        if x + w + pad > width and x > 0:
            x, y, row = 0, y + row + head + pad, 0
        placed.append((name, im, k, x, y))
        x += w + pad
        row = max(row, h)
    out = Image.new('RGB', (width, y + row + head + 2 * pad), (24, 24, 30))
    from PIL import ImageDraw
    d = ImageDraw.Draw(out)
    for name, im, k, x, y in placed:
        big = im.resize((im.width * k, im.height * k), Image.NEAREST)
        bg = Image.new('RGBA', big.size, (46, 48, 60, 255))
        bg.alpha_composite(big)
        out.paste(bg.convert('RGB'), (pad + x, pad + head + y))
        d.text((pad + x, pad + y), '%s  %dx%d' % (name, im.width, im.height), fill=(230, 226, 210))
    out.save(path)
    return path
