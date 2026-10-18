"""
The draught horse's clips, on draught_horse.py's rig, played by the rig animator as the Dusk Rider's are
(client/combat/RigAnimator.kt; dusk_rider_anims.py's gaits are where these began). Its hooves are planted: each clip
works out where every hoof stands on the ground, or where it swings through the air, and how it's turned, and solves
the leg, shin and hoof to put it there, so nothing slides. A hoof lands heel first, stands flat while the leg swings
over it, rolls onto its toe as it leaves and flips back as the leg folds; the body rides as high as the legs carrying
it allow, which is where its bob comes from. Run:

    python3 -B art/scripts/draught_horse_anims.py src/main/resources/assets/guildmark [preview-dir] [names]

Played by distance (one loop each DraughtHorseMesh.WALK_STRIDE or BOLT_STRIDE blocks, as the Dusk Rider's gaits):
'walk', a four-beat walk, the head nodding onto each forefoot, the bells swinging; 'strain', the same walk with the
same footfalls, leaning into the collar, head low and ears back (lay it over 'walk' at the walk's phase, weighted by
how steep the climb is, or for a few steps as it starts the wagon); 'bolt', a runaway's gallop, cut loose. Played by
time: 'idle' (loops: breathing, ears, the tail at the flies, a head shake and a snort, a stamp, a hind leg rested and
taken up again), 'graze' and 'nosebag' (loop), 'rear', 'hit' and 'collapse' (once; collapse ends lying dead on its
side), and 'lie' (loops, still: collapse's last pose). DraughtHorseMesh.MOMENTS has the ticks to sound.

All keys are 'set': a part turns from its rest pose by the clip and moves its joint by the clip's offset (px).
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image, ImageDraw
from animkit import Clip, qeuler, qmul, qslerp, qmat
import draught_horse as dh

GROUP = 'draught_horse'
MODEL, HOOKS, _USED = dh.build()
IDX = {p.name: p for p in MODEL.all_parts()}
BODY0 = np.array(IDX['body'].pivot, float)
G = dh.GROUND
HALF = dh.HOOF_HALF

WALK_STRIDE = 1.8       # blocks a loop of the walk covers (DraughtHorseMesh.WALK_STRIDE)
BOLT_STRIDE = 4.2       # and of the bolt
WALK_LEN = 32           # ticks in a loop (the game plays them by distance, so this is only how finely it's keyed)
BOLT_LEN = 16

MOMENTS = {}            # clip -> [(tick, what)]


def smooth(a):
    a = min(1.0, max(0.0, a))
    return a * a * (3 - 2 * a)


def wave(ph, k=1.0, off=0.0):
    return math.sin(2 * math.pi * (k * ph + off))


def rx(deg):
    return qmat(qeuler(x=deg))


def pitch_of(R):
    return math.atan2(-R[1, 2], R[2, 2])


def roll_of(R):
    return math.atan2(R[1, 0], R[0, 0])


# ================================================================== the legs: put a hoof where it should be

LEG = {}
for side, L in dh.LEGS.items():
    hip, k, f, s = (np.array(L[n], float) for n in ('pivot', 'knee', 'fetlock', 'sole'))
    A = k[1] * f[1] + k[2] * f[2]
    Bc = k[2] * f[1] - k[1] * f[2]
    kk, ff = float(k[1:] @ k[1:]), float(f[1:] @ f[1:])
    Rm = math.hypot(A, Bc)
    LEG[side] = dict(hip=hip, k=k, f=f, s=s, front=L['front'], Rm=Rm, phi=math.atan2(Bc, A), kk=kk, ff=ff,
                     reach=math.sqrt(kk + ff + 2 * Rm), rest=np.array([hip[0], G, hip[2] + k[2] + f[2] + s[2]]))


def fetlock_angles(side, Rb, Tb, target):
    """The leg's and shin's turns (degrees about x) putting the fetlock at [target] (model space), the body at Rb,
    Tb; and how far short it falls when it can't reach (px)."""
    L = LEG[side]
    d = Rb.T @ (np.asarray(target, float) - Tb) - L['hip']
    dy, dz = d[1], d[2]
    dd = dy * dy + dz * dz
    c = (dd - L['kk'] - L['ff']) / (2 * L['Rm'])
    short = math.sqrt(dd) - L['reach'] if c > 1.0 else 0.0
    c = max(-1.0, min(1.0, c))
    t2 = L['phi'] + math.acos(c) if L['front'] else L['phi'] - math.acos(c)
    k, f = L['k'], L['f']
    vy = k[1] + f[1] * math.cos(t2) - f[2] * math.sin(t2)
    vz = k[2] + f[1] * math.sin(t2) + f[2] * math.cos(t2)
    t1 = math.atan2(dz, dy) - math.atan2(vz, vy)
    return math.degrees(t1), math.degrees(t2), short


def plant(side, Rb, Tb, target, alpha=0.0, ground=True):
    """(leg, shin, hoof) turns in degrees putting the middle of the sole at [target] with the hoof tipped [alpha]
    degrees in the world (+ toe down, heel up); on the [ground], it stands on whichever edge it's tipped onto (that
    edge on the ground, not its middle). And the shortfall (px)."""
    tgt = np.array(target, float)
    if ground:
        tgt[1] -= HALF * abs(math.sin(math.radians(alpha))) + 2.6 * abs(math.sin(roll_of(Rb)))
    turn = alpha - math.degrees(pitch_of(Rb))          # the hoof's turn in the body's frame
    F = tgt - Rb @ rx(turn) @ LEG[side]['s']
    t1, t2, short = fetlock_angles(side, Rb, Tb, F)
    return (t1, t2, turn - t1 - t2), short


def legs_of(angles):
    out = {}
    for side, (t1, t2, t3) in angles.items():
        out[f'{side}_leg'] = qeuler(x=t1)
        out[f'{side}_shin'] = qeuler(x=t2)
        out[f'{side}_hoof'] = qeuler(x=t3)
    return out


