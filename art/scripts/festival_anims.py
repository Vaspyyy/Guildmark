"""
The grand festivals' clips for the townsfolk: the steps of the four dances, the band's minstrels, the crowd clapping
along, the decorations going up, and the Lanternrise's lanterns carried round the ring and let go at nightfall. They
are built on townsfolk_anims.py's skeleton, kit and previews, and played by the same CombatAnimator. Run from the
repo root:

    python3 -B art/scripts/festival_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]

Writes only the festival's clips, assets/guildmark/animations/townsfolk/fest_<clip>.json. townsfolk_anims.py builds
its own clips on import, but they are not written again here. It also writes a contact sheet per clip (or only the
clips named) into <preview-dir>, and prints where the dancers' hands are on the beats, as the table that
FestivalOrnamentRenderer ties the Ribbonwake's ribbons to the hands with (RIBBON_HANDS there), and where the
lantern is as fest_release lets it go (FestivalSite's LET_GO_*, where the sky lantern rises from).

The music is 120 beats a minute in 4/4, so a bar is 40 ticks and a beat is 10. The server starts a dancer's step
afresh on every bar (FestivalSite), so each dance step is one bar long and ends where it began. The steps move
nothing but the body: the server carries the dancers round the ring, in and out, and round their partners.

- fest_ring_step / fest_ring_step_l: a skipping step round the ring, one skip a beat, the hand toward the ring held
  up (at the Ribbonwake it holds a ribbon from the pole) and the other swinging.
- fest_ring_in: two steps in toward the middle with both arms rising to a shout, two steps back out.
- fest_couple_turn: partners face each other, hands joined, leaning back against the swing and stepping round.
- fest_twirl: a twirl on the spot, one arm over the head.
- fest_bow: the finale's bow: the right foot drawn back, a sweep of the arm, a low bow, and up.
- fest_hurrah: a crouch, a jump with both arms flung up, a wave of them, down.
- fest_clap (loop): clapping on every beat (0, 10, 20, 30, where TownsfolkClient plays the clap), bouncing to it.
- fest_lantern_walk: the Lanternrise's slow walk, two steps a bar, the lantern held out in the left hand.
- fest_lantern_raise: the lantern raised high over the head and brought down again, stepping in and out.
- fest_minstrel (loop): playing the lute standing with the band, strumming eighths and swaying through the bar.
- fest_hang (loop): reaching up on tiptoe to tie a garland or pennants, the hands working in turn.
- fest_release: the lantern brought up over the head with both hands and let go at tick 22. That is when the server
  sends up the sky lantern from where the held one is, and the townsperson turns to watch it (fest_gaze).
- fest_gaze (loop, 80): looking up at the fireworks and lanterns, the head turning slowly, now and then pointing.
"""
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import townsfolk_anims as ta
from townsfolk_anims import (clip, F_, bend, key_frame, legs, stand_legs, free, relaxed, reach, seal, mirror_dir, ticks,
                             check, fk, bump, smooth, GRIP, HIPS, qeuler, qmat, v, F, B, U, D, R, L, I)

BAR = 40
BEAT = 10
NAMES = []
LANTERN_CENTRE = (0.0, 4.0, 0.0)     # lantern part space: the middle of its glass, below the bail in the hand


def fest(name, length, loop, **kw):
    c = clip(name, length, loop, **kw)
    NAMES.append(name)
    return c


