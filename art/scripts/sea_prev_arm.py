"""The Maelgrasp's arm, previewed: short films of one arm beside the cog, driven by the Python port of `Tentacle.kt` (the joints the game would give
the model) and posed with `sea_arm.pose`, so what they show is what `MaelgraspArmModel.setupAnim` does.

    arm_attack(world): the arm rises out of the deep beside the cog, sways and watches the ship, rears back, slams across the deck, drags along it
        and seizes a stand-in player, lifts him, shakes him and flings him into the sea, and sinks again. A loop (the sea is calm at both ends).
    arm_hold(world): the arm rises, drapes itself over the starboard rail and closes its coil on the chest beside the hatch, hauls the ship over (she
        heels, the lanterns swing, the water slaps her side), a second stand-in runs across the deck and hacks it through: the length that held her
        drops on the planks, writhes and withers into spores, the stump whips back spraying ichor, flares and sinks. A loop.

Nothing here is the game: the game decides where its arms reach and when; these films invent a scene that shows the model off (and use
`Tentacle.grip` for where a seized player hangs, as the game does). Scene space is the cog's model space (px, y down, the sea at y = 0, the cog on
the x = 0 plane facing -z, her starboard -x); the arm's own frame is blocks, y up, +z toward the ship, x its right (`ArmRig` converts).
"""
import math

import numpy as np

import ship
import sea_kit as K
import sea_arm as A
import sea_tentacle as T
import sea_previews as P
from sea_previews import (TPF, GRAVITY, at, burst, clamp, fly, inv_at, rings, smooth, unit, yaw_of)

DECK = -12.0               # the cog's main deck, px above the waterline (y is down)
RAIL = -22.0               # the top of the starboard rail
HATCH = (0.0, DECK, 8.0)   # the hatch grating amidships, where the arm slams
ROOT = (-78.0, 0.0, 4.0)   # where the arm breaks the surface: about three blocks off the starboard side

EASES = {
    'io': lambda u: u * u * (3.0 - 2.0 * u),
    'in': lambda u: u ** 2.4,
    'out': lambda u: 1.0 - (1.0 - u) ** 2.4,
    'lin': lambda u: u,
    'snap': lambda u: u ** 5.0,
    'chop': lambda u: u ** 1.35,
    'spring': lambda u: 1.0 - math.exp(-6.0 * u) * math.cos(9.0 * u),
}
CHANNELS = ('aim', 'head', 'curl', 'rip', 'grip', 'rage', 'glow', 'sway', 'swf', 'hold')
BASE = dict(head=(0.0, -1.0, 0.0), curl=0.3, rip=0.15, grip=0.0, rage=0.0, glow=0.0, sway=0.0, swf=0.09, hold=0.0)


# ================================================================== the rig: one arm, its place, the conversions

class Rig:
    """An arm standing in the sea at (x, z), its front (the arm frame's +z, the side its suckers are on) turned toward [toward] (a direction, x z):
    the conversions between the scene and the arm's own frame (blocks, y up) that `Tentacle.solve` works in, the joints for a tip at a scene point,
    and the actor."""

    def __init__(self, x, z, toward=(1.0, 0.0)):
        self.M = K.place(x, 0.0, z, yaw=yaw_of(*toward))
        self.R = self.M[:3, :3]
        self.root = np.array([x, 0.0, z])

    def to_arm(self, p):
        """A scene point (px) in the arm's frame (blocks, y up)."""
        return -inv_at(self.M, p) / 16.0

    def dir_to_arm(self, d):
        return -(self.R.T @ np.asarray(d, float))

    def to_scene(self, p):
        """A point of the arm's frame (blocks, y up) in the scene (px)."""
        return at(self.M, -16.0 * np.asarray(p, float))

    def scene_joints(self, joints):
        """The joints (arm frame) as (13, 3) scene points."""
        j = np.asarray(joints, float).reshape(-1, 3)
        return np.array([self.to_scene(p) for p in j])

    def joints_of(self, pts):
        """The arm-frame joint list (39 floats) of (13, 3) scene points."""
        return [float(v) for p in pts for v in self.to_arm(p)]

    def grip_at(self, joints):
        """Where `Tentacle.grip` puts what the arm holds, in the scene."""
        return self.to_scene(T.grip(list(joints)))

    def reach(self, p, heading, curl=0.3, ripple=0.2, time=0.0, phase=0.0):
        """The joints for a tip at the scene point [p] travelling along the scene direction [heading] as it arrives (`Tentacle.solve` takes the
        direction it arrives from, the other way about)."""
        a = self.to_arm(p)
        f = self.dir_to_arm(-np.asarray(heading, float))
        return T.solve(a[0], a[1], a[2], f[0], f[1], f[2], curl, ripple, time, phase)

    def actor(self, joints, t, grip=0.0, glow=0.6, rage=0.0, cut=A.SEGS, start=0):
        st = A.ArmState(joints=joints, cut=cut, start=start, time=t, grip=grip, glow=glow, rage=rage)
        return dict(model='arm', pose=A.pose(st), root=self.M, glow=glow)


# ================================================================== keys: eased channels

def fill(keys):
    """The key list with every channel carried forward from the key before (a key names only what changes; the first starts from BASE)."""
    out, prev = [], dict(BASE)
    for k in keys:
        d = dict(prev)
        d.update(k)
        out.append(d)
        prev = d
    return out


def sample(keys, t, ship_m):
    """The channels at tick [t]: the aim (a point of the world, or a point of the ship carried through [ship_m]), the heading it arrives along, curl,
    ripple, grip, rage, glow, sway. Each interval eases by the ease its closing key names ('io' if none)."""
    if t <= keys[0]['t']:
        a = b = keys[0]
        e = 0.0
    elif t >= keys[-1]['t']:
        a = b = keys[-1]
        e = 0.0
    else:
        i = max(j for j, k in enumerate(keys) if k['t'] <= t)
        a, b = keys[i], keys[i + 1]
        e = EASES[b.get('ease', 'io')]((t - a['t']) / (b['t'] - a['t']))

    def pt(k):
        frame, p = k['aim']
        p = np.asarray(p, float)
        return at(ship_m, p) if frame == 's' else p
    out = dict(aim=pt(a) + (pt(b) - pt(a)) * e,
               head=unit(np.asarray(a['head'], float) * (1.0 - e) + np.asarray(b['head'], float) * e))
    for c in CHANNELS[2:]:
        out[c] = a[c] + (b[c] - a[c]) * e
    for c in ('curl', 'grip', 'rage', 'glow', 'hold'):
        out[c] = clamp(out[c])
    return out