# ================================================================== gaits


def foot(side, ph, stride, g):
    """Where a hoof is at phase [ph] of a gait: (target, alpha, on the ground). [g]: land (the phase it lands),
    duty (how much of the loop it stands), lift (px it rises), fold (px it's drawn back as it rises), flip (how far
    it turns back in the air, degrees), shift (px its stand is set back); heel (it lands this far toe up) and toe (it
    leaves this far heel up)."""
    L = LEG[side]
    D = stride * g['duty']
    centre = L['rest'][2] + g.get('shift', 0.0)
    heel, toe = g.get('heel', -10.0), g.get('toe', 30.0)
    u = (ph - g['land']) % 1.0
    if u < g['duty']:
        a = u / g['duty']
        alpha = heel * (1 - smooth(a / 0.16)) + toe * smooth((a - 0.6) / 0.4)
        z = centre - D / 2 + D * a
        # it turns about the edge it stands on: about the heel as it lands, about the toe as it leaves
        z += HALF * (1 - math.cos(math.radians(alpha))) * (1 if alpha < 0 else -1)
        return np.array([L['rest'][0], G, z]), alpha, True
    a = (u - g['duty']) / (1.0 - g['duty'])
    e = smooth(a)
    z0 = centre + D / 2 - HALF * (1 - math.cos(math.radians(toe)))
    z1 = centre - D / 2 + HALF * (1 - math.cos(math.radians(heel)))
    peak = g.get('peak', 0.42)
    hump = math.sin(math.pi * 0.5 * a / peak) if a < peak else math.cos(math.pi * 0.5 * (a - peak) / (1 - peak))
    raise_ = (1 - a) * HALF * math.sin(math.radians(abs(toe))) + a * HALF * math.sin(math.radians(abs(heel)))
    z = z0 + (z1 - z0) * e + g.get('fold', 0.0) * math.sin(math.pi * a) * (1 - a)
    flip = g.get('flip', 70.0)
    fpk = g.get('flip_at', 0.3)
    alpha = toe + (flip - toe) * smooth(a / fpk) if a < fpk else flip + (heel - flip) * smooth((a - fpk) / (1 - fpk))
    return np.array([L['rest'][0], G - g['lift'] * max(0.0, hump) - raise_, z]), alpha, False


def gait(name, length, stride_blocks, legs, body, rate_bob=0.9, max_drop=1.4):
    """A looping gait, every leg on its own footfall. [body](ph) -> (q_body, pos_body, {bone: q (from rest)}); the
    body then rides down as far as its standing legs need to reach the ground (at most [max_drop] px), smoothed."""
    c = Clip(name, length, blend_in=0, blend_out=0)
    stride = stride_blocks * 16.0
    n = int(length * 2)
    phases = [i / n for i in range(n)]
    need = []
    for ph in phases:
        qb, pb, _ = body(ph)
        Rb, Tb = qmat(qb), BODY0 + pb
        worst = 0.0
        for side, g in legs.items():
            tgt, alpha, on = foot(side, ph, stride, g)
            if on:
                worst = max(worst, plant(side, Rb, Tb, tgt, alpha)[1])
        need.append(min(max_drop, worst * rate_bob))
    need = np.array(need)
    for _ in range(4):      # smoothed round the loop
        need = 0.25 * np.roll(need, 1) + 0.5 * need + 0.25 * np.roll(need, -1)
    for i in range(n + 1):
        t = i / 2
        ph = phases[i % n]
        qb, pb, extra = body(ph)
        pb = pb + np.array([0.0, need[i % n], 0.0])
        Rb, Tb = qmat(qb), BODY0 + pb
        c.key('body', t, qb, pos=pb, ease='linear')
        ang = {}
        for side, g in legs.items():
            tgt, alpha, on = foot(side, ph, stride, g)
            ang[side] = plant(side, Rb, Tb, tgt, alpha, on)[0]
        for bone, q in legs_of(ang).items():
            c.key(bone, t, q, ease='linear')
        for bone, q in extra.items():
            c.key(bone, t, q, ease='linear')
    c.bob = float(need.max() - need.min())
    return c


# ---- the walk: hind left, fore left, hind right, fore right; each foot stands 0.6 of the loop
WALK_LEGS = {
    'hl': dict(land=0.00, duty=0.6, lift=2.4, fold=0.4, peak=0.45, flip=55.0, heel=-6.0, toe=26.0),
    'fl': dict(land=0.25, duty=0.6, lift=3.4, fold=1.6, peak=0.36, flip=85.0, heel=-10.0, toe=30.0),
    'hr': dict(land=0.50, duty=0.6, lift=2.4, fold=0.4, peak=0.45, flip=55.0, heel=-6.0, toe=26.0),
    'fr': dict(land=0.75, duty=0.6, lift=3.4, fold=1.6, peak=0.36, flip=85.0, heel=-10.0, toe=30.0),
}


