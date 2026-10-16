"""The gull's previews: animated GIFs and a still of the island gull, drawn with the very functions `GullModel.setupAnim` is made from (the Python
side of `island_gull_anim.py`), over the sea and sky of the cog's previews.

    python3 -B art/scripts/island_gull_previews.py <assets dir> <preview dir> [names ...]       (names: any of NAMES below; none means all)

`island_gull.py` calls `write_all(assets, prev)` after it has made the model. Standalone it reads the three textures under
<assets dir>/textures/entity/island.

Nothing here is the game. What the game decides (where a gull flies, how it banks, when it lands) the previews make up, as a scene that shows the
model off: each sequence below says how it stands in for the entity and the renderer (the path, the pitch and the roll of the whole bird, the camera,
the foam and the spray) and sets the render-state fields as the entity would. Frames are a picture every 1.6 ticks (80 ms: real time).

Scene space is the cog's model space: px (1/16 block), y down, the sea at y = 0. The gull's own model has its feet at y = 24 and the middle of its
body BODY_Y above them: `root()` puts that middle at a scene point and pitches and rolls the bird about it, as the renderer does.
"""
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np
from PIL import Image

import ship
import sea_kit as K
import sea_previews as SP
import island_gull_anim as A
import island_gull_paint as PT

TPF = SP.TPF
FRAME_MS = SP.FRAME_MS
SS = SP.SS
GIF_LIMIT = 5.5e6
SIZE = (480, 320)
NAMES = ['soar', 'flight', 'hover', 'land', 'idle', 'threat', 'float', 'dive', 'variants']
GOLD = {'soar': 'gull_soar', 'flight': 'gull_flight', 'hover': 'gull_hover', 'land': 'gull_land', 'idle': 'gull_idle', 'threat': 'gull_threat',
        'float': 'gull_float', 'dive': 'gull_dive', 'variants': 'gull_variants'}


# ================================================================== small helpers

smooth = SP.smooth
clamp = SP.clamp
lerp = SP.lerp


def ease(u):
    """0..1 eased."""
    u = clamp(u)
    return u * u * (3.0 - 2.0 * u)


def horizon_elev(cy, scale, height, frac):
    """The camera elevation (degrees) that puts the horizon at [frac] of the picture's height from the top, for a camera centred at height [cy]."""
    lo, hi = 0.2, 40.0
    for _ in range(40):
        mid = 0.5 * (lo + hi)
        e = math.radians(mid)
        row = height / 2 + ((0.0 - cy) * math.cos(e) - 640.0 * math.sin(e)) * scale
        if row / height > frac:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def cam(yaw, elev, scale, center, size=SIZE):
    return dict(yaw=yaw, elev=elev, scale=scale, size=size, center=tuple(float(v) for v in center))


def root(x, y, z, yaw=0.0, pitch=0.0, roll=0.0):
    """The gull's matrix: the middle of its body at the scene point (x, y, z), turned [yaw] degrees (see `K.facing`), its nose up [pitch], rolled
    [roll] degrees (positive: to its own left); the bird pitched and rolled about its body's middle as the renderer does."""
    M = K.place(x, y, z, yaw, pitch, roll)
    T = np.eye(4)
    T[1, 3] = -(24.0 - A.BODY_Y)
    return M @ T


def at_feet(x, y, z, yaw=0.0):
    """The matrix of a gull standing with its feet at (x, y, z)."""
    return root(x, y - A.BODY_Y, z, yaw)


def state(**kw):
    return A.GullState(**kw)


def gull(model, st, M):
    return dict(model=model, pose=A.pose(st), root=M)


class Track:
    """A number that goes through given (time, value) keys, eased between them."""

    def __init__(self, keys):
        self.keys = keys

    def __call__(self, t):
        k = self.keys
        if t <= k[0][0]:
            return k[0][1]
        for (t0, v0), (t1, v1) in zip(k, k[1:]):
            if t <= t1:
                return lerp(v0, v1, ease((t - t0) / (t1 - t0)))
        return k[-1][1]


def integrate(rate, t_end, dt=0.1):
    """A function t -> the integral of rate(t) from 0 (the wingbeat's phase for a rate that changes; tabulated and interpolated)."""
    ts = np.arange(0.0, t_end + 2 * dt, dt)
    vals = np.array([rate(t) for t in ts])
    cum = np.concatenate([[0.0], np.cumsum(0.5 * (vals[1:] + vals[:-1]) * dt)])
    return lambda t: float(np.interp(t, ts, cum))


# ================================================================== the stage: the three birds and the things they stand on