def hands_trace():
    return [('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))]


# ================================================================== the ring steps

def ring_step(name, side, look):
    """A skip a beat (right foot down on 0 and 20, left on 10 and 30, a hop between), round the ring with the ring on
    the [side] (+1 right, -1 left): that hand held up toward it, the other swinging opposite its leg."""
    c = fest(name, BAR, False, look=look, traces=hands_trace())
    s_ = side
    up_arm, low_arm = ('right_arm', 'left_arm') if s_ > 0 else ('left_arm', 'right_arm')
    for t in ticks(0, BAR, 2.0):
        ph = 2 * math.pi * t / 20.0
        hop = abs(math.sin(ph))
        weight = math.cos(ph)                           # +1: on the right foot (just landed), -1: on the left
        fr = F_(qt=bend(4.0 - 1.5 * hop, 7.0 * s_, -4.0 * s_ + 2.0 * weight), pr=(0.0, -1.4 * hop, 0.0))
        key_frame(c, t, fr, ease='linear')
        lift = 0.75 + 0.06 * math.sin(ph)
        c.key(up_arm, t, free(fr, v(f=0.22, u=lift, r=1.0 * s_), F), ease='linear')
        # The low arm swings against its own leg: the left arm forward while the left leg is back
        swing = 0.32 * weight if low_arm == 'left_arm' else -0.32 * weight
        c.key(low_arm, t, free(fr, v(f=0.12 + swing, u=-1.0, r=-0.3 * s_), B), ease='linear')
        c.key('head', t, qeuler(x=-6.0 - 2.0 * hop, y=16.0 * s_, z=-2.0 * s_), ease='linear')
        rx = -16.0 * math.cos(ph) - 14.0 * max(0.0, -math.sin(ph))
        lx = 16.0 * math.cos(ph) - 14.0 * max(0.0, math.sin(ph))
        legs(c, t, rx=rx, lx=lx, rz=2.0, lz=-2.0, ease='linear')
    c.mode('head', 'add')
    return c


ring_step('fest_ring_step', 1, ('citizen', 6))
ring_step('fest_ring_step_l', -1, ('citizen', 7))


# ---- ring in: step in, arms rising to a shout at the middle of the bar, back out
c = fest('fest_ring_in', BAR, False, look=('farmer', 0), traces=hands_trace(), events=[('hey', 20)])
for t in ticks(0, BAR, 2.0):
    e = math.sin(math.pi * t / BAR) ** 1.5
    fr = F_(qt=bend(2.0 - 6.0 * e), pr=(0.0, -1.0 * e, 0.0))
    key_frame(c, t, fr, ease='linear')
    for side, s_ in (('right_arm', 1), ('left_arm', -1)):
        d = v(f=1.0 - 0.3 * e, u=-0.7 + 2.4 * e, r=s_ * (0.3 - 0.15 * e))
        c.key(side, t, free(fr, d, B if e < 0.5 else F), ease='linear')
    c.key('head', t, qeuler(x=-14.0 * e), ease='linear')
    # Step-touch: the right foot forward then the left (in), the right foot back then the left (out)
    rx = lx = 0.0
    if t < 10:
        rx = -14.0 * math.sin(math.pi * t / 10)
    elif t < 20:
        lx = -14.0 * math.sin(math.pi * (t - 10) / 10)
    elif t < 30:
        rx = 12.0 * math.sin(math.pi * (t - 20) / 10)
    else:
        lx = 12.0 * math.sin(math.pi * (t - 30) / 10)
    legs(c, t, rx=rx, lx=lx, rz=1.5, lz=-1.5, ease='linear')
c.mode('head', 'add')


# ---- couple turn: facing the partner, both hands joined between them, leaning back, stepping round
PARTNER_HANDS = np.array([0.0, 7.5, -7.6])       # where the joined hands are: halfway to a partner 15 px off
c = fest('fest_couple_turn', BAR, False, look=('noble', 1), traces=hands_trace())
for t in ticks(0, BAR, 2.0):
    ph = 2 * math.pi * t / 20.0
    bob = abs(math.sin(ph))
    fr = F_(qt=bend(-8.0 + 1.5 * bob, 3.0 * math.sin(2 * math.pi * t / BAR), 1.5 * math.cos(ph)), pr=(0.0, -0.8 * bob, 0.0))
    key_frame(c, t, fr, ease='linear')
    for side, s_ in (('right_arm', 1), ('left_arm', -1)):
        target = PARTNER_HANDS + np.array([-s_ * 2.6, 0.4 * math.sin(ph), 0.0])
        c.key(side, t, reach(side, fr, target, palm=v(f=1.0, u=-0.2, r=-s_ * 0.6), palm_w=0.3), ease='linear')
    c.key('head', t, qeuler(x=-5.0, z=4.0 * math.sin(2 * math.pi * t / BAR)), ease='linear')
    rx = -11.0 * math.cos(ph) - 8.0 * max(0.0, -math.sin(ph))
    lx = 11.0 * math.cos(ph) - 8.0 * max(0.0, math.sin(ph))
    legs(c, t, rx=rx, lx=lx, rz=3.0 + 3.0 * max(0.0, -math.sin(ph)), lz=-3.0 - 3.0 * max(0.0, math.sin(ph)), ease='linear')
c.mode('head', 'add')


# ---- twirl: a wind-up the other way, a whole turn on the spot (the root's yaw), the right arm over the head and the
# left out, small steps round, and settle facing the way they began
c = fest('fest_twirl', BAR, False, look=('citizen', 3), traces=hands_trace())
TWIRL = [(0.0, 0.0, 0.0, 0.0, 'inout'), (5.0, -25.0, 0.4, 0.35, 'inout'), (9.0, 28.0, -0.5, 0.85, 'in'),
         (14.0, 118.0, -1.2, 1.0, 'linear'), (20.0, 220.0, -1.4, 1.0, 'linear'), (26.0, 312.0, -1.0, 1.0, 'linear'),
         (31.0, 362.0, -0.3, 0.7, 'out'), (35.5, 360.0, 0.3, 0.25, 'inout'), (40.0, 360.0, 0.0, 0.0, 'inout')]
for t, yaw, lift, arms, ease in TWIRL:
    fr = F_(qt=bend(-5.0 * arms, 0.0, 3.0 * arms), pr=(0.0, lift, 0.0), qr=qeuler(y=yaw))
    key_frame(c, t, fr, ease=ease)
    rest_r, rest_l = relaxed(fr), relaxed(fr, 'left_arm')
    up_r = free(fr, v(f=0.12, u=1.0, r=-0.28), F)
    out_l = free(fr, v(f=0.1, u=0.25, r=-1.0), D)
    c.key('right_arm', t, ta.qslerp(rest_r, up_r, smooth(arms)), ease=ease)
    c.key('left_arm', t, ta.qslerp(rest_l, out_l, smooth(arms)), ease=ease)
    c.key('head', t, qeuler(x=-10.0 * arms, z=5.0 * arms), ease=ease)
    step = math.sin(math.pi * t / 6.0) * arms
    legs(c, t, rx=-6.0 * step, lx=6.0 * step, rz=2.0 + 2.0 * arms, lz=-2.0 - 2.0 * arms, ease=ease)
c.mode('head', 'add')


# ---- bow: the right foot drawn back and the right arm sweeping out and in across the front, a low bow held, and up
c = fest('fest_bow', BAR, False, look=('noble', 0), traces=hands_trace(), events=[('bow', 16)])
BOW = [  # (t, pitch, right arm (direction, palm), left arm direction, right leg back, head, ease)
    (0.0, 0.0, None, None, 0.0, 0.0, 'inout'),
    (6.0, -3.0, (v(f=0.55, u=0.35, r=0.85), F), v(f=-0.3, u=-0.9, r=-0.45), 10.0, -6.0, 'inout'),     # the flourish
    (12.0, 26.0, (v(f=0.9, u=-0.55, r=-0.35), B), v(f=-0.55, u=-0.85, r=-0.4), 16.0, 10.0, 'inout'),  # going down
    (16.0, 36.0, (v(f=0.75, u=-0.75, r=-0.45), B), v(f=-0.7, u=-0.7, r=-0.35), 18.0, 16.0, 'out'),    # the bow
    (25.0, 37.0, (v(f=0.75, u=-0.75, r=-0.45), B), v(f=-0.7, u=-0.7, r=-0.35), 18.0, 18.0, 'inout'),  # held
    (33.0, 6.0, None, None, 6.0, 2.0, 'inout'),                                                        # up
    (40.0, 0.0, None, None, 0.0, 0.0, 'inout'),
]
for t, pitch, ra, la, back, head, ease in BOW:
    fr = F_(qt=bend(pitch, -4.0 if pitch > 10 else 0.0, 0.0), vt=(0.0, ta.sink_for(pitch) * 0.5, 0.0))
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, relaxed(fr) if ra is None else free(fr, ra[0], ra[1]), ease=ease)
    c.key('left_arm', t, relaxed(fr, 'left_arm') if la is None else free(fr, la, B), ease=ease)
    c.key('head', t, qeuler(x=head), ease=ease)
    legs(c, t, rx=back, lx=-0.25 * back, rz=1.0, lz=-1.0, ease=ease)
c.mode('head', 'add')


# ---- hurrah: crouch, spring up with both arms flung high, land, wave them, and down
c = fest('fest_hurrah', BAR, False, look=('farmer', 1), traces=hands_trace(), events=[('hurrah', 9)])
HURRAH = [  # (t, root y, pitch, arms (None: relaxed, 'back', 'up' with a sway), legs ('stand' or (rx, lx)), ease)
    (0.0, 0.0, 0.0, None, 'stand', 'inout'),
    (4.0, 1.6, 12.0, 'back', 'stand', 'inout'),
    (6.0, -0.5, -3.0, ('up', 0.0), 'stand', 'in'),
    (9.0, -4.8, -7.0, ('up', 0.0), (12.0, -9.0), 'out'),
    (12.5, 0.0, 1.0, ('up', 0.0), 'stand', 'in'),
    (14.0, 1.2, 6.0, ('up', 0.0), 'stand', 'out'),
    (18.0, 0.0, -2.0, ('up', 1.0), 'stand', 'inout'),
    (22.0, -0.4, -3.0, ('up', -1.0), 'stand', 'inout'),
    (26.0, 0.0, -2.0, ('up', 1.0), 'stand', 'inout'),
    (30.0, 0.0, -1.0, ('up', 0.0), 'stand', 'inout'),
    (36.0, 0.0, 0.0, None, 'stand', 'inout'),
    (40.0, 0.0, 0.0, None, 'stand', 'inout'),
]
for t, y, pitch, arms, lg, ease in HURRAH:
    fr = F_(qt=bend(pitch, 0.0, 0.0 if not isinstance(arms, tuple) else -3.0 * arms[1]), pr=(0.0, y, 0.0))
    key_frame(c, t, fr, ease=ease)
    if arms is None:
        qa, qb = relaxed(fr), relaxed(fr, 'left_arm')
    elif arms == 'back':
        qa = free(fr, v(f=-0.45, u=-1.0, r=0.25), F)
        qb = free(fr, v(f=-0.45, u=-1.0, r=-0.25), F)
    else:
        sway = arms[1]
        qa = free(fr, v(f=0.22, u=1.0, r=0.48 + 0.18 * sway), F)
        qb = free(fr, v(f=0.22, u=1.0, r=-0.48 + 0.18 * sway), F)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, qeuler(x=-0.5 * pitch - (14.0 if arms not in (None, 'back') else 0.0)), ease=ease)
    if lg == 'stand':
        stand_legs(c, t, root=fr['pr'], right=(2.4 if y > 0.5 else 0.4, 0.5), left=(2.4 if y > 0.5 else 0.4, -0.5), ease=ease)
    else:
        legs(c, t, rx=lg[0], lx=lg[1], rz=4.0, lz=-4.0, ease=ease)