def walk_body(ph, strain=0.0):
    """The walk's body: a roll toward the hind leg that's standing, a sway, the head nodding down onto each forefoot,
    the tail and mane swinging, the bells jingling, the ears about. [strain] (0..1) leans it into the collar."""
    nod = math.cos(2 * math.pi * (2 * ph - 0.62))          # down as each forefoot takes its weight
    roll = 1.3 * wave(ph, 1, 0.1)
    qb = qeuler(x=0.5 * nod - 2.6 * strain, y=1.0 * wave(ph, 1, 0.35), z=roll)
    pb = np.array([0.0, 0.9 * strain, -0.8 * strain])
    k = 1.0 + 0.9 * strain
    extra = {
        'neck': qeuler(x=2.6 * k * nod + 17.0 * strain, z=-0.8 * roll),
        'head': qeuler(x=-1.8 * k * nod - 9.0 * strain, y=1.5 * wave(ph, 1, 0.2)),
        'tail': qeuler(x=-4.0 * strain + 2.0 * wave(ph, 2, 0.3), y=-6.0 * wave(ph, 1, 0.25)),
        'tail_end': qeuler(x=-2.5 * wave(ph, 2, 0.15), y=-5.0 * wave(ph, 1, 0.05)),
        'mane': qeuler(z=2.0 * wave(ph, 2, 0.4)),
        'forelock': qeuler(x=3.0 * wave(ph, 2, 0.45)),
        'bells': qeuler(x=14.0 * wave(ph, 2, 0.1)),
    }
    if strain:
        extra['ear_r'] = qeuler(x=26.0 * strain + 3.0 * wave(ph, 1, 0.0), y=-10.0 * strain)
        extra['ear_l'] = qeuler(x=26.0 * strain + 3.0 * wave(ph, 1, 0.5), y=10.0 * strain)
    else:
        extra['ear_r'] = qeuler(x=4.0 * wave(ph, 1, 0.0), y=3.0 * wave(ph, 1, 0.3))
        extra['ear_l'] = qeuler(x=4.0 * wave(ph, 1, 0.5), y=-3.0 * wave(ph, 1, 0.8))
    return qb, pb, extra


walk = gait('walk', WALK_LEN, WALK_STRIDE, WALK_LEGS, walk_body)
STRAIN_LEGS = {k: dict(v, shift=(1.6 if k.startswith('h') else 1.0), lift=v['lift'] * 0.85, toe=v['toe'] + 8.0)
               for k, v in WALK_LEGS.items()}
strain = gait('strain', WALK_LEN, WALK_STRIDE, STRAIN_LEGS, lambda ph: walk_body(ph, 1.0))
MOMENTS['walk'] = sorted((round(g['land'] * WALK_LEN, 2), 'hoof') for g in WALK_LEGS.values())
MOMENTS['strain'] = list(MOMENTS['walk'])

# ---- the bolt: a heavy, flat-out gallop. Hind left, hind right, fore left, fore right, then all four gathered up
BOLT_LEGS = {
    'hl': dict(land=0.00, duty=0.30, lift=6.0, fold=2.5, peak=0.40, flip=70.0, heel=-8.0, toe=40.0),
    'hr': dict(land=0.10, duty=0.30, lift=6.0, fold=2.5, peak=0.40, flip=70.0, heel=-8.0, toe=40.0),
    'fl': dict(land=0.34, duty=0.28, lift=7.5, fold=5.0, peak=0.30, flip=100.0, heel=-14.0, toe=40.0),
    'fr': dict(land=0.44, duty=0.28, lift=7.5, fold=5.0, peak=0.30, flip=100.0, heel=-14.0, toe=40.0),
}


def bolt_body(ph):
    pitch = 6.0 * wave(ph, 1, -0.05)                    # nose up as the hind legs land, down onto the forelegs
    rise = 1.6 * max(0.0, wave(ph, 1, 0.55)) ** 1.5     # it flies after the last forefoot leaves
    qb = qeuler(x=pitch, z=1.5 * wave(ph, 1, 0.2))
    pb = np.array([0.0, -rise, 0.0])
    flap = lambda off, a: a * wave(ph, 2, off)
    extra = {
        'neck': qeuler(x=24.0 - 1.6 * pitch + 5.0 * wave(ph, 1, 0.3)),
        'head': qeuler(x=-14.0 + 3.0 * wave(ph, 1, 0.45)),
        'ear_r': qeuler(x=38.0, y=-12.0), 'ear_l': qeuler(x=38.0, y=12.0),
        'mane': qeuler(z=-14.0 + flap(0.1, 6.0), x=6.0 + flap(0.3, 4.0)),
        'forelock': qeuler(x=-30.0 + flap(0.2, 8.0)),
        'tail': qeuler(x=-58.0 + 8.0 * wave(ph, 1, 0.1), y=flap(0.0, 6.0)),
        'tail_end': qeuler(x=-26.0 + flap(0.25, 10.0), y=flap(0.4, 8.0)),
        'bells': qeuler(x=30.0 * wave(ph, 2, 0.1)),
        'trace_r_cut': qeuler(x=50.0 + flap(0.0, 18.0), z=-8.0 + flap(0.3, 6.0)),
        'trace_l_cut': qeuler(x=48.0 + flap(0.15, 18.0), z=8.0 + flap(0.45, 6.0)),
        'rein_r_cut': qeuler(x=60.0 + flap(0.1, 16.0)), 'rein_l_cut': qeuler(x=58.0 + flap(0.3, 16.0)),
    }
    return qb, pb, extra


bolt = gait('bolt', BOLT_LEN, BOLT_STRIDE, BOLT_LEGS, bolt_body, rate_bob=0.7, max_drop=2.0)
MOMENTS['bolt'] = sorted((round(g['land'] * BOLT_LEN, 2), 'hoof') for g in BOLT_LEGS.values())

# ================================================================== on the spot: every hoof planted unless lifted


def stand_angles(qb, pb, lifted=None):
    """Leg angles with every hoof flat at its rest place on the ground, the body at qb, pb; [lifted]: {side: (target,
    alpha, on the ground)} for hooves held elsewhere."""
    Rb, Tb = qmat(qb), BODY0 + pb
    out = {}
    for side in LEG:
        tgt, alpha, on = (lifted or {}).get(side, (LEG[side]['rest'], 0.0, True))
        out[side] = plant(side, Rb, Tb, tgt, alpha, on)[0]
    return out


def about(q, point):
    """The offset that turns a part by [q] about [point] (relative to its pivot) rather than about its pivot."""
    p = np.asarray(point, float)
    return p - qmat(q) @ p


