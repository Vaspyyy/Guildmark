"""
The lamplighter's moves (townsfolk/Lamplighters.kt goes round a capital's street lamps with them), built with the kit of
art/scripts/living_anims.py, which imports this at its end and has make() build them into its clips:

- carry_pole (40, loop, overlay): the pole carried from lamp to lamp at the slant light_lamp starts and ends on, the
  arm steady under its weight and the pole bobbing a little with the stride, the left arm swinging free, a look about
  and now and then up at the lamps; its wick, when lit, smokes a thread now and then (the game draws the flame only
  while it burns: Townsperson.lantern stands for the lit wick).
- light_lamp (50), rebuilt here from living_anims.py's own: its moments (the wick taps the lantern's face at 21 and the
  lamp catches at 24, Lamplighters.LIT_TICK) and its wick's way, swung out round the lantern's corner going in and
  coming back (the carried wick rests at the lantern's back corner), solved again so that the snuffer standing out
  from the pole keeps clear of the lantern and its post all the way, and the pole of the fence and the lamplighter.
- snuff_lamp (40): of a morning, the wick out: the left hand onto the pole and the pole drawn up near upright in front,
  turned a quarter in the hands as it comes up so that the snuffer's brass bell (on the pole's side, out of the way
  while it is carried and lights a lamp) comes round to face the lamp, brought up in front of the lantern and put over
  the flame at its near face (it clinks on at 18.5), held there till the flame hisses out (23,
  Lamplighters.SNUFF_TICK), drawn off with a wisp of smoke let out after it (25.5), and the pole turned back and
  lowered to the carry again, the left hand letting go; the eyes on the lamp the while. The turn is a prop track
  (TRACKS: the game turns the pole as the clip's file has it, LivingProps.track).

The station of the previews is the lamp station (a wall post, two fences and a lantern on top, a block ahead), its
glass lit or dark as the lamp is at that tick. Every point of the pole and the snuffer is kept clear of it (only the
wick and the pole's cap meet the lantern's face while lighting it, and the snuffer's mouth while putting it out), and
of the lamplighter's head and body; the clearances are printed with the clips.
"""
import math

import numpy as np

import living_props as lp

# The names of living_anims.py's kit, copied in by make() (its helpers, constants and clips so far)
_OWN = None

WICK_TIP = (43.4, 0.0, 0.0)                     # lamp_pole space: the tip of the flame, where its smoke goes up
TIP_X = 38.8                                    # lamp_pole space: from here up the pole's brass cap and the wick
FLAME_X = 40.8                                  # and from here the flame
SN_MOUTH = np.array(lp.SNUFFER_MOUTH, float)    # lamp_pole space: the middle of the snuffer's mouth
SN_OUT = np.array(lp.SNUFFER_AXIS, float)       # and the way it faces, out from the pole

# light_lamp: the wick swings out round the lantern's corner here on its way in and out (the carried wick rests at its
# back corner); the wick and the cap may meet the lantern's face while it is in its door
LL_ROUND = np.array([-6.0, -27.5, -16.0])
LL_TIP_IN = (16.5, 29.5)

# snuff_lamp's ticks: the solved stretch, the bell on the lantern, the flame out, the bell off and the smoke let out
SN_A, SN_B = 5.0, 35.0
SN_CLINK, SN_HISS, SN_SMOKE = 18.5, 23.0, 25.5
SN_HOLD = (16.0, 27.0)                          # the snuffer's mouth may meet the lantern's face
SN_DOOR = np.array([0.0, -26.6, -13.0])        # where the mouth goes: the lantern's near face, over the flame
SN_LAMP = np.array([0.0, -27.5, -16.0])        # the flame itself, in the lantern's middle
SN_TILT = 9.0                                   # degrees the mouth looks down as it sits there (the pole's top as far out
                                                # from upright, its foot in close in front of the lamplighter)
SN_LEFT = 10.0                                  # how far up the pole the left hand would hold it
SN_PITCH = [(SN_A, 0.0), (12.0, 2.0), (SN_CLINK, 3.0), (26.0, 3.0), (31.0, 2.0), (SN_B, 0.0)]
SN_TURN = [(0.0, 0.0), (SN_A + 1.0, 0.0), (13.5, 1.0), (27.5, 1.0), (33.0, 0.0), (40.0, 0.0)]   # how far turned
SN_ROLL = [90.0]                                # degrees the pole is turned in the hands at the lamp (solved, about a
                                                # quarter: the mouth square to the flame)
