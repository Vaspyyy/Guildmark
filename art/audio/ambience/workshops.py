"""Workshops: the city's trades as heard from the street. The smith's anvil, bellows, quench and grindstone,
the carpenter's saw and hammer, coins at a market stall and a cart going by over the cobbles. All mono
one-shots, played in game at the block they come from.
"""
from ambkit import *  # noqa: F401,F403

import numpy as np
from scipy import signal
from scipy.ndimage import uniform_filter1d


# ---------------------------------------------------------------- shared helpers
def ring(x, freqs, t60s, gains):
    """modal resonators driven by x, scaled so that a unit impulse sets each mode ringing at its gain
    (sfxkit's resonators() are scaled for a steady drive instead, which leaves long-ringing modes quiet
    after a knock)"""
    out = np.zeros(len(x))
    for f, t60, g in zip(freqs, t60s, gains):
        if g == 0 or f >= SR * 0.45:
            continue
        r = np.exp(-6.9078 / (max(t60, 1e-3) * SR))
        w = 2 * np.pi * f / SR
        out += g * signal.lfilter([0.0, np.sin(w)], [1.0, -2 * r * np.cos(w), r * r], x)
    return out


def knock(tc):
    """the force of an impact whose contact lasts tc seconds (a half sine), carrying one unit of momentum:
    a short contact (steel on steel) reaches far up the spectrum, a long one (hot iron, wood) stays low"""
    k = max(1, int(round(tc * SR)))
    p = np.sin(np.pi * (np.arange(k) + 0.5) / k)
    return p / p.sum()


def put(track, at, x, gain=1.0):
    """add x into track (a fixed-length array) at time at, cutting whatever runs past the end"""
    i = ns(at)
    if 0 <= i < len(track):
        e = min(len(track), i + len(x))
        track[i:e] += gain * x[: e - i]
    return track


def struck(dur, tc, freqs, t60s, gains, gain=1.0):
    """one knock of contact time tc rung through a set of modes, dur seconds of it"""
    return ring(fit(knock(tc) * gain, ns(dur)), freqs, t60s, gains)


def smooth(x, sec):
    """moving average over sec seconds, to round off the corners of an envelope (never below zero, so it
    can be raised to a power)"""
    return np.maximum(0.0, uniform_filter1d(np.asarray(x, dtype=float), size=max(1, ns(sec))))


def creak(dur, rng, rate, amp, modes, t60s, gains, jitter=0.05):
    """stick-slip friction, as in a wooden joint or an axle under load: the surfaces grip, strain and let
    go, `rate` times a second, and each slip knocks the timber's modes. rate and amp may be curves"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    amp = np.broadcast_to(np.asarray(amp, dtype=float), (n,))
    imp = np.zeros(n)
    pos = 0.0
    while pos < n:
        i = int(pos)
        if amp[i] > 1e-4:
            imp[i] += amp[i] * np.exp(0.3 * rng.standard_normal())
        pos += SR / max(rate[i], 5.0) * max(0.4, 1 + jitter * rng.standard_normal())
    return ring(imp, modes, t60s, gains)


def lead_in(x, sec=0.005):
    """a moment of silence ahead of the sound, so a knock at time zero still gets the driver's tiny fade-in
    and the file never starts on a step"""
    return np.concatenate([np.zeros(ns(sec)), x])


def yard(x, wet=0.14, seed=421):
    """a workshop yard opening onto the street: the house fronts give it a quick slap and a short tail"""
    return street(x, wet=wet, seed=seed, rt60=0.9)


# ================================================================ the smithy
def forge(x, wet=0.17, seed=401):
    """the forge: a small stone-walled room under the shop's roof, its front open to the street, so the
    reflections come quick and the tail is short; a little of it spills out along the street"""
    y = reverb(x, wet=wet, rt60=0.55, predelay=0.004, hf_damp=0.5, seed=seed, early_span=0.022)
    return street(y, wet=0.05, seed=seed + 1)


def anvil_banks(f, body, rng):
    """the modes of one anvil (an inharmonic block of steel) as struck three ways. face: the bare face,
    ringing freely; horn: out on the horn, higher and brighter; work: through hot iron lying on the face,
    which soaks up the blow and (with the tongs) damps the ring to a short one. f sets the ring's pitch,
    body the low modes where the block rocks on its stump"""
    # bending, torsion and the face's own modes, bunched and gapped as a real block's are, with a few that
    # carry the anvil's note
    rr = np.array([0.61, 0.78, 1.0, 1.09, 1.42, 1.77, 1.86, 2.31, 2.74, 3.18, 3.33, 4.05, 4.62, 5.4])
    lead = np.array([0.5, 0.75, 1.0, 0.45, 0.9, 0.5, 0.4, 0.8, 0.45, 0.5, 0.35, 0.4, 0.3, 0.25])
    nr = len(rr)
    rf = f * rr * (1 + rng.uniform(-0.015, 0.015, nr))
    rt = np.clip(0.65 * (rf / f) ** -0.85, 0.12, 0.65)
    rg = lead * rng.uniform(0.6, 1.0, nr)
    # each ring mode has a twin a hair off pitch (the block isn't quite symmetric), so the ring beats
    tw = rf * (1 + rng.uniform(0.0008, 0.0035, nr))
    twg = rng.uniform(0.3, 0.8, nr)
    hf = f * np.array([6.1, 6.9, 7.8]) * (1 + rng.uniform(-0.02, 0.02, 3))
    ht = np.array([0.1, 0.08, 0.06])
    hg = np.array([0.2, 0.15, 0.1])
    bf = body * np.array([190, 262, 345, 455, 590, 760, 930]) * (1 + rng.uniform(-0.03, 0.03, 7))
    bt = np.array([0.13, 0.12, 0.1, 0.09, 0.08, 0.07, 0.06])
    bg = np.array([0.6, 0.9, 1.0, 0.85, 0.7, 0.55, 0.45])

    def bank(ring_g, body_g, t_mult, high_g, body_t=1.0):
        return (np.concatenate([bf, rf, tw, hf]),
                np.concatenate([bt * body_t, rt * t_mult, rt * t_mult, ht * t_mult ** 0.5]),
                np.concatenate([bg * body_g, ring_g, ring_g * twg, hg * high_g]))

    face = bank(rg * rng.uniform(0.55, 1.0, nr), 0.5, 1.0, 1.0)
    hfq, htq, hgq = bank(rg * (rf / f) ** 0.9 * rng.uniform(0.4, 1.0, nr), 0.15, 0.85, 1.6)
    xf = f * np.array([1.53, 2.12, 2.87]) * (1 + rng.uniform(-0.02, 0.02, 3))  # the horn's own bending modes
    horn = (np.concatenate([hfq, xf]), np.concatenate([htq, [0.5, 0.4, 0.3]]), np.concatenate([hgq, [0.7, 0.6, 0.4]]))
    work = bank(rg * rng.uniform(0.55, 1.0, nr), 1.0, 0.35, 0.6, body_t=0.9)
    return dict(face=face, horn=horn, work=work)


def hot_blow(rng, s):
    """what a blow on hot iron adds besides the anvil: the hammer squashing the soft iron (a short, low
    smack), the stump taking the weight, and scale cracking off the work in a spit of tiny grains"""
    dur = 0.3
    t = tvec(dur)
    smack = lp(white(dur, rng), 1800, order=2) * np.exp(-t / 0.004) * np.clip(t / 0.0008, 0, 1)
    stump = struck(dur, 0.003, [122, 178, 262], [0.06, 0.05, 0.04], [0.6, 0.5, 0.3])
    scale = crackle(dur, rng, 3000 * np.exp(-t / 0.02), decay=0.0003, f_lo=2500, f_hi=9500, size_var=0.8)
    return s * (0.5 * nrm(smack) + 0.3 * nrm(stump) + 0.08 * nrm(scale))


def bar_clank(rng, f1, s):
    """the cold end of the bar in the tongs, rung by the blow: a free-free bar's modes (1 : 2.76 : 5.40 :
    8.93 ...), cut short by the tongs' grip and the soft hot end"""
    fr = f1 * np.array([1.0, 2.756, 5.404, 8.933, 13.34]) * (1 + rng.uniform(-0.01, 0.01, 5))
    return struck(0.3, rng.uniform(0.0003, 0.0005), fr, [0.14, 0.1, 0.075, 0.055, 0.04], [1.0, 0.8, 0.55, 0.35, 0.2], s)