class Track:
    """Values at ticks, eased between by smoothstep."""

    def __init__(self, *pts):
        self.pts = sorted(pts, key=lambda p: p[0])

    def __call__(self, t):
        p = self.pts
        if t <= p[0][0]:
            return np.asarray(p[0][1], float)
        for (t0, v0), (t1, v1) in zip(p, p[1:]):
            if t <= t1:
                a = smooth((t - t0) / (t1 - t0)) if t1 > t0 else 1.0
                return np.asarray(v0, float) + (np.asarray(v1, float) - np.asarray(v0, float)) * a
        return np.asarray(p[-1][1], float)


def baked(name, length, frame, blend=(0.0, 0.0), loop=False):
    """A clip keyed every half tick from [frame](t) -> {bone: q or (q, pos)}."""
    c = Clip(name, length, blend_in=blend[0], blend_out=blend[1])
    for i in range(int(length * 2) + 1):
        t = i / 2
        for bone, v in frame(t % length if loop else t).items():
            if isinstance(v, tuple):
                c.key(bone, t, v[0], pos=v[1], ease='linear')
            else:
                c.key(bone, t, v, ease='linear')
    return c


def lifted_hoof(side, up, forward=0.0, alpha=20.0):
    """A hoof raised [up] px off its rest place and set [forward] px, tipped [alpha]."""
    L = LEG[side]['rest']
    return np.array([L[0], G - up, L[2] - forward]), alpha, False


# ---- idle: 240 ticks
IDLE_LEN = 240


def idle_frame(t):
    a = 2 * math.pi * t / IDLE_LEN
    breath = math.sin(a * 5)                                  # five breaths a loop
    rest_r = float(Track((0, 0), (96, 0), (116, 1), (196, 1), (214, 0), (240, 0))(t))       # the right hind rested
    shake = float(Track((0, 0), (58, 0), (61, 1), (64, -1), (67, 0.8), (70, -0.6), (74, 0), (240, 0))(t))
    lift = float(Track((0, 0), (58, 0), (62, 1), (72, 1), (78, 0), (240, 0))(t))           # head up for the shake
    roll = 1.6 * rest_r + 0.4 * math.sin(a)
    qb = qeuler(x=0.25 * breath, z=roll)
    pb = np.array([-0.3 * rest_r, 0.18 * breath + 0.35 * rest_r, 0.0])
    lifted = {}
    if rest_r > 0.01:   # the rested leg: hip dropped, the hoof tipped up onto its toe a little forward
        L = LEG['hr']['rest']
        lifted['hr'] = (np.array([L[0], G, L[2] - 1.2 * rest_r]), 48.0 * rest_r, True)
    stamp = float(Track((0, 0), (150, 0), (154, 1), (157, 0.08), (159.5, 0.85), (162.5, 0), (240, 0))(t))
    if stamp > 0.01:
        lifted['fl'] = lifted_hoof('fl', 3.2 * stamp, -1.0 * stamp, 28.0 * stamp)
    out = {'body': (qb, pb)}
    out.update(legs_of(stand_angles(qb, pb, lifted)))
    look = float(Track((0, 0), (20, 0), (34, 1), (50, 1), (56, 0), (130, 0), (140, -0.7), (176, -0.7), (184, 0), (240, 0))(t))
    out['neck'] = qeuler(x=1.2 * breath - 6.0 * lift + 2.0 * rest_r, y=5.0 * look, z=4.0 * shake)
    out['head'] = qeuler(x=-5.0 * lift + 1.5 * rest_r, y=6.0 * look + 9.0 * shake, z=10.0 * shake)
    ear_r = Track((0, (0, 0)), (14, (0, 0)), (18, (30, -20)), (40, (30, -20)), (44, (0, 0)), (100, (0, 0)), (104, (-12, 30)),
                  (128, (-12, 30)), (132, (0, 0)), (186, (0, 0)), (189, (20, 0)), (199, (20, 0)), (202, (0, 0)), (240, (0, 0)))(t)
    ear_l = Track((0, (0, 0)), (40, (0, 0)), (44, (-10, -30)), (70, (-10, -30)), (74, (0, 0)), (150, (0, 0)), (153, (34, 18)),
                  (170, (34, 18)), (174, (0, 0)), (186, (0, 0)), (189, (20, 0)), (199, (20, 0)), (202, (0, 0)), (240, (0, 0)))(t)
    out['ear_r'] = qeuler(x=ear_r[0] + 14 * shake, y=ear_r[1])
    out['ear_l'] = qeuler(x=ear_l[0] + 14 * shake, y=ear_l[1])
    swish = float(Track((0, 0), (24, 0), (28, 1), (33, -1), (38, 0.7), (44, 0), (120, 0), (124, -1), (129, 1), (135, -0.5),
                        (140, 0), (218, 0), (221, 0.8), (226, -0.8), (231, 0), (240, 0))(t))
    late = float(Track((0, 0), (25, 0), (30, 1), (35, -1), (40, 0.7), (46, 0), (121, 0), (126, -1), (131, 1), (137, -0.5),
                       (142, 0), (219, 0), (223, 0.8), (228, -0.8), (233, 0), (240, 0))(t))
    out['tail'] = qeuler(x=-4.0 * abs(swish) + 1.2 * breath, y=26.0 * swish)
    out['tail_end'] = qeuler(y=-16.0 * late)
    out['mane'] = qeuler(z=7.0 * shake)
    out['forelock'] = qeuler(x=-8.0 * abs(shake), z=10 * shake)
    out['bells'] = qeuler(x=26.0 * shake + 6.0 * float(Track((0, 0), (154, 0), (156, 1), (158, -0.6), (161, 0.3), (164, 0), (240, 0))(t)))
    return out


idle = baked('idle', IDLE_LEN, idle_frame, loop=True)
MOMENTS['idle'] = [(60.0, 'snort'), (157.0, 'stamp'), (162.5, 'stamp')]