SN_FACE_W = 3.0                                 # how hard the solve squares the mouth to the flame (the turn in the
                                                # hands does most of it)

MARGIN = 0.6                                    # px kept between the pole (or the snuffer) and the lamp
BODY_MARGIN = 0.4                               # and between the pole and the lamplighter's head and body
CLEAR_MIN = 0.1                                 # what the checks call clear (the solves aim for the margins)


def make(la):
    """Builds the lamplighter's clips into living_anims.py's ([la]: the module, as it runs)."""
    global _OWN
    g = globals()
    if _OWN is None:
        _OWN = set(g)
    for k, v in vars(la).items():
        if not k.startswith('__') and k not in _OWN:
            g[k] = v
    g['la'] = la
    _setup(la)
    _carry_pole()
    _light_lamp()
    _snuff_lamp()
    _joins()


# ================================================================== the lamp station, and keeping clear of it

BOX_NAMES = ('post', 'fences', 'lantern', 'top')
HEAD_BOX = (np.array([-4.0, -8.0, -4.0]), np.array([4.0, 0.0, 4.0]))       # torso space: the head, unturned
TORSO_BOX = (np.array([-4.0, 0.0, -2.0]), np.array([4.0, 12.0, 2.0]))      # and the body


def _setup(la):
    global PM, BOXES, POLE_P, POLE_R, TIP, FLAME, BELL_P, BELL_MOUTH
    PM = ta.tool_local('lamp_pole')
    lamp = STATIONS['lamp']()
    # the wall post, the fences, the lantern and its top (its glass is inside it)
    BOXES = [(np.asarray(lo, float), np.asarray(hi, float)) for lo, hi, col, light in (lamp[0], lamp[1], lamp[2], lamp[4])]
    POLE_P = np.array([[x, 0.0, 0.0] for x in np.arange(2.0, 43.5, 1.0)])
    POLE_R = np.where(POLE_P[:, 0] > TIP_X, 0.95, 0.6)
    TIP = POLE_P[:, 0] > TIP_X
    FLAME = POLE_P[:, 0] > FLAME_X
    pts, mouth = [], []
    for c in next(p for p in lp._lamp_pole()[1].walk() if p.name == 'lamp_pole').cubes:
        if c.name not in ('t_lamp_pole_arm', 't_lamp_pole_crown', 't_lamp_pole_snuffer'):
            continue
        o, s = np.asarray(c.origin, float), np.asarray(c.size, float)
        for i in range(3):
            for j in range(3):
                for k in range(3):
                    pts.append(o + s * np.array([i, j, k]) / 2.0)
                    mouth.append(c.name == 't_lamp_pole_snuffer' and j == 0)     # its mouth is its -y face
    BELL_P, BELL_MOUTH = np.array(pts), np.array(mouth)
    # the previews' lamp, lit or dark as it is at the tick being drawn (INFO 'rise' hands the station the tick)
    STATIONS['lamp_light'] = lambda: _lamp_at(lit=_RISE[0] >= LL_LIT)
    STATIONS['lamp_snuff'] = lambda: _lamp_at(lit=_RISE[0] < SN_HISS)
    _no_flame(la)


def _lamp_at(lit):
    b = list(STATIONS['lamp']())
    if not lit:
        lo, hi, col, light = b[3]
        b[3] = (lo, hi, (58, 52, 44), True)
    return b


UNLIT = {'snuff_lamp'}      # clips whose previews draw the pole's wick out (the game: LivingProps, State.wick)


def _no_flame(la):
    """The previews draw no flame on the pole in the clips of UNLIT."""
    if getattr(ta.model_quads, 'lamp_clips', False):
        return
    inner = ta.model_quads

    def model_quads(look, mats, shown_tools):
        if _CUR[0] not in UNLIT:
            return inner(look, mats, shown_tools)
        parts = look.parts
        look.parts = {k: v for k, v in parts.items() if k != 'lamp_flame'}
        try:
            return inner(look, mats, shown_tools)
        finally:
            look.parts = parts
    model_quads.lamp_clips = True
    ta.model_quads = model_quads