c.mode('head', 'add')


# ---- clap along (loop): a clap on every beat, a bounce into each, swaying through the bar
CLAP_AT = np.array([0.0, 6.2, -8.4])
c = fest('fest_clap', BAR, True, look=('citizen', 2), traces=hands_trace(),
         events=[('clap', 0), ('clap', 10), ('clap', 20), ('clap', 30)])
for t in ticks(0, BAR, 2.5):
    k = (t % BEAT) / BEAT                     # 0 on the beat, the hands together
    shut = 0.5 + 0.5 * math.cos(2 * math.pi * k)
    sway = math.sin(2 * math.pi * t / BAR)
    fr = F_(qt=bend(2.0 + 2.5 * shut, 4.0 * sway, 3.0 * sway), pr=(0.0, 0.7 * shut, 0.0))
    key_frame(c, t, fr, ease='linear')
    gap = 2.4 + 4.3 * (1 - shut)
    rise = -1.0 * (1 - shut)
    ahead = 0.9 * (1 - shut)
    qa = reach('right_arm', fr, CLAP_AT + np.array([-gap, rise, ahead]), palm=L, palm_w=0.6)
    qb = reach('left_arm', fr, CLAP_AT + np.array([gap, rise, ahead]), palm=R, palm_w=0.6)
    c.key('right_arm', t, qa, ease='linear')
    c.key('left_arm', t, qb, ease='linear')
    c.key('head', t, qeuler(x=-5.0 + 3.0 * shut, z=4.0 * sway), ease='linear')
    stand_legs(c, t, root=fr['pr'], right=(0.6 + 1.6 * shut, 0.4), left=(0.6 + 1.6 * shut, -0.4), ease='linear')