# ---- graze: head down cropping the grass, chewing; a hoof shifted, the tail at the flies. 120 ticks
GRAZE_LEN = 120
GRAZE_NECK, GRAZE_HEAD = 82.0, -42.0        # how far the neck and head turn from their rest to crop the grass


def graze_frame(t):
    a = 2 * math.pi * t / GRAZE_LEN
    tear = float(Track((0, 0), (10, 0), (12, 1), (15, 0), (40, 0), (42, 1), (45, 0), (78, 0), (80, 1), (83, 0), (120, 0))(t))
    chew = math.sin(a * 15) * (1 - tear)
    qb = qeuler(x=1.2, z=0.5 * math.sin(a))
    pb = np.array([0.0, 0.3, 0.0])
    shift = float(Track((0, 0), (56, 0), (60, 1), (64, 1), (68, 0), (120, 0))(t))
    lifted = {}
    if shift > 0.01:
        lifted['fr'] = lifted_hoof('fr', 2.4 * shift, 0.6 * shift, 24.0 * shift)
    out = {'body': (qb, pb)}
    out.update(legs_of(stand_angles(qb, pb, lifted)))
    out['neck'] = qeuler(x=GRAZE_NECK - 4.0 * tear + 0.6 * chew, y=3.0 * math.sin(a))
    out['head'] = qeuler(x=GRAZE_HEAD - 7.0 * tear + 1.2 * chew, z=4.0 * tear)
    out['ear_r'] = qeuler(x=10 + 8 * math.sin(a * 2), y=-10 + 12 * math.sin(a * 3 + 1))
    out['ear_l'] = qeuler(x=10 + 8 * math.sin(a * 2 + 2), y=10 + 12 * math.sin(a * 3 + 2))
    swish = float(Track((0, 0), (86, 0), (90, 1), (95, -1), (100, 0.6), (106, 0), (120, 0))(t))
    out['tail'] = qeuler(y=24 * swish, x=-3 * abs(swish))
    out['tail_end'] = qeuler(y=-14 * swish)
    out['forelock'] = qeuler(x=18.0)
    out['mane'] = qeuler(z=-4.0)
    return out


graze = baked('graze', GRAZE_LEN, graze_frame, loop=True)
MOMENTS['graze'] = [(12.0, 'tear'), (42.0, 'tear'), (80.0, 'tear'), (100.0, 'snort')]

# ---- nosebag: head at its ease in the bag, munching; now and then a toss to shake the oats down. 100 ticks
NOSEBAG_LEN = 100


def nosebag_frame(t):
    a = 2 * math.pi * t / NOSEBAG_LEN
    toss = float(Track((0, 0), (44, 0), (47, 1), (50, 0.2), (53, 0.8), (57, 0), (100, 0))(t))
    munch = math.sin(a * 12) * (1 - toss)
    qb = qeuler(x=0.6 + 0.25 * math.sin(a * 2), z=0.6 * math.sin(a))
    pb = np.array([0.0, 0.25 + 0.15 * math.sin(a * 2), 0.0])
    out = {'body': (qb, pb)}
    out.update(legs_of(stand_angles(qb, pb)))
    out['neck'] = qeuler(x=24.0 - 18.0 * toss + 0.8 * munch)
    out['head'] = qeuler(x=6.0 - 14.0 * toss + 1.6 * munch, z=-3 * toss)
    out['ear_r'] = qeuler(x=-6 + 4 * math.sin(a * 2), y=-18 + 10 * math.sin(a * 3))
    out['ear_l'] = qeuler(x=-6 + 4 * math.sin(a * 2 + 1), y=18 + 10 * math.sin(a * 3 + 2))
    out['tail'] = qeuler(y=6 * math.sin(a), x=1.5 * math.sin(a * 2))
    out['tail_end'] = qeuler(y=-4 * math.sin(a - 0.6))
    out['bells'] = qeuler(x=20.0 * float(Track((0, 0), (46, 0), (48, 1), (50, -0.7), (53, 0.8), (56, -0.4), (60, 0), (100, 0))(t)))
    out['mane'] = qeuler(z=5 * toss)
    out['forelock'] = qeuler(x=-10 * toss)
    return out


nosebag = baked('nosebag', NOSEBAG_LEN, nosebag_frame, loop=True)
MOMENTS['nosebag'] = [(48.0, 'snort')]

# ================================================================== once through

HIND_HIPS = np.array([0.0, dh.LEGS['hr']['pivot'][1], dh.LEGS['hr']['pivot'][2]])     # in the body's frame

# ---- rear: startled by the whistle, it throws its head up, rears in the shafts, paws the air and comes down. 44 ticks
REAR_LEN = 44