# ================================================================== the ship's answer: the swell, kicks, and a haul

def ship_motion(t, kicks=(), haul=None, gain=0.45, total=None):
    """The cog's lean and bob at tick [t]: the swell (fading to nothing at both ends of a clip of [total] ticks), plus a damped shudder for each kick
    ({t, pitch, heel, bob, decay, w, hurt}), plus [haul] (a function of t: degrees of heel the arm drags her over by, or that and how far it pulls
    her down). Returns (pitch, heel, bob, hurtTime)."""
    if total is not None:                                   # (calm at both ends, so a loop closes without a lurch)
        gain *= smooth(0.0, 14.0, t) * (1.0 - smooth(total - 14.0, total, t))
    pitch, roll, bob = ship.swell(t, 0.0, gain)
    hurt = 0.0
    for k in kicks:
        a = t - k['t']
        if a < 0.0:
            continue
        env = math.exp(-a / k.get('decay', 8.0))
        w = k.get('w', 0.55)
        pitch += k.get('pitch', 0.0) * env * math.sin(w * a)
        roll += k.get('heel', 0.0) * env * math.sin(w * a + k.get('phase', 0.0))
        bob += k.get('bob', 0.0) * env * math.cos(w * a)
        hurt = max(hurt, 10.0 * max(0.0, 1.0 - a / k.get('hurt', 10.0)))
    if haul is not None:
        h = haul(t)
        if isinstance(h, tuple):
            roll += h[0]
            bob += h[1]
        else:
            roll += h
    return pitch, roll, bob, hurt


def ship_kw(t, motion, lanterns=0.9):
    pitch, heel, bob, hurt = motion
    return dict(sail=0.0, fill=0.0, brace=0.0, wind=0.3, time=t, speed=0.0, lanterns=lanterns, pitch=pitch, heel=heel, bob=bob, hurtTime=hurt, hurtDir=1)


def ship_matrix(motion):
    pitch, heel, bob, _ = motion
    return ship.tilt_root(pitch, heel, bob)


# ================================================================== small tools: keys, frames, a stand-in held and falling

def S(x, y, z):
    """A point of the ship (carried with her as she leans)."""
    return ('s', (float(x), float(y), float(z)))


def W(x, y, z):
    """A point of the world."""
    return ('w', (float(x), float(y), float(z)))


WAIST = np.array([0.0, -12.0, 0.0])        # the stand-in's waist, in his own frame


def nearest_rotation(R):
    u, _, vt = np.linalg.svd(R)
    r = u @ vt
    if np.linalg.det(r) < 0.0:
        u[:, -1] *= -1.0
        r = u @ vt
    return r


def rot_of(head, front):
    """The rotation of a stand-in whose head points along [head] and whose front is as near [front] as it can be."""
    h = unit(head)
    f = np.asarray(front, float)
    f = unit(f - h * float(f @ h))
    y_l, z_l = -h, -f
    return np.stack([np.cross(y_l, z_l), y_l, z_l], 1)


def blend_rot(a, b, u):
    return nearest_rotation(a * (1.0 - u) + b * u)


def frame(R, pos, anchor=WAIST):
    """The 4x4 that turns a model by [R] and puts its point [anchor] at [pos]."""
    M = np.eye(4)
    M[:3, :3] = R
    M[:3, 3] = np.asarray(pos, float) - R @ np.asarray(anchor, float)
    return M


def spin_about(axis, angle):
    """The rotation of [angle] radians about [axis]."""
    a = unit(axis)
    c, s = math.cos(angle), math.sin(angle)
    x, y, z = a
    return np.array([[c + x * x * (1 - c), x * y * (1 - c) - z * s, x * z * (1 - c) + y * s],
                     [y * x * (1 - c) + z * s, c + y * y * (1 - c), y * z * (1 - c) - x * s],
                     [z * x * (1 - c) - y * s, z * y * (1 - c) + x * s, c + z * z * (1 - c)]])


def crown(rng, n, centre, radius, t0, up=(2.0, 5.0), out=(0.4, 2.2), life=(14.0, 28.0), size=(0.6, 1.8), colour=(232, 242, 250), alpha=0.9, floor=0.0):
    """A crown of [n] drops thrown up from a ring of [radius] px round [centre] at tick [t0]: the spray where something breaks the water."""
    specks = []
    for _ in range(n):
        a = rng.uniform(0.0, 2.0 * math.pi)
        d = np.array([math.cos(a), 0.0, math.sin(a)])
        v = d * rng.uniform(*out) + np.array([0.0, -rng.uniform(*up), 0.0])
        specks.append(dict(p=np.asarray(centre, float) + d * radius * rng.uniform(0.7, 1.1), v=v, t0=t0 + rng.uniform(0.0, 1.2), life=rng.uniform(*life),
                           r=rng.uniform(*size), col=colour, a=alpha, g=GRAVITY, floor=floor))
    return specks


def adrift(rng, n, pts, t0, t1, life=(30.0, 55.0), size=(0.5, 1.0), colour=(196, 226, 236), alpha=0.85):
    """Drops that fall from the arm as it comes out of the sea: [n] of them between ticks [t0] and [t1], each starting on the arm at that moment
    (`pts(t)` gives the arm's points above the water, an (m, 3) array) with a little sideways drift."""
    specks = []
    for _ in range(n):
        t = rng.uniform(t0, t1)
        q = pts(t)
        if len(q) == 0:
            continue
        p = q[int(rng.integers(0, len(q)))] + rng.normal(0.0, 1.2, 3) * np.array([1.0, 0.2, 1.0])
        specks.append(dict(p=p, v=np.array([rng.normal(0.0, 0.12), rng.uniform(-0.1, 0.3), rng.normal(0.0, 0.12)]), t0=t, life=rng.uniform(*life),
                           r=rng.uniform(*size), col=colour, a=alpha, g=GRAVITY, floor=0.0))
    return specks