def _hash3(x, y, z, seed):
    return ship.hash01(np.floor(x).astype(np.int64), np.floor(y).astype(np.int64), np.floor(z).astype(np.int64), seed)


def _noise(P, cell, seed):
    return ship.fbm(P, cell, seed, 3)


def paint_stone(f):
    P = f.W
    n = _noise(P, 6.0, 3)
    m = _noise(P, 2.2, 5)
    t = 0.30 + 0.45 * n + 0.2 * (m - 0.5)
    col = ship.ramp([(52, 52, 56), (84, 82, 82), (116, 112, 106), (148, 142, 132), (178, 172, 158)], t)
    # lichen: pale green-grey and orange specks on the upper faces; whitewash (gulls) on the top
    up = (f.face == 'top')
    lich = ship.sstep(0.62, 0.74, _noise(P, 3.0, 9)) * (0.35 + 0.65 * up)
    col = ship.mixc(col, np.array([150, 160, 118.0]), 0.55 * lich)
    patch = ship.sstep(0.70, 0.76, _noise(P, 5.0, 21)) * up
    spk = (_hash3(P[..., 0] * 2.0, P[..., 1] * 2.0, P[..., 2] * 2.0, 4) > 0.55) & (patch > 0.5)
    col = np.where(spk[..., None], np.array([172, 134, 72.0]), col)
    wash = ship.sstep(0.66, 0.74, _noise(P, 4.0, 13)) * up
    col = ship.mixc(col, np.array([226, 226, 220.0]), 0.8 * wash)
    # darker and green at the waterline
    low = ship.sstep(-4.0, 6.0, P[..., 1])
    col = ship.mixc(col, np.array([40, 66, 52.0]), 0.55 * low)
    return K.Out(col)


def paint_wood(f):
    P = f.W
    grain = ship.fbm2(P[..., 1], P[..., 0] + P[..., 2], 14.0, 1.2, 7, 3)
    t = 0.30 + 0.42 * grain
    col = ship.ramp(ship.RAMP['oakdry'], t)
    # weathered grey at the top, green slime and barnacles near the water
    top = ship.sstep(-30.0, -42.0, P[..., 1])
    col = ship.mixc(col, np.array([156, 150, 138.0]), 0.55 * top)
    low = ship.sstep(-6.0, 8.0, P[..., 1])
    col = ship.mixc(col, np.array([46, 74, 52.0]), 0.6 * low)
    gap = (np.mod(np.floor((P[..., 1] + 100.0) * 2.0), 13) == 0)
    col = np.where(gap[..., None] & (f.face != 'top'), col * 0.82, col)
    return K.Out(col)


def paint_rope(f):
    P = f.W
    s = np.mod(np.floor((P[..., 0] + P[..., 2] + P[..., 1] * 2.0) * 2.0), 4)
    col = ship.ramp([(96, 78, 52), (140, 118, 80), (176, 152, 108)], (s / 3.0))
    return K.Out(col)


def props(texs_shape=(256, 256)):
    """{name: (scene, texture)} of the things the previews stand a gull on: a rock with a flat top at y = -10, a long ledge at y = -10, and a post
    whose cap is at y = -40. Flat cubes, painted by place."""
    out = {}
    K.REG.clear()
    cu = K.Cu

    def make(name, cubes, mats):
        parts = [K.part(c.name, (0, 0, 0), (0, 0, 0), [c]) for c in cubes]
        model = K.Model(name, texs_shape[0], texs_shape[1], parts)
        K.pack_atlas(model)
        sc = K.SScene(model, ())
        tex, _ = K.paint_model(sc, mats, {}, model.tex_w, model.tex_h)
        return sc, tex
    mats = {'stone': paint_stone, 'wood': paint_wood, 'rope': paint_rope}
    out['rock'] = make('rock', [cu((-34, -2, -22), (68, 16, 44), 'stone', 'rk_base'), cu((-26, -7, -17), (50, 5, 34), 'stone', 'rk_mid'),
                                cu((-18, -10, -11), (34, 3, 22), 'stone', 'rk_top'), cu((30, -5, 6), (14, 9, 12), 'stone', 'rk_side')], mats)
    K.REG.clear()
    out['ledge'] = make('ledge', [cu((-40, -2, -12), (80, 16, 26), 'stone', 'lg_base'), cu((-37, -6, -9), (74, 4, 20), 'stone', 'lg_mid'),
                                  cu((-34, -10, -7), (68, 4, 15), 'stone', 'lg_top')], mats)
    K.REG.clear()
    out['post'] = make('post', [cu((-3, -38, -3), (6, 52, 6), 'wood', 'ps_main'), cu((-3.5, -40, -3.5), (7, 2, 7), 'wood', 'ps_cap'),
                                cu((-3.5, -30, -3.5), (7, 3, 7), 'rope', 'ps_rope'), cu((-3.5, -26, -3.5), (7, 2, 7), 'rope', 'ps_rope2')], mats)
    return out