c.mode('head', 'add')
seal(c)
for _t in (0, 10, 20, 30):
    _r, _l = fk(c, _t, 'right_arm', GRIP['right_arm']), fk(c, _t, 'left_arm', GRIP['left_arm'])
    check(c, f'hands meet (t={_t})', _r, _l + np.array([-4.0, 0.0, 0.0]), tol=0.8)


# ================================================================== the Lanternrise

# ---- lantern walk: the slow ring, a step on 0 (right) and 20 (left), the lantern held out before them
c = fest('fest_lantern_walk', BAR, False, look=('priest', 0), tools_=('lantern',),
         traces=[('lantern', LANTERN_CENTRE, (255, 220, 120))])
for t in ticks(0, BAR, 2.5):
    ph = 2 * math.pi * t / BAR
    bob = abs(math.sin(ph))
    fr = F_(qt=bend(3.0, -3.0 * math.cos(ph), 1.5 * math.cos(ph)), pr=(0.0, -0.5 * bob, 0.0))
    key_frame(c, t, fr, ease='linear')
    c.key('left_arm', t, free(fr, v(f=1.0, u=0.22 + 0.04 * math.sin(2 * ph), r=0.05), R), ease='linear')
    c.key('right_arm', t, free(fr, v(f=0.1 - 0.22 * math.cos(ph), u=-1.0, r=0.2), B), ease='linear')
    c.key('head', t, qeuler(x=5.0, y=-4.0 * math.cos(ph)), ease='linear')
    rx = -15.0 * math.cos(ph) - 6.0 * max(0.0, -math.sin(ph))
    lx = 15.0 * math.cos(ph) - 6.0 * max(0.0, math.sin(ph))
    legs(c, t, rx=rx, lx=lx, rz=1.0, lz=-1.0, ease='linear')
