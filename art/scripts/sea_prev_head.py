"""The Maelgrasp's head, previewed: short films of the head beside the cog, posed with `sea_head.pose` (the Python twin of
`MaelgraspModel.setupAnim`), so what they show is what the model does.

    head_hero(world): a still at dusk: the head risen off the starboard bow, its eye on the cog, her lanterns lit.
    head_surface(world): it comes up out of the deep with its eye shut and water pouring off the mantle, opens the eye (the pupil a narrow bar that
        widens as it sees the ship), looks about, blinks, looks again, lunges and snaps with a burst of spray, settles and sinks. A loop.
    head_wrath(world): it rises, spews a cloud of ink, flies into a rage (the brow drops, the pupil shrinks to a slit, the lights blaze, the roots and
        fins thrash), lunges, then dies: the eye clouds over, the lids droop, the roots go limp and it sinks, bubbling. A loop.

Nothing here is the game: the game decides when its Maelgrasp surfaces and what it does; these films invent a scene that shows the model off. The
renderer has to lower the head itself (the model's `rise` only splays and curls the roots and the fins): the previews lower it by `SINK` px at rise 0.
Scene space is the cog's model space (px, y down, the sea at y = 0, the cog on the x = 0 plane facing -z, her starboard -x).
"""
import math

import numpy as np

import sea_kit as K
import sea_head as H
import sea_previews as P
from sea_previews import TPF, TPF_SLOW, GRAVITY, at, burst, clamp, fly, ink_cloud, ink_pool, rings, smooth, track, unit
from sea_prev_arm import crown, ship_kw, ship_motion

SINK = 96.0                          # px the head is lowered by at rise 0: the top of the mantle is 88 px above the head's origin
HEAD_AT = (-46.0, -100.0)            # where it surfaces: off the starboard bow, a little ahead of her
CAM_HEAD = dict(yaw=-22.0, elev=8.0, scale=2.4, size=(480, 520), center=(-31.0, -48.0, -66.0))
CAM_HERO = dict(yaw=-22.0, elev=5.0, scale=2.9, size=(560, 640), center=(-26.0, -50.0, -66.0))
SIPHON_MOUTH = (51.0, -3.4, 9.2)     # the siphon's mouth, in the head's own frame (px)
SIPHON_DIR = (0.85, 0.35, 0.40)


def hstate(**kw):
    return H.HeadState(**kw)


def face_yaw(cam, toward_ship=0.15):
    """The yaw the head is turned to: toward the camera, turned [toward_ship] of the way toward the cog (so its eye can turn to her)."""
    yw = math.radians(cam['yaw'])
    to_cam = np.array([-math.sin(yw), -math.cos(yw)])
    to_ship = unit(np.array([0.0 - HEAD_AT[0], 10.0 - HEAD_AT[1]]))
    d = unit(to_cam * (1.0 - toward_ship) + to_ship * toward_ship)
    return K.facing(d[0], d[1])


def head_root(st, bob=0.0, yaw=0.0, sway=0.0):
    """The head's matrix: the head at HEAD_AT, lowered by `SINK` as `st.rise` falls to 0 (the renderer does this: the model's origin is at the waterline)."""
    sink = SINK * (1.0 - clamp(st.rise))
    return K.place(HEAD_AT[0], sink + bob, HEAD_AT[1], yaw=yaw + sway)


def glow_gain(st):
    return clamp(st.glow * (1.0 + 0.4 * st.rage), 0.0, 1.4)


def hactor(st, M):
    return dict(model='head', pose=H.pose(st), root=M, glow=glow_gain(st))


def waterline_r(st, bob=0.0):
    """The radius (px) of the mantle where the sea cuts it (0 when the head is under)."""
    y = -(SINK * (1.0 - clamp(st.rise)) + bob)
    return H.mantle_r(y) if y > H.Y_TOP else 0.0