def rear_frame(t):
    up = float(Track((0, 0), (3, 0.08), (11, 0.92), (15, 1.0), (20, 0.86), (24, 1.0), (28, 0.0), (31, 0.06), (34, 0.0), (44, 0.0))(t))
    q = qeuler(x=-30.0 * up)
    pivot = HIND_HIPS + np.array([0.0, 2.0, 2.5])
    back = float(Track((0, 0), (3, 1), (12, 1), (30, 0), (44, 0))(t))
    pb = about(q, pivot) + np.array([0.0, 0.0, 0.8 * back])
    lifted = {}
    if up > 0.02:
        Rb, Tb = qmat(q), BODY0 + pb
        for side, off in (('fr', 0.0), ('fl', 0.5)):
            hipw = Tb + Rb @ LEG[side]['hip']
            k = math.sin(2 * math.pi * ((t - 9) / 8.0 + off))
            w = smooth(up * 1.4)
            tgt = hipw + np.array([0.0, 12.5 + 2.5 * k, -5.0 - 3.0 * k])
            rest = LEG[side]['rest']
            lifted[side] = (rest * (1 - w) + tgt * w, (60.0 + 25.0 * k) * w, False)
    out = {'body': (q, pb)}
    out.update(legs_of(stand_angles(q, pb, lifted)))
    toss = float(Track((0, 0), (2, 1), (12, 1), (16, 0.6), (20, 1), (26, 0.4), (30, 0.8), (34, 0.2), (44, 0))(t))
    out['neck'] = qeuler(x=-26.0 * toss + 12.0 * up, z=6.0 * math.sin(t * 0.7) * toss)
    out['head'] = qeuler(x=-22.0 * toss, y=8.0 * math.sin(t * 0.5) * toss, z=-6.0 * math.sin(t * 0.6) * toss)
    out['ear_r'] = qeuler(x=46.0 * toss, y=-14 * toss)
    out['ear_l'] = qeuler(x=46.0 * toss, y=14 * toss)
    out['tail'] = qeuler(x=-10.0 * toss + 26.0 * up, y=10 * math.sin(t * 0.8) * toss)
    out['tail_end'] = qeuler(x=8.0 * up, y=-8 * math.sin(t * 0.8 - 0.6) * toss)
    out['mane'] = qeuler(z=8.0 * math.sin(t * 0.9) * toss)
    out['forelock'] = qeuler(x=-20.0 * toss)
    out['bells'] = qeuler(x=28.0 * math.sin(t * 1.3) * (toss + up) * 0.6)
    return out


rear = baked('rear', REAR_LEN, rear_frame, blend=(2.0, 6.0))
MOMENTS['rear'] = [(2.0, 'neigh'), (28.0, 'thud'), (31.5, 'stamp')]

# ---- hit: struck, it flinches from the blow, shies and squeals. 16 ticks
HIT_LEN = 16


def hit_frame(t):
    j = float(Track((0, 0), (1.5, 1), (5, 0.7), (9, 0.25), (16, 0))(t))
    hop = float(Track((0, 0), (2, 0), (4, 1), (7, 0), (16, 0))(t))
    q = qeuler(x=-3.0 * j + 2.0 * hop, y=6.0 * j, z=-5.0 * j)
    pb = np.array([1.2 * j, -0.6 * hop, 0.4 * j])
    lifted = {}
    if hop > 0.01:
        for side in ('hr', 'hl'):
            lifted[side] = lifted_hoof(side, 2.8 * hop, -1.0 * hop, 30.0 * hop)
    out = {'body': (q, pb)}
    out.update(legs_of(stand_angles(q, pb, lifted)))
    out['neck'] = qeuler(x=-16.0 * j, y=-10.0 * j, z=8.0 * j)
    out['head'] = qeuler(x=-14.0 * j, y=-12.0 * j)
    out['ear_r'] = qeuler(x=50.0 * j)
    out['ear_l'] = qeuler(x=50.0 * j)
    out['tail'] = qeuler(x=-18.0 * j, y=14.0 * j)
    out['tail_end'] = qeuler(x=6.0 * j, y=-10.0 * j)
    out['bells'] = qeuler(x=30.0 * math.sin(t * 1.6) * j)
    out['mane'] = qeuler(z=-8.0 * j)
    return out


hit = baked('hit', HIT_LEN, hit_frame, blend=(1.0, 4.0))
MOMENTS['hit'] = [(1.0, 'neigh')]

# ---- collapse: it staggers, its forelegs buckle and it drops onto its knees, its hind end goes down, and it rolls
# over onto its left side, lets its head down and lies still. 64 ticks; it ends in 'lie'. The body rides on whatever
# of it is lowest: each frame it's let down as far as the clip asks, then lifted back until nothing but the hooves
# still standing reaches the ground.
COLLAPSE_LEN = 64
LIE_ROLL = 84.0             # how far it rolls over: onto its left side, its back a little uphill

CORNERS = {}
for _p in MODEL.all_parts():
    _lo = [np.array(c.origin, float) for c in _p.cubes]
    _hi = [lo + np.array(c.size, float) for lo, c in zip(_lo, _p.cubes)]
    CORNERS[_p.name] = np.array([(x, y, z) for lo, hi in zip(_lo, _hi) for x in (lo[0], hi[0]) for y in (lo[1], hi[1])
                                 for z in (lo[2], hi[2])], float).reshape(-1, 3)
TRAILING = set(dh.HIDDEN)   # left out of the ground test: the cut traces and reins trail on it; the nosebag isn't on


def under(frame, skip=()):
    """How far the horse posed by [frame] ({bone: q or (q, pos)}, as 'set' keys) reaches under the ground, px (< 0:
    it's clear of it); the parts named in [skip], and what hangs from them, left out."""
    low = -1e9

    def go(part, M0, T0):
        nonlocal low
        if part.name in skip or part.name in TRAILING:
            return
        R = qmat(qeuler(*part.rot))
        off = np.zeros(3)
        v = frame.get(part.name)
        if v is not None:
            q, off = v if isinstance(v, tuple) else (v, np.zeros(3))
            R = R @ qmat(q)
        Rm = M0 @ R
        P = T0 + M0 @ (np.array(part.pivot, float) + off)
        if len(CORNERS[part.name]):
            low = max(low, float((CORNERS[part.name] @ Rm.T + P)[:, 1].max()))
        for ch in part.children:
            go(ch, Rm, P)
    for p in MODEL.parts:
        go(p, np.eye(3), np.zeros(3))
    return low - G