class Stage:
    """The three birds, the props and the sea, loaded once. [texs] is [(name, texture)] from the painter, else read from [assets]."""

    def __init__(self, assets, texs=None, say=print):
        import island_gull as IG
        t0 = time.time()
        model, scene = IG.built()
        self.scene = scene
        if texs is None:
            base = os.path.join(assets, 'textures', 'entity', 'island')
            texs = [(n, np.asarray(Image.open(os.path.join(base, n + '.png')).convert('RGBA'))) for n in PT.NAMES]
        self.models = {}
        for n, tex in texs:
            self.models[n] = SP.Model(n, scene, tex, None)
        for n, (sc, tex) in props().items():
            self.models[n] = SP.Model(n, sc, tex, None)
        self.seas = {}
        say(f'  stage ready in {time.time() - t0:.0f} s')

    def sea(self, cam_, sky, size, plain, sea_t):
        key = (cam_.yaw, cam_.elev, cam_.scale, tuple(size), tuple(np.round(cam_.center, 2)), sky, plain, sea_t)
        if key not in self.seas:
            self.seas[key] = K.sea_of(cam_, sky, size, SS, sea_t, plain)
        return self.seas[key]


VARIANT = ['gull', 'gull_blackback', 'gull_young']


def spec(cam_, actors, sky='day', sea_t=0.0, plain=False, spots=(), fx=(), ring_t=None, bloom=0.0):
    return dict(cam=cam_, sky=sky, actors=list(actors), sea_t=sea_t, plain=plain, spots=list(spots), fx=list(fx), ring_t=ring_t, bloom=bloom)


def prop(name, x=0.0, y=0.0, z=0.0, yaw=0.0):
    M = K.place(x, y, z, yaw)
    return dict(model=name, pose=None, root=M)


# ================================================================== the sequences

def draw_on_sky(stage, spec_, top=(92, 140, 196), bottom=(214, 228, 240)):
    """One picture of [spec_] (its actors only) over a plain sky gradient (no sea): (uint8 picture, mask)."""
    c = spec_['cam']
    W, H = c['size']
    cam_ = ship.Camera(c['yaw'], c['elev'], c['scale'] * SS, (W * SS, H * SS), c['center'])
    light = ship.make_light(ship.SKIES[spec_.get('sky', 'day')])
    layers = [K.render_layer(K.Actor(stage.models[a['model']].scene, stage.models[a['model']].tex, None, a.get('pose'), a.get('root')), cam_, light)
              for a in spec_['actors']]
    layer = K.merge(layers)
    u = np.linspace(0.0, 1.0, H * SS)[:, None, None]
    xx = np.linspace(-1.0, 1.0, W * SS)[None, :, None]
    bg = np.array(top, float) * (1.0 - u) + np.array(bottom, float) * u
    bg = bg * np.ones((1, W * SS, 1)) + 10.0 * np.exp(-(xx ** 2 + (u - 0.55) ** 2 * 4.0) * 1.5)
    out = np.where(layer.hit[..., None], layer.img, bg)
    im = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).resize((W, H), Image.LANCZOS)
    return np.asarray(im)


def seq_variants(stage):
    """Not a sequence: the three birds side by side, standing on a ledge (above) and gliding (below). Returns the finished pictures."""
    sky = 'day'
    size = (660, 300)
    names = VARIANT
    # row 1: standing on the ledge's edge, three different moments of idling
    xs = (-15.5, 0.0, 15.5)
    stands = [dict(time=312.0, headYaw=-30.0, headPitch=6.0), dict(time=40.0, headYaw=26.0, headPitch=4.0), dict(time=96.0, headYaw=-8.0, headPitch=-6.0)]
    sc = 15.0
    c = cam(14.0, horizon_elev(-16.0, sc, size[1], 0.30), sc, (0, -16.0, 0), size)
    acts = [prop('ledge')]
    for nm, x, kw in zip(names, xs, stands):
        acts.append(gull(nm, state(**kw), at_feet(x, -10.0, 0.0, K.facing(0.62, -0.78))))
    img1, _m = SP.draw(stage, spec(c, acts, sky, plain=True))
    # row 2: gliding, seen from above and ahead
    sc = 10.5
    size2 = (660, 260)
    c2 = cam(-30.0, 24.0, sc, (0, -41.0, 0), size2)
    acts = []
    for k, (nm, x, y, z) in enumerate(zip(names, (-21.0, 0.0, 21.0), (-39.0, -43.0, -39.0), (3.0, -4.0, 3.0))):
        acts.append(gull(nm, state(time=150.0 + 20 * k, flying=1.0, spread=1.0, bank=0.25 * (k - 1)), root(x, y, z, 150.0 + 16 * (k - 1), -3.0, -6.0 * (k - 1))))
    img2 = draw_on_sky(stage, spec(c2, acts, sky))
    return [img1, img2]