def float_up(p0, v0, t0, t, y_float=-3.0, g=GRAVITY, dt=0.25):
    """Where a body thrown from [p0] with [v0] at tick [t0] is at tick [t]: falling through the air, then in the sea (slowed, pushed back up toward
    the surface and settling there, what a swimmer does). Returns (position, velocity, tick it hit the water or None)."""
    p, v = np.array(p0, float), np.array(v0, float)
    now = t0
    hit = None
    while now < t - 1e-9:
        h = min(dt, t - now)
        if p[1] < 0.0:
            v[1] += g * h
        else:
            if hit is None:
                hit = now
            v[1] += (-0.03 * (p[1] - y_float) - 0.45 * v[1]) * h
            v[0] *= math.exp(-0.22 * h)
            v[2] *= math.exp(-0.22 * h)
        p = p + v * h
        now += h
    return p, v, hit


# ================================================================== pass one: what the arm and the ship do, tick by tick

def arm_frames(rig, keys, total, n, kicks=(), haul=None, phase=0.7, solve_time=1.0):
    """For each of the [n] pictures (ticks 0 .. [total]) the ship's motion, the arm's channels and joints (arm frame), its joints and the point it holds
    in the scene. An arm that holds (the `hold` channel) is steered so the middle of its coil, where `Tentacle.grip` puts what it holds, is on the aim."""
    out = []
    for f in range(n):
        t = f * total / n
        mot = ship_motion(t, kicks, haul, total=total)
        sm = ship_matrix(mot)
        ch = sample(keys, t, sm)
        sw = ch['sway'] * np.array([math.sin(ch['swf'] * t), 0.7 * math.sin(ch['swf'] * 1.31 * t + 1.0), math.sin(ch['swf'] * 0.83 * t + 2.0)])
        goal = ch['aim'] + sw
        a = goal.copy()
        joints = rig.reach(a, ch['head'], ch['curl'], ch['rip'], t * solve_time, phase)
        if ch['hold'] > 0.01:
            for _ in range(4):
                a = a + ch['hold'] * (goal - rig.grip_at(joints))
                joints = rig.reach(a, ch['head'], ch['curl'], ch['rip'], t * solve_time, phase)
        sj = rig.scene_joints(joints)
        out.append(dict(t=t, mot=mot, sm=sm, ch=ch, joints=joints, sj=sj, grip=rig.grip_at(joints)))
    return out


def above_water(frame_):
    """The arm's joints (and the points between them) that are above the sea, (m, 3)."""
    sj = frame_['sj']
    pts = []
    for a, b in zip(sj, sj[1:]):
        for u in (0.0, 0.5):
            p = a + (b - a) * u
            if p[1] < -3.0:
                pts.append(p)
    return np.array(pts) if pts else np.zeros((0, 3))


def presence(frame_):
    """How much of the arm is out of the sea, 0..1 (the foam on the water round its root follows it)."""
    top = -float(frame_['sj'][:, 1].min())
    return smooth(0.0, 24.0, top)


# ================================================================== the attack

CAM_ARM = dict(yaw=32.0, elev=31.0, scale=2.75, size=(440, 520), center=(-28.0, -48.0, 0.0))
CAM_HOLD = dict(yaw=32.0, elev=31.0, scale=2.6, size=(440, 520), center=(-31.0, -52.0, 14.0))