def hammer_tink(scale=1.0):
    """the hammer's own head ringing briefly off a hard face"""
    return struck(0.12, 0.0001, scale * np.array([3150, 4470, 6080, 7900]), [0.05, 0.04, 0.03, 0.02],
                  [0.3, 0.22, 0.15, 0.08])


def tongs(rng):
    """the work turned in the tongs: the jaws clink on the bar and the bar scrapes over the face"""
    dur = 0.25
    t = tvec(dur)
    clink = struck(dur, 0.00012, rng.uniform(0.95, 1.05) * np.array([2380, 3710, 5240]), [0.06, 0.045, 0.03],
                   [1, .6, .35])
    scr = tvf(white(dur, rng), "bp", 2600 - 900 * t / dur, 1.5) * np.clip((t - 0.03) / 0.03, 0, 1) * \
        np.exp(-np.maximum(0, t - 0.06) / 0.04)
    return 0.6 * nrm(clink) + 0.35 * nrm(scr)


def anvil_phrase(events, f, body, hammer, bar, rng, dur, tap=0.55):
    """a smith's phrase at the anvil. events are (time, kind, strength): b a blow on the hot work, f a light
    tap on the bare face, h a tap out on the horn, t the work turned in the tongs. bar is the lowest mode of
    the stock being worked; tap scales the light taps against the blows"""
    n = ns(dur)
    banks = anvil_banks(f, body, rng)
    ex = {k: np.zeros(n) for k in banks}
    extra = Mix(dur)
    nb = sum(1 for e in events if e[1] == "b")
    k = 0
    for at, kind, s in events:
        at = max(0.0, at + (rng.normal(0, 0.008) if at > 0 else 0.0))
        s *= rng.uniform(0.92, 1.06)
        if kind == "b":
            cool = k / max(1, nb - 1)  # the iron cools through the heat and rings a little more each blow
            k += 1
            put(ex["work"], at, knock(rng.uniform(0.001, 0.0018)), s)
            put(ex["work"], at, knock(rng.uniform(0.00008, 0.00016)), s * (0.16 + 0.1 * cool))
            extra.add(hot_blow(rng, s), at)
            extra.add(bar_clank(rng, bar * rng.uniform(0.99, 1.01), s * (0.5 + 0.2 * cool)), at)
        elif kind in ("f", "h"):
            s *= tap
            spot = "face" if kind == "f" else "horn"
            put(ex[spot], at, knock(rng.uniform(0.00007, 0.00015)), s)
            extra.add(hammer_tink(hammer) * s, at)
            if rng.random() < 0.5:  # the hammer bounces off the face
                put(ex[spot], at + rng.uniform(0.03, 0.055), knock(0.0001), s * rng.uniform(0.2, 0.35))
        elif kind == "t":
            extra.add(tongs(rng) * s, at)
    y = sum(ring(ex[key], *banks[key]) for key in banks)
    return y + fit(extra.out(), n)


# (ring pitch Hz, body scale, hammer scale, lowest mode of the bar being worked Hz, events) for each variant
ANVIL_PHRASES = [
    # steady drawing out, a tap after most blows
    (1480, 1.0, 1.0, 340, [(0.0, "b", 1.0), (0.23, "f", 0.3), (0.47, "b", 0.95), (0.93, "b", 1.0), (1.15, "f", 0.28),
                      (1.29, "f", 0.22), (1.52, "b", 0.92), (1.99, "b", 1.0), (2.24, "h", 0.26), (2.42, "h", 0.2)]),
    # taps to find the rhythm, two blows, a pause to turn the work, two more
    (1290, 0.93, 0.94, 290, [(0.0, "f", 0.26), (0.15, "f", 0.22), (0.36, "b", 1.0), (0.83, "b", 0.93), (1.04, "f", 0.28),
                        (1.4, "t", 0.5), (1.78, "b", 1.0), (2.2, "b", 0.95), (2.42, "f", 0.25), (2.55, "f", 0.2),
                        (2.84, "h", 0.28)]),
    # quick and even, seven blows
    (1660, 1.07, 1.06, 410, [(0.0, "b", 0.95), (0.19, "f", 0.2), (0.37, "b", 1.0), (0.73, "b", 0.92), (0.93, "f", 0.22),
                        (1.12, "b", 1.0), (1.48, "b", 0.95), (1.67, "f", 0.2), (1.86, "b", 1.0), (2.26, "b", 0.97),
                        (2.48, "f", 0.26), (2.61, "f", 0.2), (2.8, "h", 0.24)]),
    # three heavy, slow blows
    (1150, 0.88, 0.9, 255, [(0.0, "b", 1.0), (0.58, "b", 1.0), (0.8, "f", 0.3), (1.18, "b", 0.97), (1.42, "f", 0.27),
                       (1.55, "f", 0.2), (1.85, "h", 0.3)]),
]