# ---- the soar: banked circling on still wings, with a flap or two

def seq_soar(stage, n=90):
    """A gull circling right on still wings over the sea, its circle a full turn in the loop, banked 24 degrees (`bank` 0.85: the renderer rolls
    the bird, the model sweeps the outer wing up a little and counter-rolls the head), a glide at 1.7 px a tick; two beats in the middle of the loop
    (`flap` up, a few beats of `flapPhase`, down) lift it a little. Seen from a low camera, the horizon behind it."""
    period = n * TPF
    R, alt0 = 27.0, -46.0
    sc = 7.0
    size = (620, 340)
    c = cam(24.0, horizon_elev(-46.0, sc, size[1], 0.66), sc, (0.0, -46.0, 0.0), size)
    flap_env = Track([(0.0, 0.0), (0.40 * period, 0.0), (0.46 * period, 1.0), (0.60 * period, 1.0), (0.66 * period, 0.0), (period, 0.0)])
    phase = integrate(lambda t: 0.55 + 0.25 * flap_env(t), period + 4.0)
    climb = integrate(lambda t: -0.16 * flap_env(t) + 0.0, period + 4.0)
    drift = climb(period)

    def pos(t):
        th = -2.0 * math.pi * t / period
        return np.array([R * math.cos(th), alt0 + climb(t) - drift * t / period, R * math.sin(th)])
    specs = []
    for i in range(n):
        t = i * TPF
        p = pos(t)
        q = pos(t + 0.5)
        v = (q - p) / 0.5
        yaw = K.facing(v[0], v[2])
        pitch = math.degrees(math.atan2(-v[1], math.hypot(v[0], v[2]))) - 3.0
        fe = flap_env(t)
        st = state(time=200.0 + t, flying=1.0, spread=1.0, flap=fe, flapPhase=phase(t), bank=0.85)
        specs.append(spec(c, [gull('gull', st, root(p[0], p[1], p[2], yaw, pitch, -24.0))], 'day', sea_t=t, plain=False))
    return specs


# ---- the flight: a gull on a rock springs into the air and climbs away, wings beating

def seq_flight(stage, n=96):
    """TAKEOFF from the rock: it stands (idle stirrings), the action starts at tick 12, the feet leave the rock at tick 20 (action tick 8) and the
    entity starts to rise: the path climbs at 52 degrees, flattening to 12, at a speed that grows from 0.4 to 2.3 px a tick; the renderer pitches
    the bird to 0.35 of its path. `flying`, `spread` and `flap` become 1 by tick 14 of the action, as the entity would have them by the time the
    script lets go. The camera follows it."""
    T0 = 12.0
    lift = T0 + A.TAKEOFF_LIFT
    rate = lambda t: 0.62 + 0.0 * t
    phase = integrate(rate, n * TPF + 4.0)
    ts = np.arange(0.0, n * TPF + 4.0, 0.25)
    xs, ys = [0.0], [0.0]
    for k in range(1, len(ts)):
        tau = ts[k] - lift
        if tau <= 0.0:
            xs.append(0.0)
            ys.append(0.0)
            continue
        sp = 0.4 + 1.9 * ease(tau / 26.0)
        gam = math.radians(lerp(52.0, 12.0, ease(tau / 60.0)))
        xs.append(xs[-1] + sp * math.cos(gam) * 0.25)
        ys.append(ys[-1] - sp * math.sin(gam) * 0.25)
    X, Y = (lambda t: float(np.interp(t, ts, xs))), (lambda t: float(np.interp(t, ts, ys)))
    top = -10.0
    sc = 9.0
    specs = []
    cx, cyy = 2.0, -17.0
    for i in range(n):
        t = i * TPF
        x, y = X(t), top + Y(t)
        if i:
            k = 0.07 if t < lift + 6.0 else 0.15
            cx += (x + 6.0 - cx) * k
            cyy += (y - 5.0 - cyy) * k * 0.85
        cc = cam(-48.0, horizon_elev(cyy, sc, SIZE[1], lerp(0.50, 0.74, ease(t / (n * TPF)))), sc, (cx, cyy, 0.0))
        tau = t - T0
        if t < T0:
            st = state(time=60.0 + t)
        elif tau <= A.ACTION_TICKS[A.TAKEOFF]:
            up = ease((tau - 8.0) / 6.0)
            st = state(time=60.0 + t, action=A.TAKEOFF, actionTime=tau, flying=up, spread=up, flap=up, flapPhase=phase(t))
        else:
            st = state(time=60.0 + t, flying=1.0, spread=1.0, flap=1.0, flapPhase=phase(t))
        v = np.array([X(t + 0.5) - x, (top + Y(t + 0.5)) - y])
        gam = math.degrees(math.atan2(-v[1], max(v[0], 1e-6))) if t > lift else 0.0
        M = root(x, y - A.BODY_Y, 0.0, K.facing(1.0, 0.0), 0.35 * gam, 0.0)
        specs.append(spec(cc, [prop('rock'), gull('gull', st, M)], 'day', sea_t=t, plain=False))
    return specs