def streams(rng, t0, t1, sink_of, M_of, rate=1.3, life=(14.0, 30.0)):
    """Water pouring off the mantle: drops that leave its surface between ticks [t0] and [t1] (about [rate] a tick), each starting on the mantle at a height
    between the sea and the top (`sink_of(t)` is how far the head is lowered), on the side facing the camera more than not, and falling to the sea."""
    out = []
    t = t0
    while t < t1:
        t += rng.exponential(1.0 / rate)
        sink = sink_of(t)
        top = H.Y_TOP + sink
        y_hi = -sink                                   # the sea, in the head's frame
        if y_hi - top < 6.0:
            continue
        y = rng.uniform(top + 4.0, y_hi)
        r = H.mantle_r(y) + 1.0
        a = rng.uniform(-math.pi, math.pi)
        local = np.array([r * math.sin(a), y, H.mantle_zc(y) - r * math.cos(a)])
        p = at(M_of(t), local)
        n = unit(np.array([math.sin(a), 0.0, -math.cos(a)]))
        sp = np.array([0.0, 0.0, 0.0])
        sp[0], sp[2] = 0.12 * float((M_of(t)[:3, :3] @ n)[0]), 0.12 * float((M_of(t)[:3, :3] @ n)[2])
        out.append(dict(p=p, v=sp, t0=t, life=rng.uniform(*life), r=rng.uniform(0.45, 1.1), col=(200, 224, 236), a=0.85, g=GRAVITY, floor=0.0))
    return out


def bubbles(rng, t0, t1, centre, spread, strength=0.5, life=14.0, rate=0.9):
    """Foam rings of bubbles popping at the surface round [centre] (x, z) between [t0] and [t1]: events for `rings`."""
    out = []
    t = t0
    while t < t1:
        t += rng.exponential(1.0 / rate)
        a, d = rng.uniform(0.0, 2.0 * math.pi), spread * math.sqrt(rng.uniform(0.0, 1.0))
        out.append((centre[0] + d * math.cos(a), centre[1] + d * math.sin(a), t, strength * rng.uniform(0.5, 1.0), 1.4, 0.18, life))
    return out


def ink_jets(rng, t0, t1, M_of, st_of, rate=3.0):
    """Ink flung from the siphon's mouth between ticks [t0] and [t1]: dark drops that arc into the sea."""
    out = []
    t = t0
    while t < t1:
        t += rng.exponential(1.0 / rate)
        M = M_of(t)
        p = at(M, SIPHON_MOUTH)
        d = unit(M[:3, :3] @ np.array(SIPHON_DIR))
        pulse = 0.55 + 0.45 * math.sin(t * 0.85)
        out += burst(rng, 3, p, t, speed=(3.5 + 3.0 * pulse, 7.0 + 3.0 * pulse), cone=0.30, axis=d + np.array([0.0, -0.55, 0.0]), life=(10.0, 22.0), size=(1.0, 2.4),
                     colour=(46, 22, 70), alpha=0.97, g=GRAVITY * 0.6, floor=1.0)
    return out


def wobble(t, k=0.045):
    """The swell under the head: how far it rides up and down (px)."""
    return 1.6 * math.sin(k * t + 1.0) + 0.7 * math.sin(2.3 * k * t)


# ================================================================== the hero

def head_hero(world):
    """A still at dusk: the head surfaced off the starboard bow, eye open and on the cog (its pupil wide as it sees her), her lanterns lit, the lights of
    the iris and the veins burning, water running off the mantle."""
    cam = CAM_HERO
    yaw = face_yaw(cam)
    st = hstate(time=61.0, pupil=0.5, lookYaw=-30.0, lookPitch=0.0, glow=0.8, beak=0.0)
    bob = 0.6
    M = head_root(st, bob, yaw)
    rng = np.random.default_rng(5)
    drops = streams(rng, 0.0, 30.0, lambda t: 0.0, lambda t: M, rate=2.2, life=(20.0, 34.0))
    spot = (HEAD_AT[0], HEAD_AT[1], waterline_r(st, bob) + 1.5, 0.9, 1.0)
    ev = [(HEAD_AT[0], HEAD_AT[1], -14.0, 0.8, waterline_r(st) + 2.0, 0.8, 90.0)]
    return [dict(cam=cam, sky='dusk', cog=P.shipkw(0.0, lanterns=0.95, pitch=0.0, heel=0.0, bob=0.0), cog_static=True, actors=[hactor(st, M)], spots=[spot] + rings(ev, 18.0),
                 fx=fly(drops, 18.0), sea_t=0.0, ring_t=18.0, bloom=1.0, murk=1.7)]