@recipe("anvil_work", variants=4, max_len=[3.3, 3.7, 3.7, 2.8], fade_out=0.3)
def anvil_work(v, rng):
    """a smith working hot iron at the anvil: a phrase of hammer blows, each a heavy, dull thunk with a short
    ring (the hot iron soaks up the blow), and between them the light, bright taps a smith makes on the face
    or the horn to keep time, in an uneven human rhythm; heard in the open-fronted forge"""
    f, body, hammer, bar, events = ANVIL_PHRASES[v]
    dur = events[-1][0] + 1.0
    y = anvil_phrase(events, f, body, hammer, bar, rng, dur)
    return forge(lead_in(hp(lp(y, 11000), 70)), seed=401 + v)


def flap(rng, sc=1.0):
    """the bellows' leather valve flap slapping shut against its board"""
    dur = 0.12
    t = tvec(dur)
    slap = lp(white(dur, rng), 1500 * sc, order=2) * np.exp(-t / 0.006) * np.clip(t / 0.002, 0, 1)
    board = struck(dur, 0.002, sc * np.array([140, 230, 370]), [0.05, 0.04, 0.03], [1, .6, .3])
    return 0.6 * nrm(slap) + 0.5 * nrm(board)


def fire_roar(dur, rng, s):
    """a coal fire under the blast: a turbulent roar low in the chest and a breathier band above it, both
    flickering, opening up as the swell s (0..1 per sample) rises"""
    flick = 0.45 + 0.55 * smooth_random(dur, rng, 11)
    roar = hp(lp(brown(dur, rng), 420, order=4), 75) * s * flick
    br = tvf(pink(dur, rng), "bp", 300 * (1300 / 300) ** s, 0.8) * s ** 1.4 * (0.55 + 0.45 * smooth_random(dur, rng, 19))
    return nrm(roar) + 0.9 * nrm(br)


@recipe("bellows", variants=2, peak_db=-5.0, max_len=[2.2, 2.4], fade_out=0.45)
def bellows(v, rng):
    """the forge's great leather bellows pumped once: the lever's frame creaking as it comes down and the
    valve flap slapping shut, a long breath of air down the pipe, then the coals answering with a fierce
    roar and a spit of crackle, and the flap fluttering open as the boards rise again"""
    dur = [2.2, 2.4][v]
    sc = [1.0, 0.85][v]  # the bigger pair sounds lower
    m = Mix(dur)
    # the lever comes down: its pivot creaks as it takes the weight, and the valve flap slaps shut
    cd = [0.26, 0.3][v]
    u = np.clip(tvec(cd) / cd, 0, 1)
    cr = creak(cd, rng, sc * (20 + 80 * u ** 0.8), bell(cd, 0.55, 1.1, 1.3) * (0.7 + 0.3 * smooth_random(cd, rng, 25)),
               sc * np.array([380, 610, 1000, 1520, 2230]), [0.05, 0.04, 0.03, 0.025, 0.02], [1, .8, .6, .4, .25])
    m.add(nrm(cr), 0.0, 0.9)
    m.add(flap(rng, sc), [0.1, 0.13][v], 0.5)
    # the air: one long breath through the nozzle, the chamber huffing under it
    on, pk, end = [(0.12, 0.42, 1.35), (0.15, 0.5, 1.55)][v]
    flow = smooth(env_points([(0, 0), (on, 0), (pk, 1.0), (pk + 0.4, 0.88), (end - 0.3, 0.55), (end, 0), (dur, 0)],
                             dur), 0.06)
    wob = 1 + 0.15 * (smooth_random(dur, rng, 9) - 0.5)
    air = tvf(colored(dur, rng, -1.0), "bp", sc * (600 + 500 * flow), 0.55) * flow ** 1.3 * wob
    hiss = bp(white(dur, rng), 2200, 8000) * flow ** 2 * wob
    huff = bp(brown(dur, rng), 80, 320) * flow
    m.add(nrm(air), 0.0, 0.5)
    m.add(nrm(hiss), 0.0, 0.14)
    m.add(nrm(huff), 0.0, 0.18)
    # at full pressure the boards and their hinge groan under the load
    gd = [0.36, 0.42][v]
    u = np.clip(tvec(gd) / gd, 0, 1)
    groan = creak(gd, rng, sc * (50 + 95 * np.sin(np.pi * u ** 0.7)), bell(gd, 0.4, 1.2, 1.5),
                  sc * np.array([340, 560, 880, 1320, 1900, 2600]), [0.05, 0.045, 0.04, 0.03, 0.025, 0.02],
                  [0.7, 1.0, 0.9, 0.75, 0.55, 0.35])
    m.add(nrm(groan), pk + 0.12, 1.4)
    # the fire answering: it swells as the blast reaches it, peaks as the push ends and settles slowly
    sw = smooth(env_points([(0, 0), (on + 0.15, 0), (pk + 0.25, 0.9), (pk + 0.5, 1.0), (end + 0.1, 0.8), (dur, 0.04)],
                           dur), 0.1)
    m.add(fire_roar(dur, rng, sw), 0.0, 0.85)
    spit = crackle(dur, rng, 8 + 200 * sw ** 2, decay=0.0005, f_lo=1300, f_hi=8000, size_var=1.0) * sw
    m.add(nrm(spit), 0.0, 0.45)
    for _ in range(int(rng.integers(4, 8))):  # a few coals popping hard
        at = rng.uniform(pk, end + 0.4)
        f0 = rng.uniform(1200, 2800)
        m.add(struck(0.06, 0.0002, [f0, f0 * 1.6, f0 * 2.3], [0.03, 0.02, 0.015], [1, .6, .4]), at, rng.uniform(0.08, 0.16))
    m.add(nrm(hp(white(dur, rng), 4500) * sw ** 3), 0.0, 0.04)  # sparks going up the chimney
    # the boards rising again: the flap flutters open as the air rushes back in, the frame creaks lightly
    fd = 0.2
    flut = lp(white(fd, rng), 800 * sc) * (0.5 + 0.5 * np.sin(2 * np.pi * rng.uniform(22, 30) * tvec(fd))) ** 2 * \
        bell(fd, 0.3, 1.2, 1.4)
    m.add(nrm(flut), end + 0.02, 0.18)
    cd2 = 0.22
    cr2 = creak(cd2, rng, sc * (40 + 40 * np.sin(np.pi * np.clip(tvec(cd2) / cd2, 0, 1))), bell(cd2, 0.5, 1.2, 1.5),
                sc * np.array([420, 680, 1090, 1650]), [0.05, 0.04, 0.03, 0.025], [1, .7, .5, .3])
    m.add(nrm(cr2), end + 0.08, 0.6)
    return forge(lead_in(fit(m.out(), ns(dur))), wet=0.14, seed=411 + v)