# ---- the hover: hanging on the wind

def seq_hover(stage, n=84):
    """A gull hanging on the wind over the sea (the entity: `hover` 1, `flap` 0.35, a quick beat of 0.8 rad a tick, the bird drifting a little and
    rocking: its position moves 2 px about a point, the renderer pitches it 14 degrees nose up and sways it); it looks down at the water, its head
    sweeping a little (`headYaw`, `headPitch`)."""
    sc = 11.0
    c = cam(20.0, horizon_elev(-38.0, sc, SIZE[1], 0.80), sc, (0.0, -38.0, 0.0))
    specs = []
    phase = integrate(lambda t: 0.80, n * TPF + 4.0)
    for i in range(n):
        t = i * TPF
        x = 2.2 * math.sin(0.045 * t)
        y = -36.0 + 1.5 * math.sin(0.075 * t) + 0.6 * math.sin(0.19 * t + 1.0)
        z = 1.0 * math.sin(0.03 * t + 2.0)
        bank = 0.18 * math.sin(0.05 * t + 0.7)
        st = state(time=300.0 + t, flying=1.0, spread=1.0, flap=0.35, flapPhase=phase(t), hover=1.0, bank=bank,
                   headYaw=14.0 * math.sin(0.06 * t), headPitch=-16.0 + 5.0 * math.sin(0.09 * t))
        pitch = 14.0 + 3.0 * math.sin(0.075 * t + 0.4)
        specs.append(spec(c, [gull('gull', st, root(x, y, z, 20.0 + 38.0 + 6.0 * math.sin(0.04 * t), pitch, -bank * 10.0))], 'day', sea_t=t, plain=False))
    return specs


# ---- the landing: flaring onto a post

def seq_land(stage, n=42):
    """LAND onto the post: a glide in from the left at 2.2 px a tick, sinking 1 px a tick; the action starts 9 ticks in; across it the bird slows
    to a stop (x eases out over 17 ticks) and settles at the post's top at tick 17 of the action (its feet on the post: the body's middle BODY_Y over
    the cap at y = -40, exactly where the model puts the soles at the touchdown); `flying`, `spread` and `flap` are down to 0 by tick 22, as the
    entity would have them when the script lets go. The renderer pitches it to 0.6 of its path while it sinks and leaves it to the model in the flare."""
    top = -40.0
    P = 9.0
    D = 2.2 * 17.0 / 2.0
    H0 = 1.0 * 17.0 / 2.0
    rate = lambda t: 0.50
    phase = integrate(rate, n * TPF + 4.0)
    sc = 8.5
    c = cam(-42.0, horizon_elev(top - 6.0, sc, SIZE[1], 0.70), sc, (-8.0, top - 6.0, 0.0))
    specs = []
    for i in range(n):
        t = i * TPF
        tau = t - P
        if tau < 0.0:
            x = -D - 2.2 * (-tau)
            h = H0 + 1.0 * (-tau)
            pitch = -0.6 * math.degrees(math.atan2(1.0, 2.2))
            st = state(time=500.0 + t, flying=1.0, spread=1.0, flap=0.0 + 0.0, flapPhase=phase(t), bank=0.0)
        else:
            u = clamp(tau / A.LAND_TOUCH)
            x = -D * (1.0 - u) ** 2
            h = H0 * (1.0 - u) ** 2
            pitch = -0.6 * math.degrees(math.atan2(1.0, 2.2)) * (1.0 - ease(tau / 8.0))
            if tau > A.LAND_TOUCH:
                x, h, pitch = 0.0, 0.0, 0.0
            fl = 1.0 - ease((tau - 12.0) / 6.0)
            if tau <= A.ACTION_TICKS[A.LAND]:
                st = state(time=500.0 + t, action=A.LAND, actionTime=tau, flying=1.0 - ease((tau - 14.0) / 4.0), spread=1.0 - ease((tau - 18.0) / 6.0),
                           flap=0.0 + 0.0 * fl, flapPhase=phase(t))
            else:
                st = state(time=500.0 + t)
        specs.append(spec(c, [prop('post', 0.0, 0.0, 0.0), gull('gull', st, root(x, top - A.BODY_Y - h, 0.0, K.facing(1.0, 0.0), pitch, 0.0))], 'day', sea_t=t,
                          plain=False))
    return specs


