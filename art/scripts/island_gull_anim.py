"""The gull's animation: what `GullModel.setupAnim` does, written once.

Everything between the markers `# ==== DSL` and `# ==== END DSL` is written in the small subset of Python that `island_kit.py` translates to
Kotlin (floats, ints, `if`, `for k in range`, helper functions, `s.field` reads of the render state, and the four operations `turn`, `shift`,
`squash` and `show` on parts). The previews run these very functions as they are (the operations record into a pose); `island_gull.py` turns
them into Kotlin and checks the compiled class against this Python numerically.

Conventions: radians; px; the model's own axes (x to the bird's left, y down, -z ahead); a part's rotation is Rz Ry Rx applied to its cubes.
For a wing on side `sg` (+1 the bird's left wing, -1 the right) the joint angles read: `zRot = -sg * raise`, `yRot = -sg * sweep_back`,
`xRot = -leading_edge_up`. A link of the leg hangs along its own +y, so a positive `xRot` swings its foot backward (+z). A link of the neck
stands along its own -y, so a positive `xRot` leans its top forward (-z) and tips a head's bill down.
"""
import math

# ================================================================== what the geometry and the animation share

BODY_Y = 3.5                      # px from the soles of the feet to the middle of the body (where the renderer pivots the bird)
FLOAT_SINK = 3.0                  # px the body sinks when floating: it rides with its belly in the water

# torso slices from the breast back: (z from, z to, width, height, y centre); each is built as an octagon (two boxes, see island_gull_geo)
TORSO = [(-3.5, -2.5, 2.0, 2.0, 0.4), (-2.5, -1.5, 3.0, 3.0, 0.1), (-1.5, -0.5, 3.0, 3.0, 0.0), (-0.5, 0.5, 3.0, 3.0, 0.0),
         (0.5, 1.5, 3.0, 2.5, -0.1), (1.5, 2.5, 2.5, 2.0, -0.2), (2.5, 3.5, 2.0, 1.5, -0.3)]
NECK = dict(base=(0.0, -0.8, -2.4), n1_len=1.5, n2_len=1.5, w1=2.5, d1=2.5, w2=2.0, d2=2.0)
HEAD = dict(h=2.0)
TAIL = dict(root=(0.0, -0.2, 2.8), half=0.75, len=2.5, side_w=1.0, side_len=2.5)
WING = dict(shoulder=(0.9, -1.1, -0.9), arm_len=2.5, arm_lead=-1.0, arm_chord=2.0, arm_flap=1.5, arm_cov=1.0,
            fore_len=2.5, fore_lead=-1.0, fore_chord=1.5, fore_flap=1.5, fore_cov=1.0,
            hand_len=2.0, hand_lead=-0.8, hand_chord=1.5,
            pri_root=[(0.5, 0.9), (0.9, 0.5), (1.3, 0.1), (1.5, -0.3)], pri_len=[3.5, 4.0, 4.5, 5.0], pri_y=[0.12, 0.08, 0.04, 0.0])
LEG = dict(hip=(0.9, 0.8, 0.5), l1=1.3, l2=1.5)

# how long each action lasts (ticks), indexed by the action constants (0 unused)
ACTION_TICKS = (0, 70, 64, 22, 30, 44, 22, 26, 50)
TAKEOFF_LIFT = 8.0         # tick of TAKEOFF at which the feet leave the ground
LAND_TOUCH = 17.0          # tick of LAND at which the feet touch down
DIVE_HIT = 20.0            # tick of DIVE at which the beak enters the water
DIVE_SURFACE = 34.0        # tick of DIVE at which it comes up again

NONE = 0
PREEN = 1
CALL = 2
PECK = 3
RUFFLE = 4
THREAT = 5
TAKEOFF = 6
LAND = 7
DIVE = 8
STATE_CONSTS = ('NONE', 'PREEN', 'CALL', 'PECK', 'RUFFLE', 'THREAT', 'TAKEOFF', 'LAND', 'DIVE')

# part sets the animation indexes by side (0 the bird's left, 1 its right) or by number
ARM = ('arm_l', 'arm_r')
ARM_S = ('arm_l_s', 'arm_r_s')
FORE = ('fore_l', 'fore_r')
FORE_S = ('fore_l_s', 'fore_r_s')
HAND = ('hand_l', 'hand_r')
PRI = ('pri_l1', 'pri_l2', 'pri_l3', 'pri_l4', 'pri_r1', 'pri_r2', 'pri_r3', 'pri_r4')
LEGS = ('leg_l', 'leg_r')
SHIN = ('shin_l', 'shin_r')
FOOT = ('foot_l', 'foot_r')
EYE = ('eye_l', 'eye_r')
TAIL_SIDE = ('tail_l', 'tail_r')

