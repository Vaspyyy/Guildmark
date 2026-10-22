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


def draw(sc, mesh, part, at, extra=None, drop=(), scale=1.0, double=False, yaw=0.0, y=0, ref=None, pose=None):
    """Part [part] of [mesh], standing at [at] in model px (turned [yaw] about up, the block turned [y] as its blockstate does); [pose]
    {part name: matrix} turns one of its children against the rest."""
    p = next(q for q in mesh.parts if q.name == part)
    q = without(p, set(drop)) if drop else p
    shim = type('M', (), {})()
    shim.parts, shim.model, shim.texture = [q], mesh.model, mesh.texture
    sc.mesh(shim, part, W.placer(at=at, yaw=yaw, y=y, extra=extra), pose=pose, scale=scale, double=double, ref=ref)


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
    scale_m, boards_m, box_m, beads_m = ms
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

    # ---- the strongbox: shut, opened (the scribe pays in), held, shut again (the lid falls, strikes and the hasp rattles)
    def lid_at(sc, at, angle, rel):
        px, py, pz = S.LID_PIVOT
        draw(sc, box_m, 'lid', (at[0] + px, at[1] + py, at[2] + pz), extra=mp(rx=angle), double=True)
        th = math.radians(angle)
        hx, hy, hz = S.HASP_AT
        draw(sc, box_m, 'hasp', (at[0] + px + hx, at[1] + py + hy * math.cos(th) - hz * math.sin(th), at[2] + pz + hy * math.sin(th) + hz * math.cos(th)),
             extra=mp(rx=angle + rel))

    def lid_state(t):
        """(from, to, age): the lid is told to open at tick 12 and to shut at tick 78."""
        if t < 12:
            return 0.0, 0.0, 0.0
        if t < 78:
            return 0.0, S.LID_OPEN, t - 12.0
        return S.LID_OPEN, 0.0, t - 78.0

    def box_frame(t, yaw=196.0, elev=24.0):
        frm, to, age = lid_state(t)
        angle, rel = S.lid_angle(age, frm, to), S.hasp_angle(age, frm, to)
        if t < 12:
            angle, rel = 0.0, 0.0
        sc = K.Scene(pa)
        floor(sc, -8, -8, 24, 24)
        sc.model(mods['strongbox'])
        lid_at(sc, (0, 0, 0), angle, rel)
        return shot(sc, yaw=yaw, elev=elev, scale=17.0, size=(320, 300), center=(8, 6, 8))

    frames = [box_frame(t) for t in np.arange(0.0, 130.0, 1.0)]
    done.append(K.save_gif(frames, os.path.join(out, 'store_strongbox.gif'), ms=50))

    # ---- the counting frame: rows pushed one after another, a run of beads away at a time, then some pulled back
    plan = [(10.0, (4, 7, 2, 9, 5, 1)), (62.0, (6, 3, 8, 0, 5, 9)), (112.0, (0, 0, 0, 0, 0, 0))]

    def row_state(r, t):
        """(count before, count after, start tick) of the last change of row [r] begun by tick [t]."""
        cur, ev = 0, (0, 0, -1e9)
        for t0, counts in plan:
            if counts[r] != cur:
                start = t0 + r * S.BEAD_ROW_GAP
                if t >= start:
                    ev = (cur, counts[r], start)
                cur = counts[r]
        return ev

    def frame_scene(t, counts_at=None):
        sc = K.Scene(pa)
        floor(sc, -8, -8, 24, 24)
        sc.model(mods['counting_frame'])
        for r in range(S.ROWS):
            frm, to, start = row_state(r, t)
            age = t - start
            for i in range(S.COLS):
                was, now = i < frm, i < to
                if was == now:
                    x = S.bead_rest(i, now)
                else:
                    order = (i - frm) if to > frm else (frm - 1 - i)
                    x = S.bead_x(i, now, age, order)
                draw(sc, beads_m, f'bead_{r}_{i}', (x, S.bead_y(r), S.ROD_Z))
        return sc

    def frame_frame(t):
        return shot(frame_scene(t), yaw=192.0, elev=8.0, scale=19.0, size=(330, 340), center=(8, 7.5, 8))

    frames = [frame_frame(t) for t in np.arange(0.0, 160.0, 1.0)]
    done.append(K.save_gif(frames, os.path.join(out, 'store_counting_frame.gif'), ms=50))

    # ---- the three beside the scale and the works' own "To let" board, at a size a room shows them
    sc = K.Scene(pa)
    floor(sc, -16, -16, 130, 40)
    sc.model(works_model(assets, 'to_let_board'), at=(0, 0, 0))
    sc.model(mods['store_scale'], at=(22, 0, 0))
    px, py, pz = S.SCALE_PIVOT
    draw(sc, scale_m, 'beam', (22 + px, py, pz))
    for name, side in (('pan_goods', S.GOODS), ('pan_weights', S.WEIGHTS)):
        hx, hy = S.hook_at(side, 0.0)
        draw(sc, scale_m, name, (22 + hx, hy, 8.0))
    sc.model(mods['strongbox_shut'], at=(48, 0, 0))
    sc.model(mods['strongbox'], at=(70, 0, 0))
    lid_at(sc, (70, 0, 0), S.LID_OPEN, S.hasp_angle(99.0, 0.0, S.LID_OPEN))
    sc.model(mods['counting_frame'], at=(94, 0, 0))
    for r in range(S.ROWS):
        count = (3, 6, 1, 9, 4, 7)[r]
        for i in range(S.COLS):
            draw(sc, beads_m, f'bead_{r}_{i}', (94 + S.bead_rest(i, i < count), S.bead_y(r), S.ROD_Z))
    done.append(save_sheet([sc.render(yaw=192.0, elev=14.0, scale=7.0, size=(860, 330), center=(66, 9, 8), ss=2)],
                           os.path.join(out, 'store_fittings.png'), cols=1))

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


