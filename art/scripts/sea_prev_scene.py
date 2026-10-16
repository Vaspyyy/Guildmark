"""The sea monsters together: the Maelgrasp's four arms hold the cog at night while its head surfaces beside her.

    night(world): four arms rise out of the dark sea (two off her starboard side and two off her port side), arch over the rails and close their coils on her
        deck; she is hauled down and sways from side to side under their pull, her lanterns swinging; the head comes up behind her with its eye shut, opens
        the eye, looks over her rail at the crew, flares into a rage as the arms tighten and lunges; then the arms let go and sink, the head sinks, and she
        rights herself. A loop.

Nothing here is the game: the game decides where the arms hold and when the head comes; this film invents a scene that shows the models off. Scene space
is the cog's model space (px, y down, the sea at y = 0, the cog on the x = 0 plane facing -z, her starboard -x).
"""
import math

import numpy as np

import sea_head as H
import sea_prev_head as HD
from sea_previews import TPF_SLOW, at, burst, clamp, fly, rings, smooth, track
from sea_prev_arm import S, W, Rig, adrift, above_water, arm_frames, crown, fill, presence, ship_kw

CAM_NIGHT = dict(yaw=6.0, elev=21.0, scale=1.78, size=(450, 470), center=(-18.0, -52.0, 60.0))
HEAD_NIGHT = (-62.0, 160.0)               # where the head comes up: astern of her, beyond her from the camera, facing her

# the four arms: where each comes up (x, z), the side it holds her from (+1: starboard, whose arms reach toward +x; -1: port), where its coil closes (a point
# of the ship), and the tick it starts to rise
ARMS = [
    dict(root=(-84.0, -40.0), side=+1, hook=(-7.0, -28.0, -38.0), t0=4.0, seed=11),
    dict(root=(84.0, -14.0), side=-1, hook=(7.0, -28.0, -12.0), t0=11.0, seed=12),
    dict(root=(-84.0, 18.0), side=+1, hook=(-7.0, -28.0, 20.0), t0=18.0, seed=13),
    dict(root=(84.0, 40.0), side=-1, hook=(7.0, -44.0, 52.0), t0=25.0, seed=14),
]


def arm_keys(a, total, t_rel):
    """The keys of one arm: up out of the deep, over the rail, onto the deck, held, let go and down again."""
    rx, rz = a['root']
    sg = a['side']
    t0 = a['t0']
    hx, hy, hz = a['hook']
    hook = S(hx, hy, hz)
    over = S(-sg * 40.0, -88.0, hz + 1.5)
    h = (float(sg), 0.0, 0.0)
    return fill([
        dict(t=0.0, aim=W(rx, 190, rz)),
        dict(t=t0, aim=W(rx, 190, rz)),
        dict(t=t0 + 5.0, aim=W(rx, 150, rz)),
        dict(t=t0 + 20.0, aim=W(rx, -112, rz), head=(0.2 * sg, -1, 0), glow=0.5, curl=0.3, rip=0.35),
        dict(t=t0 + 30.0, aim=W(rx + sg * 14.0, -96, rz + 3.0), head=(0.5 * sg, -1, 0.1), curl=0.55, rip=0.5, sway=3.0),
        dict(t=t0 + 38.0, aim=over, head=h, curl=0.5, glow=0.7, sway=2.0),
        dict(t=t0 + 46.0, aim=hook, head=h, curl=0.65, hold=1.0, sway=0.0, ease='io'),
        dict(t=t0 + 51.0, aim=hook, grip=1.0, rage=0.3, ease='out'),
        dict(t=t0 + 62.0, aim=hook, glow=0.9, rage=0.5, rip=0.4, ease='lin'),
        dict(t=t_rel - 6.0, aim=hook, glow=1.0, rage=0.8, rip=0.2, ease='lin'),
        dict(t=t_rel, aim=hook, ease='lin'),
        dict(t=t_rel + 8.0, aim=W(rx + sg * 16.0, -92, rz), head=(-0.3 * sg, -1, 0), curl=0.4, grip=0.0, hold=0.0, rage=0.3, glow=0.6, sway=3.0, ease='io'),
        dict(t=t_rel + 28.0, aim=W(rx, 180, rz), head=(0, -1, 0), glow=0.0, rage=0.0, sway=0.0, ease='in'),
        dict(t=total, aim=W(rx, 190, rz)),
    ])