# The legs' poses going down, as the pitches (degrees from straight down, + back) of the forearm or thigh, the cannon
# and the hoof in the world, the body level: on its knees, the forearms upright, the cannons flat on the ground
# behind and the toes curled; then lying on its brisket, the forearms forward along the ground with the cannons
# folded back under them, and the hind legs folded, the hocks on the ground and the cannons forward under the belly.
KNEEL = {'fr': (-6.0, 86.0, 112.0), 'fl': (-2.0, 88.0, 116.0)}
BRISKET = {'fr': (-74.0, 88.0, 116.0), 'fl': (-66.0, 90.0, 118.0), 'hr': (46.0, -88.0, -108.0), 'hl': (40.0, -90.0, -114.0)}
# On its side (turns from the body, its own frame): the legs lying out, the lower (left) pair on the ground and the
# upper pair let down onto them (the last value: how far a leg falls toward the ground, about z).
LYING = {'fl': (-14.0, 26.0, 24.0, 0.0), 'fr': (-40.0, 64.0, 30.0, -11.0),
         'hl': (22.0, -14.0, 28.0, 0.0), 'hr': (-6.0, -42.0, 40.0, -11.0)}


def from_world(pitch, a):
    """(leg, shin, hoof) turns from world pitches [a] with the body pitched [pitch]."""
    return (a[0] - pitch, a[1] - a[0], a[2] - a[1])


def mix(a, b, w):
    return tuple(x + (y - x) * w for x, y in zip(a, b))


def collapse_frame(t):
    stagger = float(Track((0, 0), (3, 1), (6, -0.7), (9, 0.5), (12, 0), (64, 0))(t))
    knees = float(Track((0, 0), (8, 0), (18, 1), (64, 1))(t))           # the forelegs buckling onto the knees
    hind = float(Track((0, 0), (19, 0), (30, 1), (64, 1))(t))           # the hind end down, onto its brisket
    over = float(Track((0, 0), (31, 0), (43, 1), (64, 1))(t))           # rolled onto its side
    settle = float(Track((0, 0), (41, 0), (54, 1), (64, 1))(t))         # the head let down, the legs eased
    kick = float(Track((0, 0), (48, 0), (51, 1), (53, 0.7), (58, 0), (64, 0))(t))   # a last stretch of a hind leg
    toss = float(Track((0, 0), (2, 1), (8, 0.4), (14, 0), (64, 0))(t))
    pitch = 14.0 * knees * (1 - hind) + 2.0 * hind * (1 - over)
    q = qmul(qeuler(z=LIE_ROLL * smooth(over) + 3.0 * stagger), qeuler(x=pitch, y=-4.0 * stagger))
    low = 16.0 * max(knees, hind, over)                 # how far down it's let go before it's caught by what's under it
    pb = np.array([1.2 * stagger + 2.5 * over, low, 0.4 * hind * (1 - over)])

    def legs(pb):
        stand = stand_angles(q, pb)
        ang = {}
        for side in ('fr', 'fl'):
            a = stand[side]
            if knees > 0:
                a = mix(a, from_world(pitch, KNEEL[side]), smooth(knees * 1.15))
            if hind > 0:
                a = mix(a, from_world(pitch, BRISKET[side]), hind)
            ang[side] = a
        for side in ('hr', 'hl'):
            a = stand[side]
            if hind > 0:
                a = mix(a, from_world(pitch, BRISKET[side]), smooth(hind * 1.1))
            ang[side] = a
        out = {}
        for side, a in ang.items():
            lie = LYING[side]
            a = mix(a, lie[:3], over) if over > 0 else a
            if side == 'hr':
                a = (a[0] - 30.0 * kick, a[1] + 26.0 * kick, a[2] + 10.0 * kick)
            fall = lie[3] * over
            out[f'{side}_leg'] = qeuler(x=a[0], z=fall)
            out[f'{side}_shin'] = qeuler(x=a[1])
            out[f'{side}_hoof'] = qeuler(x=a[2])
        return out

    out = {}
    out['neck'] = qeuler(x=-10.0 * stagger - 14.0 * toss + 20.0 * knees * (1 - over) + 8.0 * over * (1 - settle),
                         y=-6.0 * stagger, z=34.0 * smooth(settle))
    out['head'] = qeuler(x=-16.0 * toss + 12.0 * over - 6.0 * settle, z=26.0 * smooth(settle))
    standing = ('hr_leg', 'hl_leg') if hind == 0 else ()
    if knees == 0:
        standing += ('fr_leg', 'fl_leg')
    for _ in range(4):          # let it down onto whatever's under it
        frame = dict(out, body=(q, pb), **legs(pb))
        sink = under(frame, standing)
        if abs(sink) < 0.01:
            break
        pb = pb + np.array([0.0, min(low - pb[1], -sink), 0.0])
    out.update(legs(pb))
    out['body'] = (q, pb)
    out['ear_r'] = qeuler(x=44.0 * (1 - settle) + 24.0 * settle, y=-10.0 * settle)
    out['ear_l'] = qeuler(x=44.0 * (1 - settle) + 24.0 * settle, y=10.0 * settle)
    out['tail'] = qeuler(x=-6.0 * stagger + 24.0 * over, y=14.0 * stagger, z=-30.0 * smooth(settle))
    out['tail_end'] = qeuler(x=10.0 * over, y=-10.0 * stagger, z=-14.0 * settle)
    out['mane'] = qeuler(z=16.0 * settle)
    out['forelock'] = qeuler(x=-12.0 * over, z=20.0 * settle)
    out['bells'] = qeuler(x=20.0 * math.sin(t * 1.1) * (1 - settle) * (knees + over) * 0.5, z=-50.0 * over)
    out['trace_r_cut'] = qeuler(x=-10.0 * over, z=-30.0 * over)
    out['trace_l_cut'] = qeuler(x=-10.0 * over, z=-60.0 * over)
    out['rein_r_cut'] = qeuler(x=20.0 * over, z=-30.0 * over)
    out['rein_l_cut'] = qeuler(x=20.0 * over, z=-60.0 * over)
    return out


collapse = baked('collapse', COLLAPSE_LEN, collapse_frame, blend=(2.0, 0.0))
MOMENTS['collapse'] = [(1.0, 'neigh'), (17.0, 'thud'), (29.0, 'thud'), (41.0, 'thud'), (50.0, 'snort')]