# ---- idling on the rock: preen, the long call, a peck at a crumb, a ruffle

def heading_of(yaw):
    """The horizontal direction (dx, dz) a bird turned [yaw] degrees (see `K.facing`) faces."""
    return -math.sin(math.radians(yaw)), -math.cos(math.radians(yaw))


def seq_idle(stage, n=164):
    """A gull on the rock with nothing to do: it stands and looks about (the model's own stirrings), then, one after another, PREENs (70 ticks),
    makes the long CALL (64), PECKs a crumb on the rock (22) and RUFFLEs (30), a pause between. The entity would pick the actions; `time` runs on
    so the idle looks and blinks go on in the gaps. The camera sways about the bird."""
    plan = [(A.PREEN, 14.0), (A.CALL, 100.0), (A.PECK, 180.0), (A.RUFFLE, 212.0)]
    top = -10.0
    sc = 12.0
    specs = []
    yaw_b = 40.0
    hx, hz = heading_of(yaw_b)
    for i in range(n):
        t = i * TPF
        kw = dict(time=700.0 + t)
        for a, t0 in plan:
            if t0 <= t < t0 + A.ACTION_TICKS[a]:
                kw.update(action=a, actionTime=t - t0)
        theta = 4.0 + 16.0 * math.sin(2.0 * math.pi * t / (n * TPF))
        c = cam(theta, horizon_elev(top - 6.0, sc, SIZE[1], 0.50), sc, (0.0, top - 6.0, 0.0))
        acts = [prop('rock'), gull('gull', state(**kw), at_feet(0.0, top, 0.0, yaw_b + theta))]
        fx = []
        ct = 180.0 + 15.0
        if 150.0 <= t < ct:
            fx.append((hx * 4.7, top - 0.5, hz * 4.7, 0.8, (236, 218, 168), 1.0, 0.35))
            fx.append((hx * 4.7 + 0.5, top - 0.4, hz * 4.7 - 0.4, 0.5, (210, 190, 140), 1.0, 0.35))
        specs.append(spec(c, acts, 'day', sea_t=t, plain=True, fx=fx))
    return specs


# ---- the threat: two gulls square up

def seq_threat(stage, n=60):
    """Two gulls on the rock facing each other, the big black-backed one (left) THREATs (44 ticks, from tick 6): wings half open and held up, neck
    stretched, three jabs of the beak; the young one (right) answers with a threat of its own from tick 18, then gives way: after tick 56 it turns
    (over 8 ticks) and walks off to the right (`walk` 1, `walkPhase` running at 0.45 rad a tick, 1.1 px a tick), and the other lowers its head
    and relaxes."""
    top = -10.0
    sc = 10.0
    specs = []
    ts = 6.0
    ty = 18.0
    walk0 = 56.0
    phase = lambda t: 0.45 * max(0.0, t - walk0)
    for i in range(n):
        t = i * TPF
        c = cam(10.0, 15.0, sc, (0.0, top - 6.0, 0.0))
        ka = dict(time=900.0 + t)
        if ts <= t < ts + A.ACTION_TICKS[A.THREAT]:
            ka.update(action=A.THREAT, actionTime=t - ts, headYaw=0.0)
        kb = dict(time=950.0 + t)
        if ty <= t < ty + A.ACTION_TICKS[A.THREAT]:
            kb.update(action=A.THREAT, actionTime=t - ty)
        turn = ease((t - walk0) / 8.0)
        yb = 90.0 + 180.0 * turn
        xb = 9.0
        if t > walk0:
            kb.update(walk=ease((t - walk0) / 3.0), walkPhase=phase(t))
            xb += 1.1 * max(0.0, t - walk0 - 6.0) * (1.0 if turn >= 1.0 else 0.0)
        acts = [prop('rock'), gull('gull_blackback', state(**ka), at_feet(-9.0, top, 0.0, -90.0)), gull('gull_young', state(**kb), at_feet(xb, top, 0.0, yb))]
        specs.append(spec(c, acts, 'day', sea_t=t, plain=False))
    return specs