def arm_attack(world, n=None):
    """The arm rises out of the deep beside the cog, sways and watches her, rears back and slams across the deck, drags along it and seizes a
    stand-in player at the waist (the coil of its tip closing round him), lifts him, shakes him and flings him into the sea, and sinks again. The
    ship answers the slam, and the lanterns swing. A loop (the sea is calm at both ends); the stand-in begins again on the deck."""
    total = 186.0
    n = n or int(round(total / TPF))
    rig = Rig(ROOT[0], ROOT[2], (1.0, 0.0))
    rng = np.random.default_rng(31)
    t_slam, t_grab, t_fling = 69.5, 92.0, 133.0
    pa = np.array([8.0, DECK, 6.0])
    keys = fill([
        dict(t=0.0, aim=W(-78, 190, 4)),
        dict(t=8.0, aim=W(-78, 150, 4)),
        dict(t=32.0, aim=W(-78, -112, 4), head=(0.2, -1, 0), glow=0.5, curl=0.3, rip=0.35),
        dict(t=44.0, aim=W(-66, -92, 10), head=(0.5, -1, 0.1), curl=0.55, rip=0.6, sway=4.0),
        dict(t=54.0, aim=W(-52, -80, 12), head=(0.8, -1, 0.1), curl=0.45, glow=0.6),
        dict(t=62.0, aim=W(-106, -98, 14), head=(-0.6, -1, 0), curl=0.5, rip=0.3, rage=0.35, glow=0.8, sway=2.0),
        dict(t=65.0, aim=W(-110, -102, 14), curl=0.6, ease='lin'),
        dict(t=t_slam, aim=S(-5, DECK - 1, 9), head=(0.35, 1, 0), curl=0.35, rip=0.1, glow=1.0, rage=0.55, sway=0.0, ease='in'),
        dict(t=76.0, aim=S(-5, DECK - 1, 9), glow=0.8, ease='lin'),
        dict(t=t_grab - 4.0, aim=S(3, DECK - 3, 7), head=(1, 0.2, 0), curl=0.85, rip=0.2, ease='io'),
        dict(t=t_grab + 2.5, aim=S(pa[0], DECK - 12, pa[2]), hold=1.0, curl=1.0, grip=1.0, ease='out'),
        dict(t=t_grab + 9.0, aim=S(pa[0], DECK - 14, pa[2]), ease='lin'),
        dict(t=106.0, aim=W(-50, -62, 0), ease='io'),
        dict(t=122.0, aim=W(-50, -66, 0), sway=7.0, swf=0.75, ease='lin'),
        dict(t=t_fling - 5.0, aim=W(-44, -76, 8), sway=0.0, ease='io'),
        dict(t=t_fling, aim=W(-84, -63, -2), ease='in'),
        dict(t=t_fling + 0.1, aim=W(-84.5, -62.8, -2.5), hold=0.0, grip=0.0, curl=0.5, ease='lin'),
        dict(t=t_fling + 5.0, aim=W(-104, -50, -24), ease='out'),
        dict(t=t_fling + 13.0, aim=W(-104, -60, -30), curl=0.7, glow=0.5, rage=0.2, ease='out'),
        dict(t=150.0, aim=W(-90, -84, 6), head=(0.2, -1, 0), curl=0.45, glow=0.3, rage=0.0, sway=2.0, ease='io'),
        dict(t=170.0, aim=W(-78, 170, 4), head=(0, -1, 0), curl=0.3, glow=0.0, sway=0.0, ease='in'),
        dict(t=total, aim=W(-78, 190, 4)),
    ])
    kicks = [dict(t=t_slam, pitch=-0.9, heel=-1.5, bob=-0.07, decay=9.0, hurt=12.0),
             dict(t=t_fling, pitch=0.3, heel=0.6, bob=-0.02, decay=7.0, hurt=5.0)]
    fr = arm_frames(rig, keys, total, n, kicks)
    ts = np.array([f['t'] for f in fr])
    grips = np.array([f['grip'] for f in fr])

    # ---- the stand-in
    yaw_a = K.facing(-1.0, 0.1)
    head_up = lambda f: clamp((-float(f['sj'][:, 1].min()) - 30.0) / 130.0)

    def held_matrix(f):
        sj = f['sj']
        nrm = np.cross(sj[10] - sj[8], sj[12] - sj[8])
        nrm = unit(nrm) if np.linalg.norm(nrm) > 1e-6 else np.array([0.0, 0.0, -1.0])
        if nrm[2] > 0.0:
            nrm = -nrm
        return rot_of(nrm, (0.0, 1.0, 0.0))

    # the arm's tip speed when it lets go
    k_rel = int(np.searchsorted(ts, t_fling))
    k0, k1 = max(k_rel - 1, 0), min(k_rel + 1, n - 1)
    v_rel = 0.4 * (grips[k1] - grips[k0]) / (ts[k1] - ts[k0]) * np.array([0.25, 1.0, 0.5])
    p_rel = grips[k_rel]
    print('   release', np.round(p_rel, 1), np.round(v_rel, 2))

    def stand_in(f):
        t = f['t']
        sm = f['sm']
        sway = 0.5 * math.sin(t * 0.21)
        if t < t_grab - 1.0:
            fear = smooth(30.0, 58.0, t)
            hop_t = t - t_slam
            hop = 5.0 * math.sin(math.pi * clamp(hop_t / 9.0)) if 0.0 <= hop_t <= 9.0 else 0.0
            back = 3.0 * smooth(0.0, 6.0, hop_t)
            arms = 0.32 * fear * (0.85 + 0.15 * math.sin(t * 0.9)) + 0.5 * smooth(-1.0, 2.0, hop_t) * (1.0 - smooth(8.0, 18.0, hop_t))
            nod = -0.42 * head_up(f) * fear
            pp = P.player_pose(arms=arms, swing=0.12 * math.sin(t * 0.8), nod=nod, spread=0.22 * fear)
            pos = pa + np.array([0.0, -hop, back + sway])
            Ms = sm @ K.place(pos[0], pos[1], pos[2], yaw=yaw_a)
            return pp, Ms
        # seized
        Rs = sm[:3, :3] @ K.place(0, 0, 0, yaw=yaw_a)[:3, :3]
        stand_waist = at(sm, pa + np.array([0.0, -12.0 - 0.0, 3.0]))
        if t < t_fling:
            u = smooth(t_grab - 1.0, t_grab + 5.0, t)
            Rh = held_matrix(f)
            R = blend_rot(Rs, Rh, u)
            waist = stand_waist + (f['grip'] - stand_waist) * smooth(t_grab - 1.0, t_grab + 4.0, t)
            panic = math.sin(t * 1.5)
            pp = P.player_pose(arms=0.5 + 0.2 * (1.0 - u) + 0.1 * panic * u, swing=0.5 * panic * u, kick=0.35 * math.sin(t * 1.25 + 1.0) * u, hang=-1.2 * u, nod=0.3 * u,
                               spread=0.25 * u + 0.15 * math.sin(t * 1.1) * u)
            return pp, frame(R, waist)
        # flung: a body thrown, tumbling, in the sea
        tau = t - t_fling
        p, v, hit = float_up(p_rel, v_rel, t_fling, t)
        Rh = held_matrix(fr[min(k_rel, n - 1)])
        axis = np.cross(unit(v_rel), np.array([0.0, -1.0, 0.0])) if np.linalg.norm(v_rel) > 1e-3 else np.array([1.0, 0.0, 0.0])
        up_R = sm[:3, :3] @ K.place(0, 0, 0, yaw=yaw_a)[:3, :3]
        spin = spin_about(axis, 0.22 * min(tau, 20.0))
        R = spin @ Rh
        if hit is not None:
            R = blend_rot(R, up_R, smooth(hit, hit + 14.0, t))
        flail = math.sin(t * 1.8)
        pp = P.player_pose(arms=0.75 + 0.15 * flail, swing=0.7 * flail, kick=0.6 * math.sin(t * 1.5), hang=-0.4, nod=0.1, spread=0.6)
        return pp, frame(R, p)

    # ---- the sea, the spray, the splinters
    present = [presence(f) for f in fr]
    t_emerge = next((f['t'] for f, pr in zip(fr, present) if pr > 0.02), 8.0)
    _, _, t_hit = float_up(p_rel, v_rel, t_fling, total)
    p_hit = float_up(p_rel, v_rel, t_fling, t_hit)[0] if t_hit is not None else p_rel
    near = lambda t: fr[min(range(n), key=lambda k: abs(fr[k]['t'] - t))]
    specks = crown(rng, 46, ROOT, 10.0, t_emerge, up=(2.0, 5.5), out=(0.5, 2.0), life=(16.0, 30.0))
    specks += adrift(rng, 70, lambda t: above_water(near(t)), t_emerge, t_emerge + 34.0)
    ps = at(fr[min(range(n), key=lambda k: abs(fr[k]['t'] - t_slam))]['sm'], (-5.0, DECK, 9.0))
    specks += burst(rng, 34, ps, t_slam, speed=(1.2, 5.2), cone=1.25, axis=(0.1, -1.0, 0.0), life=(10.0, 24.0), size=(0.7, 1.8), colour=(184, 140, 90), alpha=1.0, floor=DECK + 0.5)
    specks += burst(rng, 26, ps, t_slam, speed=(0.6, 2.6), cone=1.4, axis=(0.0, -1.0, 0.0), life=(14.0, 30.0), size=(1.4, 3.0), colour=(214, 196, 166), alpha=0.5, floor=DECK + 0.5)
    if t_hit is not None:
        specks += crown(rng, 80, p_hit, 3.0, t_hit, up=(2.5, 7.0), out=(0.6, 3.2), life=(14.0, 30.0), size=(0.7, 2.2))
    t_sink = 163.0
    specks += crown(rng, 40, ROOT, 10.0, t_sink, up=(1.0, 3.0), out=(0.4, 1.6), life=(14.0, 26.0))
    events = [(ROOT[0], ROOT[2], t_emerge, 0.9, 8.0, 0.9, 50.0), (ROOT[0], ROOT[2], t_slam, 0.4, 8.0, 0.7, 36.0), (ROOT[0], ROOT[2], t_fling, 0.7, 8.0, 0.9, 40.0),
              (ROOT[0], ROOT[2], t_sink, 0.9, 8.0, 0.9, 40.0)]
    if t_hit is not None:
        events.append((p_hit[0], p_hit[2], t_hit, 1.0, 5.0, 0.9, 50.0))
    specs = []
    for f, pr in zip(fr, present):
        t = f['t']
        ch = f['ch']
        pp, Mp = stand_in(f)
        actors = [rig.actor(f['joints'], t, ch['grip'], ch['glow'], ch['rage']), dict(model='player', pose=pp, root=Mp)]
        spots = [(ROOT[0], ROOT[2], 12.0 + 1.2 * math.sin(t * 0.3), 0.85 * pr, 0.0)] + rings(events, t)
        specs.append(dict(cam=CAM_ARM, sky='dusk', cog=ship_kw(t, f['mot']), actors=actors, spots=spots, fx=fly(specks, t), sea_t=0.0, ring_t=t, bloom=1.0, murk=1.7))
    return specs