# numbers
L1 = LEG['l1']            # the leg's first link: hip to ankle
L2 = LEG['l2']            # its second: the tarsus
HIP_X = LEG['hip'][0]
HIP_Y = LEG['hip'][1]
HIP_Z = LEG['hip'][2]
SOLE_Y = 24.0 - 0.25      # where the foot's pivot stands when planted: a card 0.5 thick
FAN = (0.46, 0.30, 0.15, 0.0)             # how far each primary is fanned back from the hand's line in a glide (inner to outer)
FLEX = (0.45, 0.65, 0.85, 1.0)            # how much each primary bends at its tip
DEG = math.pi / 180.0
# the folded wing (for the bird's left; the right is its mirror): rotation about the span, yaw back, droop of the outer edge; the forearm's and the
# hand's carry also how far each layer lies below the one above
FOLD_ARM = (0.0, 1.45, -0.90)
FOLD_FORE = (0.0, -2.90, 0.0, 0.5)
FOLD_HAND = (0.0, 2.90, 0.0, 0.5)
FOLD_FLAP = -2.9
FOLD_POS = (0.3, 0.0, 0.0)             # how far the shoulder is moved when folded: outward (the bird's left; mirrored), down, back
# the neck on the ground, floating and in the air: the two links' lean and the head's tip (radians), then how the legs hang
STAND_PITCH = -0.20
FLOAT_PITCH = -0.12
AIR_PITCH = -0.02
STAND_NECK = (0.85, -0.75, -0.02)
FLOAT_NECK = (0.60, -0.55, -0.05)
AIR_NECK = (1.25, -0.60, -0.45)
LEG_TUCK = (1.45, -0.10, 1.70)
LEG_DANGLE = (0.30, 0.35, -0.20)
LEG_FLARE = (-0.60, 0.50, -0.30)
STRIDE = 0.9
LIFT = 0.8


# ================================================================== the runtime the previews use (the Kotlin has its own)

class GullState:
    """A render state as the previews hold it (the same fields as GullRenderState.kt; the harness sets them by name)."""

    def __init__(self, **kw):
        self.time = 0.0
        self.flying = 0.0
        self.spread = 0.0
        self.flapPhase = 0.0
        self.flap = 0.0
        self.bank = 0.0
        self.hover = 0.0
        self.floating = 0.0
        self.headYaw = 0.0
        self.headPitch = 0.0
        self.beak = 0.0
        self.walk = 0.0
        self.walkPhase = 0.0
        self.action = 0
        self.actionTime = 0.0
        self.fish = False
        self.variant = 0
        for k, v in kw.items():
            if not hasattr(self, k):
                raise AttributeError(k)
            setattr(self, k, v)


State = GullState          # the name the animation's annotations use (island_kit reads them as the render state)

_POSE = {}
_REST = {}


def set_rest(rest):
    """[rest]: {part name: (position, rotation radians)} from the built model."""
    _REST.clear()
    _REST.update(rest)


def _ent(name):
    e = _POSE.get(name)
    if e is None:
        pos, rot = _REST[name]
        e = _POSE[name] = {'pos': [float(v) for v in pos], 'rot': [float(v) for v in rot]}
    return e


def turn(p, x, y, z):
    r = _ent(p)['rot']
    r[0] += x
    r[1] += y
    r[2] += z


def shift(p, x, y, z):
    r = _ent(p)['pos']
    r[0] += x
    r[1] += y
    r[2] += z


def squash(p, x, y, z):
    _ent(p)['scale'] = (x, y, z)


def show(p, on):
    _ent(p)['visible'] = bool(on)


def pose(state):
    """{part: {pos, rot, scale, visible}} for [state]: only the parts the animation touches."""
    _POSE.clear()
    pose_all(state)
    return {k: {kk: (tuple(vv) if isinstance(vv, list) else vv) for kk, vv in e.items()} for k, e in _POSE.items()}


# ==== DSL: everything below, down to END DSL, is translated to Kotlin

def clamp(x: float, lo: float, hi: float) -> float:
    return min(hi, max(lo, x))


