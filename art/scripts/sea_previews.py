"""The sea monsters' previews: stills and animated GIFs of the Gnashfin, the Maelgrasp's arm and head, the figurehead and the cog together,
drawn with the Python `pose()` of each model (the same animation the Kotlin `setupAnim` does) over the cog's own sea and sky. Each film is a GIF kept
to about 3 MB (a phone should not mind), an MP4 of the same pictures (needs ffmpeg) and a contact sheet; each still is a PNG.

    python3 -B art/scripts/sea_previews.py <assets dir> <preview dir> [names ...]

(names: any of NAMES below, none meaning all of them; `overview` alone remakes overview.png from what is already in the folder.)

`sea_monsters.py` calls `write_all(out, prev, art, built)` after it has made the models. Standalone it reads the textures it finds under
<assets dir>/textures/entity (and, for the cog, the repo's own).

Nothing here is the game: where the game decides things (where a Gnashfin swims, how an arm reaches, what a player does) the previews make
up a scene that shows the model off, and say so in the sequences' docstrings. Each sequence builds a list of frame specs (a plain dict: camera,
sky, the cog's state, the actors with their poses and places, foam spots, spray) and `draw` turns a spec into a picture; the pictures are drawn
four at a time, and a GIF is written with `ship.write_gif` (a still sea costs next to nothing, so only what moves is stored).

The Gnashfin's sequences are here; the arm's are in `sea_prev_arm.py`, the head's in `sea_prev_head.py` and the night with all of them in
`sea_prev_scene.py` (they are loaded when they are wanted: they use this module's stage).

Scene space is the cog's model space: px (1/16 block), y down, the sea at y = 0, the cog on the x = 0 plane facing -z, its starboard -x.
"""
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np
from PIL import Image

import ship
import sea_kit as K
import sea_gnashfin as G
import sea_arm as A
import sea_head as H

REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
REPO_ASSETS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'guildmark')

TPF = 1.6                      # game ticks to a picture (a GIF frame lasts 80 ms: 1.6 ticks of 50 ms, so the animations play in real time)
FRAME_MS = 80
TPF_SLOW = 2.0                 # the same for the two longest, most detailed films (100 ms a picture: a fifth fewer pictures, and they still play in real time)
MS_SLOW = 100
GIF_LIMIT = 3.0e6              # bytes: what a phone should not mind
WORKERS = 4
SS = 2                         # every picture is drawn at this many times its size and shrunk
GRAVITY = 0.58                 # px per tick squared: what the leaps and the spray fall by (a little slower than the game's, so a leap can be seen)

NAMES = ['gnashfin_hero', 'gnashfin_circling', 'gnashfin_leap', 'gnashfin_snap', 'gnashfin_gnaw', 'gnashfin_stranded',
         'arm_attack', 'arm_hold', 'head_hero', 'head_surface', 'head_wrath', 'head_face', 'night_still', 'night', 'figurehead']


# a night that can be seen by: the cog's own night is for her lanterns, this one has a moon behind the camera (the stars and the dark sea are the same)
ship.SKIES.setdefault('moonlit', dict(ship.SKIES['night'], toL=(-0.40, -0.66, -0.64), key=(0.92, 1.00, 1.24), amb=(0.27, 0.33, 0.52), top=(8, 12, 34), hor=(54, 70, 116),
                                      sea=(7, 17, 38), sea2=(22, 42, 76), fog=(30, 42, 74)))


# ================================================================== small helpers