def sdist(P, lo, hi):
    """Signed distances of points [P] (n x 3) from a box: outside positive, inside negative."""
    c, h = (lo + hi) / 2.0, (hi - lo) / 2.0
    q = np.abs(P - c) - h
    return np.linalg.norm(np.maximum(q, 0.0), axis=1) + np.minimum(q.max(axis=1), 0.0)


def rollx(deg):
    """A turn of [deg] degrees about x: the pole's turn in the hands, about its own length."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[1.0, 0.0, 0.0], [0.0, c, -s], [0.0, s, c]])


def rolled(P, roll):
    """lamp_pole-space points [P] (n x 3) with the pole turned [roll] degrees in the hands."""
    return P if not roll else P @ rollx(roll).T


def on_arm(fr, qa, P):
    """lamp_pole-space points [P] in the world, for a right arm keyed [qa] under [fr]."""
    Ra, j = arm_world('right_arm', qa, **fr)
    return (Ra @ (PM[:3, :3] @ P.T + PM[:3, 3:4])).T + j


def pole_point(fr, qa, p, roll=0.0):
    """A lamp_pole-space point in the world, the pole turned [roll] degrees in the hands."""
    return on_arm(fr, qa, rolled(np.asarray(p, float)[None, :], roll))[0]


def pole_dir(fr, qa, d, roll=0.0):
    """A lamp_pole-space direction in the world, the pole turned [roll] degrees in the hands."""
    Ra, j = arm_world('right_arm', qa, **fr)
    return Ra @ PM[:3, :3] @ (rollx(roll) @ np.asarray(d, float))


def torso_points(fr, P):
    """World points [P] in the torso's own space (torso_local, for many)."""
    A = (np.asarray(P, float) - HIPS - fr['pr']) @ qmat(fr['qr'])
    return (A - fr['vt']) @ qmat(fr['qt']) + HIPS


def gaps(pole, bell, t=None, tip_in=None, hold=None, flame=True):
    """The room (px) left between the pole's points [pole] (the snuffer's [bell]) and each of the lamp's boxes: the wick
    and the cap don't count against the lantern while [tip_in] (ticks), nor the snuffer's mouth against the lantern and
    its top while [hold]; the flame not at all unless [flame]. Arrays, the points' order, MARGIN where not counted."""
    out = []
    for i, (lo, hi) in enumerate(BOXES):
        d = sdist(pole, lo, hi) - POLE_R
        if not flame:
            d = np.where(FLAME, MARGIN, d)
        if tip_in and i == 2 and (t is None or tip_in[0] <= t <= tip_in[1]):
            d = np.where(TIP, MARGIN, d)
        b = sdist(bell, lo, hi)
        if hold and i >= 2 and (t is None or hold[0] <= t <= hold[1]):
            b = np.where(BELL_MOUTH, MARGIN, b)
        out.append((d, b))
    return out


def clear_res(fr, qa, t, w=1.5, roll=0.0, **kw):
    """Residuals pushing every point of the pole and the snuffer at least MARGIN px out of the lamp's boxes (gaps),
    the pole turned [roll] degrees in the hands."""
    out = []
    for d, b in gaps(on_arm(fr, qa, POLE_P), on_arm(fr, qa, rolled(BELL_P, roll)), t, **kw):
        out.append(np.maximum(0.0, MARGIN - d) * w)
        out.append(np.maximum(0.0, MARGIN - b) * w)
    return np.concatenate(out)


def body_res(fr, qa, w=1.5):
    """Residuals keeping the pole BODY_MARGIN px off the head (unturned: looking up only draws the face back from a pole
    in front of it) and the body."""
    L = torso_points(fr, on_arm(fr, qa, POLE_P))
    return np.concatenate([np.maximum(0.0, BODY_MARGIN - (sdist(L, lo, hi) - POLE_R)) * w for lo, hi in (HEAD_BOX, TORSO_BOX)])


def body_gap(fr, qa):
    L = torso_points(fr, on_arm(fr, qa, POLE_P))
    return min(float(np.min(sdist(L, lo, hi) - POLE_R)) for lo, hi in (HEAD_BOX, TORSO_BOX))