# ================================================================== a length of arm cut loose: a limp chain falling, lying and writhing

BARRELS = ((-21.0, 15.5), (-21.2, 25.0))                      # the two barrels along the starboard rail: x, z (about 4.3 px across, their tops at y -23)
WALL_X = -26.5                                                # the inside of the starboard bulwark (its top is at y -22)


def landing(x, z):
    """The height (y, down) of what a thing dropped on the main deck at (x, z) comes to rest on: the top of a barrel, or the planks."""
    for bx, bz in BARRELS:
        if (x - bx) ** 2 + (z - bz) ** 2 <= 4.3 ** 2:
            return -23.0
    return DECK


def simulate_chain(p0, v0, t0, times, lengths, radii, spin=0.0, g=GRAVITY, dt=0.25, push=None):
    """The points of a loose length of arm at each tick of [times] (ascending, from [t0] on), in the cog's own frame: [p0] ((n, 3) px) thrown with
    [v0] and turning at [spin] rad/tick about z, a chain of links of [lengths] under gravity that comes to rest on the main deck (`landing`; each
    point is held up by its [radii]), is kept inside the bulwark, and is moved by [push](tick, n) -> (n, 3) accelerations (its death throes).
    Returns {tick: points}."""
    P = np.array(p0, float)
    n = len(P)
    c = P.mean(0)
    V = np.tile(np.asarray(v0, float), (n, 1))
    V += np.cross(np.array([0.0, 0.0, spin]), P - c)
    prev = P - V * dt
    out = {}
    now = t0
    pending = list(times)
    while pending:
        while pending and pending[0] <= now + 1e-9:
            out[pending.pop(0)] = P.copy()
        if not pending:
            break
        acc = np.zeros_like(P)
        acc[:, 1] = g
        if push is not None:
            acc += push(now, n)
        new = P + (P - prev) * (1.0 - 0.015 * dt) + acc * dt * dt
        prev, P = P, new
        for _ in range(14):
            for i, L in enumerate(lengths):
                d = P[i + 1] - P[i]
                dist = max(float(np.linalg.norm(d)), 1e-6)
                corr = d * (0.5 * (dist - L) / dist)
                P[i] += corr
                P[i + 1] -= corr
            for i in range(n):
                floor = landing(P[i, 0], P[i, 2]) - radii[i]
                if P[i, 1] > floor:
                    P[i, 1] = floor
                if P[i, 0] < WALL_X and P[i, 1] > RAIL - radii[i]:
                    P[i, 0] = WALL_X
        for i in range(n):                       # what lies on the planks drags: it keeps little of its sliding, and does not bounce
            if P[i, 1] >= landing(P[i, 0], P[i, 2]) - radii[i] - 1e-3:
                prev[i, 0] = P[i, 0] - (P[i, 0] - prev[i, 0]) * 0.55
                prev[i, 2] = P[i, 2] - (P[i, 2] - prev[i, 2]) * 0.55
                prev[i, 1] = P[i, 1]
        now += dt
    return out


def sword_tip(M, rot, length=25.5):
    """The tip of the stand-in's sword (matrix [M], right arm turned by [rot], radians about x, y, z) in the scene."""
    return at(M, np.array([-6.0, -22.0, 0.0]) + ship.rotm_rad(*rot) @ np.array([0.0, length, 0.0]))


def slash(tip_of, t, span=3.4, step=0.35, size=1.5, colour=(255, 244, 214), strength=1.0):
    """The bright trail of a blade as discs for `draw`: the tip's places over the last [span] ticks, [tip_of](tick) giving each."""
    out = []
    s = t - span
    while s <= t + 1e-9:
        p = tip_of(s)
        k = max(0.0, 1.0 - (t - s) / span) ** 1.6
        out.append((p[0], p[1], p[2], size * (0.5 + 0.5 * k), colour, strength * k * 0.9, 0.6))
        s += step
    return out


def turn(a, b, u):
    """The angle [a] turned toward [b] (degrees, the short way) by [u]."""
    return a + ((b - a + 180.0) % 360.0 - 180.0) * u


# ================================================================== the hold

CASTLE = -30.0             # the stern castle's deck, where the stand-in with the sword stands (its front edge is at z 36)