# ================================================================== the Counting House's
def hcat(images, pad=4):
    """[images] side by side on the preview ground."""
    h = max(i.height for i in images)
    out = Image.new('RGB', (sum(i.width for i in images) + pad * (len(images) - 1), h), W.PREVIEW_BG)
    x = 0
    for im in images:
        out.paste(im, (x, (h - im.height) // 2))
        x += im.width + pad
    return out


def rot2(deg, x, y):
    """A point turned [deg] about z in model space (positive takes +x toward +y)."""
    a = math.radians(deg)
    return x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)


# a made-up country for the map: towns (world x, world z, class), threads between them (a, b, in use), and the wagons on them
DEMO_TOWNS = [(-100, 20, 3), (330, -250, 2), (-520, -200, 1), (-300, 300, 0), (560, 150, 1), (50, -300, 0), (-650, 80, 0), (200, 330, 2)]
DEMO_POP = [0, 6, 11, 16, 21, 26, 31, 36]
DEMO_EDGES = [(0, 1, True), (0, 2, False), (0, 3, False), (0, 5, False), (0, 7, False), (1, 5, False), (1, 4, False), (2, 6, False),
              (2, 5, False), (3, 6, False), (3, 7, False), (7, 4, True)]
# (edge, colour, speed px a tick, the tick it sets out, how far along it starts)
DEMO_WAGONS = [(0, 0, 0.115, 70, 0.0), (11, 4, 0.14, 78, 1.5), (3, 1, 0.10, 84, 0.0), (7, 2, 0.12, 92, 4.0), (9, 3, 0.11, 98, 1.0),
               (1, 5, 0.13, 106, 0.0), (5, 0, 0.12, 112, 2.0), (10, 2, 0.105, 120, 0.0)]


def house_previews(assets, out):
    import house_art as H
    import store_art as S
    ms = H.meshes()
    work = tempfile.mkdtemp(prefix='trade_house_')
    pa = scratch_assets(assets, work, H.block_textures(), ms)
    mods = H.models()
    map_m, boards_m, bell_m = ms
    done = []

    # ---- the map: where a town is, how a wagon sits on its thread, and what moves
    xs_, zs_ = [t[0] for t in DEMO_TOWNS], [t[1] for t in DEMO_TOWNS]
    cx, cz = (min(xs_) + max(xs_)) / 2.0, (min(zs_) + max(zs_)) / 2.0
    k = H.map_scale(max(xs_) - min(xs_), max(zs_) - min(zs_))
    pts = [(H.map_x(x, cx, k), H.map_y(z, cz, k)) for x, z, _ in DEMO_TOWNS]
    Z = H.MAP_Z

    def thread(sc, a, b, hot, grow):
        (x0, y0), (x1, y1) = pts[a], pts[b]
        dx, dy = x1 - x0, y1 - y0
        L = math.hypot(dx, dy) * grow
        ang = math.degrees(math.atan2(dy, dx))
        draw(sc, map_m, 'thread_hot' if hot else 'thread', (x0, y0, Z), extra=mp(rz=ang) @ np.diag([max(L, 1e-4), 1.0, 1.0]))

    def pin(sc, i, age, t):
        cls = DEMO_TOWNS[i][2]
        s = H.pin_pop(age)
        if s <= 0.0:
            return
        x, y = pts[i]
        draw(sc, map_m, f'pin_{cls}', (x, y, Z), scale=s)
        g = H.glint_at(t, i)
        if g > 0.02 and s >= 1.0:
            draw(sc, map_m, 'glint', (x + H.PIN_D[cls] * 0.2, y + H.PIN_D[cls] * 0.2, Z), scale=g)

    def wagon(sc, ci, x, y, dx, dy, dist, t, phase, run=1.0, scale=1.0):
        """The renderer's order: translate, tilt about z, flip about up, draw the wagon and its pennant; the wheels turn about z too."""
        name = H.NAMES[ci]
        tilt = H.wagon_tilt(dx, dy) + H.wagon_rock(abs(dist), phase)
        flip = H.wagon_flip(dx)
        at = np.array([x, y + H.wagon_bob(abs(dist), phase) * scale, Z + H.WAGON_DZ * scale])
        draw(sc, map_m, f'wagon_{name}', at, extra=mp(ry=flip, rz=tilt), scale=scale)
        fx, fy = H.FLAG_AT
        px_, py_ = rot2(tilt, fx * (-1 if flip else 1), fy)
        yaw, roll = H.flag_yaw(t, phase, run), H.flag_roll(t, phase, run)
        extra = W.Rz(tilt) @ W.Ry(-flip) @ W.Ry(-yaw) @ W.Rz(roll)
        draw(sc, map_m, f'flag_{name}', (at[0] + px_ * scale, at[1] + py_ * scale, at[2]), extra=extra, scale=scale)
        for wx, wy in H.WHEEL_AT:
            px_, py_ = rot2(tilt, wx, wy)
            draw(sc, map_m, 'wheel', (at[0] + px_ * scale, at[1] + py_ * scale, at[2] - H.WHEEL_Z * scale),
                 extra=mp(rz=tilt + H.wheel_turn(dist)), scale=scale)

    def wagon_state(j, t):
        e, ci, speed, t0, off = DEMO_WAGONS[j]
        a, b, _ = DEMO_EDGES[e]
        (x0, y0), (x1, y1) = pts[a], pts[b]
        L = math.hypot(x1 - x0, y1 - y0)
        ux, uy = (x1 - x0) / L, (y1 - y0) / L
        d = ((t - t0) * speed + off) % (2.0 * L)
        out_ = d < L
        path = d if out_ else 2.0 * L - d
        dx, dy = (ux, uy) if out_ else (-ux, -uy)
        return x0 + ux * path, y0 + uy * path, dx, dy, -ux * path          # the last: the way rolled toward the viewer's right (-x)

    def map_scene(t):
        sc = K.Scene(pa)
        sc.model(mods['house_map'])
        for a, b, hot in DEMO_EDGES:
            grow = H.thread_grow(t - (max(DEMO_POP[a], DEMO_POP[b]) + 4.0))
            if grow > 0.0:
                thread(sc, a, b, hot, grow)
        for i in range(len(DEMO_TOWNS)):
            pin(sc, i, t - DEMO_POP[i], t)
        for j, (e, ci, speed, t0, off) in enumerate(DEMO_WAGONS):
            if t >= t0:
                x, y, dx, dy, dist = wagon_state(j, t)
                wagon(sc, ci, x, y, dx, dy, dist, t, 0.9 * j, scale=max(0.01, H.pin_pop(t - t0)))
        return sc

    def map_shot(t, scale=11.0, size=(560, 372), yaw=186.0, elev=5.0):
        return shot(map_scene(t), yaw=yaw, elev=elev, scale=scale, size=size, center=(8, 16, 15))

    frames = [map_shot(t) for t in np.arange(0.0, 230.0, 2.0)]
    done.append(K.save_gif(frames, os.path.join(out, 'house_map.gif'), ms=60))

    # ---- the map at the size a room shows it: 3, 5 and 9 screen px to a model px, straight on
    imgs = [shot(map_scene(215.0), yaw=180.0, elev=0.0, scale=sc_, size=sz, center=(8, 16, 15))
            for sc_, sz in ((3.0, (160, 108)), (5.0, (260, 176)), (9.0, (470, 312)))]
    done.append(save_sheet([K.label(i, f'{s} px to a model px') for i, s in zip(imgs, (3, 5, 9))], os.path.join(out, 'house_map_sizes.png'), cols=3))

    # ---- the four pins and the six wagons on the parchment, to tell them apart at a distance
    def census(scale, size):
        sc = K.Scene(pa)
        sc.model(mods['house_map'])
        for c in range(4):
            draw(sc, map_m, f'pin_{c}', (22.0 - c * 5.0, 22.0, Z))
        draw(sc, map_m, 'glint', (22.0 - 3 * 5.0 + 1.0, 23.0, Z))
        for c in range(len(H.COLOURS)):
            wagon(sc, c, 22.0 - c * 4.6, 14.0, -1.0, 0.0, 0.0, 12.0 * c, 0.9 * c, run=0.4)
            wagon(sc, c, 22.0 - c * 4.6, 8.0, 1.0, 0.0, 0.0, 12.0 * c + 4, 0.9 * c + 1.0, run=0.4)
        return shot(sc, yaw=180.0, elev=0.0, scale=scale, size=size, center=(8, 16, 15))
    imgs = [census(sc_, sz) for sc_, sz in ((3.0, (160, 108)), (6.0, (310, 210)), (12.0, (600, 400)))]
    done.append(save_sheet([K.label(i, f'{s} px to a model px') for i, s in zip(imgs, (3, 6, 12))],
                           os.path.join(out, 'house_pins_and_wagons.png'), cols=1))

    # ---- the bell: rung (the rope pulled down at tick 10), the swing building, the clapper striking, dying away; from the front and from the side
    bx, by, bz = H.BELL_AXLE

    def bell_scene(t, pull=10.0):
        age = t - pull
        th = H.bell_swing(age) + H.idle_swing(t) * (1.0 if age <= 0 or age >= H.RING_TICKS else 0.25)
        sc = K.Scene(pa)
        floor(sc, -8, -8, 24, 24)
        sc.model(mods['house_bell'])
        draw(sc, bell_m, 'bell', (bx, by, bz), extra=mp(rx=th))
        draw(sc, bell_m, 'clapper', (bx, by, bz), extra=mp(rx=th + H.clapper_rel(age)))
        dy, dz = H.rope_at(th)
        pulled = 1.4 * S.smooth(age / 4.0) * (1.0 - S.smooth((age - 4.0) / 10.0)) if age > 0 else 0.0     # the rope is hauled down a little, and let go
        draw(sc, bell_m, 'rope', (bx + H.WHEEL_X, by + dy - pulled, bz - H.WHEEL_RAD + dz), extra=mp(rx=H.rope_sway(age)))
        return sc

    def bell_frame(t):
        a = shot(bell_scene(t), yaw=205.0, elev=10.0, scale=17.0, size=(300, 330), center=(8, 8.5, 6))
        b = shot(bell_scene(t), yaw=270.0, elev=4.0, scale=17.0, size=(300, 330), center=(8, 8.5, 6))
        return hcat([a, b])
    frames = [bell_frame(t) for t in np.arange(0.0, 170.0, 1.0)]
    done.append(K.save_gif(frames, os.path.join(out, 'house_bell.gif'), ms=50))

    # ---- the six name boards on their brackets, each swinging in its own breeze, and from tick 100 in rain
    def board_shot(c, t):
        sc = K.Scene(pa)
        floor(sc, -16, -16, 32, 32)
        sc.model(mods['house_name_board'])
        draw(sc, boards_m, f'board_{H.NAMES[c]}', S.BOARD_PIVOT, extra=mp(rx=H.board_swing(t, 0.9 * c + 0.3, t >= 100)))
        return sc.render(yaw=186.0, elev=9.0, scale=9.0, size=(260, 170), center=(8, 10, 8), ss=2)

    def boards_frame(t):
        shots = [board_shot(c, t) for c in range(len(H.COLOURS))]
        row = lambda ims: hcat(ims, pad=2)
        out_ = Image.new('RGB', (3 * 260 + 4, 2 * 170 + 2), W.PREVIEW_BG)
        out_.paste(row(shots[0:3]), (0, 0))
        out_.paste(row(shots[3:6]), (0, 172))
        return out_
    frames = [boards_frame(t) for t in np.arange(0.0, 200.0, 2.0)]
    done.append(K.save_gif(frames, os.path.join(out, 'house_name_boards.gif'), ms=80))

    # ---- beside the works' own, at the size a room shows them: the map, the "To let" board, the bell, a name board
    sc = K.Scene(pa)
    floor(sc, -40, -16, 190, 40)
    sc.model(mods['house_map'], at=(0, 0, 0))
    sc.model(works_model(assets, 'to_let_board'), at=(60, 0, 0))
    sc.model(mods['house_bell'], at=(84, 0, 0))
    draw(sc, bell_m, 'bell', (84 + bx, by, bz))
    draw(sc, bell_m, 'clapper', (84 + bx, by, bz))
    draw(sc, bell_m, 'rope', (84 + bx + H.WHEEL_X, by, bz - H.WHEEL_RAD))
    sc.model(mods['house_name_board'], at=(108, 0, 0))
    draw(sc, boards_m, 'board_blue', (108 + S.BOARD_PIVOT[0], S.BOARD_PIVOT[1], S.BOARD_PIVOT[2]))
    done.append(save_sheet([sc.render(yaw=184.0, elev=7.0, scale=5.4, size=(900, 330), center=(66, 14, 8), ss=2)],
                           os.path.join(out, 'house_beside_works.png'), cols=1))

    # ---- the pictures: every texture, large
    items = [(n, im) for n, im in H.block_textures().items()] + [(m.texture, m.paint(), 800) for m in ms]
    done.append(flow_sheet(items, os.path.join(out, 'house_pictures.png')))
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