def stance_res(fr):
    """The torso kept on the legs: not lifted off them (a gap at the waist), nor sunk far into them."""
    s = float(fr['vt'][1])
    return [max(0.0, -s) * 4.0, max(0.0, s - 1.0) * 2.0]


def clearance(c, **kw):
    """The least room (px) between the pole (or the snuffer) and the lamp over [c] as drawn, when, and at what (gaps
    says what doesn't count)."""
    look = ta.look_for(ta.INFO[c.name]['look'])
    worst = (1e9, None, None)
    for t in ticks(0.0, c.length, 0.5):
        _CUR[0], _CUR[1] = c.name, t
        M = part_matrices(look, ta.pose_at(c, t, ta.walk_base(t, speed=0.0, age=t)))['lamp_pole']
        _CUR[0] = None
        pole = (M[:3, :3] @ POLE_P.T).T + M[:3, 3]
        bell = (M[:3, :3] @ BELL_P.T).T + M[:3, 3]
        for name, (d, b) in zip(BOX_NAMES, gaps(pole, bell, t, **kw)):
            g = min(float(np.min(d)), float(np.min(b)))
            if g < worst[0]:
                worst = (g, t, name)
    return worst

# ================================================================== carry_pole

def _carry_pole():
    # (40, loop, overlay) the arm hangs steady under the pole (light_lamp's and snuff_lamp's first frame), a touch
    # forward, the pole bobbing with each stride and swaying a little; the left arm swings free with the walk (added
    # to it, a little out); the head looks about and up at the lamps now and then
    c = clip('carry_pole', 40, True, look=('citizen', 4), tools_=('lamp_pole',), overlay=True,
             events=[('smoke', 12.0), ('smoke', 32.0)], traces=[('lamp_pole', LAMP_WICK, (255, 220, 120))],
             views=((32, -12), (90, -2), (-60, -10)))
    for t in every(40, 1.0):
        ph = 2 * math.pi * t / 40.0
        stride = math.sin(2 * ph)
        c.key('right_arm', t, hang('right_arm', out=IDLE_ARM_OUT + 0.8 * math.sin(ph), fwd=2.5 + 1.2 * stride), ease='linear')
        c.key('left_arm', t, qeuler(x=1.5 * math.sin(2 * ph), z=-3.0 - 1.0 * math.sin(ph)), ease='linear')
        up = math.sin(math.pi * min(1.0, max(0.0, (t - 14.0) / 14.0))) ** 2
        c.key('head', t, qeuler(x=1.0 * math.sin(2 * ph) - 9.0 * up, y=10.0 * math.sin(ph + 0.6) * (1.0 - 0.5 * up)), ease='linear')
    c.mode('left_arm', 'add')
    c.mode('head', 'add')
    seal(c)

# ================================================================== light_lamp, solved again round the snuffer

def ll_path():
    """light_lamp's wick's way: LL_PATH's moments and its work at the door, but swung out round the lantern's corner
    after leaving the carry and before coming back to it, and carried in front of the lantern as high as the pole comes
    when drawn upright (LL_PATH dips it to the lantern's foot, which holds the hand down by the knee)."""
    d = LAMP_DOOR
    return [(LL_A, LL_REST), (8.5, LL_ROUND), (11.0, np.array([-3.3, -29.0, -10.5])), (16.0, np.array([-1.0, -28.5, -10.5])),
            (LL_IN, d), (22.5, d + np.array([0.4, -0.3, -0.3])), (LL_LIT, d + np.array([-0.3, 0.2, -0.2])),
            (25.5, d + np.array([0.2, -0.2, -0.3])), (LL_OUT, d), (30.0, np.array([0.0, -28.5, -9.8])),
            (36.0, np.array([-2.6, -28.5, -10.4])), (40.0, LL_ROUND), (LL_B, LL_REST)]