c.mode('head', 'add')


# ---- lantern raise: up over the head together, held at the top of the bar, and down; stepping in and back
c = fest('fest_lantern_raise', BAR, False, look=('priest', 0), tools_=('lantern',),
         traces=[('lantern', LANTERN_CENTRE, (255, 220, 120))], events=[('high', 20)])
for t in ticks(0, BAR, 2.0):
    e = smooth(min(1.0, math.sin(math.pi * t / BAR) * 1.25))
    fr = F_(qt=bend(3.0 - 7.0 * e), pr=(0.0, -0.9 * e, 0.0))
    key_frame(c, t, fr, ease='linear')
    lantern_dir = v(f=1.0 - 0.62 * e, u=0.22 + 0.95 * e, r=0.05 - 0.12 * e)
    c.key('left_arm', t, free(fr, lantern_dir, R), ease='linear')
    # The free hand rises out to the side, palm to the sky, clear of the face
    c.key('right_arm', t, free(fr, v(f=0.9 - 0.55 * e, u=-0.75 + 1.6 * e, r=0.25 + 0.3 * e), F if e > 0.5 else B), ease='linear')
    c.key('head', t, qeuler(x=5.0 - 24.0 * e), ease='linear')
    rx = lx = 0.0
    if t < 10:
        rx = -10.0 * math.sin(math.pi * t / 10)
    elif t < 20:
        lx = -10.0 * math.sin(math.pi * (t - 10) / 10)
    elif t < 30:
        rx = 9.0 * math.sin(math.pi * (t - 20) / 10)
    else:
        lx = 9.0 * math.sin(math.pi * (t - 30) / 10)
    legs(c, t, rx=rx, lx=lx, rz=1.0, lz=-1.0, ease='linear')