def smooth(a, b, x):
    t = min(max((x - a) / (b - a), 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)


def clamp(v, lo=0.0, hi=1.0):
    return lo if v < lo else hi if v > hi else v


def lerp(a, b, u):
    return a + (b - a) * u


def track(t, keys):
    """A value (number or vector) that eases from key to key: [keys] is [(t, value), ...], held before the first and after the last."""
    if t <= keys[0][0]:
        return np.asarray(keys[0][1], float)
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t <= t1:
            return np.asarray(v0, float) + (np.asarray(v1, float) - np.asarray(v0, float)) * smooth(t0, t1, t)
    return np.asarray(keys[-1][1], float)


def unit(v):
    v = np.asarray(v, float)
    n = float(np.linalg.norm(v))
    return v / n if n > 1e-9 else v


def yaw_of(dx, dz):
    """The yaw (degrees) that makes a model's front (-z) face the horizontal direction (dx, dz)."""
    return K.facing(dx, dz)


def pitch_of(dx, dy, dz):
    """Degrees nose up of a model moving along (dx, dy, dz) (y is down in the scene)."""
    return math.degrees(math.atan2(-dy, math.hypot(dx, dz)))


def at(M, p):
    """The point [p] (3) through the 4x4 [M]."""
    return (M @ np.array([p[0], p[1], p[2], 1.0]))[:3]


def inv_at(M, p):
    return (np.linalg.inv(M) @ np.array([p[0], p[1], p[2], 1.0]))[:3]


# ================================================================== the world: the cog, the three models, a stand-in player

def _load(path):
    return np.asarray(Image.open(path).convert('RGBA'))


class Model:
    """One drawable model: [scene] (parts and faces), [tex], [glow] and a name."""

    def __init__(self, name, scene, tex, glow):
        self.name, self.scene, self.tex, self.glow = name, scene, tex, glow


def player_model(shirt=(44, 150, 158), pants=(52, 62, 128)):
    """A blocky stand-in for a player (4/8/12 px limbs, 32 px tall, feet at the origin, facing -z), in flat colours with a face, whose head, arms
    and legs hang from their joints so a preview can pose them, and a sword that rides the right arm (hidden unless a pose shows it). Only for the
    previews. Returns (scene, texture)."""
    K.REG.clear()
    skin, hair, boot, eye_w, eye_b = (206, 160, 128), (62, 42, 30), (50, 46, 52), (236, 236, 240), (60, 80, 150)
    steel, gold = (206, 214, 224), (214, 170, 70)
    cols = {}

    def cube(name, o, s, rgb, **kw):
        c = K.Cu(o, s, 'flat', name, **kw)
        cols[name] = rgb
        return c

    def blk(name, pivot, o, s, rgb, **kw):
        return K.part(name, pivot, (0, 0, 0), [cube(name, o, s, rgb, **kw)])
    parts = [
        blk('pl_body', (0, 0, 0), (-4, -24, -2), (8, 12, 4), shirt),
        blk('pl_head', (0, -24, 0), (-4, -8, -4), (8, 8, 8), skin),
        blk('pl_hair', (0, -24, 0), (-4.25, -8.25, -4.25), (8.5, 2.5, 8.5), hair),
        blk('pl_arm_r', (-6, -22, 0), (-2, -2, -2), (4, 12, 4), shirt),
        blk('pl_arm_l', (6, -22, 0), (-2, -2, -2), (4, 12, 4), shirt),
        blk('pl_leg_r', (-2, -12, 0), (-2, 0, -2), (4, 12, 4), pants),
        blk('pl_leg_l', (2, -12, 0), (-2, 0, -2), (4, 12, 4), pants),
        blk('pl_boot_r', (-2, -12, 0), (-2.25, 9, -2.25), (4.5, 3, 4.5), boot),
        blk('pl_boot_l', (2, -12, 0), (-2.25, 9, -2.25), (4.5, 3, 4.5), boot),
        K.part('pl_sword', (-6, -22, 0), (0, 0, 0), [cube('pl_sword', (-0.75, 10.5, -0.5), (1.5, 15, 1), steel),
                                                      cube('pl_sword_g', (-2.5, 9, -0.75), (5, 1.5, 1.5), gold)]),
    ]
    model = K.Model('player', 64, 64, parts)
    K.pack_atlas(model)
    sc = K.SScene(model)
    tex = np.zeros((model.tex_h * K.HD, model.tex_w * K.HD, 4), np.uint8)
    for k in range(len(sc.FC)):
        name = sc.cubes[int(sc.FC[k])][1].name
        x, y, w, h = [int(v) for v in sc.FR[k]]
        tex[y:y + h, x:x + w] = (*cols[name], 255)
        if name == 'pl_head' and sc.FF[k] == 'front':
            for ex in (3, 10):
                tex[y + 7:y + 9, x + ex:x + ex + 3] = (*eye_w, 255)
                tex[y + 7:y + 9, x + ex + (2 if ex == 3 else 0):x + ex + (3 if ex == 3 else 1)] = (*eye_b, 255)
            tex[y + 11:y + 12, x + 6:x + 10] = (150, 80, 70, 255)
    return sc, tex


def player_pose(arms=0.0, swing=0.0, kick=0.0, nod=0.0, spread=0.0, sword=False, hang=0.0, sweep=0.0, left=None):
    """The stand-in's limbs: [arms] raises both (0 down, 1 straight up), [swing] swings them fore and aft, [kick] kicks the legs (one fore, one aft),
    [hang] swings both legs forward together (a body held at the waist lets them dangle), [nod] tilts the head, [spread] opens the arms out to the
    sides (radians in each case, but the arms: 0..1); [sword] puts the sword in the right hand; [sweep] turns the right arm (and the sword) about the
    vertical once it is raised (radians, positive toward its own side: a horizontal slash); [left] raises the left arm by that much instead of [arms]."""
    up = -math.pi * clamp(arms)
    up_l = -math.pi * clamp(arms if left is None else left)
    right = dict(rot=(up + swing, sweep, -spread))
    out = {
        'pl_arm_r': right,
        'pl_arm_l': dict(rot=(up_l - swing, 0.0, spread)),
        'pl_leg_r': dict(rot=(kick + hang, 0.0, 0.0)),
        'pl_leg_l': dict(rot=(-kick + hang, 0.0, 0.0)),
        'pl_boot_r': dict(rot=(kick + hang, 0.0, 0.0)),
        'pl_boot_l': dict(rot=(-kick + hang, 0.0, 0.0)),
        'pl_head': dict(rot=(nod, 0.0, 0.0)),
        'pl_hair': dict(rot=(nod, 0.0, 0.0)),
        'pl_sword': dict(rot=right['rot'], visible=bool(sword)),
    }
    return out


class World:
    """Everything a preview draws, loaded once: the cog (built, painted from its PNGs), the three models and the stand-in player. [built] is what
    `sea_monsters.write_model` returned ({texture name: (model, scene, tex, glow, problems)}); without it the models are built here and their
    textures read from [out]/textures/entity/sea."""

    def __init__(self, out, built=None, say=print):
        t0 = time.time()
        self.say = say
        self.out = out
        built = built or {}
        cache = os.environ.get('SEA_PREVIEW_CACHE')            # (for working on the previews: the cog's scene takes 20 s to build, so keep it)
        if cache and os.path.exists(cache):
            import pickle
            with open(cache, 'rb') as f:
                self.cog_model, self.cog_scene = pickle.load(f)
        else:
            self.cog_model = ship.build()
            ship.pack_atlas(self.cog_model)
            self.cog_scene = ship.Scene(self.cog_model)
            if cache:
                import pickle
                with open(cache, 'wb') as f:
                    pickle.dump((self.cog_model, self.cog_scene), f)
        self.lens = {p.name: p.cubes[0].size[1] for p in self.cog_model.all_parts() if p.name.startswith(('brace_', 'lift_'))}
        for base in (os.path.join(out, 'textures', 'entity', 'ship'), os.path.join(REPO_ASSETS, 'textures', 'entity', 'ship')):
            if os.path.exists(os.path.join(base, 'cog.png')):
                self.cog_tex, self.cog_glow = _load(os.path.join(base, 'cog.png')), _load(os.path.join(base, 'cog_glow.png'))
                break
        else:
            _m, _s, self.cog_tex, self.cog_glow = ship.painted()
        self.models = {}
        for kind, name, mod in (('gnashfin', 'gnashfin', G), ('arm', 'maelgrasp_arm', A), ('head', 'maelgrasp', H)):
            if name in built:
                _model, scene, tex, glow = built[name][:4]
            else:
                _model = mod.build()
                K.pack_atlas(_model)
                scene = K.SScene(_model, mod.DOUBLE)
                base = os.path.join(out, 'textures', 'entity', 'sea')
                tex, glow = _load(os.path.join(base, name + '.png')), _load(os.path.join(base, name + '_glow.png'))
            self.models[kind] = Model(kind, scene, tex, glow)
        psc, ptex = player_model()
        self.models['player'] = Model('player', psc, ptex, None)
        psc, ptex = player_model(shirt=(176, 62, 52), pants=(70, 58, 52))
        self.models['player2'] = Model('player2', psc, ptex, None)
        self.seas = {}
        self.cog_layers = {}
        say(f'  stage ready in {time.time() - t0:.0f} s')

    # ---- the cog
    def cog_pose(self, kw):
        st = ship.CogState(**kw)
        return ship.pose(st, self.lens), ship.tilt_root(st.pitch, st.heel, st.bob), st

    def cog_layer(self, cam, light, kw, static=False, turn=0.0):
        """The cog's layer for the camera and the state [kw] (CogState kwargs); kept when the ship is [static] (the frames that share it share the layer)."""
        key = (cam.yaw, cam.elev, cam.scale, tuple(cam.size), tuple(np.round(cam.center, 2)), turn, tuple(sorted(kw.items()))) if static else None
        if key is not None and key in self.cog_layers:
            return self.cog_layers[key]
        pose, root, st = self.cog_pose(kw)
        act = K.Actor(self.cog_scene, self.cog_tex, self.cog_glow, pose, root, st.lanterns)
        layer = K.render_layer(act, cam, light)
        if key is not None:
            self.cog_layers[key] = layer
        return layer

    # ---- the sea
    def sea(self, cam, sky, size, plain, sea_t):
        key = (cam.yaw, cam.elev, cam.scale, tuple(size), tuple(np.round(cam.center, 2)), sky, plain, sea_t)
        if key not in self.seas:
            self.seas[key] = K.sea_of(cam, sky, size, SS, sea_t, plain)
        return self.seas[key]


# ================================================================== effects: spray, foam, ink, chips

def burst(rng, n, p0, t0, speed=(1.5, 4.0), cone=0.9, axis=(0.0, -1.0, 0.0), life=(18.0, 40.0), size=(0.5, 1.3), colour=(232, 242, 250), alpha=0.9,
          g=GRAVITY, floor=0.0):
    """[n] flying specks leaving [p0] at tick [t0] in a cone of half-angle [cone] radians about [axis], gone when they fall to y = [floor] (the sea;
    None: never). Returns the list `fly` draws from."""
    ax = unit(axis)
    u = unit(np.cross(ax, [0.3, 0.2, 0.9]))
    w = np.cross(ax, u)
    out = []
    for _ in range(n):
        a = rng.uniform(0.0, 2.0 * math.pi)
        c = math.sqrt(rng.uniform(0.0, 1.0)) * cone
        d = ax * math.cos(c) + (u * math.cos(a) + w * math.sin(a)) * math.sin(c)
        out.append(dict(p=np.asarray(p0, float), v=d * rng.uniform(*speed), t0=t0, life=rng.uniform(*life), r=rng.uniform(*size), col=colour, a=alpha, g=g,
                        floor=floor))
    return out


def fly(specks, t):
    """The specks of `burst` at tick [t] as discs for `draw`: (x, y, z, radius px, rgb, alpha, softness)."""
    out = []
    for s in specks:
        age = t - s['t0']
        if age < 0.0 or age > s['life']:
            continue
        p = s['p'] + s['v'] * age + np.array([0.0, 0.5 * s['g'] * age * age, 0.0])
        if s['floor'] is not None and p[1] > s['floor'] and age > 2.0:
            continue
        fade = 1.0 - smooth(0.6, 1.0, age / s['life'])
        out.append((p[0], p[1], p[2], s['r'], s['col'], s['a'] * fade, 0.55))
    return out


def rings(events, t):
    """Foam spots for `K.water_rings` from [events] ((x, z, tick, strength, start radius, growth px per tick, life ticks)) at tick [t]."""
    out = []
    for x, z, t0, k, r0, grow, life in events:
        age = t - t0
        if age < 0.0 or age > life:
            continue
        out.append((x, z, r0 + grow * age, k * (1.0 - age / life) ** 1.3))
    return out


def ink_cloud(seed, p0, t0, t, reach=70.0, n=26, life=120.0, colour=(16, 8, 26), drift=(0.0, 0.0)):
    """A cloud of ink spewed at [p0] (a point of the sea, scene px) at tick [t0]: soft dark discs just under the surface that swell, spread over it, drift
    with [drift] (px per tick) and thin out, for `draw`'s discs (under the water, so they are never brighter than a smudge)."""
    rng = np.random.default_rng(seed)
    out = []
    age = t - t0
    if age < 0.0 or age > life:
        return out
    grow = 1.0 - math.exp(-age / 20.0)
    fade = (1.0 - smooth(0.5, 1.0, age / life)) * smooth(0.0, 0.06, age / life + 0.02)
    for i in range(n):
        a = rng.uniform(0.0, 2.0 * math.pi)
        d = reach * math.sqrt(rng.uniform(0.05, 1.0)) * grow
        y = 1.5 + rng.uniform(0.0, 7.0) * (0.4 + 0.6 * grow) + 0.05 * age
        r = rng.uniform(6.0, 13.0) * (0.35 + 0.65 * grow) * (1.0 + 0.5 * age / life)
        out.append((p0[0] + drift[0] * age + d * math.cos(a), y, p0[2] + drift[1] * age + d * math.sin(a), r, colour, 0.55 * fade, 0.9))
    return out


def ink_pool(seed, p0, t0, t, drift=(0.0, 0.0), reach=70.0, n=16, life=120.0):
    """The stains (x, z, radius px, strength; for `compose`) of a cloud of ink spewed at [p0] (x, z) at tick [t0]: it spreads over the sea from the place it
    came out, swells, drifts with [drift] (px per tick), and thins out over [life] ticks."""
    rng = np.random.default_rng(seed)
    age = t - t0
    if age < 0.0 or age > life:
        return []
    grow = 1.0 - math.exp(-age / 20.0)
    fade = (1.0 - smooth(0.5, 1.0, age / life)) * smooth(0.0, 0.05, age / life + 0.02)
    out = []
    for _ in range(n):
        a = rng.uniform(0.0, 2.0 * math.pi)
        d = reach * math.sqrt(rng.uniform(0.05, 1.0)) * grow
        r = rng.uniform(9.0, 20.0) * (0.35 + 0.65 * grow) * (1.0 + 0.5 * age / life)
        out.append((p0[0] + drift[0] * age + d * math.cos(a), p0[1] + drift[1] * age + d * math.sin(a), r, 0.92 * fade))
    return out


# ================================================================== drawing a frame

def draw(world, spec):
    """One picture from a frame spec: (uint8 array (H, W, 3), mask (H, W) 0..1 of where the cog and the creatures are).

    spec keys: cam (yaw, elev, scale, size, center), sky, cog (CogState kwargs or None), cog_static (the ship does not move: share its layer), actors (list of
    dicts: model, pose, root, glow), spots (foam), streaks (foam trails), inks (stains of ink on the sea), fx (discs: (x, y, z, radius px, rgb, alpha,
    softness)), plain, sea_t, ring_t, murk, light_turn."""
    c = spec['cam']
    W, Ht = c['size']
    cam = ship.Camera(c['yaw'], c['elev'], c['scale'] * SS, (W * SS, Ht * SS), c['center'])
    sky = spec.get('sky', 'dusk')
    light = ship.make_light(ship.SKIES[sky], spec.get('light_turn', 0.0))
    layers = []
    if spec.get('cog') is not None:
        layers.append(world.cog_layer(cam, light, spec['cog'], spec.get('cog_static', False), spec.get('light_turn', 0.0)))
    for a in spec.get('actors', ()):
        m = world.models[a['model']]
        layers.append(K.render_layer(K.Actor(m.scene, m.tex, m.glow, a.get('pose'), a.get('root'), a.get('glow', 0.0), only=a.get('only')), cam, light))
    layer = K.merge(layers)
    sea = world.sea(cam, sky, (W, Ht), spec.get('plain', False), spec.get('sea_t', 0.0))
    im = K.compose(layer, cam, (W, Ht), sky, ss=SS, sea_t=spec.get('sea_t', 0.0), bloom=spec.get('bloom', 1.0), spots=spec.get('spots', ()), sea=sea,
                   ring_t=spec.get('ring_t'), murk=spec.get('murk', 1.0), streaks=spec.get('streaks', ()), inks=spec.get('inks', ()))
    arr = np.asarray(im, np.float32)
    if spec.get('fx'):
        arr = draw_fx(arr, layer, cam, spec['fx'])
    m = np.asarray(Image.fromarray((layer.hit * 255).astype(np.uint8)).resize((W, Ht), Image.BOX), np.float32) / 255.0
    return np.clip(arr, 0, 255).astype(np.uint8), m


def draw_fx(img, layer, cam, items):
    """The discs of [items] over the picture [img] (output size), hidden where the cog or a creature is nearer to the camera."""
    H_, W_ = img.shape[:2]
    pts = cam.project(np.array([[i[0], i[1], i[2]] for i in items], float))
    img = img.copy()
    for (px, py, dz), (x, y, z, r, rgb, a, soft) in zip(pts, items):
        cx, cy = px / SS, py / SS
        rr = max(0.7, r * cam.scale / SS)
        x0, x1 = int(max(0, math.floor(cx - rr - 1))), int(min(W_, math.ceil(cx + rr + 2)))
        y0, y1 = int(max(0, math.floor(cy - rr - 1))), int(min(H_, math.ceil(cy + rr + 2)))
        if x0 >= x1 or y0 >= y1 or a <= 0.01:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1]
        d = np.hypot(xx + 0.5 - cx, yy + 0.5 - cy) / rr
        cov = np.clip((1.0 - d) / max(soft, 1e-3), 0.0, 1.0)
        dep = layer.depth[np.clip(((yy + 0.5) * SS).astype(int), 0, layer.depth.shape[0] - 1), np.clip(((xx + 0.5) * SS).astype(int), 0, layer.depth.shape[1] - 1)]
        w = (cov * a * (dz < dep))[..., None]
        img[y0:y1, x0:x1] = img[y0:y1, x0:x1] * (1.0 - w) + np.array(rgb, np.float32)[None, None, :] * w
    return img