lie = baked('lie', 4, lambda t: collapse_frame(COLLAPSE_LEN), loop=True)       # still: a short loop does
MOMENTS['lie'] = []

CLIPS = [idle, walk, strain, bolt, graze, nosebag, rear, hit, collapse, lie]
STRIDES = {'walk': WALK_STRIDE, 'strain': WALK_STRIDE, 'bolt': BOLT_STRIDE}


def summary():
    return {'walk': WALK_STRIDE, 'bolt': BOLT_STRIDE}, {c.name: MOMENTS.get(c.name, []) for c in CLIPS}


# ================================================================== posing as the game does


def rest(bone):
    return qeuler(*IDX[bone].rot)


def posed(layers):
    """{part: (R, offset)} for dh.posed_faces: each layer (clip, t, weight) over the last, as the rig animator does."""
    pose = {name: [rest(name), np.zeros(3)] for name in IDX}
    for clip, t, w in layers:
        if w <= 0:
            continue
        for bone in clip.keys:
            if bone not in pose:
                continue
            qk, pk = clip.sample(bone, t)
            q0, v0 = pose[bone]
            if clip.modes.get(bone) == 'add':
                gq, gv = qmul(q0, qk), v0 + pk
            else:
                gq, gv = qmul(rest(bone), qk), pk
            pose[bone] = [qslerp(q0, gq, w), v0 + (gv - v0) * w]
    return {name: (qmat(q), off) for name, (q, off) in pose.items()}


def hoof_points(pose, side):
    """The toe and heel of a hoof's underside (model space) in a pose."""
    Rb, ob = pose['body']
    Tb = BODY0 + ob
    L = LEG[side]
    R1 = Rb @ pose[f'{side}_leg'][0]
    R2 = R1 @ pose[f'{side}_shin'][0]
    R3 = R2 @ pose[f'{side}_hoof'][0]
    F = Tb + Rb @ L['hip'] + R1 @ L['k'] + R2 @ L['f']
    return [F + R3 @ (L['s'] + np.array([0.0, 0.0, dz])) for dz in (-HALF, 0.0, HALF)]


def check(clip, stride=None):
    """How far a hoof slides while its toe, middle or heel is on the ground, and how far one sinks under it (px)."""
    slide, sink = 0.0, 0.0
    held = {}
    for i in range(int(clip.length * 2) + 1):
        t = i / 2
        pose = posed([(clip, t, 1.0)])
        go = 0.0 if stride is None else stride * 16.0 * t / clip.length
        for side in LEG:
            for j, p in enumerate(hoof_points(pose, side)):
                key = (side, j)
                sink = max(sink, p[1] - G)
                if abs(p[1] - G) < 0.12:
                    if key in held:
                        slide = max(slide, abs(p[2] - go - held[key]))
                    else:
                        held[key] = p[2] - go
                else:
                    held.pop(key, None)
    return slide, sink


def frame_img(layers, yaw, pitch=-10, scale=5.0, size=(300, 260), go=0.0, tex=None, center=(0.0, -16.0, -4.0), show=(),
              extra=(), bg=(58, 64, 80)):
    """The horse posed by [layers], gone [go] px forward over the ground."""
    faces = dh.posed_faces(MODEL, posed(layers), T=dh.TO_GROUND + np.array([0.0, 0.0, -go]), show=show) + list(extra)
    c = np.asarray(center, float) + np.array([0.0, 0.0, -go])
    return dh.wg.render(faces, tex, yaw, pitch, scale, size, c, bg=bg, extent=(-10, 10, -14, 10))


def contact(clip, tex, n=8, views=(-90, -30), stride=None, show=()):
    """A row of moments through [clip] from each of [views]; a gait walks on over the ground by its stride."""
    W, H = 300, 260
    out = Image.new('RGB', (W * n, H * len(views) + 18), (30, 32, 40))
    d = ImageDraw.Draw(out)
    d.text((4, 3), clip.name + ('' if stride is None else f'  (stride {stride} blocks)'), fill=(235, 235, 235))
    for i in range(n):
        t = clip.length * i / (n if (stride is not None or clip.blend_out == 0) else n - 1)
        go = 0.0 if stride is None else stride * 16.0 * t / clip.length
        for j, yaw in enumerate(views):
            out.paste(frame_img([(clip, t, 1.0)], yaw, go=go, tex=tex, show=show), (i * W, 18 + j * H))
        d.text((i * W + 4, 21), f't={t:.1f}', fill=(255, 255, 255))
    return out


CUT = ('trace_r_cut', 'trace_l_cut', 'rein_r_cut', 'rein_l_cut')


def shown(name):
    return CUT if name in ('bolt', 'collapse', 'lie') else ('nosebag',) if name == 'nosebag' else ()


def previews(out, only=None):
    os.makedirs(out, exist_ok=True)
    tex = dh.texture(MODEL, 'bay')
    paths = []
    for c in CLIPS:
        if only and c.name not in only:
            continue
        p = os.path.join(out, f'horse_{c.name}.png')
        contact(c, tex, stride=STRIDES.get(c.name), show=shown(c.name)).save(p)
        paths.append(p)
    return paths


if __name__ == '__main__':
    assets = sys.argv[1]
    prev_dir = sys.argv[2] if len(sys.argv) > 2 else None
    only = set(sys.argv[3].split(',')) if len(sys.argv) > 3 else None
    for c in CLIPS:
        if only and c.name not in only:
            continue
        slide, sink = check(c, STRIDES.get(c.name))
        print(c.write(assets, GROUP), f'hooves slide {slide:.2f} px, sink {sink:.2f} px' + (f', bob {c.bob:.2f} px' if hasattr(c, 'bob') else ''))
    if prev_dir:
        for p in previews(prev_dir, only):
            print('preview', p)