# ---- floating

def seq_float(stage, n=88):
    """A gull riding the swell (`floating` 1): the entity bobs it 1.2 px and tips it a few degrees with the sea (the model adds its own small bob and
    sinks the body 3 px: its belly in the water), a little foam ring about it; it looks about, dips its head to the water (PECK at tick 40) and
    shakes its feathers (RUFFLE at tick 92)."""
    sc = 16.0
    specs = []
    for i in range(n):
        t = i * TPF
        kw = dict(time=1100.0 + t, floating=1.0)
        for a, t0 in ((A.PECK, 40.0), (A.RUFFLE, 92.0)):
            if t0 <= t < t0 + A.ACTION_TICKS[a]:
                kw.update(action=a, actionTime=t - t0)
        bob = 0.9 * math.sin(0.13 * t) + 0.4 * math.sin(0.31 * t + 1.0)
        tip = 3.5 * math.sin(0.13 * t + 1.2)
        rol = 3.0 * math.sin(0.10 * t)
        yaw = 38.0 + 6.0 * math.sin(0.03 * t)
        c = cam(8.0, 17.0, sc, (0.0, -4.0, 0.0))
        M = root(0.0, bob - A.BODY_Y, 0.0, yaw, tip, rol)
        events = [(0.0, 0.0, 44.0, 0.9, 5.0, 0.35, 30.0), (0.0, 0.0, 64.0, 0.6, 5.0, 0.30, 30.0)]
        spots = [(0.0, 0.0, 5.2, 0.5, 0.25)] + SP.rings(events, t)
        specs.append(spec(c, [gull('gull', state(**kw), M)], 'day', sea_t=t, plain=False, spots=spots, ring_t=t))
    return specs


# ---- the dive

def spline(keys):
    """A smooth path through [keys] ((tick, x, y) triples): a Catmull-Rom spline over the ticks, returned as a function tick -> (x, y)."""
    ks = np.array(keys, float)

    def at(t):
        t = min(max(t, ks[0, 0]), ks[-1, 0])
        i = int(np.searchsorted(ks[:, 0], t, side='right') - 1)
        i = min(max(i, 0), len(ks) - 2)
        p0, p1 = ks[i], ks[i + 1]
        pm = ks[i - 1] if i > 0 else p0 - (p1 - p0)
        pp = ks[i + 2] if i + 2 < len(ks) else p1 + (p1 - p0)
        h = p1[0] - p0[0]
        u = (t - p0[0]) / h
        m0 = (p1[1:] - pm[1:]) / (p1[0] - pm[0]) * h
        m1 = (pp[1:] - p0[1:]) / (pp[0] - p0[0]) * h
        h00, h10, h01, h11 = 2 * u ** 3 - 3 * u ** 2 + 1, u ** 3 - 2 * u ** 2 + u, -2 * u ** 3 + 3 * u ** 2, u ** 3 - u ** 2
        r = h00 * p0[1:] + h10 * m0 + h01 * p1[1:] + h11 * m1
        return float(r[0]), float(r[1])
    return at