# ================================================================== the surfacing

def head_surface(world, n=100):
    """It comes up out of the deep beside the cog with its eye shut, water pouring off its mantle; opens the eye (the pupil a bar that widens as it
    sees her), looks about, blinks, looks again, lunges and snaps with a burst of spray, settles and sinks. A loop (the sea is calm at both ends)."""
    total = n * TPF
    cam = CAM_HEAD
    yaw0 = face_yaw(cam)
    rng = np.random.default_rng(23)
    k_rise = [(0.0, 0.0), (4.0, 0.0), (40.0, 1.0), (128.0, 1.0), (150.0, 0.0), (total, 0.0)]
    k_lid = [(0.0, 1.0), (34.0, 1.0), (45.0, 0.45), (53.0, 0.0), (82.0, 0.0), (84.0, 1.0), (88.0, 0.0), (108.0, 0.0), (110.0, 1.0), (113.0, 0.0), (116.0, 0.0), (134.0, 0.0),
             (146.0, 1.0), (total, 1.0)]
    k_pupil = [(0.0, 0.2), (46.0, 0.12), (62.0, 0.5), (92.0, 0.35), (100.0, 0.62), (118.0, 0.85), (130.0, 0.9), (146.0, 0.3), (total, 0.3)]
    k_yaw = [(0.0, 0.0), (50.0, 0.0), (64.0, -26.0), (82.0, -26.0), (96.0, 22.0), (106.0, 22.0), (118.0, -26.0), (total, -26.0)]
    k_pitch = [(0.0, 0.0), (50.0, -4.0), (64.0, -2.0), (82.0, -2.0), (96.0, 16.0), (106.0, 16.0), (118.0, 0.0), (140.0, -8.0), (total, -8.0)]
    k_beak = [(0.0, 0.0), (112.0, 0.0), (123.0, 1.0), (127.0, 1.0), (130.0, 0.0), (total, 0.0)]
    k_lunge = [(0.0, 0.0), (110.0, 0.0), (126.0, 1.0), (128.0, 1.0), (146.0, 0.0), (total, 0.0)]
    k_glow = [(0.0, 0.1), (40.0, 0.2), (56.0, 0.7), (118.0, 0.85), (140.0, 0.4), (total, 0.1)]
    t_bite = 128.0

    def state(t):
        k = lambda keys: float(track(t, keys))
        gl = k(k_glow)
        return hstate(time=t + 20.0, rise=k(k_rise), lookYaw=k(k_yaw), lookPitch=k(k_pitch), pupil=k(k_pupil), lid=k(k_lid), beak=k(k_beak), lunge=k(k_lunge),
                      glow=gl * (0.88 + 0.12 * math.sin(0.19 * t)))

    def root(t):
        st = state(t)
        sway = 5.0 * math.sin(0.021 * t + 0.5) * smooth(30.0, 50.0, t)
        turn = -30.0 * smooth(100.0, 120.0, t) * (1.0 - smooth(132.0, 152.0, t))          # it turns to the ship to bite
        return head_root(st, wobble(t) * clamp(st.rise * 3.0), yaw0, sway + turn)
    sink_of = lambda t: SINK * (1.0 - float(track(t, k_rise)))
    specks = streams(rng, 6.0, 78.0, sink_of, root, rate=1.5)
    specks += streams(rng, 118.0, 150.0, sink_of, root, rate=0.7)
    # the first break of the sea, and the sink
    t_up, t_down = 8.0, 144.0
    specks += crown(rng, 40, (HEAD_AT[0], 0.0, HEAD_AT[1]), 12.0, t_up, up=(2.0, 5.5), out=(0.4, 2.2), life=(16.0, 30.0))
    specks += crown(rng, 30, (HEAD_AT[0], 0.0, HEAD_AT[1]), 36.0, t_up + 6.0, up=(1.0, 3.8), out=(0.2, 1.4), life=(16.0, 30.0))
    # the snap: the jaws clap at the water's edge
    st_b = state(t_bite)
    tip = at(root(t_bite), np.array(H.BEAK_TIP) + np.array([0.0, 5.0 * st_b.lunge, -9.0 * st_b.lunge]))
    specks += burst(rng, 70, tip + np.array([0.0, -2.0, 0.0]), t_bite, speed=(2.0, 7.5), cone=1.1, axis=(0.0, -1.0, -0.2), life=(14.0, 30.0), size=(0.6, 1.9))
    specks += burst(rng, 30, tip, t_bite, speed=(1.0, 4.0), cone=1.4, axis=(0.0, -1.0, 0.0), life=(14.0, 30.0), size=(1.0, 2.4), alpha=0.6)
    specks += crown(rng, 36, (HEAD_AT[0], 0.0, HEAD_AT[1]), 36.0, t_down, up=(0.6, 2.4), out=(0.2, 1.2), life=(14.0, 26.0))
    events = [(HEAD_AT[0], HEAD_AT[1], t_up, 1.0, 14.0, 1.0, 60.0), (HEAD_AT[0], HEAD_AT[1], t_up + 9.0, 0.7, 24.0, 0.9, 60.0),
              (tip[0], tip[2], t_bite, 0.9, 5.0, 0.9, 42.0), (HEAD_AT[0], HEAD_AT[1], t_bite + 3.0, 0.6, 30.0, 0.8, 40.0),
              (HEAD_AT[0], HEAD_AT[1], t_down, 0.8, 30.0, 0.8, 44.0)]
    events += bubbles(rng, 140.0, 158.0, HEAD_AT, 30.0, 0.55, life=14.0, rate=0.6)
    specs = []
    for f in range(n):
        t = f * total / n
        st = state(t)
        M = root(t)
        spots = [(HEAD_AT[0], HEAD_AT[1], waterline_r(st, wobble(t)) + 1.5, 0.9 * smooth(0.0, 0.1, st.rise), 0.0)] + rings(events, t)
        specs.append(dict(cam=cam, sky='dusk', cog=ship_kw(t, ship_motion(t, gain=0.6, total=total), 0.95), actors=[hactor(st, M)], spots=spots, fx=fly(specks, t), sea_t=0.0, ring_t=t, bloom=1.0, murk=1.7))
    return specs