def bubbles(dur, rng, rate, r_lo, r_hi):
    """a stream of bubbles in water, each ringing at its Minnaert pitch (about 3.26 m*Hz over its radius),
    sliding up as it rises and dying within a few dozen cycles (van den Doel's model); rate is a curve"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    out = np.zeros(n)
    for i in np.nonzero(rng.random(n) < rate / SR)[0]:
        r = np.exp(rng.uniform(np.log(r_lo), np.log(r_hi)))
        f0 = 3.26 / r
        d = 0.043 * f0 + 0.0014 * f0 ** 1.5
        L = min(n - i, ns(4.6 / d))
        if L < 16:
            continue
        tt = np.arange(L) / SR
        s = np.sin(2 * np.pi * np.cumsum(f0 * (1 + 0.1 * d * tt)) / SR) * np.exp(-d * tt)
        out[i:i + L] += (r / 1e-3) ** 0.7 * np.exp(0.5 * rng.standard_normal()) * s * np.clip(tt / 0.0003, 0, 1)
    return out


@recipe("quench", variants=2, peak_db=-4.0, max_len=[2.7, 3.0], fade_out=0.6)
def quench(v, rng):
    """red-hot steel plunged into the slack tub: a violent sizzling hiss tearing into the water with a burst of
    bubbles and boiling crackle, dying away into a long fizz of steam. (2) a heavier piece, swirled twice so
    fresh water reaches it and it flares up again"""
    dur = [2.7, 3.0][v]
    t = tvec(dur)
    if v == 0:
        e = 0.8 * np.exp(-t / 0.32) + 0.2 * np.exp(-t / 1.2)
    else:
        e = 0.7 * np.exp(-t / 0.42) + 0.3 * np.exp(-t / 1.4)
        for c, w, g in ((0.78, 0.13, 0.32), (1.5, 0.15, 0.2)):
            e = e + g * np.exp(-0.5 * ((t - c) / w) ** 2)
    e = e * np.clip(t / 0.012, 0, 1) ** 1.5
    m = Mix(dur)
    # the plunge: water thrown aside and a gulp of big bubbles
    m.add(nrm(bp(white(0.2, rng), 300, 2500) * np.exp(-tvec(0.2) / 0.03)), 0.0, 0.25)
    m.add(nrm(bubbles(0.3, rng, 60 * np.exp(-tvec(0.3) / 0.08), 2.5e-3, 6e-3)), 0.0, 0.22)
    # the sizzle: steam bursting off the steel in a dense crackle, over a hiss that thins as it cools
    grains = crackle(dur, rng, 300 + 15000 * e, decay=0.00025, f_lo=1400, f_hi=11000, size_var=0.9,
                     amp_curve=e ** 0.6)
    m.add(nrm(grains), 0.0, 0.8)
    hiss = tvf(white(dur, rng), "hp", 900 + 2200 * (1 - e / e.max()), 0.7) * e * \
        (0.65 + 0.35 * smooth_random(dur, rng, 160))
    m.add(nrm(hiss), 0.0, 0.7)
    # the water boiling hard against it: a churning rumble, a froth of small bubbles and a few big ones
    # gulping up while the steel is hottest
    boil = bp(pink(dur, rng), 90, 800) * e ** 1.5 * (0.5 + 0.5 * smooth_random(dur, rng, 40))
    m.add(nrm(boil), 0.0, 0.35)
    m.add(nrm(bubbles(dur, rng, 500 * e ** 1.5, 0.4e-3, 2.2e-3)), 0.0, 0.3)
    m.add(nrm(bubbles(dur, rng, 40 * e ** 2, 2e-3, 4e-3)), 0.0, 0.18)
    # the steam fizzing on after, with the odd tiny bubble ticking at the surface
    fizz = bp(white(dur, rng), 3000, 9000) * (np.exp(-t / 1.1) + 0.3 * e) * (0.75 + 0.25 * smooth_random(dur, rng, 7))
    m.add(nrm(fizz) * np.clip(t / 0.05, 0, 1), 0.0, 0.2)
    m.add(nrm(bubbles(dur, rng, 6 * np.exp(-t / 1.5), 0.3e-3, 0.9e-3)), 0.0, 0.05)
    y = lp(fit(m.out(), ns(dur)), 10000)
    return forge(lead_in(y), wet=0.12, seed=421 + v)


@recipe("grindstone", variants=2, peak_db=-5.0, max_len=[3.3, 3.0], fade_out=0.35)
def grindstone(v, rng):
    """an edge put to the treadle grindstone: a rasping scrape of steel on sandstone that brightens and
    darkens as each push on the treadle hurries the wheel round, a thin ring from the blade, a hint of sparks,
    and the wooden frame rumbling and knocking under it all"""
    dur = [3.3, 3.0][v]
    t = tvec(dur)
    fp = [1.4, 1.7][v]  # wheel turns (and treadle pushes) per second
    ph = 2 * np.pi * np.cumsum(fp * (1 + 0.08 * (smooth_random(dur, rng, 0.8) - 0.5))) / SR + rng.uniform(0, 6.28)
    speed = 1 + 0.2 * np.sin(ph) + 0.06 * np.sin(2 * ph + 1.3)
    strokes = [[(0.0, 1.45), (1.68, 1.5)], [(0.0, 0.85), (1.02, 0.9), (2.08, 0.85)]][v]
    press = np.zeros(len(t))
    for a, d in strokes:
        u = np.clip((t - a) / d, 0, 1)
        press += np.clip((t - a) / 0.07, 0, 1) * np.clip((a + d - t) / 0.09, 0, 1) * (0.85 + 0.15 * np.sin(np.pi * u))
    press *= 1 + 0.15 * np.sin(ph + 0.8)  # the stone's face isn't quite true: it presses harder once a turn
    bright = press * speed
    rough = 0.75 + 0.25 * smooth_random(dur, rng, 35)
    grit = crackle(dur, rng, 12000, decay=0.0002, f_lo=300, f_hi=14000, size_var=0.7)
    rasp = tvf(grit, "bp", 900 + 2400 * bright, 0.55) * press * np.sqrt(speed) * rough
    zizz = tvf(grit, "hp", 2500 + 2000 * bright, 0.7) * press * speed * rough
    bf = [1900, 2250][v] * np.array([1.0, 1.594, 2.136, 2.653, 3.156, 3.928])
    blade = ring(grit * press, bf, [0.16, 0.13, 0.11, 0.09, 0.07, 0.06], [1, .7, .6, .45, .3, .2])
    sparks = hp(white(dur, rng), 5500) * press ** 2 * speed ** 2
    m = Mix(dur)
    m.add(nrm(rasp), 0.0, 0.8)
    m.add(nrm(zizz), 0.0, 0.35)
    m.add(nrm(blade), 0.0, 0.2)
    m.add(nrm(sparks), 0.0, 0.08)
    # the frame: the wheel's rumble on its axle, and the treadle knocking at the bottom of each push
    m.add(nrm(bp(brown(dur, rng), 55, 220) * (0.7 + 0.3 * np.sin(ph))), 0.0, 0.3)
    turns = np.nonzero(np.diff(np.floor((ph - 4.2) / (2 * np.pi))) > 0)[0]
    for i in turns:
        m.add(struck(0.2, 0.002, [118, 176, 262, 391], [0.07, 0.06, 0.05, 0.04], [1, .8, .6, .4]), i / SR,
              rng.uniform(0.12, 0.18))
    return yard(lead_in(hp(fit(m.out(), ns(dur)), 50)), wet=0.12, seed=431 + v)


# ================================================================ the carpenter
def stroke_curves(plan, dur, gap=0.05):
    """lay out a sawyer's strokes: plan is (push?, seconds, weight) for each; returns the blade's speed
    (m/s), the force behind it, the roughness of the cut and when the last stroke ends"""
    n = ns(dur)
    t = tvec(dur)
    vel, force, rough = np.zeros(n), np.zeros(n), np.zeros(n)
    at = 0.0
    for push, d, w in plan:
        u = np.clip((t - at) / d, 0, 1)
        on = (t >= at) & (t < at + d)
        length = 0.38 * w ** 0.5
        vel += on * length / (0.7 * d) * np.sin(np.pi * u) ** 0.7
        force += on * w * (1.0 if push else 0.42)
        rough += on * (0.55 if push else 0.3)
        at += d + gap
    return vel, force, rough, at - gap


def tooth_train(vel, force, rough, rng, pitch):
    """one impulse per tooth through the cut: teeth pass at speed / pitch a second, and every chip is its own
    size (rougher under a heavy push, now and then a tooth catching)"""
    n = len(vel)
    ph = np.cumsum(vel) / (pitch * SR)
    idx = np.nonzero(np.diff(np.floor(ph)) > 0)[0] + 1
    per = pitch / np.maximum(vel[idx], 0.05) * SR
    idx = np.clip((idx + 0.3 * per * rng.uniform(-0.5, 0.5, len(idx))).astype(int), 0, n - 1)
    a = force[idx] * np.sqrt(vel[idx] / (vel.max() + 1e-9)) * np.exp(rough[idx] * rng.standard_normal(len(idx)))
    a *= np.where(rng.random(len(idx)) < 0.02 * rough[idx] / 0.55, 2.2, 1.0)
    imp = np.zeros(n)
    np.add.at(imp, idx, a)
    return imp


SAWS = [
    # tooth pitch (m), strokes (push?, seconds, weight), plank modes (Hz), offcut modes (Hz)
    (0.0042, [(True, 0.44, 1.0), (False, 0.38, 1.0), (True, 0.43, 1.0), (False, 0.37, 0.95), (True, 0.42, 1.0),
              (False, 0.36, 0.9), (True, 0.3, 0.6), (False, 0.26, 0.5)],
     [118, 176, 251, 347, 462, 610, 790, 1020, 1300], [640, 990, 1460, 2120]),
    (0.005, [(True, 0.38, 1.0), (False, 0.33, 1.0), (True, 0.37, 1.0), (False, 0.31, 0.95), (True, 0.36, 0.95),
             (False, 0.28, 0.75), (True, 0.24, 0.55)],
     [97, 148, 214, 301, 405, 540, 715, 930], [560, 870, 1300, 1890]),
]


@recipe("saw", variants=2, peak_db=-5.0, max_len=[4.3, 3.8], fade_out=0.3)
def saw(v, rng):
    """a carpenter sawing through a plank: push and pull strokes (the push cutting, louder and rougher), the
    teeth rasping through the wood with a grain at their passing rate, the plank booming under them; the last
    short strokes as the cut runs out, the offcut breaking away and knocking down onto the ground"""
    pitch, plan, plank_f, off_f = SAWS[v]
    dur = sum(d for _, d, _ in plan) + 0.05 * len(plan) + 0.75
    vel, force, rough, end = stroke_curves(plan, dur)
    t = tvec(dur)
    imp = tooth_train(vel, force, rough, rng, pitch)
    # each tooth tears a chip: a tiny burst of noise
    kern = np.exp(-np.arange(ns(0.0012)) / ns(0.0003)) * rng.standard_normal(ns(0.0012))
    chips = bp(signal.fftconvolve(imp, kern)[: len(t)], 1500, 8000)
    nf = len(plank_f)
    plank = ring(lp(imp, 3000), plank_f, np.linspace(0.12, 0.035, nf), np.linspace(1.0, 0.35, nf))
    blade = ring(imp, [1180, 1730, 2390, 3260, 4180], [0.07, 0.06, 0.05, 0.04, 0.03], [1, .8, .6, .4, .25])
    # the blade's flanks rubbing in the kerf
    rub = bp(pink(dur, rng), 400, 2500) * smooth(vel * force, 0.02)
    m = Mix(dur)
    m.add(nrm(chips), 0.0, 0.75)
    m.add(nrm(plank), 0.0, 0.6)
    m.add(nrm(blade), 0.0, 0.12)
    m.add(nrm(rub), 0.0, 0.18)
    # the last fibres tear and the offcut drops (about 0.3 s to the ground) and bounces once
    tear = crackle(0.05, rng, 4000, decay=0.0005, f_lo=600, f_hi=6000) * np.exp(-tvec(0.05) / 0.012)
    m.add(nrm(tear), end - 0.02, 0.25)
    m.add(nrm(struck(0.1, 0.0003, off_f, [0.05, 0.04, 0.03, 0.02], [1, .8, .5, .3])), end - 0.015, 0.15)
    drop = end + 0.3
    for i, g in enumerate((1.0, 0.3)):
        at = drop + i * rng.uniform(0.08, 0.11)
        k = struck(0.2, 0.0005, np.array(off_f) * rng.uniform(0.98, 1.02), [0.06, 0.045, 0.035, 0.025], [1, .8, .5, .3])
        th = lp(white(0.1, rng), 600) * np.exp(-tvec(0.1) / 0.01)
        m.add(nrm(k), at, 0.4 * g)
        m.add(nrm(th), at, 0.25 * g)
    return yard(lead_in(fit(m.out(), ns(dur))), wet=0.13, seed=441 + v)


def nail_ping(dur, rng, length, d=0.0035):
    """the nail standing proud of the wood, rung by the blow: a cantilever clamped in the timber, its modes
    at 1 : 6.27 : 17.5 and its pitch rising as less of it sticks out (f1 = 0.56 * (d/4) * c / L^2)"""
    f1 = 0.5596 * (d / 4) * 5050.0 / length ** 2
    fr = f1 * np.array([1.0, 6.267, 17.55])
    return modal(dur, fr, [0.18, 0.08, 0.04], [1.0, 0.5, 0.25], rng)


WOOD_KNOCKS = [
    # (time, strength, nail length standing proud in m (0 = head flush)); board modes, board t60s
    ([(0.0, 0.45, 0.055), (0.4, 1.0, 0.05), (0.78, 0.95, 0.032), (1.14, 0.9, 0.016), (1.52, 1.0, 0.0)],
     [142, 214, 301, 412, 560, 745, 980, 1290], [0.16, 0.13, 0.11, 0.09, 0.075, 0.06, 0.05, 0.04]),
    ([(0.0, 0.5, 0.06), (0.36, 0.95, 0.054), (0.71, 1.0, 0.038), (1.02, 0.55, 0.03), (1.4, 1.0, 0.017),
      (1.8, 1.0, 0.0)],
     [96, 151, 223, 318, 441, 598, 802], [0.18, 0.15, 0.12, 0.1, 0.08, 0.065, 0.05]),
]


@recipe("wood_knock", variants=2, peak_db=-3.0, max_len=[2.2, 2.5], fade_out=0.25)
def wood_knock(v, rng):
    """a carpenter driving a nail into timber: a light tap to start it, then blows that ping off its head,
    the ping climbing as less of the nail stands proud, the last a dull knock as it seats; the board booms
    under every one"""
    hits, bf, bt = WOOD_KNOCKS[v]
    dur = hits[-1][0] + 0.7
    m = Mix(dur)
    nb = len(bf)
    for at, s, length in hits:
        at = max(0.0, at + (rng.normal(0, 0.012) if at > 0 else 0.0))
        s *= rng.uniform(0.93, 1.05)
        seated = length <= 0
        # the board: through the nail the blow arrives smoothed by its slip into the wood; once the head is down
        # the hammer face meets the timber itself, a fuller, duller knock
        tc = rng.uniform(0.0009, 0.0013) if seated else rng.uniform(0.0004, 0.0007)
        board = struck(0.5, tc, np.array(bf) * rng.uniform(0.99, 1.01), bt, np.linspace(1.0, 0.4, nb))
        m.add(board, at, s * (1.3 if seated else 1.0))
        click = hp(white(0.02, rng), 1800) * np.exp(-tvec(0.02) / 0.0015)
        m.add(nrm(click), at, s * (0.15 if seated else 0.3))
        if not seated:
            m.add(nrm(nail_ping(0.4, rng, length)), at, s * 0.35 * min(1.0, length / 0.04) ** 0.5)
            m.add(nrm(hammer_tink(rng.uniform(0.95, 1.05))), at, s * 0.12)
    return yard(lead_in(hp(fit(m.out(), ns(dur)), 60)), wet=0.15, seed=451 + v)


# ================================================================ the market
COIN = np.array([1.0, 1.73, 2.33, 3.91, 4.11])  # a free disc's lowest modes: (2,0), (0,1), (3,0), (1,1), (4,0)
COUNTER = (np.array([380, 610, 920, 1350, 1900, 2600]), [0.05, 0.04, 0.035, 0.03, 0.025, 0.02],
           [1.0, 0.9, 0.75, 0.6, 0.45, 0.3])


def coin_ring(rng, f, t60, dur=None):
    """one small coin ringing: a thin disc's inharmonic modes, each split in two (no coin is quite flat)"""
    dur = dur or min(0.6, t60 * 1.3 + 0.01)
    fr = f * COIN * (1 + rng.uniform(-0.012, 0.012, len(COIN)))
    a = np.array([1.0, 0.55, 0.6, 0.3, 0.3]) * rng.uniform(0.5, 1.0, len(COIN))
    tl = t60 * COIN ** -0.6
    return modal(dur, fr, tl, a, rng) + 0.5 * modal(dur, fr * (1 + rng.uniform(0.001, 0.004, len(fr))), tl, a, rng)