def _light_lamp():
    global LL2_SOL, LL2_X
    path = ll_path()
    sols, xs, guess = {}, {}, None
    for t in ticks(LL_A, LL_B, 0.5):
        target = KV(path, t)

        def res(fr, qa, qb, target=target, t=t):
            Ra, j = arm_world('right_arm', qa, **fr)
            out = list((j + Ra @ tool_point('lamp_pole', LAMP_WICK) - target) * 2.0)
            lg = hand_point('left_arm', qb, fr)
            near, k = on_pole(fr, qa, lg)
            out += list((lg - near) * 1.2) + [(k - LAMP_LEFT) * 0.04]
            return out + list(clear_res(fr, qa, t, tip_in=LL_TIP_IN)) + list(body_res(fr, qa)) + stance_res(fr)
        fr, qa, qb, guess = body_solve(res, pitch0=K(LL_PITCH, t), guess=guess, pitch_w=0.3, yaw_w=0.15, root_w=1.0)
        sols[t], xs[t] = (fr, qa, qb), guess
    LL2_SOL, LL2_X = sols, xs
    c = clip('light_lamp', 50, False, look=('citizen', 4), tools_=('lamp_pole',), station_='lamp_light',
             events=[('reach', LL_IN), ('lit', LL_LIT)], traces=[('lamp_pole', LAMP_WICK, (255, 220, 120))],
             views=((40, -10, True), (90, -2), (-50, -10)), pan=(0.0, -12.0, -6.0))
    ta.INFO['light_lamp']['rise'] = lambda t: t
    _lift(c, sols, LL_A, LL_B, 50.0, LL_LEFT_ON, 1.0, 41.0, LAMP_DOOR + np.array([0.0, 1.0, -2.0]))
    check(c, 'wick in the lantern\'s face (t=21)', ta.fk(c, LL_IN, 'lamp_pole', LAMP_WICK), LAMP_DOOR, tol=0.5)
    check(c, 'wick in the lantern\'s face (t=24)', ta.fk(c, LL_LIT, 'lamp_pole', LAMP_WICK), LAMP_DOOR, tol=0.8)
    for k in (10.0, LL_IN, 30.0):
        fr_k, qa_k, qb_k = sols[k]
        near_k, up_k = on_pole(fr_k, qa_k, ta.fk(c, k, 'left_arm', GRIP['left_arm']))
        check(c, f'left hand on the pole (t={k:g}, {up_k:.1f} px up it)', ta.fk(c, k, 'left_arm', GRIP['left_arm']), near_k, tol=0.8)
    _report_clear(c, sols, tip_in=LL_TIP_IN)


def _lift(c, sols, a, b, length, left_on, look_from, look_to, look_at):
    """Keys a lamp clip from its solved stretch [a]..[b] ([sols]), blending in from the carry before it and out to the
    carry after it; the left hand on the pole as [left_on] has it, the eyes on [look_at] from [look_from] to [look_to]."""
    for t in every(int(length)):
        if t < a:
            w = smooth(t / a)
            f1, qa1, qb1 = sols[a]
            fr, qa, qb = mix_frames(LL_FR0, f1, w), qslerp(LL_Q0, qa1, w), qb1
        elif t <= b:
            fr, qa, qb = sols[t]
        else:
            w = smooth((t - b) / (length - b))
            f1, qa1, qb1 = sols[b]
            fr, qa, qb = mix_frames(f1, LL_FR0, w), qslerp(qa1, LL_Q0, w), qb1
        key_frame(c, t, fr, ease='linear')
        c.key('right_arm', t, qa, ease='linear')
        c.key('left_arm', t, qslerp(hang('left_arm', fr), qb, smooth(K(left_on, t))), ease='linear')
        plant(c, t, fr)
        up = smooth((t - look_from) / 8.0) * (1.0 - smooth((t - look_to) / 8.0))
        target = look_at(t) if callable(look_at) else look_at
        c.key('head', t, qslerp(qeuler(), head_at(fr, target), 0.85 * up), ease='linear')
    c.mode('head', 'set')


def _report_clear(c, sols, **kw):
    g, t, name = clearance(c, **kw)
    sinks = [float(fr['vt'][1]) for fr, qa, qb in sols.values()]
    steps = [float(np.hypot(fr['pr'][0], fr['pr'][2])) for fr, qa, qb in sols.values()]
    body = min(body_gap(fr, qa) for fr, qa, qb in sols.values())
    print(f'   {c.name}: {g:.2f} px clear of the lamp at the least (t={t}, the {name}), {body:.2f} px of the '
          f'lamplighter; the torso sinks {min(sinks):.2f}..{max(sinks):.2f} px, the hips move {max(steps):.2f} px at most')
    # (the carry they start and end on passes the lantern's back corner 0.16 px off, the pole's cap behind it)
    ta.INFO[c.name]['checks'].append((f'the pole and snuffer clear of the lamp (least at t={t}, the {name})',
                                      np.array([max(0.0, CLEAR_MIN - g)]), np.array([0.0]), 0.0))
    ta.INFO[c.name]['checks'].append(('the pole clear of the lamplighter', np.array([max(0.0, CLEAR_MIN - body)]),
                                      np.array([0.0]), 0.0))