c.mode('head', 'add')


# ---- release: the lantern from the chest up over the head in both hands, up on the toes, and let go at 22 (the server
# sends the sky lantern up from just there and turns them to watch it); then the arms open after it
RELEASE_TICK = 22
RELEASE = [  # (t, left grip target, right hand target, root y, head pitch, ease)
    (0.0, (1.5, 9.5, -8.0), (-1.5, 10.5, -8.5), 0.0, 6.0, 'inout'),
    (8.0, (1.3, 1.0, -8.5), (-1.8, 2.5, -8.5), 0.0, -10.0, 'inout'),
    (15.0, (1.0, -6.0, -6.0), (-2.2, -4.5, -6.0), -0.6, -28.0, 'inout'),
    (20.0, (0.8, -8.5, -4.5), (-2.4, -7.0, -4.5), -1.3, -36.0, 'out'),
    (22.0, (0.8, -9.0, -4.0), (-2.4, -7.5, -4.0), -1.4, -38.0, 'inout'),
]
c = fest('fest_release', BAR, False, look=('citizen', 5), tools_=('lantern',),
         traces=[('lantern', LANTERN_CENTRE, (255, 220, 120)), ('right_arm', GRIP['right_arm'], (255, 200, 120))],
         events=[('let_go', RELEASE_TICK)])
for t, lg, rg, y, head, ease in RELEASE:
    fr = F_(qt=bend(-4.0 * min(1.0, t / 15.0)), pr=(0.0, y, 0.0))
    key_frame(c, t, fr, ease=ease)
    c.key('left_arm', t, reach('left_arm', fr, np.array(lg), palm=R, palm_w=0.1), ease=ease)
    c.key('right_arm', t, reach('right_arm', fr, np.array(rg), palm=L, palm_w=0.1), ease=ease)
    c.key('head', t, qeuler(x=head), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=(0.5, 0.0), left=(0.5, 0.0), ease=ease)
for t, spread, y, head, ease in ((28.0, 1.0, -0.6, -42.0, 'out'), (34.0, 1.0, 0.0, -40.0, 'inout'), (40.0, 0.9, 0.0, -38.0, 'inout')):
    fr = F_(qt=bend(-5.0), pr=(0.0, y, 0.0))
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, free(fr, v(f=0.3, u=1.0, r=0.55 * spread), F), ease=ease)
    c.key('left_arm', t, free(fr, v(f=0.3, u=1.0, r=-0.55 * spread), F), ease=ease)
    c.key('head', t, qeuler(x=head), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=(0.5, 0.0), left=(0.5, 0.0), ease=ease)
c.mode('head', 'add')