def tick(rng, s=1.0, tc=0.00015):
    """something small and hard landing on the counter's boards"""
    f, t60, g = COUNTER
    return struck(0.12, tc, f * rng.uniform(0.97, 1.03), t60, g, s)


def coin_land(m, rng, at, f, s, flat=True):
    """a coin landing on the counter: flat, the wood takes it with a tick and stops its ring; on its edge it
    rings out first"""
    m.add(nrm(tick(rng)), at, s * 0.6)
    m.add(nrm(coin_ring(rng, f, 0.04 if flat else 0.16)), at, s * (0.35 if flat else 0.6))


def coin_clink(m, rng, at, f1, f2, s, t60=0.1):
    """two coins striking each other"""
    d = min(0.6, t60 * 1.3 + 0.01)
    m.add(nrm(coin_ring(rng, f1, t60, d) + coin_ring(rng, f2, t60 * 0.8, d)), at, s)


def coin_settle(m, rng, at, f, s, span=0.3):
    """a coin wobbling down flat (an Euler's disk): taps that come faster and smaller until it lies still"""
    tt, gap, g = at, 0.05, s
    while tt < at + span and gap > 0.004:
        m.add(nrm(tick(rng, tc=0.0001)), tt, g * 0.5)
        m.add(nrm(coin_ring(rng, f, 0.03)), tt, g * 0.4)
        tt += gap
        gap *= 0.84
        g *= 0.9