# ================================================================== snuff_lamp

def sn_roll(t):
    """How far (degrees) snuff_lamp has the pole turned in the hands at [t]: none in the carry, SN_ROLL at the lamp."""
    return SN_ROLL[0] * K(SN_TURN, t)


def best_roll(fr, qa):
    """The turn of the pole in the hands that brings the snuffer's mouth squarest to the flame (SN_FACE), for a right
    arm keyed [qa] under [fr]: turned r about the pole, the mouth faces -cos r of the pole's y and -sin r of its z."""
    M = arm_world('right_arm', qa, **fr)[0] @ PM[:3, :3]
    return math.degrees(math.atan2(-float(M[:, 2] @ SN_FACE), -float(M[:, 1] @ SN_FACE)))


def sn_path(rest):
    """The mouth's way: the pole drawn up near upright in front and turned in the hands, the bell coming round and in
    below the lantern on its right, across and up in front of its face and onto it over the flame; held there, a little
    worked, till the flame's out; drawn off and down, turned back, and back to the carry."""
    return [(SN_A, rest),
            (9.5, np.array([-5.5, -23.5, -15.0])),
            (13.5, np.array([-2.0, -25.2, -11.4])),
            (16.5, np.array([0.0, -26.0, -11.6])),
            (SN_CLINK, SN_DOOR),
            (20.5, SN_DOOR + np.array([0.15, -0.1, 0.0])),
            (SN_HISS, SN_DOOR),
            (24.5, SN_DOOR),
            (26.5, SN_DOOR + np.array([0.0, 0.3, 1.7])),
            (29.5, np.array([-2.0, -25.2, -11.4])),
            (32.5, np.array([-5.5, -23.5, -15.0])),
            (SN_B, rest)]


def _sn_solve(t, target, fw, guess):
    roll = sn_roll(t)
    mouth, out_dir = rolled(SN_MOUTH[None, :], roll)[0], rollx(roll) @ SN_OUT

    def res(fr, qa, qb):
        Ra, j = arm_world('right_arm', qa, **fr)
        out = list((j + Ra @ (PM[:3, :3] @ mouth + PM[:3, 3]) - target) * 2.0)
        out += list((Ra @ PM[:3, :3] @ out_dir - SN_FACE) * SN_FACE_W * fw)
        lg = hand_point('left_arm', qb, fr)
        near, k = on_pole(fr, qa, lg)
        out += list((lg - near) * 1.2) + [(k - SN_LEFT) * 0.04]
        return (out + list(clear_res(fr, qa, t, roll=roll, hold=SN_HOLD, flame=False)) + list(body_res(fr, qa))
                + stance_res(fr))
    return body_solve(res, pitch0=K(SN_PITCH, t), guess=guess, pitch_w=0.3, yaw_w=0.15, root_w=1.0)


def _pole_track(t, pivot):
    """snuff_lamp's track for the pole: where the arm carries it, turned sn_roll(t) in the hands."""
    c = getc('snuff_lamp')
    look = ta.look_for(ta.INFO['snuff_lamp']['look'])
    M = part_matrices(look, ta.pose_at(c, t, ta.walk_base(t, speed=0.0, age=t)), hang=False)['lamp_pole']
    return M[:3, :3] @ rollx(sn_roll(t)), None, None