# ---- gaze (loop, 80): face to the sky, the head turning slowly from one burst to the next, a point up now and then
c = fest('fest_gaze', 80, True, look=('child', 0), traces=hands_trace())
for t in ticks(0, 80, 4.0):
    ph = 2 * math.pi * t / 80.0
    point = bump(t, 22.0, 58.0)
    fr = F_(qt=bend(-5.0 - 2.0 * point, 5.0 * math.sin(ph), 1.5 * math.sin(2 * ph)))
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, ta.qslerp(relaxed(fr), free(fr, v(f=0.55, u=1.0, r=0.3 + 0.25 * math.sin(ph)), F), smooth(point)), ease='linear')
    c.key('left_arm', t, relaxed(fr, 'left_arm', out=5.0, fwd=2.0), ease='linear')
    c.key('head', t, qeuler(x=-30.0 + 4.0 * math.sin(2 * ph), y=20.0 * math.sin(ph)), ease='linear')
    stand_legs(c, t, root=fr['pr'], right=(0.6, 0.3), left=(0.6, -0.3), ease='linear')
c.mode('head', 'add')
seal(c)


# ================================================================== the band and the helpers

# ---- minstrel (loop): the lute played standing, eighths strummed (down on the beat), swaying through the bar
c = fest('fest_minstrel', BAR, True, look=('bard', 0), tools_=('lute',), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
for t in ticks(0, BAR, 1.25):
    beat = (t % BEAT) / BEAT
    stroke = math.cos(2 * math.pi * t / 5.0)
    bob = math.cos(2 * math.pi * beat)
    sway = math.sin(2 * math.pi * t / BAR)
    fr = F_(qt=bend(2.0 + 1.4 * bob, 5.0 * sway, 2.0 * sway))
    key_frame(c, t, fr, root=False, ease='linear')
    strum = ta.lute_point(ta.LUTE_STRUM) + np.array([0.3 * stroke, -1.4 * stroke, -0.3])
    strum = HIPS + qmat(fr['qt']) @ (strum - HIPS) + fr['vt']
    c.key('right_arm', t, reach('right_arm', fr, strum, palm=B, palm_w=0.2), ease='linear')
    slide = 0.8 * bump(t, 28.0, 40.0)
    fret = HIPS + qmat(fr['qt']) @ (ta.lute_point(ta.LUTE_FRET) - HIPS + np.array([slide, 0.0, 0.0])) + fr['vt']
    c.key('left_arm', t, reach('left_arm', fr, fret, palm=B, palm_w=0.2, p=(1.0, 7.8, 0.0)), ease='linear')
    c.key('head', t, qeuler(x=4.0 * bob - 3.0, y=-8.0 * sway, z=4.0 * sway), ease='linear')
    tap = max(0.0, math.cos(2 * math.pi * beat)) ** 3
    legs(c, t, rx=-1.0 - 7.0 * tap, lx=1.5, rz=2.0, lz=-2.0, ease='linear', rpos=(0.0, -0.6 * tap, 0.0))
c.mode('head', 'add')
seal(c)


# ---- hang (loop): up on the toes, both hands up before them at a garland's knot, working it in turn, and a tug to
# pull it tight at the end of the bar
KNOT = np.array([0.0, -9.5, -7.5])
c = fest('fest_hang', BAR, True, look=('citizen', 1), traces=hands_trace())
for t in ticks(0, BAR, 2.5):
    ph = 2 * math.pi * t / BEAT
    sway = math.sin(2 * math.pi * t / BAR)
    lift = 0.5 + 0.5 * math.sin(2 * math.pi * t / 20.0)
    fr = F_(qt=bend(-6.0, 3.0 * sway, 1.5 * sway), pr=(0.0, -0.6 - 0.5 * lift, 0.0))
    key_frame(c, t, fr, ease='linear')
    tug = bump(t, 28.0, 40.0)
    work = 1.0 - tug
    rt = KNOT + np.array([-1.6 + 0.8 * work * math.cos(ph) - 2.2 * tug, 2.0 * work * math.sin(ph) + 1.2 * tug, 0.8 * work * math.cos(ph)])
    lt = KNOT + np.array([1.6 - 0.8 * work * math.cos(ph + math.pi) + 2.2 * tug, 2.0 * work * math.sin(ph + math.pi) + 1.2 * tug,
                          0.8 * work * math.cos(ph + math.pi)])
    c.key('right_arm', t, reach('right_arm', fr, rt, palm=F, palm_w=0.2), ease='linear')
    c.key('left_arm', t, reach('left_arm', fr, lt, palm=F, palm_w=0.2), ease='linear')
    c.key('head', t, qeuler(x=-28.0 + 3.0 * work * math.sin(ph) + 6.0 * tug, z=3.0 * sway), ease='linear')
    legs(c, t, rx=-2.0, lx=2.0, rz=1.5, lz=-1.5, ease='linear')
c.mode('head', 'add')
seal(c)


# ================================================================== where the hands are

RIBBON_TICKS = (0, 10, 20, 30)     # each step ends where it began, so 40 is 0 again
RIBBON_STEPS = (('RING_STEP', 'fest_ring_step'), ('RING_STEP_L', 'fest_ring_step_l'), ('RING_IN', 'fest_ring_in'),
                ('TWIRL', 'fest_twirl'), ('BOW', 'fest_bow'), ('HURRAH', 'fest_hurrah'))


def hand_blocks(c, t, side):
    """Where a hand's grip is at [t], in blocks from the feet: (to the right, up, forward)."""
    p = fk(c, t, side, GRIP[side])
    return (-p[0] / 16.0, (24.0 - p[1]) / 16.0, -p[2] / 16.0)


def ribbon_table():
    """The Ribbonwake's dance steps' hands on the beats, as FestivalOrnamentRenderer's RIBBON_HANDS (Kotlin)."""
    lines = []
    for activity, name in RIBBON_STEPS:
        c = next(x for x in ta.clips if x.name == name)
        lines.append(f'Activity.{activity} to arrayOf(')
        for side in ('right_arm', 'left_arm'):
            nums = [n for t in RIBBON_TICKS for n in hand_blocks(c, t, side)]
            lines.append('    doubleArrayOf(' + ', '.join(f'{n:.2f}' for n in nums) + '),')
        lines.append('),')
    return lines


def main(argv):
    assets, previews = argv[1], argv[2]
    only = set(argv[3:])
    unknown = only - set(NAMES)
    if unknown:
        raise SystemExit(f'no such clip: {sorted(unknown)}')
    os.makedirs(previews, exist_ok=True)
    mine = [c for c in ta.clips if c.name in NAMES]
    for c in mine:
        info = ta.INFO[c.name]
        bad = ta.check_loop(c) if info['loop'] else []
        if bad:
            print(f'!! {c.name}: ' + '; '.join(bad))
        c.write(assets, ta.GROUP)
    print(f'wrote {len(mine)} clips to {os.path.join(assets, "animations", ta.GROUP)}')
    for c in mine:
        for line in ta.report(c):
            print(line)
    print('the ribbon hands on the beats (FestivalOrnamentRenderer.RIBBON_HANDS): blocks from the feet, right, up, ahead')
    for line in ribbon_table():
        print(line)
    rel = next(x for x in ta.clips if x.name == 'fest_release')
    p = fk(rel, RELEASE_TICK, 'lantern', LANTERN_CENTRE)
    print(f'the lantern let go at t={RELEASE_TICK}: {-p[0] / 16:+.2f} right, {(24 - p[1]) / 16:.2f} up, {-p[2] / 16:+.2f} forward')
    for c in mine:
        if only and c.name not in only:
            continue
        ta.sheet(c).save(os.path.join(previews, f'townsfolk_{c.name}.png'))


if __name__ == '__main__':
    main(sys.argv)