def night(world, n=None):
    """See the module's docstring."""
    n = n or 88
    total = n * TPF_SLOW                                  # (a picture every 2 ticks: see `sequences`)
    t_rel = 122.0
    rng = np.random.default_rng(61)
    # ---- the ship's answer: she is hauled down by the arms, rolls from one side to the other as they take turns, and her lanterns swing
    tugs = [(62.0, -1), (76.0, +1), (90.0, -1), (101.0, +1), (111.0, -1)]
    kicks = [dict(t=tt, pitch=0.3 * s, heel=-1.4 * s, bob=-0.03, decay=7.0, hurt=8.0) for tt, s in tugs]
    kicks.append(dict(t=t_rel + 4.0, pitch=-0.5, heel=1.8, bob=0.1, decay=10.0, hurt=9.0, phase=math.pi / 2.0, w=0.4))

    def haul(t):
        k = smooth(52.0, 84.0, t) * (1.0 - smooth(t_rel - 2.0, t_rel + 10.0, t))
        return 1.2 * math.sin(0.30 * t) * k, -0.5 * k
    frs = [arm_frames(Rig(a['root'][0], a['root'][1], (a['side'] * 1.0, 0.0)), arm_keys(a, total, t_rel + 3.0 * i), total, n, kicks, haul, phase=0.7 + 0.9 * i) for i, a in enumerate(ARMS)]
    rigs = [Rig(a['root'][0], a['root'][1], (a['side'] * 1.0, 0.0)) for a in ARMS]
    # ---- the head
    cam = CAM_NIGHT
    HD.HEAD_AT = HEAD_NIGHT
    yaw0 = HD.face_yaw(cam, 0.12)
    k_rise = [(0.0, 0.0), (40.0, 0.0), (74.0, 1.0), (t_rel + 6.0, 1.0), (t_rel + 36.0, 0.0), (total, 0.0)]
    k_lid = [(0.0, 1.0), (72.0, 1.0), (82.0, 0.3), (88.0, 0.0), (112.0, 0.0), (total, 0.0)]
    k_pupil = [(0.0, 0.2), (80.0, 0.12), (92.0, 0.5), (100.0, 0.06), (t_rel - 6.0, 0.06), (t_rel + 6.0, 1.0), (total, 0.3)]
    k_yaw = [(0.0, 0.0), (86.0, 0.0), (98.0, -34.0), (t_rel, -34.0), (total, 0.0)]
    k_beak = [(0.0, 0.0), (t_rel - 8.0, 0.0), (t_rel - 1.0, 1.0), (t_rel + 3.0, 1.0), (t_rel + 6.0, 0.0), (total, 0.0)]
    k_lunge = [(0.0, 0.0), (t_rel - 14.0, 0.0), (t_rel - 2.0, 1.0), (t_rel + 4.0, 1.0), (t_rel + 20.0, 0.0), (total, 0.0)]
    k_rage = [(0.0, 0.0), (94.0, 0.0), (106.0, 1.0), (t_rel + 6.0, 1.0), (t_rel + 20.0, 0.0), (total, 0.0)]
    k_glow = [(0.0, 0.1), (60.0, 0.2), (88.0, 0.8), (106.0, 1.0), (t_rel + 6.0, 1.0), (t_rel + 30.0, 0.0), (total, 0.0)]

    def hstate(t):
        k = lambda keys: float(track(t, keys))
        return H.HeadState(time=t + 70.0, rise=k(k_rise), lookYaw=k(k_yaw), lookPitch=0.0, pupil=k(k_pupil), lid=k(k_lid), beak=k(k_beak), lunge=k(k_lunge),
                           rage=k(k_rage), glow=k(k_glow) * (0.9 + 0.1 * math.sin(0.23 * t)))

    def hroot(t):
        st = hstate(t)
        shake = float(track(t, k_rage)) * (1.2 * math.sin(1.9 * t) + 0.7 * math.sin(3.1 * t + 1.0))
        return HD.head_root(st, HD.wobble(t) * clamp(st.rise * 3.0), yaw0, 3.0 * math.sin(0.021 * t + 0.5) + 0.7 * shake)

    # ---- the spray
    hx, hz = HEAD_NIGHT
    sink_of = lambda t: HD.SINK * (1.0 - float(track(t, k_rise)))
    specks = HD.streams(rng, 44.0, 112.0, sink_of, hroot, rate=1.4)
    specks += HD.streams(rng, t_rel + 8.0, t_rel + 40.0, sink_of, hroot, rate=0.7)
    specks += crown(rng, 40, (hx, 0.0, hz), 12.0, 46.0, up=(2.0, 5.5), out=(0.4, 2.2), life=(16.0, 30.0))
    specks += crown(rng, 30, (hx, 0.0, hz), 36.0, 52.0, up=(1.0, 3.8), out=(0.2, 1.4), life=(16.0, 30.0))
    specks += crown(rng, 36, (hx, 0.0, hz), 36.0, t_rel + 34.0, up=(0.6, 2.4), out=(0.2, 1.2), life=(14.0, 26.0))
    events = [(hx, hz, 46.0, 1.0, 14.0, 1.0, 60.0), (hx, hz, 55.0, 0.7, 24.0, 0.9, 60.0), (hx, hz, t_rel + 2.0, 0.7, 30.0, 0.9, 40.0), (hx, hz, t_rel + 34.0, 0.8, 30.0, 0.8, 40.0)]
    events += HD.bubbles(rng, t_rel + 24.0, t_rel + 44.0, HEAD_NIGHT, 34.0, 0.6, life=14.0, rate=0.7)
    present = [[presence(f) for f in fr] for fr in frs]
    near = lambda fr, t: fr[min(range(n), key=lambda k: abs(fr[k]['t'] - t))]
    for i, (a, fr, pr) in enumerate(zip(ARMS, frs, present)):
        rx, rz = a['root']
        t_em = next((f['t'] for f, p in zip(fr, pr) if p > 0.02), a['t0'] + 8.0)
        t_sk = next((f['t'] for f, p in zip(fr, pr) if f['t'] > t_rel + 10.0 and p < 0.12), t_rel + 30.0)
        specks += crown(rng, 40, (rx, 0.0, rz), 10.0, t_em, up=(2.0, 5.5), out=(0.5, 2.0), life=(16.0, 30.0))
        specks += adrift(rng, 50, lambda t, fr=fr: above_water(near(fr, t)), t_em, t_em + 30.0)
        specks += crown(rng, 30, (rx, 0.0, rz), 10.0, t_sk, up=(1.0, 3.0), out=(0.4, 1.6), life=(14.0, 26.0))
        events += [(rx, rz, t_em, 0.9, 8.0, 0.9, 50.0), (rx, rz, t_sk, 0.9, 8.0, 0.9, 36.0)]
        tg = a['t0'] + 48.0
        specks += burst(rng, 22, at(near(fr, tg)['sm'], a['hook']), tg, speed=(0.8, 3.0), cone=1.3, axis=(0.0, -1.0, 0.0), life=(10.0, 22.0), size=(0.6, 1.5),
                        colour=(184, 140, 90), alpha=1.0, floor=None)
        for tt, _s in tugs:
            events.append((a['side'] * -31.0, a['hook'][2], tt + 2.0 * i, 0.6, 5.0, 0.7, 30.0))
    specs = []
    for f in range(n):
        t = f * total / n
        mot = frs[0][f]['mot']
        st = hstate(t)
        acts = [rigs[i].actor(frs[i][f]['joints'], t, frs[i][f]['ch']['grip'], frs[i][f]['ch']['glow'], frs[i][f]['ch']['rage']) for i in range(len(ARMS))]
        acts.append(HD.hactor(st, hroot(t)))
        spots = [(hx, hz, HD.waterline_r(st, HD.wobble(t)) + 1.5, 0.9 * smooth(0.0, 0.1, st.rise), 0.0)]
        for a, fr, pr in zip(ARMS, frs, present):
            spots.append((a['root'][0], a['root'][1], 12.0 + 1.2 * math.sin(t * 0.3 + a['t0']), 0.85 * pr[f], 0.0))
        spots += rings(events, t)
        specs.append(dict(cam=cam, sky='moonlit', cog=ship_kw(t, mot, 1.0), actors=acts, spots=spots, fx=fly(specks, t), sea_t=0.0, ring_t=t, bloom=0.9, murk=1.7))
    return specs


def night_still(world):
    """The night's best moment as a still, larger: all four arms closed on her, the head risen behind her and its eye on her crew, the sea lit by the arms."""
    specs = night(world)
    k = min(range(len(specs)), key=lambda i: abs(i * TPF_SLOW - 108.0))
    spec = dict(specs[k])
    spec['cam'] = dict(CAM_NIGHT, scale=CAM_NIGHT['scale'] * 1.5, size=(720, 750))
    return [spec]