# ---- drawing the frames, four at a time

_G = {}


def _job(i):
    return i, draw(_G['world'], _G['specs'][i])


@ship.previewing
def render(world, specs, workers=WORKERS):
    """The pictures and masks of [specs], in order. The cog's cached layers and the sea are made once before the workers fork. Drawn within
    `ship.fast_noise()` (the sea, the sky and the foam are noise; a pixel may differ by a level from what numpy would give), which the forked workers
    keep; the textures are painted elsewhere, outside it."""
    _G['world'], _G['specs'] = world, specs
    import multiprocessing as mp
    done = {}
    if workers <= 1 or len(specs) == 1:
        for i in range(len(specs)):
            done[i] = draw(world, specs[i])
    else:
        # warm what the frames share: the sea of each camera, and the cog's layer for a static ship
        for s in specs:
            c = s['cam']
            cam = ship.Camera(c['yaw'], c['elev'], c['scale'] * SS, (c['size'][0] * SS, c['size'][1] * SS), c['center'])
            world.sea(cam, s.get('sky', 'dusk'), c['size'], s.get('plain', False), s.get('sea_t', 0.0))
            if s.get('cog') is not None and s.get('cog_static'):
                world.cog_layer(cam, ship.make_light(ship.SKIES[s.get('sky', 'dusk')], s.get('light_turn', 0.0)), s['cog'], True, s.get('light_turn', 0.0))
        ctx = mp.get_context('fork')
        with ctx.Pool(workers) as pool:
            for i, r in pool.imap_unordered(_job, range(len(specs)), chunksize=1):
                done[i] = r
    order = sorted(done)
    return [done[i][0] for i in order], [done[i][1] for i in order]