@recipe("coins", variants=3, peak_db=-6.0, max_len=[1.6, 1.1, 1.5], fade_out=0.2)
def coins(v, rng):
    """coins at a market stall: (1) a small handful let fall onto the wooden counter, then counted off one by
    one; (2) a purse of them jingled in the hand; (3) coins slid across the counter and stacked"""
    dur = [1.6, 1.1, 1.5][v]
    m = Mix(dur)
    f = lambda: float(np.exp(rng.uniform(np.log(3600), np.log(7200))))  # noqa: E731
    if v == 0:
        for at in np.sort(rng.uniform(0, 0.07, 7)):
            flat = rng.random() < 0.5
            coin_land(m, rng, at, f(), rng.uniform(0.5, 1.0), flat)
            if not flat:  # off its edge, it bounces once and falls flat
                coin_land(m, rng, at + rng.uniform(0.03, 0.06), f(), rng.uniform(0.2, 0.4), True)
        for at in rng.uniform(0.01, 0.12, 4):
            coin_clink(m, rng, at, f(), f(), rng.uniform(0.3, 0.6), t60=0.08)
        coin_settle(m, rng, 0.1, f(), 0.5)
        for i, at in enumerate((0.62, 0.84, 1.05, 1.25)):  # counted off: each one set down on the last
            at += rng.normal(0, 0.015)
            coin_land(m, rng, at, f(), rng.uniform(0.4, 0.55), True)
            if i:
                coin_clink(m, rng, at + 0.002, f(), f(), 0.25, t60=0.06)
    elif v == 1:
        for at, g in ((0.0, 1.0), (0.31, 0.8), (0.56, 0.6)):
            for tt in np.sort(at + rng.exponential(0.03, int(rng.integers(10, 15)))):
                if tt < dur - 0.1:
                    c = coin_ring(rng, f() * 0.85, rng.uniform(0.025, 0.06))
                    m.add(nrm(lp(c, 7000)), tt, g * rng.uniform(0.3, 0.8))
            leather = bp(pink(0.14, rng), 500, 3500) * bell(0.14, 0.3, 1.2, 1.6)
            m.add(nrm(leather), at, g * 0.25)
            m.add(nrm(struck(0.1, 0.003, [160, 250], [0.04, 0.03], [1, .6])), at + 0.01, g * 0.3)
    else:
        at = 0.0
        for i in range(3):
            sd = rng.uniform(0.12, 0.17)
            st = tvec(sd)
            slide = bp(white(sd, rng), 1500, 6000) * bell(sd, 0.4, 1.2, 1.5) * (0.6 + 0.4 * smooth_random(sd, rng, 90))
            m.add(nrm(slide), at, 0.2)
            m.add(nrm(crackle(sd, rng, 500, decay=0.0004, f_lo=1500, f_hi=7000) * bell(sd, 0.4, 1.2, 1.5)), at, 0.12)
            put_at = at + sd + rng.uniform(0.04, 0.08)
            coin_clink(m, rng, put_at, f(), f(), 0.8, t60=0.14)
            m.add(nrm(tick(rng)), put_at, 0.25)
            for k, d in enumerate((0.02, 0.034)):
                m.add(nrm(coin_ring(rng, f(), 0.03)), put_at + d, 0.2 * 0.6 ** k)
            at = put_at + rng.uniform(0.22, 0.32)
    return yard(lead_in(fit(m.out(), ns(dur))), wet=0.1, seed=461 + v)