def seq_dive(stage, n=34):
    """DIVE: a gull gliding in, head down, folds its wings and plunges at 60 degrees: the beak enters the water at tick 20 (spray and rings), it
    goes under, takes a fish at tick 27, turns up and breaks the surface at tick 34, opening its wings and beating them as it climbs out with the fish
    crosswise in its beak. The path (through keys, a smooth spline) and the bird's pitch along it are the entity's and the renderer's; the action
    starts at tick 4 of the picture. Under the water the model is tinted and darkened by the stage."""
    A0 = 4.0
    path = spline([(-6.0, -50.0, -42.0), (0.0, -41.0, -42.0), (4.0, -34.0, -40.5), (8.0, -26.0, -37.0), (12.0, -17.5, -29.5), (16.0, -8.5, -19.0),
                   (20.0, 0.0, -7.4), (22.0, 3.5, 0.0), (24.0, 7.5, 5.5), (27.0, 11.5, 9.0), (30.0, 14.5, 9.5), (32.0, 16.5, 6.5), (34.0, 19.0, 0.5),
                   (36.0, 22.0, -4.5), (40.0, 28.0, -10.5), (46.0, 38.0, -16.5), (56.0, 56.0, -23.0), (70.0, 80.0, -30.0)])

    def pitch_at(tau):
        a, b = path(tau - 0.6), path(tau + 0.6)
        return math.degrees(math.atan2(-(b[1] - a[1]), max(b[0] - a[0], 1e-6)))
    phase = integrate(lambda t: 0.62, n * TPF + 4.0)
    sc = 8.4
    size = (600, 540)
    rng = np.random.default_rng(7)
    xh = path(20.0)[0] + 3.6
    spray = SP.burst(rng, 28, (xh, 0.0, 0.0), 20.0 + A0, speed=(1.0, 3.2), cone=0.75, axis=(0.15, -1.0, 0.0), life=(14.0, 30.0), size=(0.35, 0.9),
                     colour=(232, 242, 250), alpha=0.95, g=0.20)
    xs2 = path(34.0)[0] + 3.0
    spray2 = SP.burst(rng, 14, (xs2, 0.0, 0.0), 34.0 + A0, speed=(0.8, 2.2), cone=0.9, axis=(0.1, -1.0, 0.0), life=(10.0, 22.0), size=(0.3, 0.7),
                      colour=(232, 242, 250), alpha=0.95, g=0.20)
    events = [(xh, 0.0, 20.0 + A0, 1.7, 3.0, 0.55, 40.0), (xh, 0.0, 24.0 + A0, 1.2, 3.0, 0.45, 36.0), (xs2, 0.0, 34.0 + A0, 1.4, 2.5, 0.40, 30.0)]
    specs = []
    for i in range(n):
        t = i * TPF
        tau = t - A0                          # ticks into the action: the picture starts A0 ticks before it
        x, y = path(tau)
        pitch = pitch_at(tau)
        fish = tau >= 28.0
        if tau < 0.0:
            st = state(time=1500.0 + t, flying=1.0, spread=1.0, headPitch=-26.0, headYaw=0.0, bank=0.0)
        elif tau <= A.ACTION_TICKS[A.DIVE]:
            fl = ease((tau - 28.0) / 9.0)
            st = state(time=1500.0 + t, action=A.DIVE, actionTime=tau, flying=1.0, spread=1.0, flap=fl, flapPhase=phase(t), fish=fish)
        else:
            st = state(time=1500.0 + t, flying=1.0, spread=1.0, flap=1.0, flapPhase=phase(t), fish=True)
        pan = -10.0 + 28.0 * ease((tau - 30.0) / 20.0)
        cc = cam(8.0, horizon_elev(-14.0, sc, size[1], 0.14), sc, (pan, -14.0, 0.0), size)
        fx = SP.fly(spray, t) + SP.fly(spray2, t)
        spots = SP.rings(events, t)
        specs.append(spec(cc, [gull('gull', st, root(x, y, 0.0, K.facing(1.0, 0.0), pitch, 0.0))], 'day', sea_t=t, plain=False, spots=spots, fx=fx, ring_t=t))
    return specs


SEQUENCES = {'variants': seq_variants, 'soar': seq_soar, 'flight': seq_flight, 'hover': seq_hover, 'land': seq_land, 'idle': seq_idle, 'threat': seq_threat,
             'float': seq_float, 'dive': seq_dive}


def render_specs(stage, specs, workers=SP.WORKERS):
    return SP.render(stage, specs, workers=workers)


def write_all(assets, prev, only=None, texs=None, say=print):
    """Draws the sequences of [only] (names from NAMES; all by default) into [prev]: GIFs `gull_<name>.gif` and the still `gull_variants.png`."""
    os.makedirs(prev, exist_ok=True)
    names = [n for n in NAMES if only is None or n in only]
    t0 = time.time()
    stage = Stage(assets, texs, say)
    for name in names:
        t1 = time.time()
        specs = SEQUENCES[name](stage)
        if name == 'variants':
            frames, masks = specs, None
        else:
            frames, masks = render_specs(stage, specs)
        out = os.path.join(prev, GOLD[name])
        if name == 'variants':
            SP.save_png(out + '.png', np.concatenate(frames, 0))
            say(f'    {GOLD[name]}.png')
            specs = frames
        else:
            SP.save_gif(out + '.gif', frames, masks, limit=GIF_LIMIT, say=say)
        say(f'  {name}: {len(specs)} pictures in {time.time() - t1:.0f} s')
        stage.seas.clear()                    # a sea per picture (the waves move): do not keep them all
        del frames, masks, specs
    say(f'  previews done in {time.time() - t0:.0f} s')


if __name__ == '__main__':
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    write_all(sys.argv[1], sys.argv[2], sys.argv[3:] or None)