def mix(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def smooth(a: float, b: float, x: float) -> float:
    """0 at [a] and below, 1 at [b] and above, eased between."""
    t = clamp((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def bump(x: float, a: float, b: float, c: float, d: float) -> float:
    """0 before [a], eased up to 1 at [b], held to [c], eased down to 0 at [d]."""
    return smooth(a, b, x) * (1.0 - smooth(c, d, x))


def pulse(x: float, c: float, w: float) -> float:
    """A soft bump 1 at [c], gone [w] either side."""
    t = clamp(1.0 - abs(x - c) / w, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def fract(x: float) -> float:
    return x - floor(x)


def dart(x: float) -> float:
    """A square-ish wave from -1 to 1 with quick soft flanks (a bird's head going from one look to the next)."""
    return 2.0 * smooth(-0.25, 0.25, sin(x)) - 1.0


def wrap(x: float) -> float:
    """[x] brought into -pi..pi."""
    return x - 2.0 * math.pi * floor((x + math.pi) / (2.0 * math.pi))


def stroke(ph: float) -> float:
    """The wing's elevation through a beat: 1 at the top, -1 at the bottom; the downstroke (top to bottom) is quicker than the upstroke."""
    return cos(ph) + 0.15 * sin(2.0 * ph)


def blink(t: float) -> float:
    """How closed the eyes are (0 open, 1 shut): two blinks of different rhythm laid over each other."""
    a = fract(t / 57.0)
    b = fract(t / 91.0 + 0.37)
    return max(max(0.0, 1.0 - abs(a - 0.03) / 0.03), max(0.0, 1.0 - abs(b - 0.025) / 0.025))


# ---------------------------------------------------------------- the actions: where each is, and how fully

def at_of(s: 'State', a: int) -> float:
    """Ticks into action [a] if that is the one running (else 0)."""
    return s.actionTime if s.action == a else 0.0


def ev(s: 'State', a: int, tin: float, tout: float) -> float:
    """How fully action [a] holds the pose: 0 when another or none runs, eased in over its first [tin] ticks and out over its last [tout]."""
    if s.action != a:
        return 0.0
    return smooth(0.0, tin, s.actionTime) * (1.0 - smooth(float(ACTION_TICKS[a]) - tout, float(ACTION_TICKS[a]), s.actionTime))


def busy(s: 'State') -> float:
    """0 when nothing is going on with the bird (idle stirring is allowed), up to 1 when an action has it."""
    b = 0.0
    if s.action != NONE:
        b = ev(s, s.action, 6.0, 6.0)
    return max(b, clamp(s.walk, 0.0, 1.0))


# ---------------------------------------------------------------- what mode it is in

def w_air(s: 'State') -> float:
    a = clamp(s.flying, 0.0, 1.0)
    a = mix(a, smooth(TAKEOFF_LIFT, TAKEOFF_LIFT + 5.0, at_of(s, TAKEOFF)), ev(s, TAKEOFF, 1.5, 5.0))
    a = mix(a, 1.0 - smooth(LAND_TOUCH - 3.0, LAND_TOUCH + 0.5, at_of(s, LAND)), ev(s, LAND, 1.5, 4.0))
    a = mix(a, 1.0, ev(s, DIVE, 2.0, 6.0))
    return a


def w_float(s: 'State') -> float:
    return (1.0 - w_air(s)) * clamp(s.floating, 0.0, 1.0)


def w_ground(s: 'State') -> float:
    return (1.0 - w_air(s)) * (1.0 - clamp(s.floating, 0.0, 1.0))


def w_hover(s: 'State') -> float:
    return clamp(s.hover, 0.0, 1.0) * w_air(s) * (1.0 - ev(s, DIVE, 2.0, 6.0))


# ---------------------------------------------------------------- the beat of the wings: how open, how hard, where in the beat

def dive_spread(at: float) -> float:
    return 1.0 - 0.9 * smooth(1.0, 7.0, at) + 0.9 * smooth(27.0, 38.0, at)


def spread_eff(s: 'State', side: int) -> float:
    sp = clamp(s.spread, 0.0, 1.0)
    sp = mix(sp, smooth(1.0, 6.5, at_of(s, TAKEOFF)), ev(s, TAKEOFF, 1.5, 5.0))
    sp = mix(sp, 1.0 - smooth(LAND_TOUCH + 0.5, LAND_TOUCH + 8.0, at_of(s, LAND)), ev(s, LAND, 1.5, 4.0))
    sp = mix(sp, dive_spread(at_of(s, DIVE)), ev(s, DIVE, 2.0, 8.0))
    sp = max(sp, 0.60 * ev(s, THREAT, 5.0, 5.0))
    sp = max(sp, 0.45 * preen_lift(s, side))
    return sp


def flap_eff(s: 'State') -> float:
    fl = clamp(s.flap, 0.0, 1.0)
    fl = mix(fl, smooth(4.0, 7.0, at_of(s, TAKEOFF)), ev(s, TAKEOFF, 1.5, 5.0))
    fl = mix(fl, 1.0 - smooth(14.0, 19.0, at_of(s, LAND)), ev(s, LAND, 1.5, 4.0))
    fl = mix(fl, smooth(30.0, 36.0, at_of(s, DIVE)), ev(s, DIVE, 2.0, 8.0))
    return fl


def phase_eff(s: 'State') -> float:
    ph = s.flapPhase
    tk = 2.9 * smooth(7.0, 12.0, at_of(s, TAKEOFF)) + (2.0 * math.pi - 2.9) * smooth(12.5, 19.5, at_of(s, TAKEOFF))
    ph = ph + wrap(tk - ph) * ev(s, TAKEOFF, 1.0, 1.0) * (1.0 - smooth(17.0, 21.0, at_of(s, TAKEOFF)))
    ln = 4.0 * math.pi * smooth(0.0, 16.0, at_of(s, LAND))
    ph = ph + wrap(ln - ph) * ev(s, LAND, 1.0, 1.0) * (1.0 - smooth(17.0, 22.0, at_of(s, LAND)))
    dv = 4.0 * math.pi * smooth(30.0, 46.0, at_of(s, DIVE))
    ph = ph + wrap(dv - ph) * ev(s, DIVE, 1.0, 1.0) * (1.0 - smooth(44.0, 49.0, at_of(s, DIVE)))
    return ph


# ---------------------------------------------------------------- the small stirrings of a bird at rest, and the scripts of the actions

def calm(s: 'State') -> float:
    return 1.0 - busy(s)


def idle_look(s: 'State') -> float:
    """Where a resting bird looks (radians, + to its right): it holds each look a while and flicks to the next."""
    return 0.50 * dart(0.071 * s.time + 0.4) + 0.30 * dart(0.043 * s.time + 2.0) + 0.10 * dart(0.19 * s.time + 5.0)


def idle_tip(s: 'State') -> float:
    """Where it holds its head up and down (radians, + down)."""
    return 0.12 * dart(0.057 * s.time + 1.1) + 0.06 * dart(0.13 * s.time)


def preen_lift(s: 'State', side: int) -> float:
    """How far the wing on [side] is lifted for the beak to go under it (the right wing in the first half of the preen, the left in the second)."""
    at = at_of(s, PREEN)
    v = ev(s, PREEN, 4.0, 5.0)
    return v * (bump(at, 13.0, 18.0, 26.0, 31.0) * (1.0 - float(side)) + bump(at, 46.0, 51.0, 59.0, 64.0) * float(side))


def call_gape(s: 'State') -> float:
    """The long call: five notes, each a wide gape."""
    at = at_of(s, CALL)
    g = 0.0
    for k in range(5):
        g = max(g, pulse(at, 20.0 + 8.5 * float(k), 3.4))
    return g * ev(s, CALL, 4.0, 4.0)


def peck_dip(s: 'State') -> float:
    """Pecking: two quick jabs at the ground."""
    at = at_of(s, PECK)
    return max(pulse(at, 6.5, 3.2), pulse(at, 14.0, 3.2)) * ev(s, PECK, 2.0, 3.0)


def jab(s: 'State') -> float:
    """The threat: three jabs of the head and beak."""
    at = at_of(s, THREAT)
    return max(max(pulse(at, 14.0, 2.6), pulse(at, 25.0, 2.6)), pulse(at, 35.0, 2.6)) * ev(s, THREAT, 5.0, 5.0)


def shake(s: 'State') -> float:
    """The ruffle's shudder: a fast wave that swells and dies."""
    at = at_of(s, RUFFLE)
    return bump(at, 3.0, 6.0, 17.0, 23.0) * sin(1.9 * at) * ev(s, RUFFLE, 2.0, 3.0)


def puff(s: 'State') -> float:
    at = at_of(s, RUFFLE)
    return bump(at, 0.0, 4.0, 19.0, 29.0) * ev(s, RUFFLE, 1.0, 1.0)


def crouch(s: 'State') -> float:
    """TAKEOFF: how far the bird has sunk to spring (1 at the lowest, at tick 7), 0 once it has sprung."""
    at = at_of(s, TAKEOFF)
    return smooth(0.0, 7.0, at) * (1.0 - smooth(7.0, 9.0, at)) * ev(s, TAKEOFF, 1.0, 4.0)


def spring(s: 'State') -> float:
    """TAKEOFF: the stretch of the legs as the feet leave the ground (tick 8)."""
    at = at_of(s, TAKEOFF)
    return smooth(6.5, 8.5, at) * (1.0 - smooth(8.5, 13.0, at)) * ev(s, TAKEOFF, 1.0, 4.0)


def flare(s: 'State') -> float:
    """LAND: the flare, body up and feet forward, from the first ticks to the touchdown (tick 17), then it eases off."""
    at = at_of(s, LAND)
    return smooth(2.0, 7.0, at) * (1.0 - smooth(LAND_TOUCH + 0.5, LAND_TOUCH + 7.0, at)) * ev(s, LAND, 1.5, 4.0)


def settle(s: 'State') -> float:
    """LAND: the dip of the legs taking the weight at the touchdown."""
    at = at_of(s, LAND)
    return pulse(at, LAND_TOUCH + 2.0, 3.5) * ev(s, LAND, 1.5, 4.0)


# ---------------------------------------------------------------- the body

def body_pitch(s: 'State') -> float:
    p = w_ground(s) * STAND_PITCH + w_float(s) * FLOAT_PITCH + w_air(s) * AIR_PITCH
    p += -0.30 * w_hover(s)
    p += 0.34 * peck_dip(s) + 0.16 * ev(s, THREAT, 5.0, 5.0) - 0.10 * smooth(11.0, 17.0, at_of(s, CALL)) * ev(s, CALL, 6.0, 6.0)
    p += 0.12 * crouch(s) - 0.20 * spring(s) - 0.45 * flare(s)
    p += 0.03 * sin(0.12 * s.time) * w_float(s)
    return p


def body_dy(s: 'State') -> float:
    """How far the body is moved down (px): floating, crouching, bobbing with the beat and the stride."""
    f = flap_eff(s) * w_air(s)
    ph = phase_eff(s)
    d = FLOAT_SINK * w_float(s)
    d += w_float(s) * (0.22 * sin(0.11 * s.time) + 0.12 * sin(0.23 * s.time + 1.0))
    d += 0.30 * f * cos(ph - 0.4)
    d += 0.9 * crouch(s) - 0.5 * spring(s) + 0.5 * settle(s)
    d += 0.30 * ev(s, THREAT, 5.0, 5.0) + 0.60 * peck_dip(s)
    d += 0.12 * shake(s) * sin(3.7 * s.time)
    d += 0.10 * clamp(s.walk, 0.0, 1.0) * cos(2.0 * s.walkPhase)
    return d


def body_dz(s: 'State') -> float:
    return -0.55 * peck_dip(s) - 0.2 * jab(s)


def body_pose(s: 'State'):
    sh = shake(s)
    turn('body', body_pitch(s), 0.17 * sh, 0.05 * sh)
    shift('body', 0.0, body_dy(s), body_dz(s))
    pf = 1.0 + 0.09 * puff(s)
    squash('torso', pf, pf, pf)


# ---------------------------------------------------------------- the legs

def leg_pose(s: 'State', side: int):
    sg = 1.0 if side == 0 else -1.0
    a = w_air(s)
    th = body_pitch(s)
    c = cos(th)
    sn = sin(th)
    hy = 24.0 - BODY_Y + body_dy(s) + HIP_Y * c - HIP_Z * sn
    hz = body_dz(s) + HIP_Y * sn + HIP_Z * c
    wk = clamp(s.walk, 0.0, 1.0) * (1.0 - a)
    u = fract((s.walkPhase + float(side) * math.pi) / (2.0 * math.pi))
    zf = 0.0
    if u < 0.58:
        zf = -1.0 + 2.0 * u / 0.58
    else:
        zf = 1.0 - 2.0 * smooth(0.58, 1.0, u)
    lift = sin(math.pi * clamp((u - 0.58) / 0.42, 0.0, 1.0))
    ty = SOLE_Y - LIFT * wk * lift
    tz = HIP_Z - 0.4 + STRIDE * wk * zf
    dy = ty - hy
    dz = tz - hz
    d = clamp(sqrt(dy * dy + dz * dz), abs(L1 - L2) + 0.05, L1 + L2 - 0.02)
    a1 = acos(clamp((L1 * L1 + d * d - L2 * L2) / (2.0 * L1 * d), -1.0, 1.0))
    al1 = atan2(dz, dy) + a1
    ay = hy + L1 * cos(al1)
    az = hz + L1 * sin(al1)
    al2 = atan2(tz - az, ty - ay)
    x1 = al1 - th
    x2 = al2 - al1
    x3 = -al2 + 0.35 * wk * lift
    p1 = LEG_TUCK[0]
    p2 = LEG_TUCK[1]
    p3 = LEG_TUCK[2]
    hv = w_hover(s)
    p1 = mix(p1, LEG_DANGLE[0] + 0.10 * sin(0.37 * s.time + float(side)), hv)
    p2 = mix(p2, LEG_DANGLE[1] + 0.12 * sin(0.37 * s.time + float(side) + 1.0), hv)
    p3 = mix(p3, LEG_DANGLE[2], hv)
    lf = flare(s)
    p1 = mix(p1, LEG_FLARE[0], lf)
    p2 = mix(p2, LEG_FLARE[1], lf)
    p3 = mix(p3, LEG_FLARE[2], lf)
    x1 = mix(x1, p1, a)
    x2 = mix(x2, p2, a)
    x3 = mix(x3, p3, a)
    turn(LEGS[side], x1, 0.0, -sg * 0.07)
    turn(SHIN[side], x2, 0.0, 0.0)
    turn(FOOT[side], x3, 0.0, 0.0)
    fw = 1.0 - 0.5 * a * (1.0 - lf)
    squash(FOOT[side], fw, 1.0, 1.0)


# ---------------------------------------------------------------- the neck and head

def neck_head(s: 'State'):
    a = w_air(s)
    f = w_float(s)
    g = w_ground(s)
    n1x = g * STAND_NECK[0] + f * FLOAT_NECK[0] + a * AIR_NECK[0]
    n2x = g * STAND_NECK[1] + f * FLOAT_NECK[1] + a * AIR_NECK[1]
    hx = g * STAND_NECK[2] + f * FLOAT_NECK[2] + a * AIR_NECK[2]
    n1y = 0.0
    n2y = 0.0
    hy = 0.0
    gape = clamp(s.beak, 0.0, 1.0)
    squint = 0.0
    # where the entity looks, and the small stirrings of a resting bird, and into a turn
    look = s.headYaw * DEG + (idle_look(s) * (1.0 - 0.5 * a) + 0.35 * s.bank) * calm(s)
    tip = s.headPitch * DEG + idle_tip(s) * calm(s)
    hover = w_hover(s)
    hx += 0.35 * hover
    n1x += -0.12 * tip
    n2x += -0.30 * tip
    hx += -0.58 * tip
    n1y += 0.25 * look
    n2y += 0.35 * look
    hy += 0.40 * look
    # PECK: the head goes down to the ground and jabs
    pk = peck_dip(s)
    lk = ev(s, PECK, 3.0, 4.0)
    n1x += 0.30 * lk + 0.32 * pk
    n2x += 0.20 * lk + 0.50 * pk
    hx += 0.30 * lk + 0.50 * pk
    # CALL: head thrown down, then up and back, five notes
    at = at_of(s, CALL)
    cv = ev(s, CALL, 3.0, 6.0)
    bow = bump(at, 0.0, 5.0, 9.0, 14.0) * cv
    up = smooth(11.0, 17.0, at) * (1.0 - smooth(54.0, 62.0, at)) * cv
    cg = call_gape(s)
    n1x += 0.35 * bow - 0.45 * up
    n2x += 0.30 * bow + 0.45 * up
    hx += 0.60 * bow - 1.05 * up - 0.25 * cg
    gape = max(gape, 0.95 * cg + 0.30 * up + 0.2 * bow)
    # PREEN: the head turns to the shoulder, the beak works along the feathers and under the wing
    pa = at_of(s, PREEN)
    pv = ev(s, PREEN, 4.0, 5.0)
    pr = bump(pa, 3.0, 9.0, 30.0, 36.0)
    pl = bump(pa, 36.0, 42.0, 62.0, 68.0)
    toward = (pr - pl) * pv
    work = pv * (pr + pl) * sin(0.55 * pa)
    nib = pv * (pr + pl) * (0.5 + 0.5 * sin(1.7 * pa))
    n1y += 0.55 * toward + 0.10 * work
    n2y += 0.80 * toward + 0.16 * work
    hy += 0.75 * toward + 0.22 * work
    n1x += 0.30 * pv * (pr + pl)
    n2x += 0.10 * pv * (pr + pl)
    hx += 0.55 * pv * (pr + pl) + 0.10 * nib
    gape = max(gape, 0.30 * nib)
    squint = max(squint, 0.55 * pv * (pr + pl))
    # THREAT: the neck stretched forward, the head low, the beak jabbing
    tv = ev(s, THREAT, 5.0, 5.0)
    jb = jab(s)
    n1x += 0.30 * tv + 0.25 * jb
    n2x += 0.45 * tv
    hx += 0.15 * tv + 0.30 * jb
    gape = max(gape, 0.70 * jb + 0.15 * tv)
    # RUFFLE: the head shakes
    sh = shake(s)
    hy += 0.25 * sh
    n2y += 0.12 * sh
    squint = max(squint, 0.5 * ev(s, RUFFLE, 2.0, 3.0))
    # TAKEOFF, LAND, DIVE: the neck tucks to crouch, stretches to the landing place, and forward into the plunge
    tc = crouch(s)
    sp = spring(s)
    n1x += 0.25 * tc - 0.25 * sp
    hx += 0.15 * tc
    ln = flare(s)
    n1x += -0.30 * ln
    n2x += 0.20 * ln
    hx += 0.35 * ln
    dv = ev(s, DIVE, 3.0, 8.0)
    dd = bump(at_of(s, DIVE), 0.0, 6.0, 24.0, 32.0) * dv
    n1x += -0.30 * dd
    n2x += 0.45 * dd
    hx += 0.35 * dd
    gape = max(gape, 0.12 * dd)
    turn('neck1', n1x, n1y, 0.0)
    turn('neck2', n2x, n2y, 0.0)
    turn('head', hx, hy, 0.0 - 0.18 * s.bank * a)
    turn('jaw', 0.75 * gape, 0.0, 0.0)
    turn('bill', -0.16 * gape, 0.0, 0.0)
    cs = 1.0 + 0.14 * cg
    squash('neck2', cs, 1.0, cs)
    squash('head', 1.0 / cs, 1.0, 1.0 / cs)
    bl = max(blink(s.time), squint)
    for k in range(2):
        squash(EYE[k], 1.0, max(0.12, 1.0 - 0.9 * bl), 1.0)
    show('fish', s.fish)


# ---------------------------------------------------------------- the wings

def wing_pose(s: 'State', side: int):
    sg = 1.0 if side == 0 else -1.0
    sp = spread_eff(s, side)
    f = flap_eff(s) * clamp(sp * 2.0, 0.0, 1.0)
    ph = phase_eff(s)
    a = w_air(s)
    o = sg * clamp(s.bank, -1.0, 1.0) * a
    hv = w_hover(s)
    # --- how far each joint has opened (the elbow and the wrist first, then the shoulder)
    e_arm = smooth(0.12, 1.0, sp)
    e_el = smooth(0.0, 0.8, sp)
    e_wr = smooth(0.0, 0.75, sp)
    e_fl = smooth(0.0, 0.6, sp)
    # --- the beat: the elevation of the arm, the forearm and the hand lags the stroke (the tip is last), each by its own amount
    ar_a = 0.15 + f * (0.42 * stroke(ph) - 0.06) + o * 0.16
    fo_a = 0.14 + f * (0.60 * stroke(ph - 0.50) - 0.08) + o * 0.16
    ha_a = -0.10 + f * (0.80 * stroke(ph - 1.05) - 0.10) + o * 0.10
    fx = clamp(0.5 - 0.5 * stroke(ph - 1.0), 0.0, 1.0) * f
    # --- the other things the actions do to the wing
    tv = ev(s, THREAT, 5.0, 5.0)
    ar_a += 0.50 * tv + 0.10 * tv * sin(1.3 * s.time + float(side))
    fo_a += 0.35 * tv
    ha_a += -0.25 * tv
    sh = shake(s)
    ar_a += 0.10 * sh * sin(1.3 + float(side)) + 0.10 * puff(s)
    ln = flare(s)
    ar_a += 0.20 * ln
    ha_a += 0.20 * ln
    ar_a += 0.07 * hv * sin(0.21 * s.time + float(side) * 2.0)
    ha_a += hv * (0.20 * sin(0.55 * s.time + float(side) * 1.7) + 0.08 * sin(0.9 * s.time))
    soar = (1.0 - f) * a * (1.0 - hv)
    ar_a += 0.03 * soar * sin(0.07 * s.time + float(side) * 1.3)
    ha_a += 0.05 * soar * sin(0.11 * s.time + float(side) * 2.1)
    # --- the open pose (glide): arm a little forward, hand swept back and drooped; the beat flexes the wrist on the way up
    arm_b = -0.10 - 0.10 * f * sin(ph) - 0.12 * o
    fore_b = 0.04 + 0.30 * fx
    hand_b = 0.22 + 0.45 * fx - 0.10 * o - 0.45 * ln - 0.15 * tv
    arm_c = -0.20 * f * sin(ph) + 0.35 * ln
    hand_c = -0.25 * f * sin(ph - 0.8) + 0.20 * ln
    # --- the folded pose: the arm back along the body and tipped over like a roof, the forearm folded forward, the hand back again
    arm_a = mix(FOLD_ARM[2], ar_a, e_arm)
    arm_bb = mix(FOLD_ARM[1], arm_b, e_arm)
    arm_cc = mix(FOLD_ARM[0], arm_c, e_arm)
    fore_a = mix(FOLD_FORE[2], fo_a - ar_a, e_el)
    fore_bb = mix(FOLD_FORE[1], fore_b, e_el)
    hand_a = mix(FOLD_HAND[2], ha_a - fo_a, e_wr)
    hand_bb = mix(FOLD_HAND[1], hand_b, e_wr)
    hand_cc = mix(FOLD_HAND[0], hand_c, e_wr)
    turn(ARM[side], -arm_cc, -sg * arm_bb, -sg * arm_a)
    shift(ARM[side], sg * FOLD_POS[0] * (1.0 - e_arm), FOLD_POS[1] * (1.0 - e_arm), FOLD_POS[2] * (1.0 - e_arm))
    turn(FORE[side], -FOLD_FORE[0] * (1.0 - e_el), -sg * fore_bb, -sg * fore_a)
    turn(HAND[side], -hand_cc, -sg * hand_bb, -sg * hand_a)
    # the layers of the folded wing lie one under the other
    shift(FORE[side], 0.0, FOLD_FORE[3] * (1.0 - e_el), 0.0)
    shift(HAND[side], 0.0, FOLD_HAND[3] * (1.0 - e_wr), 0.0)
    # --- the flaps of secondaries: folded under, they fan open behind the arm and the forearm
    camber = -0.10 + 0.18 * f * sin(ph - 0.3) - 0.25 * ln
    turn(ARM_S[side], mix(FOLD_FLAP, camber, e_fl), 0.0, 0.0)
    turn(FORE_S[side], mix(FOLD_FLAP, camber, e_fl), 0.0, 0.0)
    # --- the primaries: fanned a little in a glide, closed on the downstroke, open on the way up; the tips bend with the beat
    for k in range(4):
        i = side * 4 + k
        fan = FAN[k] * (0.85 + 0.55 * fx - 0.35 * f * max(0.0, sin(ph)) + 0.5 * ln + 0.3 * tv + 0.4 * hv)
        bend = FLEX[k] * (0.10 + 0.34 * f * sin(ph - 0.2) + 0.08 * soar * sin(0.17 * s.time + float(k)))
        turn(PRI[i], 0.0, -sg * fan * e_wr, -sg * bend * e_wr)


# ---------------------------------------------------------------- the tail

def tail_pose(s: 'State'):
    f = flap_eff(s)
    ph = phase_eff(s)
    a = w_air(s)
    hv = w_hover(s)
    ln = flare(s)
    pitch = 0.08 * w_ground(s) - 0.25 * w_float(s) + 0.03 * a - 0.10 * f * cos(ph - 0.5)
    pitch += 0.40 * hv + 0.45 * ln - 0.25 * ev(s, THREAT, 5.0, 5.0) - 0.15 * crouch(s) + 0.30 * spring(s)
    pitch += -0.15 * peck_dip(s) - 0.10 * call_gape(s)
    flick = pulse(fract(s.time / 141.0 + 0.2), 0.5, 0.012) * calm(s) * (1.0 - a)
    pitch += -0.30 * flick
    fan = 0.05 + 0.10 * a + 0.35 * hv + 0.30 * ln + 0.30 * ev(s, THREAT, 5.0, 5.0) + 0.15 * spring(s)
    sh = shake(s)
    turn('tail', pitch, -0.25 * clamp(s.bank, -1.0, 1.0) * a + 0.35 * sh + 0.3 * flick * sin(2.2 * s.time), 0.0)
    turn('tail_l', 0.0, fan, 0.0)
    turn('tail_r', 0.0, -fan, 0.0)


def pose_all(s: 'State'):
    body_pose(s)
    for side in range(2):
        leg_pose(s, side)
        wing_pose(s, side)
    neck_head(s)
    tail_pose(s)


# ==== END DSL

DSL = [clamp, mix, smooth, bump, pulse, fract, dart, wrap, stroke, blink, at_of, ev, busy, w_air, w_float, w_ground, w_hover, dive_spread, spread_eff,
       flap_eff, phase_eff, calm, idle_look, idle_tip, preen_lift, call_gape, peck_dip, jab, shake, puff, crouch, spring, flare, settle, body_pitch,
       body_dy, body_dz, body_pose, leg_pose, neck_head, wing_pose, tail_pose, pose_all]


def sin(x):
    return math.sin(x)


def cos(x):
    return math.cos(x)


def floor(x):
    return math.floor(x)


def sqrt(x):
    return math.sqrt(x)


def acos(x):
    return math.acos(x)


def atan2(y, x):
    return math.atan2(y, x)