# ================================================================ the street
def clop(rng, s, f_knock=520):
    """an iron-shod hoof set down on cobbles at a walk: the weight behind it in a soft thud, the hollow knock of
    the horn, and the shoe's iron clacking on the stone"""
    dur = 0.25
    t = tvec(dur)
    thud = struck(dur, 0.0012, [95, 150, 230], [0.05, 0.04, 0.03], [1, .6, .35])
    horn = struck(dur, 0.0004, f_knock * np.array([1.0, 1.71, 2.63, 4.1]), [0.045, 0.032, 0.022, 0.012],
                  [1, .6, .35, .2])
    shoe = struck(dur, 0.0001, rng.uniform(0.95, 1.05) * np.array([2300, 3400, 4700, 6100]), [0.03, 0.022, 0.016, 0.012],
                  [1, .7, .5, .3])
    grit = crackle(dur, rng, 1500 * np.exp(-t / 0.015), decay=0.0006, f_lo=1500, f_hi=7000)
    return s * (0.8 * nrm(thud) + 0.9 * nrm(horn) + 0.35 * nrm(shoe) + 0.15 * nrm(grit))


def wheel_hits(dur, rng, speed, sett=(0.09, 0.2), big=0.05):
    """when and how hard one iron-shod wheel knocks as it rolls over cobbles: once at every joint between the
    stones (speed in m/s, sett lengths in metres), now and then a deeper gap"""
    times, sizes = [], []
    tt = rng.uniform(0, sett[1] / speed)
    while tt < dur:
        times.append(tt)
        sizes.append(np.exp(0.45 * rng.standard_normal()) * (2.2 if rng.random() < big else 1.0))
        tt += rng.uniform(*sett) / speed
    return np.array(times), np.array(sizes)