def arm_hold(world, n=None):
    """The arm rises beside the cog, sways and leans over her, drapes itself across the starboard rail and closes its coil on the hatch, and
    hauls: she heels over to it, her lanterns swing and the water slaps along her side. A stand-in with a sword (the red shirt) runs along the
    front of the stern castle and cuts across it with a sweep of the blade, at the top of its arch: the length that held her drops on the planks,
    writhes and withers into spores, and the stump whips back across the sea spraying ichor, flares and sinks. She rights herself. A loop (the
    stand-in walks back to where he began)."""
    n = n or 100
    total = n * TPF
    pz = 25.0                                              # the plane the arm works in: the hatch's aft half
    rig = Rig(ROOT[0], pz, (1.0, 0.0))
    rng = np.random.default_rng(47)
    t_cut = round(92.0 / TPF) * TPF
    hook = S(-6.0, -28.0, pz + 1.5)
    cutj = 6
    keys = fill([
        dict(t=0.0, aim=W(-78, 190, pz)),
        dict(t=6.0, aim=W(-78, 150, pz)),
        dict(t=26.0, aim=W(-78, -112, pz), head=(0.2, -1, 0), glow=0.5, curl=0.3, rip=0.35),
        dict(t=38.0, aim=W(-66, -96, pz + 4), head=(0.5, -1, 0.1), curl=0.55, rip=0.5, sway=3.0),
        dict(t=47.0, aim=S(-40, -88, pz + 1.5), head=(1, 0.0, 0), curl=0.5, glow=0.7, sway=2.0),
        dict(t=58.0, aim=hook, head=(1, 0.0, 0), curl=0.65, hold=1.0, sway=0.0, ease='io'),
        dict(t=63.0, aim=hook, grip=1.0, rage=0.3, ease='out'),
        dict(t=72.0, aim=hook, glow=0.9, rage=0.55, rip=0.45, ease='lin'),
        dict(t=t_cut - 4.0, aim=hook, rage=0.7, rip=0.15, ease='lin'),
        dict(t=t_cut, aim=hook, ease='lin'),
        dict(t=t_cut + 0.2, aim=W(-44, -64, pz + 2), head=(-0.4, -1, 0), curl=0.35, rip=0.7, grip=0.0, hold=0.0, glow=1.0, rage=1.0, sway=0.0, ease='lin'),
        dict(t=t_cut + 3.5, aim=W(-118, -110, pz + 14), head=(-0.8, -1, 0), curl=0.6, rip=0.9, sway=10.0, swf=0.8, ease='out'),
        dict(t=t_cut + 8.5, aim=W(-70, -80, pz - 8), head=(0.3, -1, 0.3), curl=0.9, rip=1.0, sway=26.0, swf=0.8, ease='io'),
        dict(t=t_cut + 14.0, aim=W(-100, -100, pz + 10), head=(-0.6, -1, -0.2), curl=0.7, rip=1.0, sway=34.0, swf=0.62, ease='io'),
        dict(t=t_cut + 22.0, aim=W(-70, -86, pz - 2), head=(0.2, -1, 0.2), curl=0.9, rip=0.9, glow=0.8, rage=0.9, sway=30.0, swf=0.7, ease='io'),
        dict(t=t_cut + 32.0, aim=W(-86, -66, pz + 4), head=(-0.3, -1, 0), curl=0.6, rip=0.7, glow=0.5, rage=0.6, sway=18.0, swf=0.6, ease='io'),
        dict(t=t_cut + 46.0, aim=W(-78, 180, pz), head=(0, -1, 0), curl=0.3, rip=0.4, glow=0.2, rage=0.3, sway=0.0, ease='in'),
        dict(t=total, aim=W(-78, 190, pz), glow=0.0, rage=0.0),
    ])
    # the cog heels toward the arm (negative: her starboard side goes down) while it hauls, and rights herself with a roll when the arm lets go
    haul = lambda t: ((-7.0 * smooth(60.0, 84.0, t) - 0.4 * math.sin(0.85 * t) * smooth(62.0, 72.0, t), -0.2 * smooth(60.0, 84.0, t))) if t < t_cut else (0.0, 0.0)
    kicks = [dict(t=58.0, pitch=-0.3, heel=-0.8, bob=-0.03, decay=8.0, hurt=8.0),
             dict(t=70.0, pitch=0.3, heel=0.5, bob=-0.02, decay=7.0, hurt=5.0),
             dict(t=81.0, pitch=-0.3, heel=-0.6, bob=-0.03, decay=7.0, hurt=6.0),
             dict(t=t_cut, pitch=0.9, heel=-7.0, bob=-0.2, decay=9.0, phase=math.pi / 2.0, hurt=10.0, w=0.45)]
    fr = arm_frames(rig, keys, total, n, kicks, haul)
    near = lambda t: fr[min(range(n), key=lambda k: abs(fr[k]['t'] - t))]
    f_cut = near(t_cut)
    assert abs(f_cut['t'] - t_cut) < 1e-6, (f_cut['t'], t_cut)
    J = f_cut['sj'][cutj]
    J_ship = inv_at(f_cut['sm'], J)
    print('   cut joint (ship frame)', np.round(J_ship, 1), ' grip', np.round(inv_at(f_cut['sm'], f_cut['grip']), 1))

    # ---- the stand-in with the sword: he runs along the front of the stern castle from the port side, plants himself a sword's reach from the arm
    # facing the bow, and cuts across it with a forehand sweep (the blade's middle meets the arm at the joint)
    shoulder_y = CASTLE - 22.0
    d_hit, ell = 21.0, 15.0                      # the blade's middle meets the arm; the arm lies this far to his right of his shoulder
    dy = J_ship[1] - shoulder_y
    fwd = math.sqrt(max(d_hit ** 2 - ell ** 2 - dy ** 2, 4.0))
    x_b, z_b = J_ship[0] + ell + 6.0, J_ship[2] + fwd
    u_hit = unit(np.array([-ell, dy, -fwd]))      # his shoulder to the joint, in his own axes (he faces -z, his right is -x)
    a_hit = -math.acos(u_hit[1])                  # the right arm raised (negative: forward)
    b_hit = math.atan2(-u_hit[0], -u_hit[2])      # and turned toward his right
    arms_hit = -a_hit / math.pi
    print('   stand-in at x', round(x_b, 1), 'z', round(z_b, 1), 'arm raise', round(arms_hit, 2), 'sweep', round(math.degrees(b_hit)), 'deg')
    x_start, z_start = 22.0, 54.0
    t_run0, t_run1 = t_cut - 15.0, t_cut - 4.0
    yaw_watch = K.facing(J_ship[0] - x_start, J_ship[2] - z_start)
    yaw_run, yaw_strike, yaw_back = K.facing(x_b - x_start, z_b - z_start), 0.0, K.facing(x_start - x_b, z_start - z_b)

    def b_path(t):
        run = smooth(t_run0, t_run1, t) - smooth(t_cut + 30.0, t_cut + 54.0, t)
        x = x_start + (x_b - x_start) * run
        z = z_start + (z_b - z_start) * run + 3.0 * smooth(t_cut + 1.0, t_cut + 8.0, t) * (1.0 - smooth(t_cut + 26.0, t_cut + 36.0, t))
        return x, z

    def b_speed(t):
        (x0, z0), (x1, z1) = b_path(t - 0.5), b_path(t + 0.5)
        return math.hypot(x1 - x0, z1 - z0)

    arms_key = [(-1e9, 0.0, 'lin'), (t_run0 - 1.0, 0.0, 'io'), (t_run0 + 1.0, 0.2, 'io'), (t_cut - 4.8, 0.2, 'io'), (t_cut - 3.2, arms_hit, 'out'),
                (t_cut + 3.2, arms_hit, 'lin'), (t_cut + 6.5, 0.3, 'out'), (t_cut + 14.0, 0.12, 'io'), (t_cut + 30.0, 0.0, 'io'), (1e9, 0.0, 'lin')]
    sweep_key = [(-1e9, 0.0, 'lin'), (t_cut - 4.8, 0.0, 'io'), (t_cut - 3.2, b_hit + 1.7, 'out'), (t_cut, b_hit, 'chop'), (t_cut + 3.2, b_hit - 1.2, 'out'),
                 (t_cut + 6.5, 0.0, 'io'), (1e9, 0.0, 'lin')]

    def keyed(track, t):
        for (ta, va, _), (tb, vb, eb) in zip(track, track[1:]):
            if ta <= t < tb:
                return va + (vb - va) * EASES[eb]((t - ta) / (tb - ta))
        return track[-1][1]

    def b_state(f):
        t = f['t'] if isinstance(f, dict) else f
        x, z = b_path(t)
        spd = clamp(b_speed(t) / 2.4)
        walk = spd * math.sin(1.05 * t)
        yaw = yaw_watch
        yaw = turn(yaw, yaw_run, smooth(t_run0 - 2.0, t_run0 + 0.5, t))
        yaw = turn(yaw, yaw_strike, smooth(t_run1 - 3.0, t_run1 + 0.5, t))
        yaw = turn(yaw, yaw_back, smooth(t_cut + 28.0, t_cut + 33.0, t))
        yaw = turn(yaw, yaw_watch, smooth(t_cut + 56.0, t_cut + 62.0, t))
        arms, sweep = keyed(arms_key, t), keyed(sweep_key, t)
        strike = smooth(t_cut - 6.0, t_cut - 4.0, t) * (1.0 - smooth(t_cut + 4.0, t_cut + 8.0, t))
        lunge = smooth(t_run1 - 1.0, t_run1 + 0.5, t) * (1.0 - smooth(t_cut + 3.0, t_cut + 10.0, t))
        look = clamp((-float(f['sj'][:, 1].min()) - 20.0) / 120.0) if isinstance(f, dict) else 0.0
        pp = P.player_pose(arms=arms + 0.03 * math.sin(t * 0.3), swing=0.55 * walk * (1.0 - 0.7 * clamp(arms * 2.0)) * (1.0 - strike), kick=0.8 * walk + 0.5 * lunge,
                           nod=-0.3 * look * (1.0 - spd), sword=True, sweep=sweep, left=arms * (1.0 - strike) + 0.12 * strike)
        hop = 1.2 * abs(math.sin(1.05 * t)) * spd
        return pp, np.array([x, CASTLE - hop, z]), yaw

    sm_of = lambda t: ship_matrix(ship_motion(t, kicks, haul, total=total))

    def tip_of(tt):
        pp, pos, yaw = b_state(tt)
        return sword_tip(sm_of(tt) @ K.place(pos[0], pos[1], pos[2], yaw=yaw), pp['pl_arm_r']['rot'])

    # ---- the severed length: it falls on the hatch and the planks, writhes, and withers away
    rest = np.array([inv_at(f_cut['sm'], p) for p in f_cut['sj'][cutj:]], float)
    links = [T.LENGTHS[i] * 16.0 for i in range(cutj, T.SEGMENTS)]
    radii = [T.RADII[i] * 16.0 * 0.85 for i in range(cutj, T.SEGMENTS + 1)]

    def push(now, m):
        k = now - t_cut
        env = math.exp(-k / 30.0) * smooth(3.0, 7.0, k)
        a = np.zeros((m, 3))
        if env < 1e-3:
            return a
        for i in range(m):
            a[i, 1] = -3.0 * env * max(0.0, math.sin(0.55 * now - 0.95 * i)) ** 3
            a[i, 2] = 0.45 * env * math.sin(0.47 * now + 1.9 * i)
            a[i, 0] = 0.30 * env * math.sin(0.39 * now + 2.4 * i)
        return a

    after = [fr[k]['t'] for k in range(n) if fr[k]['t'] >= t_cut - 1e-6]
    chain = simulate_chain(rest, np.array([0.5, -0.6, 0.2]), t_cut, after, links, radii, spin=0.0, push=push)
    t_dis0, t_dis1 = t_cut + 30.0, t_cut + 50.0
    lows = lambda tt: chain[tt][:, 1] + np.array(radii) - np.array([landing(q[0], q[2]) for q in chain[tt]])
    t_land = next((tt for tt in after if (lows(tt) > -2.0).any()), t_cut + 6.0)
    print('   the length lands at', round(t_land, 1))

    # ---- the spray, the sparks, the ichor, the spores
    present = [presence(f) for f in fr]
    t_emerge = next((f['t'] for f, pr in zip(fr, present) if pr > 0.02), 6.0)
    ichor_cols = [(120, 244, 224), (176, 150, 255), (224, 255, 246)]
    root = np.array([ROOT[0], 0.0, pz])
    specks = crown(rng, 46, root, 10.0, t_emerge, up=(2.0, 5.5), out=(0.5, 2.0), life=(16.0, 30.0))
    specks += adrift(rng, 70, lambda t: above_water(near(t)), t_emerge, t_emerge + 30.0)
    g_scene = lambda t, p: at(near(t)['sm'], p)
    specks += burst(rng, 22, g_scene(60.0, (-6.0, -14.0, pz + 1.5)), 59.0, speed=(0.8, 3.0), cone=1.3, axis=(0.0, -1.0, 0.0), life=(10.0, 22.0), size=(0.6, 1.5),
                    colour=(184, 140, 90), alpha=1.0, floor=None)
    for col in ichor_cols:
        specks += burst(rng, 22, J, t_cut, speed=(1.5, 6.0), cone=1.2, axis=(-0.6, -0.7, 0.0), life=(18.0, 36.0), size=(0.7, 1.9), colour=col, alpha=0.95, floor=0.0)
    specks += burst(rng, 26, J, t_cut, speed=(2.5, 8.0), cone=1.5, axis=(0.0, -1.0, 0.0), life=(6.0, 14.0), size=(0.5, 1.2), colour=(255, 232, 170), alpha=1.0, g=0.2, floor=None)
    for k in range(26):                                   # ichor welling from the stump while it flails
        tk = t_cut + 2.0 + k * 1.15
        f = near(tk)
        d = unit(f['sj'][cutj] - f['sj'][cutj - 1])
        specks += burst(rng, 3, f['sj'][cutj], tk, speed=(0.4, 2.2), cone=0.9, axis=d, life=(14.0, 28.0), size=(0.6, 1.5), colour=ichor_cols[k % 3], alpha=0.95, floor=0.0)
    pt_land = at(near(t_land)['sm'], chain[t_land][int(np.argmax(lows(t_land)))])
    for col in ichor_cols[:2]:
        specks += burst(rng, 14, pt_land, t_land, speed=(0.6, 2.6), cone=1.3, axis=(0.0, -1.0, 0.0), life=(12.0, 26.0), size=(0.6, 1.5), colour=col, alpha=0.95, floor=DECK + 1.0)
    for tt in after:                                      # spores rising from it as it withers
        if t_dis0 - 6.0 <= tt <= t_dis1:
            q = at(near(tt)['sm'], chain[tt][int(rng.integers(0, len(chain[tt])))])
            specks += burst(rng, 3, q, tt, speed=(0.15, 0.7), cone=0.8, axis=(0.0, -1.0, 0.0), life=(22.0, 44.0), size=(0.5, 1.2), colour=ichor_cols[int(rng.integers(0, 3))],
                            alpha=0.9, g=-0.012, floor=None)
    t_sink = next((f['t'] for f, pr in zip(fr, present) if f['t'] > t_cut + 18.0 and pr < 0.12), t_cut + 36.0)
    specks += crown(rng, 36, root, 10.0, t_sink, up=(1.0, 3.0), out=(0.4, 1.6), life=(14.0, 26.0))
    events = [(ROOT[0], pz, t_emerge, 0.9, 8.0, 0.9, 50.0), (ROOT[0], pz, t_cut + 7.0, 0.6, 8.0, 0.8, 36.0), (ROOT[0], pz, t_sink, 0.9, 8.0, 0.9, 36.0)]
    for k, tk in enumerate((60.0, 69.0, 78.0, 86.0)):
        events.append((-31.0, pz + 6.0 * (k - 1), tk, 0.6, 5.0, 0.6, 30.0))
    events.append((-31.0, pz + 6.0, t_cut + 2.0, 0.8, 5.0, 0.9, 34.0))

    yw, el = math.radians(CAM_HOLD['yaw']), math.radians(CAM_HOLD['elev'])
    to_cam = np.array([-math.sin(yw) * math.cos(el), -math.sin(el), -math.cos(yw) * math.cos(el)])
    specs = []
    for f, pr in zip(fr, present):
        t = f['t']
        ch = f['ch']
        sm = f['sm']
        pp, pos, yaw = b_state(f)
        Mb = sm @ K.place(pos[0], pos[1], pos[2], yaw=yaw)
        acts = []
        if t < t_cut - 1e-6:
            acts.append(rig.actor(f['joints'], t, ch['grip'], ch['glow'], ch['rage']))
        else:
            acts.append(rig.actor(f['joints'], t, ch['grip'], ch['glow'], ch['rage'], cut=cutj))
            s = 1.0 - smooth(t_dis0, t_dis1, t)
            if s > 0.03:
                pts_s = np.array([at(sm, q) for q in chain[t]])
                piece = rig.actor(rig.joints_of(np.vstack([f['sj'][:cutj], pts_s])), t + 3.0, 0.2, 0.55 * (1.0 - smooth(t_cut, t_dis1, t)) + 0.1, 0.4 * (1.0 - smooth(t_cut, t_cut + 20.0, t)),
                                  cut=A.SEGS, start=cutj)
                c = pts_s.mean(0)
                Ms = np.eye(4)
                Ms[:3, :3] *= s
                Ms[:3, 3] = c * (1.0 - s)
                piece['root'] = Ms @ rig.M
                acts.append(piece)
        acts.append(dict(model='player2', pose=pp, root=Mb))
        fx = fly(specks, t)
        if t_cut - 3.5 <= t <= t_cut + 3.2:
            fx += slash(tip_of, t)
            fl = 1.0 - smooth(t_cut - 0.5, t_cut + 2.8, t)
            if t >= t_cut - 0.5:
                fp = J + to_cam * 9.0                  # (in front of the arm's own skin, or it would be hidden by it)
                fx.append((fp[0], fp[1], fp[2], 9.0 * (0.6 + 0.4 * fl), (255, 250, 226), 0.9 * fl, 0.9))
        spots = [(ROOT[0], pz, 12.0 + 1.2 * math.sin(t * 0.3), 0.85 * pr, 0.0)] + rings(events, t)
        specs.append(dict(cam=CAM_HOLD, sky='dusk', cog=ship_kw(t, f['mot']), actors=acts, spots=spots, fx=fx, sea_t=0.0, ring_t=t, bloom=1.0, murk=1.7))
    return specs