# ================================================================== the wrath

def head_wrath(world, n=110):
    """It rises, spews a cloud of ink (the siphon flares and pulses), flies into a rage (the brow drops, the pupil shrinks to a slit, the lights blaze, the
    roots and the fins thrash), lunges at the ship, is struck and dies: the eye clouds over, the lids droop, the roots go limp and the head sinks, bubbling. A
    loop (the sea is calm at both ends)."""
    total = n * TPF
    cam = CAM_HEAD
    yaw0 = face_yaw(cam)
    rng = np.random.default_rng(29)
    k_rise = [(0.0, 0.0), (3.0, 0.0), (26.0, 1.0), (128.0, 1.0), (162.0, 0.0), (total, 0.0)]
    k_lid = [(0.0, 0.0), (112.0, 0.0), (140.0, 0.0), (total, 0.0)]
    k_pupil = [(0.0, 0.3), (26.0, 0.3), (40.0, 0.7), (64.0, 0.2), (76.0, 0.06), (96.0, 0.06), (101.0, 1.0), (106.0, 0.9), (114.0, 0.5), (total, 0.5)]
    k_yaw = [(0.0, 0.0), (30.0, 0.0), (42.0, 30.0), (62.0, 30.0), (74.0, -24.0), (96.0, -24.0), (104.0, -24.0), (120.0, -8.0), (total, -8.0)]
    k_pitch = [(0.0, 0.0), (30.0, 0.0), (44.0, 8.0), (64.0, 8.0), (76.0, 0.0), (120.0, -14.0), (total, -14.0)]
    k_ink = [(0.0, 0.0), (36.0, 0.0), (42.0, 1.0), (66.0, 1.0), (72.0, 0.0), (total, 0.0)]
    k_rage = [(0.0, 0.0), (62.0, 0.0), (78.0, 1.0), (108.0, 1.0), (118.0, 0.3), (130.0, 0.0), (total, 0.0)]
    k_beak = [(0.0, 0.0), (80.0, 0.0), (84.0, 0.35), (92.0, 0.3), (96.0, 0.35), (99.0, 1.0), (104.0, 1.0), (107.0, 0.0), (118.0, 0.0), (total, 0.0)]
    k_lunge = [(0.0, 0.0), (90.0, 0.0), (101.0, 1.0), (104.0, 1.0), (116.0, 0.0), (total, 0.0)]
    k_dying = [(0.0, 0.0), (110.0, 0.0), (150.0, 1.0), (total, 1.0)]
    k_glow = [(0.0, 0.1), (24.0, 0.6), (62.0, 0.7), (80.0, 1.0), (108.0, 1.0), (126.0, 0.5), (156.0, 0.0), (total, 0.0)]
    t_lunge = 102.0

    def state(t):
        k = lambda keys: float(track(t, keys))
        gl = k(k_glow)
        return hstate(time=t + 30.0, rise=k(k_rise), lookYaw=k(k_yaw), lookPitch=k(k_pitch), pupil=k(k_pupil), lid=k(k_lid), beak=k(k_beak), lunge=k(k_lunge), ink=k(k_ink),
                      rage=k(k_rage), dying=k(k_dying), glow=gl * (0.9 + 0.1 * math.sin(0.23 * t)))

    def shake(t):
        """The head shudders in its rage and at the strike."""
        r = float(track(t, k_rage)) * (1.0 - float(track(t, k_dying)))
        return r * (1.4 * math.sin(1.9 * t) + 0.8 * math.sin(3.1 * t + 1.0))

    def turn_of(t):
        """How far it is turned (degrees, away from the ship above 0): it shows its flank to spew ink, then turns on the ship, and strikes."""
        return 40.0 * (smooth(26.0, 40.0, t) - smooth(66.0, 80.0, t)) - 14.0 * smooth(82.0, 94.0, t) * (1.0 - smooth(112.0, 128.0, t))

    def root(t):
        st = state(t)
        sway = 5.0 * math.sin(0.021 * t + 0.5) * smooth(14.0, 26.0, t) * (1.0 - float(track(t, k_dying)))
        return head_root(st, wobble(t) * clamp(st.rise * 3.0), yaw0, sway + turn_of(t) + 0.8 * shake(t))
    sink_of = lambda t: SINK * (1.0 - float(track(t, k_rise)))
    specks = streams(rng, 3.0, 56.0, sink_of, root, rate=1.5)
    specks += streams(rng, 130.0, 164.0, sink_of, root, rate=0.8)
    specks += crown(rng, 40, (HEAD_AT[0], 0.0, HEAD_AT[1]), 12.0, 5.0, up=(2.0, 5.5), out=(0.4, 2.2), life=(16.0, 30.0))
    specks += crown(rng, 30, (HEAD_AT[0], 0.0, HEAD_AT[1]), 36.0, 11.0, up=(1.0, 3.8), out=(0.2, 1.4), life=(16.0, 30.0))
    specks += ink_jets(rng, 40.0, 70.0, root, state)
    st_l = state(t_lunge)
    tip = at(root(t_lunge), np.array(H.BEAK_TIP) + np.array([0.0, 5.0 * st_l.lunge, -9.0 * st_l.lunge]))
    specks += burst(rng, 80, tip + np.array([0.0, -2.0, 0.0]), t_lunge, speed=(2.0, 8.0), cone=1.15, axis=(0.0, -1.0, -0.2), life=(14.0, 30.0), size=(0.6, 2.0))
    specks += crown(rng, 40, (HEAD_AT[0], 0.0, HEAD_AT[1]), 36.0, 160.0, up=(0.6, 2.4), out=(0.2, 1.2), life=(14.0, 26.0))
    events = [(HEAD_AT[0], HEAD_AT[1], 5.0, 1.0, 14.0, 1.0, 60.0), (HEAD_AT[0], HEAD_AT[1], 14.0, 0.7, 24.0, 0.9, 60.0)]
    for k in range(4):                                                           # the rage: the sea shivers about its mantle in widening rings
        events.append((HEAD_AT[0], HEAD_AT[1], 78.0 + 7.0 * k, 0.55, 34.0, 1.0, 30.0))
    events += [(tip[0], tip[2], t_lunge, 0.9, 5.0, 0.9, 42.0), (HEAD_AT[0], HEAD_AT[1], t_lunge + 2.0, 0.6, 30.0, 0.9, 40.0), (HEAD_AT[0], HEAD_AT[1], 160.0, 0.8, 30.0, 0.8, 40.0)]
    events += bubbles(rng, 126.0, 168.0, HEAD_AT, 34.0, 0.6, life=14.0, rate=0.8)
    kicks = [dict(t=t_lunge + 3.0, pitch=0.5, heel=-1.1, bob=-0.04, decay=9.0, hurt=7.0)]           # the wave of the lunge reaches her
    # the ink: clouds drifting from the siphon's mouth across the sea (drawn as dark discs; they thin out as they spread)
    mouth = at(root(50.0), SIPHON_MOUTH)
    out_dir = unit(root(50.0)[:3, :3] @ np.array(SIPHON_DIR))
    specs = []
    for f in range(n):
        t = f * total / n
        st = state(t)
        M = root(t)
        inks, fx = [], fly(specks, t)
        for c, (t0, seed, reach) in enumerate(((44.0, 4, 120.0), (52.0, 5, 112.0), (60.0, 6, 100.0))):
            p0 = (mouth[0] + 16.0 * c * out_dir[0], mouth[2] + 16.0 * c * out_dir[2])
            dr = (0.06 * out_dir[0], 0.06 * out_dir[2])
            inks += ink_pool(seed, p0, t0, t, drift=dr, reach=reach, n=18, life=110.0)
            fx += ink_cloud(seed + 10, (p0[0], 0.0, p0[1]), t0, t, reach=reach * 0.8, n=16, life=110.0, drift=dr)
        spots = [(HEAD_AT[0], HEAD_AT[1], waterline_r(st, wobble(t)) + 1.5, 0.9 * smooth(0.0, 0.1, st.rise), 0.0)] + rings(events, t)
        specs.append(dict(cam=cam, sky='dusk', cog=ship_kw(t, ship_motion(t, kicks, gain=0.6, total=total), 0.95), actors=[hactor(st, M)], spots=spots, fx=fx, inks=inks,
                          sea_t=0.0, ring_t=t, bloom=1.0, murk=1.7))
    return specs