CARTS = [
    # wheels, speed m/s, cart body modes, wheel modes, load modes, creak times, horse?
    dict(wheels=4, speed=1.2, body=[84, 116, 156, 210, 285], wheel=[205, 320, 470, 680, 930],
         load=[150, 235, 345, 480], creaks=[1.1, 3.7], horse=True, dur=5.6),
    dict(wheels=2, speed=1.05, body=[122, 172, 238, 330], wheel=[330, 510, 760, 1080],
         load=[720, 1150, 1690, 2420], creaks=[0.6, 1.9, 3.2], horse=False, dur=4.8),
]


@recipe("cart", variants=2, peak_db=-4.0, max_len=[5.8, 5.0], fade_out=0.6)
def cart(v, rng):
    """a cart going by over the cobbles: iron-shod wheels rumbling and chattering on the stones, the boards and
    the load rattling, the axle creaking, the whole coming up, passing and drawing away. (1) a farm cart behind
    a slow horse; (2) a handcart pushed along by hand"""
    c = CARTS[v]
    dur = c["dur"]
    n = ns(dur)
    t = tvec(dur)
    tp = dur * 0.5
    # level and brightness from the distance: it passes about 2.5 m off
    dist = np.sqrt(2.5 ** 2 + (c["speed"] * (t - tp)) ** 2)
    near = 2.5 / dist
    hits = np.zeros(n)
    boards = np.zeros(n)
    load = np.zeros(n)
    for w in range(c["wheels"]):
        times, sizes = wheel_hits(dur, rng, c["speed"])
        for tt, sz in zip(times, sizes):
            put(hits, tt, knock(rng.uniform(0.00015, 0.0004)), sz)
            if sz > 1.3 and rng.random() < 0.6:  # a loose board jumps and knocks
                for _ in range(int(rng.integers(1, 3))):
                    put(boards, tt + rng.uniform(0.012, 0.05), knock(0.0003), sz * rng.uniform(0.4, 1.0))
            if sz > 1.5 and rng.random() < 0.35:
                put(load, tt + rng.uniform(0.02, 0.07), knock(0.0004), sz * rng.uniform(0.5, 1.0))
    nt = 14
    tyre_f = np.geomspace(420, 3600, nt) * (1 + rng.uniform(-0.04, 0.04, nt))
    tyre = ring(hits, tyre_f, np.linspace(0.12, 0.05, nt), rng.uniform(0.4, 1.0, nt) * np.linspace(1, 0.4, nt))
    wheel = ring(hits, c["wheel"], np.linspace(0.05, 0.025, len(c["wheel"])), np.linspace(1, 0.5, len(c["wheel"])))
    body = ring(lp(hits, 400), c["body"], np.linspace(0.12, 0.08, len(c["body"])), np.linspace(1, 0.6, len(c["body"])))
    stone = hp(hits, 2500)
    density = smooth(np.abs(hits) > 0, 0.25)
    density /= density.max() + 1e-12
    rumble = bp(brown(dur, rng), 60, 240) * (0.4 + 0.6 * density)
    chatter = crackle(dur, rng, 250, decay=0.0005, f_lo=1500, f_hi=6000) * (0.3 + 0.7 * density)
    bf = np.array([265, 410, 580, 790, 1080])
    m = Mix(dur)
    m.add(nrm(tyre), 0.0, 0.45)
    m.add(nrm(wheel), 0.0, 0.6)
    m.add(nrm(body), 0.0, 0.7)
    m.add(nrm(stone), 0.0, 0.12)
    m.add(nrm(rumble), 0.0, 0.35)
    m.add(nrm(chatter), 0.0, 0.15)
    m.add(nrm(ring(boards, bf, [0.05, 0.04, 0.035, 0.03, 0.025], [1, .8, .6, .45, .3])), 0.0, 0.35)
    m.add(nrm(ring(load, c["load"], [0.08, 0.06, 0.05, 0.04], [1, .7, .5, .3])), 0.0, 0.25)
    if c["horse"]:
        m.add(nrm(jingle(dur, rng, 40, 2200, 5500, t60=(0.02, 0.06), spread=dur, amp_decay=99.0)), 0.0, 0.05)
    for ct in c["creaks"]:
        cd = rng.uniform(0.25, 0.4)
        u = np.clip(tvec(cd) / cd, 0, 1)
        cr = creak(cd, rng, 70 + 90 * np.sin(np.pi * u), bell(cd, 0.5, 1.3, 1.5), [300, 520, 830, 1260],
                   [0.04, 0.035, 0.03, 0.025], [1, .8, .6, .4])
        m.add(nrm(cr), ct + rng.normal(0, 0.1), 0.25)
    y = fit(m.out(), n) * near
    if c["horse"]:  # the horse walks a few steps ahead, so it passes a moment before the wheels do
        hd = np.sqrt(2.5 ** 2 + (c["speed"] * (t - tp + 1.2)) ** 2)
        hy = Mix(dur)
        tt, k = 0.2, 0
        while tt < dur - 0.3:
            hy.add(clop(rng, (1.0 if k % 2 else 0.75) * rng.uniform(0.85, 1.1), rng.uniform(470, 560)), tt)
            tt += (0.24 if k % 2 == 0 else 0.33) * rng.uniform(0.92, 1.08)
            k += 1
        y = y + 0.6 * fit(hy.out(), n) * (2.5 / hd)
    y = tvf(y, "lp", 3500 + 8000 * near ** 2, 0.707)
    y = y * np.clip(t / 0.5, 0, 1) * np.clip((dur - t) / 0.8, 0, 1)
    return street(lead_in(hp(y, 70)), wet=0.18, seed=471 + v)