def gif_palette(frames, masks, colours):
    """A palette for all of [frames]: median cut over the pixels of the cog and the creatures in a sample of them and a sprinkling of the sea, each
    colour counted by the square root of how many pixels have it, so a small bright thing (the glow of a lure) is not lost among the planks and water."""
    take = []
    for i in range(0, len(frames), max(1, len(frames) // 10)):
        take += [frames[i][masks[i] > 0.02], frames[i][masks[i] <= 0.02][::13]]
    pix = np.concatenate(take, 0).astype(np.int64)
    key = (pix[:, 0] >> 2) * 4096 + (pix[:, 1] >> 2) * 64 + (pix[:, 2] >> 2)
    uniq, first, count = np.unique(key, return_index=True, return_counts=True)
    reps = np.maximum(1, np.round(np.sqrt(count)).astype(int))
    stack = np.repeat(pix[first], reps, axis=0).astype(np.uint8)
    q = Image.fromarray(stack.reshape(1, -1, 3)).quantize(colors=colours, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    return np.array(q.getpalette()[:3 * colours], float).reshape(-1, 3)


def write_gif(path, frames, masks, durations, colours=254, bits=6, tol=400.0):
    """`ship.write_gif` with this module's palette (see `gif_palette`)."""
    real = ship.gif_palette
    ship.gif_palette = gif_palette
    try:
        return ship.write_gif(path, frames, masks, durations, colours=colours, bits=bits, tol=tol)
    finally:
        ship.gif_palette = real


def save_gif(path, frames, masks, durations=None, limit=GIF_LIMIT, say=print):
    """The GIF of [frames] at [path], its size kept under [limit] bytes by letting the writer leave more of the slow changes alone, as far as 3600 (a
    few levels of one channel; beyond that the slow changes visibly stick and jump). Returns the size."""
    durations = durations or [FRAME_MS] * len(frames)
    size = tol = 0
    for tol in (400.0, 900.0, 1800.0, 2700.0, 3600.0):
        size = write_gif(path, frames, masks, durations, tol=tol)
        if size <= limit:
            break
    say(f'    {os.path.basename(path)}: {len(frames)} frames, {size / 1e6:.2f} MB (tolerance {tol:.0f})' + ('  OVER THE LIMIT' if size > limit else ''))
    return size


def save_mp4(path, frames, ms=FRAME_MS, repeat=1):
    """The same pictures as an H.264 video (ffmpeg, no sound; twice as large with the pixels left square, and 17 on the quality scale, so it is the one to watch
    where the GIF's 256 colours show), played [repeat] times over. Returns its size, or None where there is no ffmpeg."""
    import shutil
    import subprocess
    if not shutil.which('ffmpeg'):
        return None
    h, w = frames[0].shape[:2]
    cmd = ['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{w}x{h}', '-framerate', f'{1000.0 / ms:g}', '-i', '-',
           '-vf', 'scale=iw*2:ih*2:flags=neighbor,format=yuv420p', '-c:v', 'libx264', '-preset', 'medium', '-crf', '17', '-movflags', '+faststart', path]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for _ in range(repeat):
        for f in frames:
            proc.stdin.write(np.ascontiguousarray(f).tobytes())
    proc.stdin.close()
    proc.wait()
    return os.path.getsize(path) if proc.returncode == 0 else None


def sheet(frames, cols=4, count=12):
    """A contact sheet of [count] of [frames], evenly chosen."""
    idx = [int(k * len(frames) / count) for k in range(count)] if len(frames) > count else list(range(len(frames)))
    return ship.sheet_of([frames[i] for i in idx], cols)


def save_png(path, arr):
    Image.fromarray(arr).save(path, optimize=True)


# ================================================================== the Gnashfin

def softmin(a, b, k=0.35):
    """A rounded min(a, b)."""
    return -math.log(math.exp(-k * a) + math.exp(-k * b)) / k


def gstate(**kw):
    return G.GnashState(**kw)


def gactor(st, M, glow=None):
    """The Gnashfin's actor at the matrix [M]; its glow burns as [st] says (or [glow])."""
    return dict(model='gnashfin', pose=G.pose(st), root=M, glow=st.glow if glow is None else glow)


def swell_loop(tick, lap):
    """`ship.swell(tick, 0, 1)` with each of its beats moved to the nearest whole number of cycles in [lap] ticks, so that a ship on it is back where she
    began after [lap] ticks."""
    def w(freq):
        return 2.0 * math.pi * max(1, round(freq * lap / (2.0 * math.pi))) / lap
    pitch = 0.7 * (math.sin(tick * w(0.069)) * 0.7 + math.sin(tick * w(0.153) + 1.1) * 0.3)
    roll = math.sin(tick * w(0.048) + 0.6) * 0.75 + math.sin(tick * w(0.117) + 2.3) * 0.25
    bob = 0.04 * (math.sin(tick * w(0.083) + 0.3) * 0.7 + math.sin(tick * w(0.19)) * 0.3)
    return pitch, roll, bob


def shipkw(tick, lap=None, **kw):
    """A cog at anchor on the swell (sail furled) at [tick], with whatever else [kw] says; with [lap], on a swell that goes round in [lap] ticks."""
    pitch, roll, bob = ship.swell(tick, 0.0, 1.0) if lap is None else swell_loop(tick, lap)
    base = dict(sail=0.0, fill=0.0, brace=0.0, wind=0.3, time=tick, speed=0.0, pitch=pitch, heel=roll, bob=bob)
    base.update(kw)
    return base


def turn_bend(dx, dz, ddx, ddz, k=42.0):
    """The Gnashfin's `bend` (to its own right above 0) for a path with velocity (dx, dz) and acceleration (ddx, ddz): curvature, in fish lengths."""
    v2 = dx * dx + dz * dz
    kappa = (ddx * dz - ddz * dx) / max(v2, 1e-6) ** 1.5
    return clamp(kappa * k, -1.0, 1.0)


def wake(path, t, life=46.0, width=1.4, strength=0.8, vee=0.42, steps=2):
    """The foam streaks of the wake of something that swam the path [path] (tick -> (x, z, dx, dz, ...)) up to tick [t]: a trail behind it
    and two arms of a V opening behind it, fading over [life] ticks."""
    out = []
    samples = []
    for i in range(int(life * steps) + 1):
        a = i / steps
        x, z, dx, dz = path(t - a)[:4]
        h = unit((dx, dz))
        samples.append((a, x, z, h))
    for (a0, x0, z0, h0), (a1, x1, z1, h1) in zip(samples, samples[1:]):
        f0, f1 = (1.0 - a0 / life) ** 1.3, (1.0 - a1 / life) ** 1.3
        out.append((x0, z0, x1, z1, width + 0.05 * a0, width + 0.05 * a1, strength * f0, strength * f1))
        for sgn in (-1.0, 1.0):
            o0, o1 = 1.5 + vee * a0, 1.5 + vee * a1
            out.append((x0 + sgn * h0[1] * o0, z0 - sgn * h0[0] * o0, x1 + sgn * h1[1] * o1, z1 - sgn * h1[0] * o1, 0.9, 1.1,
                        0.6 * strength * f0 * smooth(0.0, 5.0, a0), 0.6 * strength * f1 * smooth(0.0, 5.0, a1)))
    return out


def gnashfin_hero(world, at=(-132.0, -34.0, -68.0), face=(0.85, -0.50), pitch=24.0, cam=None, u=0.35):
    """A still at dusk: a Gnashfin bursts out of the sea off the cog's starboard bow and leaps at her against the sunset, jaws wide, every light on it
    burning, its fins spread, water pouring off it and spray flung from where it broke the surface; the cog lies at the edge of the picture with her
    lanterns lit."""
    cam = cam or dict(yaw=18.0, elev=4.5, scale=6.0, size=(640, 560), center=(-103.0, -38.0, -66.0))
    rng = np.random.default_rng(17)
    t = 20.0
    st = gstate(time=57.0, swim=1.0, leap=u, jaw=1.0, glow=1.0, gnaw=0.0)
    d = unit(face)
    p = np.array(at, float)
    M = K.place(p[0], p[1], p[2], yaw=yaw_of(d[0], d[1]), pitch=pitch, roll=-8.0)
    back = unit(np.array([-d[0], -d[1]]))
    base = np.array([p[0] + back[0] * 26.0, 0.0, p[2] + back[1] * 26.0])
    specks = burst(rng, 110, base, 2.0, speed=(2.5, 8.5), cone=0.95, axis=(d[0] * 0.5, -1.0, d[1] * 0.5), life=(16.0, 32.0), size=(0.6, 2.0))
    for k in range(26):
        tk = 4.0 + k * 0.8
        q = p + np.array([back[0], 0.0, back[1]]) * rng.uniform(0.0, 22.0) + np.array([rng.normal(0.0, 3.0), rng.uniform(0.0, 16.0), rng.normal(0.0, 3.0)])
        specks += burst(rng, 3, q, tk, speed=(0.1, 0.9), cone=1.4, axis=(0.0, 1.0, 0.0), life=(10.0, 24.0), size=(0.4, 1.1), alpha=0.85)
    events = [(base[0], base[2], 2.0, 1.0, 8.0, 0.9, 60.0), (base[0], base[2], 5.0, 0.6, 16.0, 0.9, 60.0)]
    kw = dict(sail=0.0, fill=0.0, brace=0.0, wind=0.3, time=0.0, speed=0.0, lanterns=0.95, pitch=0.0, heel=0.0, bob=0.0)
    return [dict(cam=cam, sky='dusk', cog=kw, cog_static=True, actors=[gactor(st, M)], spots=[(base[0], base[2], 11.0, 0.9, 0.7)] + rings(events, t), fx=fly(specks, t),
                 sea_t=0.0, ring_t=t, bloom=1.0, murk=2.0)]


def circling(world, n=52):
    """The Gnashfin circling the cog at anchor, its dorsal fin cutting the surface: an ellipse round the ship at a sprinter's pace, banked into its
    turn and bending with it, a wake of foam streaming behind the fin; its lights burn, the lure bead glows. A loop of one lap: the path, the swell and the
    fish's own sway go round in [n] pictures, and what does not (the beat of its tail, the flutter of the rigging) is blended across the seam by the 10
    pictures more it makes (see `crossfade_loop`)."""
    cx, cz, rx, rz = 0.0, 6.0, 66.0, 108.0
    lap = n * TPF
    cam = dict(yaw=34.0, elev=21.0, scale=2.55, size=(450, 440), center=(0.0, -6.0, 6.0))

    def path(t):
        th = 2.0 * math.pi * t / lap
        x, z = cx + rx * math.cos(th), cz + rz * math.sin(th)
        w = 2.0 * math.pi / lap
        dx, dz = -rx * math.sin(th) * w, rz * math.cos(th) * w
        ddx, ddz = -rx * math.cos(th) * w * w, -rz * math.sin(th) * w * w
        return x, z, dx, dz, ddx, ddz
    specs = []
    for f in range(n + 10):
        t = f * TPF
        x, z, dx, dz, ddx, ddz = path(t)
        bend = turn_bend(dx, dz, ddx, ddz)
        sway = math.sin(3.0 * math.tau * t / lap)
        y = 11.5 + 1.2 * sway
        st = gstate(time=t + 10.0, swim=0.85, bend=bend, glow=0.75 + 0.25 * math.sin(2.0 * math.tau * t / lap))
        M = K.place(x, y, z, yaw=yaw_of(dx, dz), pitch=1.5 * sway, roll=-9.0 * bend)
        spots = [(x, z, 4.5, 0.9, 0.4)]
        streaks = wake(path, t)
        specs.append(dict(cam=cam, sky='day', cog=shipkw(t, lap), actors=[gactor(st, M)], spots=spots, streaks=streaks, sea_t=0.0, ring_t=t, bloom=0.8, murk=2.2))
    return specs


def leap(world, n=58):
    """The Gnashfin leaping across the cog's deck: it comes up fast from deep water off the starboard side, breaks the surface in a crown of
    spray, arcs over the rail and the deck (the model's own arch, spread of fins and flick of the tail on top of the path's pitch) shedding water
    and falls into the sea beyond the port rail. The fish is gone from the water for a moment before it comes again: the swell goes round once in the
    film's [n] pictures, and the rings and spray of its fall go on past the end into the 10 pictures more it makes, to be blended into the calm sea it
    began in (see `crossfade_loop`)."""
    vx = 5.0
    x_exit, x_enter, zc = -64.0, 64.0, 12.0
    T = (x_enter - x_exit) / vx
    peak = 82.0
    g = 8.0 * peak / (T * T)
    t_exit = 14.0                                       # tick the fish breaks the surface at
    cam = dict(yaw=34.0, elev=24.0, scale=2.85, size=(450, 470), center=(2.0, -40.0, 10.0))
    rng = np.random.default_rng(11)

    def pos(t):
        tau = t - t_exit
        x = x_exit + vx * tau
        y_par = -g * T / 2.0 * tau + 0.5 * g * tau * tau          # the parabola, y down: 0 at the exit and entry, -peak between
        y_par = y_par - 0.0
        # underwater the fish swims level and deep, and is bent up into the parabola
        y = softmin(y_par, 30.0, 0.22)
        return np.array([x, y, zc + 3.0 * math.sin(0.05 * t)])
    specks = []
    specks += burst(rng, 70, (x_exit, 0.0, zc), t_exit + 0.5, speed=(2.5, 8.0), cone=0.9, axis=(0.45, -1.0, 0.1), life=(14.0, 26.0), size=(0.6, 1.9))
    specks += burst(rng, 90, (x_enter, 0.0, zc), t_exit + T, speed=(2.5, 9.0), cone=0.95, axis=(0.15, -1.0, 0.0), life=(14.0, 28.0), size=(0.6, 2.1))
    for k in range(int(T / 1.5)):                                    # water streaming off the fish
        tk = t_exit + 2.0 + k * 1.5
        p = pos(tk)
        specks += burst(rng, 3, p, tk, speed=(0.1, 0.8), cone=1.4, axis=(0.0, 1.0, 0.0), life=(10.0, 22.0), size=(0.4, 0.9), alpha=0.8)
    events = [(x_exit, zc, t_exit, 0.95, 6.0, 0.8, 55.0), (x_enter, zc, t_exit + T, 1.0, 7.0, 0.9, 60.0)]
    specs = []
    t_end = t_exit + T + 18.0
    for f in range(n + 10):
        t = f * (t_end / n)
        p = pos(t)
        p2 = pos(t + 0.4)
        d = p2 - p
        air = t_exit <= t <= t_exit + T
        u = clamp((t - t_exit) / T)
        pitch = pitch_of(*d)
        st = gstate(time=t * 1.0 + 6.0, swim=1.0, leap=u if air else -1.0, jaw=0.55 * smooth(0.0, 0.2, u) * (1.0 - smooth(0.7, 0.95, u)) if air else 0.0,
                    glow=0.6 + 0.4 * math.sin(math.pi * u) if air else 0.6)
        M = K.place(p[0], p[1], p[2], yaw=yaw_of(d[0], d[2]), pitch=pitch, roll=0.0)
        fx = fly(specks, t)
        specs.append(dict(cam=cam, sky='day', cog=shipkw(t, t_end), actors=[gactor(st, M)], spots=rings(events, t), fx=fx, sea_t=0.0, ring_t=t, bloom=0.8, murk=2.2))
    return specs


def snap(world, n=60):
    """The Gnashfin's bite, close to: it idles at the surface, opens its jaw to warn (a half-gape that clamps shut), gapes wide with its lights
    blazing, lunges and snaps with a burst of spray, shakes its head as it clamps and settles back. A loop: it ends where it began."""
    cam = dict(yaw=58.0, elev=16.0, scale=5.0, size=(450, 380), center=(-4.0, -4.0, -2.0))
    rng = np.random.default_rng(5)
    heading = (-0.90, 0.22)                                  # the way it faces (x, z): across the camera, a little toward it
    h = unit(heading)
    base = np.array([12.0, 7.0, 4.0])
    keys_jaw = [(0.0, 0.0), (8.0, 0.0), (15.0, 0.55), (19.0, 0.62), (21.0, 0.0), (30.0, 0.0), (44.0, 1.0), (50.0, 1.0), (51.5, 0.0), (58.0, 0.0), (80.0, 0.0)]
    keys_lunge = [(0.0, 0.0), (30.0, 0.0), (44.0, -0.15), (50.0, -0.15), (52.0, 1.0), (58.0, 0.7), (72.0, 0.0), (80.0, 0.0)]
    keys_gnaw = [(0.0, 0.0), (51.0, 0.0), (53.0, 1.0), (58.0, 0.6), (66.0, 0.0), (80.0, 0.0)]
    keys_glow = [(0.0, 0.5), (30.0, 0.5), (44.0, 1.0), (58.0, 1.0), (72.0, 0.5), (80.0, 0.5)]
    total = n * TPF
    specks = burst(rng, 60, base + np.array([h[0] * 25.0, 4.0, h[1] * 25.0]), 51.0, speed=(2.0, 7.0), cone=1.1, axis=(h[0] * 0.5, -1.0, h[1] * 0.5), life=(12.0, 26.0), size=(0.5, 1.8))
    specs = []
    for f in range(n):
        t = f * total / n
        k = lambda keys: float(track(t, keys))
        lunge = k(keys_lunge)
        p = base + np.array([h[0], 0.0, h[1]]) * (lunge * 30.0)
        p[1] += -2.5 * math.sin(math.pi * clamp(lunge)) + 1.2 * math.sin(t * 0.13)
        gape = k(keys_jaw)
        st = gstate(time=t * 1.0 + 3.0, swim=0.35 + 0.65 * clamp(lunge), jaw=gape, gnaw=k(keys_gnaw), glow=k(keys_glow))
        M = K.place(p[0], p[1], p[2], yaw=yaw_of(h[0], h[1]), pitch=8.0 * clamp(lunge) + 4.0 * gape, roll=0.0)
        ev = [(p[0] + h[0] * 22.0, p[2] + h[1] * 22.0, 51.5, 0.9, 5.0, 0.7, 40.0)]
        spots = [(p[0], p[2], 4.5, 0.55 + 0.35 * clamp(lunge), 0.3)] + rings(ev, t)
        specs.append(dict(cam=cam, sky='day', actors=[gactor(st, M)], spots=spots, fx=fly(specks, t), sea_t=0.0, ring_t=t, bloom=1.0, murk=2.6))
    return specs


def blend_pose(a, b, w):
    """The pose [a] (dict of part -> pos / rot / scale / visible) moved the fraction [w] of the way to [b] (parts and keys the two share)."""
    out = {}
    for name, da in a.items():
        db = b.get(name, {})
        d = {}
        for key, va in da.items():
            vb = db.get(key)
            if key == 'visible' or vb is None:
                d[key] = va if w < 0.5 or vb is None else vb
            else:
                d[key] = tuple(float(x) + (float(y) - float(x)) * w for x, y in zip(va, vb))
        out[name] = d
    return out


def looped(pose_of, t, length, fade=14.0):
    """pose_of(t), for a loop of [length] ticks: over its last [fade] ticks the pose is blended into the pose of the start, so the GIF closes without a jump."""
    if t < length - fade:
        return pose_of(t)
    return blend_pose(pose_of(t), pose_of(t - length), smooth(length - fade, length, t))


def rest_on(world, kind, pose, M, floor_y):
    """The matrix [M] (a creature's place) moved up or down so the lowest point of the posed model stands on y = [floor_y]."""
    lo, hi = K.extent_of(world.models[kind].scene, pose, M)
    out = M.copy()
    out[1, 3] += floor_y - hi[1]
    return out


def gnaw(world, n=24):
    """The Gnashfin worrying the cog's planks at the waterline off the starboard bow: nose against the hull, back awash, head shaking as it bites
    and clamps, splinters and spray flying at each bite. A loop of three bites."""
    zc = -14.0
    hbw = float(ship.hb(zc, 0.0))
    bite = 2.0 * math.pi / 0.62                           # the head-shake's own period: a bite to each shake
    length = 3.0 * bite
    tpf = length / n
    d = unit((1.0, -0.12))                                # the way it faces: square to the hull, a little toward the bow
    nose = np.array([-(hbw + 1.0), 0.0, zc])
    centre = nose - np.array([d[0], 0.0, d[1]]) * 27.0
    contact = nose + np.array([0.0, 1.0, 0.0])
    rng = np.random.default_rng(3)
    cam = dict(yaw=164.0, elev=15.0, scale=4.2, size=(450, 410), center=(-hbw - 16.0, -4.0, zc - 4.0))
    chips = []
    for k in range(-1, 5):
        tk = bite * (k + 0.5) + 0.0
        chips += burst(rng, 18, contact + np.array([-1.5, -2.0, 0.0]), tk, speed=(1.2, 4.6), cone=1.25, axis=(-0.8, -0.9, 0.0), life=(10.0, 22.0), size=(0.7, 1.6),
                       colour=(184, 140, 90), alpha=1.0, floor=2.0)
        chips += burst(rng, 10, contact + np.array([-1.5, -2.0, 0.0]), tk, speed=(1.5, 5.0), cone=1.3, axis=(-0.6, -1.0, 0.0), life=(12.0, 24.0), size=(0.4, 1.0), floor=2.0)
    events = [(nose[0] - 2.0, nose[2], bite * (k + 0.5), 0.7, 3.0, 0.5, 26.0) for k in range(-1, 5)]

    def st_of(t):
        c = 0.5 + 0.5 * math.cos(0.62 * t + 1.9)                 # 1 at the moment of the clamp (a quarter-turn after the shake's peak, so it bites at the swing's end)
        return gstate(time=t + 20.0, swim=0.1, jaw=0.9, gnaw=0.35 + 0.65 * c, glow=0.5 + 0.2 * math.sin(0.21 * t))
    specs = []
    for f in range(n):
        t = f * tpf
        st = st_of(t)
        pose = looped(lambda tt: G.pose(st_of(tt)), t, length, 12.0)
        M = K.place(centre[0], 6.0 + 0.8 * math.sin(0.4 * t), centre[2], yaw=yaw_of(d[0], d[1]), pitch=3.0 + 4.0 * (1.0 - clamp(st.gnaw)) , roll=0.0)
        specs.append(dict(cam=cam, sky='day', cog=shipkw(t * 1.0), cog_static=False, actors=[dict(model='gnashfin', pose=pose, root=M, glow=st.glow)],
                          spots=[(centre[0] + d[0] * 22.0, centre[2] + d[1] * 22.0, 6.0, 0.5, 0.3)] + rings(events, t), fx=fly(chips, t), sea_t=0.0, ring_t=t, bloom=0.8, murk=2.2))
    return specs


def stranded(world, n=44):
    """The Gnashfin floundering on the cog's main deck forward of the mast: on its side, bucking, slapping its tail, snapping, gills heaving, fins
    flailing, hopping and rolling over from flank to flank, flicking water off with each slap."""
    deck = -12.0
    rng = np.random.default_rng(8)
    cam = dict(yaw=22.0, elev=44.0, scale=5.4, size=(450, 420), center=(-2.0, -16.0, -24.0))
    length = n * TPF
    base = np.array([-3.0, 0.0, -26.0])
    drops = []
    for k in range(0, 8):
        tk = (math.pi / 2.0 + 2.0 * math.pi * k) / 1.05 - 3.0
        drops += burst(rng, 12, base + np.array([-4.0, -14.0, 14.0]), tk, speed=(1.0, 3.6), cone=1.2, axis=(-0.2, -1.0, 0.4), life=(10.0, 22.0), size=(0.4, 1.1), floor=deck + 1.0)

    def st_of(t):
        return gstate(time=t + 12.0, stranded=1.0, jaw=0.0, glow=0.35 + 0.15 * math.sin(0.4 * t))
    specs = []
    for f in range(n):
        t = f * TPF
        pose = looped(lambda tt: G.pose(st_of(tt)), t, length, 16.0)
        roll = 72.0 * math.sin(0.41 * t) + 18.0 * math.sin(1.07 * t + 1.0) + (90.0 if math.sin(0.41 * t) > 0 else -90.0) * 0.0
        hop = 4.5 * max(0.0, math.sin(1.05 * t + 0.4)) ** 2
        M0 = K.place(base[0] + 2.5 * math.sin(0.4 * t), 0.0, base[2] + 1.5 * math.sin(0.3 * t), yaw=-64.0 + 12.0 * math.sin(0.37 * t),
                     pitch=4.0 * math.sin(0.83 * t), roll=roll)
        M = rest_on(world, 'gnashfin', pose, M0, deck - hop)
        specs.append(dict(cam=cam, sky='day', cog=shipkw(0.0, pitch=0.0, heel=0.0, bob=0.0), cog_static=True,
                          actors=[dict(model='gnashfin', pose=pose, root=M, glow=0.35)], fx=fly(drops, t), sea_t=0.0, bloom=0.8))
    return specs


def figurehead(world, n=56):
    """The cog's bow close to, at dusk: the slain Maelgrasp's beak lashed on the bowsprit rides the swell (its lower jaw swings with her pitch, the loose ends
    of its lashing stream in the wind), the bow lantern swings, her bow throws foam. The swell and the wind are made to go round in [n] pictures; the
    little that does not (the flutter of the lashing) is blended across the seam."""
    total = n * TPF
    cam = dict(yaw=34.0, elev=14.0, scale=14.0, size=(480, 480), center=(0.0, -38.0, -90.0))
    m = 10
    specs = []
    for f in range(n + m):
        t = f * TPF
        a = 2.0 * math.pi * t / total
        pitch = 1.9 * math.sin(a) + 0.5 * math.sin(2.0 * a + 1.0)
        heel = 1.1 * math.sin(a + 1.3)
        bob = 0.07 * math.sin(a + 0.4)
        kw = dict(sail=0.0, fill=0.0, brace=0.0, wind=0.75, windYaw=0.0, time=t, speed=0.0, lanterns=1.0, figurehead=True, pitch=pitch, heel=heel, bob=bob)
        spots = [(0.0, -80.0, 7.0, 0.35 + 0.25 * math.sin(a + 1.0), 0.6)]
        specs.append(dict(cam=cam, sky='dusk', cog=kw, actors=[], spots=spots, sea_t=0.0, ring_t=t, bloom=1.0, murk=1.7))
    return specs


def crossfade_loop(frames, masks, m):
    """The pictures of a loop of len(frames) - [m] pictures from [frames], whose last [m] go on past the loop's end: the first [m] of the loop are blended
    from those, so the picture after the last one is the one that follows it."""
    n = len(frames) - m
    out_f, out_m = [], []
    for k in range(n):
        if k < m:
            u = (k + 1.0) / (m + 1.0)
            a, b = frames[n + k].astype(np.float32), frames[k].astype(np.float32)
            out_f.append(np.clip(a * (1.0 - u) + b * u, 0, 255).astype(np.uint8))
            out_m.append(masks[n + k] * (1.0 - u) + masks[k] * u)
        else:
            out_f.append(frames[k])
            out_m.append(masks[k])
    return out_f, out_m


def place_holding(anchor, target, yaw=0.0, pitch=0.0, roll=0.0):
    """The matrix that puts a model with its local point [anchor] at the scene point [target], turned as `K.place` says."""
    M = K.place(0.0, 0.0, 0.0, yaw=yaw, pitch=pitch, roll=roll)
    M[:3, 3] = np.asarray(target, float) - M[:3, :3] @ np.asarray(anchor, float)
    return M


# ================================================================== writing them all

def sequences():
    """{name: (builder(world) -> list of frame specs, kind[, ms a picture lasts])}: kind is 'still' (a PNG), 'gif' (a GIF and a contact sheet) or 'loop' (a
    GIF whose last pictures go on past the loop's end and are blended into its first: see `crossfade_loop`)."""
    import sea_prev_arm as R
    import sea_prev_head as HD
    import sea_prev_scene as SC
    return {
        'gnashfin_hero': (gnashfin_hero, 'still'), 'gnashfin_circling': (circling, 'loop'), 'gnashfin_leap': (leap, 'loop'), 'gnashfin_snap': (snap, 'gif'),
        'gnashfin_gnaw': (gnaw, 'gif'), 'gnashfin_stranded': (stranded, 'gif'),
        'arm_attack': (R.arm_attack, 'gif'), 'arm_hold': (R.arm_hold, 'gif'),
        'head_hero': (HD.head_hero, 'still'), 'head_surface': (HD.head_surface, 'gif'), 'head_wrath': (HD.head_wrath, 'gif'), 'head_face': (HD.head_face, 'gif', MS_SLOW),
        'night_still': (SC.night_still, 'still'), 'night': (SC.night, 'gif', MS_SLOW),
        'figurehead': (figurehead, 'loop'),
    }


def overview(pictures, height=300, cols=4):
    """One sheet of the [pictures] ((name, array) pairs): each shrunk to [height] px tall, in rows of [cols], named under it."""
    from PIL import ImageDraw
    cells = []
    for name, arr in pictures:
        im = Image.fromarray(arr)
        w = max(1, int(round(im.width * height / im.height)))
        cells.append((name, im.resize((w, height), Image.LANCZOS)))
    rows = [cells[i:i + cols] for i in range(0, len(cells), cols)]
    pad, label = 6, 16
    W = max(sum(c[1].width + pad for c in r) + pad for r in rows)
    H = len(rows) * (height + label + pad) + pad
    sheet_ = Image.new('RGB', (W, H), (16, 18, 24))
    d = ImageDraw.Draw(sheet_)
    y = pad
    for r in rows:
        x = pad
        for name, im in r:
            sheet_.paste(im, (x, y))
            d.text((x + 2, y + height + 2), name, fill=(190, 200, 214))
            x += im.width + pad
        y += height + label + pad
    return np.asarray(sheet_)


def picture_of(prev, name):
    """A picture of the sequence [name] from what is in [prev] (the still itself, or the GIF's picture 55 per cent of the way through), or None."""
    png, gif = os.path.join(prev, name + '.png'), os.path.join(prev, name + '.gif')
    if os.path.exists(png):
        return np.asarray(Image.open(png).convert('RGB'))
    if os.path.exists(gif):
        im = Image.open(gif)
        im.seek(int(im.n_frames * 0.55))
        return np.asarray(im.convert('RGB'))
    return None


def write_overview(prev, drawn=()):
    """`overview.png` in [prev]: a picture of every sequence, from [drawn] ((name, picture) pairs just made) or else from what is in [prev]."""
    drawn = dict(drawn)
    every = [(n, drawn[n] if n in drawn else picture_of(prev, n)) for n in NAMES]
    path = os.path.join(prev, 'overview.png')
    save_png(path, overview([(n, a) for n, a in every if a is not None]))
    return path


def write_all(out, prev, art=None, built=None, names=None, say=print, video=True):
    """Draws the sequences [names] (all of NAMES by default) into [prev]: a PNG for each still; for each film a GIF, an MP4 of the same (unless [video] is
    false) and a contact sheet; and `overview.png` with a picture of each (the ones not drawn this time are taken from what is already in [prev]).
    [out] is the guildmark assets folder the textures are read from (or `built`, what `sea_monsters.write_model` returned). Returns {name: GIF or PNG size}."""
    os.makedirs(prev, exist_ok=True)
    t0 = time.time()
    world = World(out, built, say=say)
    table = sequences()
    sizes, shown = {}, []
    for name in (names or NAMES):
        t1 = time.time()
        builder, kind, *more = table[name]
        specs = builder(world)
        frames, masks = render(world, specs)
        if kind == 'still':
            path = os.path.join(prev, name + '.png')
            save_png(path, frames[0])
            sizes[name] = os.path.getsize(path)
            shown.append((name, frames[0]))
            say(f'    {name}.png: {frames[0].shape[1]}x{frames[0].shape[0]}, {sizes[name] / 1e6:.2f} MB')
        else:
            if kind == 'loop':
                frames, masks = crossfade_loop(frames, masks, 10)
            ms = more[0] if more else FRAME_MS
            sizes[name] = save_gif(os.path.join(prev, name + '.gif'), frames, masks, durations=[ms] * len(frames), say=say)
            if video:
                mp4 = save_mp4(os.path.join(prev, name + '.mp4'), frames, ms, repeat=3 if kind == 'loop' else 1)
                if mp4:
                    say(f'    {name}.mp4: {mp4 / 1e6:.2f} MB')
            save_png(os.path.join(prev, name + '_sheet.png'), sheet(frames, 4, 12))
            shown.append((name, frames[int(len(frames) * 0.55)]))
        say(f'  {name}: {time.time() - t1:.0f} s')
    if shown:
        write_overview(prev, shown)
    say(f'previews: {len(sizes)} written in {time.time() - t0:.0f} s')
    return sizes


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    names = argv[2:] or None
    if names == ['overview']:
        print(write_overview(argv[1]))
        return 0
    for n in names or ():
        if n not in NAMES:
            raise SystemExit(f'unknown name {n}; the names are: overview, {", ".join(NAMES)}')
    write_all(argv[0], argv[1], names=names)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