def _snuff_lamp():
    global SN_SOL, SN_X, SN_REST, SN_FACE
    SN_REST = pole_point(LL_FR0, LL_Q0, SN_MOUTH)
    a = math.radians(SN_TILT)
    SN_FACE = np.array([0.0, math.sin(a), -math.cos(a)])
    path = sn_path(SN_REST)
    face = [(SN_A, 0.0), (11.0, 0.2), (15.0, 1.0), (27.0, 1.0), (30.0, 0.2), (SN_B, 0.0)]
    # the turn at the lamp first: the pose holding the bell on the lantern, and the turn squaring its mouth to the flame
    SN_ROLL[0], guess = 90.0, None
    for _ in range(3):
        fr, qa, qb, guess = _sn_solve(SN_CLINK, SN_DOOR, 1.0, guess)
        SN_ROLL[0] = best_roll(fr, qa)
    sols, xs, guess = {}, {}, None
    for t in ticks(SN_A, SN_B, 0.5):
        fr, qa, qb, guess = _sn_solve(t, KV(path, t), K(face, t), guess)
        sols[t], xs[t] = (fr, qa, qb), guess
    SN_SOL, SN_X = sols, xs
    c = clip('snuff_lamp', 40, False, look=('citizen', 4), tools_=('lamp_pole',), station_='lamp_snuff',
             events=[('clink', SN_CLINK), ('hiss', SN_HISS), ('smoke', SN_SMOKE)],
             traces=[('lamp_pole', tuple(SN_MOUTH), (230, 200, 140))],
             views=((40, -10, True), (90, -2), (-50, -10)), pan=(0.0, -12.0, -6.0))
    ta.INFO['snuff_lamp']['rise'] = lambda t: t
    left_on = [(0, 0.0), (1.0, 0.0), (SN_A, 1.0), (30.5, 1.0), (34.0, 0.0), (40, 0.0)]

    def look(t):
        # on the lamp, and up after the smoke as it goes
        return SN_DOOR + np.array([0.0, 1.0 - 4.0 * smooth((t - SN_SMOKE) / 6.0), -2.0])
    _lift(c, sols, SN_A, SN_B, 40.0, left_on, 1.0, 32.0, look)
    TRACKS['snuff_lamp'] = {'lamp_pole': _pole_track}
    print(f'   snuff_lamp: the pole turned {SN_ROLL[0]:.0f} degrees in the hands at the lamp; the mouth carried at',
          np.round(SN_REST, 1))
    for k in (SN_CLINK, SN_HISS):
        m = ta.fk(c, k, 'lamp_pole', tuple(SN_MOUTH))
        check(c, f'snuffer on the lantern\'s face (t={k:g})', m, SN_DOOR, tol=0.5)
        f = ta.fk(c, k, 'lamp_pole', tuple(SN_MOUTH + SN_OUT)) - m
        off = math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(f, SN_FACE))))))
        check(c, f'its mouth square to the flame (t={k:g}, {off:.0f} deg off)', f, SN_FACE, tol=0.2)
    for k in (10.0, SN_HISS, 28.0):
        fr_k, qa_k, qb_k = sols[k]
        near_k, up_k = on_pole(fr_k, qa_k, ta.fk(c, k, 'left_arm', GRIP['left_arm']))
        check(c, f'left hand on the pole (t={k:g}, {up_k:.1f} px up it)', ta.fk(c, k, 'left_arm', GRIP['left_arm']), near_k, tol=0.8)
    _report_clear(c, sols, hold=SN_HOLD, flame=False)


# ================================================================== where things happen, what is made, what meets what

def moment_specs():
    """living_anims.moment_specs, and the lamplighter's moments: the wick's smoke from its tip, the snuffer's clink on
    the lantern's face, the flame's hiss in its middle, the smoke let out at its door."""
    specs = _MOMENT_SPECS()
    specs['carry_pole'] = {'smoke': ('part', 'lamp_pole', WICK_TIP)}
    specs['snuff_lamp'] = {'clink': ('point', tuple(SN_DOOR)), 'hiss': ('point', tuple(SN_LAMP)),
                           'smoke': ('point', tuple(SN_DOOR + np.array([0.0, -0.5, 0.6])))}
    return specs


def _joins():
    global _MOMENT_SPECS
    if la.moment_specs is not moment_specs:
        _MOMENT_SPECS = la.moment_specs
        la.moment_specs = moment_specs
    for name in ('carry_pole', 'snuff_lamp'):
        if name not in WANTED:
            WANTED.append(name)
    carry = getc('carry_pole')
    for name, end in (('light_lamp', 50), ('snuff_lamp', 40)):
        join(f'{name} starts from carry_pole', carry, 0, getc(name), 0, tol=1.5)
        join(f'{name} ends on carry_pole', getc(name), end, carry, 0, tol=1.5)