# ================================================================== the face, close to

def head_face(world, n=80, sky='day'):
    """The face close to, by day (the dusk is too dark to see the small things by): the head comes up with its eye shut and water running off its brow, opens the eye (the pupil a
    narrow bar), looks about, blinks, the pupil swells and shrinks, the beak gapes and chatters, the brow drops in a rage and the lights blaze, it lunges at
    the lens, is struck, and dies: the pupil blows wide, the film clouds the eye, the lids droop, the roots go limp and it sinks under the sea. A loop."""
    total = n * TPF_SLOW                                                 # (a picture every 2 ticks: see `sequences`)
    yaw0 = face_yaw(CAM_HEAD, 0.1)
    rng = np.random.default_rng(37)
    st0 = hstate()
    M0 = head_root(st0, 0.0, yaw0)
    face = at(M0, np.array([0.0, -20.0, -38.0]))
    cam = dict(yaw=CAM_HEAD['yaw'], elev=7.0, scale=6.4, size=(400, 476), center=(float(face[0]), float(face[1]) + 2.0, float(face[2])))
    k_rise = [(0.0, 0.0), (2.0, 0.0), (16.0, 1.0), (126.0, 1.0), (156.0, 0.0), (total, 0.0)]
    k_lid = [(0.0, 1.0), (14.0, 1.0), (26.0, 0.0), (48.0, 0.0), (50.0, 1.0), (53.0, 0.0), (112.0, 0.0), (total, 0.0)]
    k_pupil = [(0.0, 0.1), (26.0, 0.1), (40.0, 0.45), (56.0, 0.45), (62.0, 0.04), (68.0, 0.04), (72.0, 0.95), (88.0, 0.6), (100.0, 0.06), (112.0, 0.08), (116.0, 1.0), (total, 0.3)]
    k_yaw = [(0.0, 0.0), (30.0, 0.0), (38.0, -42.0), (46.0, -42.0), (56.0, 38.0), (64.0, 38.0), (72.0, 0.0), (total, 0.0)]
    k_pitch = [(0.0, 0.0), (30.0, 0.0), (38.0, 12.0), (46.0, 12.0), (56.0, -8.0), (64.0, -8.0), (72.0, 0.0), (total, 0.0)]
    k_beak = [(0.0, 0.0), (74.0, 0.0), (82.0, 1.0), (90.0, 1.0), (94.0, 0.0), (98.0, 0.0), (106.0, 0.3), (112.0, 0.3), (115.0, 1.0), (122.0, 1.0), (126.0, 0.0), (total, 0.0)]
    k_lunge = [(0.0, 0.0), (108.0, 0.0), (116.0, 1.0), (120.0, 1.0), (136.0, 0.0), (total, 0.0)]
    k_rage = [(0.0, 0.0), (94.0, 0.0), (106.0, 1.0), (118.0, 1.0), (126.0, 0.0), (total, 0.0)]
    k_dying = [(0.0, 0.0), (120.0, 0.0), (146.0, 1.0), (total, 1.0)]
    k_glow = [(0.0, 0.1), (20.0, 0.3), (40.0, 0.7), (94.0, 0.7), (106.0, 1.0), (124.0, 1.0), (150.0, 0.0), (total, 0.0)]

    def state(t):
        k = lambda keys: float(track(t, keys))
        return hstate(time=t + 50.0, rise=k(k_rise), lookYaw=k(k_yaw), lookPitch=k(k_pitch), pupil=k(k_pupil), lid=k(k_lid), beak=k(k_beak), lunge=k(k_lunge),
                      rage=k(k_rage), dying=k(k_dying), glow=k(k_glow) * (0.92 + 0.08 * math.sin(0.23 * t)))

    def shake(t):
        r = float(track(t, k_rage)) * (1.0 - float(track(t, k_dying)))
        return r * (1.2 * math.sin(2.1 * t) + 0.7 * math.sin(3.3 * t + 1.0))

    def root(t):
        st = state(t)
        sway = 3.0 * math.sin(0.021 * t + 0.5) * smooth(14.0, 26.0, t) * (1.0 - float(track(t, k_dying)))
        return head_root(st, wobble(t) * clamp(st.rise * 3.0), yaw0, sway + shake(t))
    sink_of = lambda t: SINK * (1.0 - float(track(t, k_rise)))
    specks = streams(rng, 2.0, 40.0, sink_of, root, rate=1.6)
    specks += streams(rng, 120.0, 156.0, sink_of, root, rate=0.8)
    specks += crown(rng, 40, (HEAD_AT[0], 0.0, HEAD_AT[1]), 36.0, 6.0, up=(1.0, 3.8), out=(0.2, 1.4), life=(16.0, 30.0))
    t_snap = 116.0
    st_s = state(t_snap)
    tip = at(root(t_snap), np.array(H.BEAK_TIP) + np.array([0.0, 5.0 * st_s.lunge, -9.0 * st_s.lunge]))
    specks += burst(rng, 60, tip + np.array([0.0, -2.0, 0.0]), t_snap, speed=(1.5, 6.0), cone=1.2, axis=(0.0, -1.0, -0.3), life=(14.0, 28.0), size=(0.4, 1.2))
    specks += crown(rng, 40, (HEAD_AT[0], 0.0, HEAD_AT[1]), 36.0, 148.0, up=(0.6, 2.4), out=(0.2, 1.2), life=(14.0, 26.0))
    events = [(HEAD_AT[0], HEAD_AT[1], 6.0, 1.0, 14.0, 1.0, 60.0), (tip[0], tip[2], t_snap, 0.9, 5.0, 0.9, 42.0), (HEAD_AT[0], HEAD_AT[1], 150.0, 0.8, 30.0, 0.8, 40.0)]
    events += bubbles(rng, 136.0, 158.0, HEAD_AT, 30.0, 0.6, life=14.0, rate=0.7)
    specs = []
    for f in range(n):
        t = f * total / n
        st = state(t)
        M = root(t)
        spots = [(HEAD_AT[0], HEAD_AT[1], waterline_r(st, wobble(t)) + 1.5, 0.9 * smooth(0.0, 0.1, st.rise), 0.0)] + rings(events, t)
        specs.append(dict(cam=cam, sky=sky, cog=None, actors=[hactor(st, M)], spots=spots, fx=fly(specks, t), sea_t=0.0, ring_t=t, bloom=1.0, murk=1.7))
    return specs
