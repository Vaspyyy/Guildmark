"""Bells for the city: the great bell of the keep or cathedral tower, a town bell, the quarter chime rung
before the hour, and a ship's bell in the harbour.

All of them are modal synthesis of a cast bell, no samples. Each partial of the church-bell series (hum,
prime, tierce, quint, nominal, then the uppers, which crowd together and go inharmonic towards the top) is
a pair of decaying sines a fraction of a hertz apart: no casting is perfectly round, so every mode of a real
bell splits into such a doublet, and the two beat slowly against each other as the bell dies away. A blow
starts every mode from rest, so a bell struck again while it still rings adds up the way a real one does.
How long the clapper stays on the bronze sets how far up the series the blow reaches: a harder blow is
shorter and brighter.
"""
from ambkit import *  # noqa: F401,F403

import numpy as np
from scipy import signal


# ---------------------------------------------------------------- partial tables
# One row per partial: (cents from the nominal, level dB re the nominal, T60 s, doublet beat Hz).
# The cents are a real bell's, a few off the ideal ratios here and there; the cloud of uppers above the
# last row is added by upper_cloud().

GREAT = [  # a big, well-tuned cathedral bell
    (-2404, -9.0, 11.5, 0.35),   # hum: two octaves under the nominal, the last thing still sounding
    (-1198, -7.5, 7.2, 0.60),    # prime
    (-894, -4.0, 6.6, 0.85),     # tierce: the minor third that makes a bell sound like a bell
    (-486, -15.0, 4.4, 1.10),    # quint
    (0, 0.0, 5.8, 0.80),         # nominal: the strike note
    (398, -20.0, 3.2, 1.45),     # deciem
    (527, -22.0, 2.8, 1.30),     # undeciem
    (707, -5.5, 4.2, 1.20),      # superquint (duodeciem)
    (931, -24.0, 2.2, 1.50),
    (1241, -8.5, 2.0, 1.60),     # octave nominal
    (1492, -21.0, 1.6, 1.70),
    (1722, -13.0, 1.6, 1.80),    # the uppers, spreading out and going inharmonic
    (1938, -23.0, 1.3, 1.90),
    (2114, -16.0, 1.25, 1.90),
    (2444, -19.0, 1.0, 2.00),
]

TOWN = [  # an older village bell: the hum a little sharp, the prime a little flat
    (-2372, -12.0, 6.0, 0.55),
    (-1226, -8.5, 4.2, 0.80),
    (-886, -4.5, 3.8, 1.10),
    (-462, -14.0, 2.6, 1.40),
    (0, 0.0, 3.4, 1.00),
    (420, -19.0, 1.9, 1.70),
    (556, -21.0, 1.7, 1.60),
    (724, -5.0, 2.5, 1.50),
    (940, -24.0, 1.3, 1.90),
    (1268, -8.0, 1.5, 1.90),
    (1505, -21.0, 1.1, 2.00),
    (1745, -12.5, 1.1, 2.20),
    (2140, -15.5, 0.85, 2.40),
    (2470, -18.5, 0.7, 2.50),
]

CHIME = [  # a modern tuned chime bell (decays as for the 330 Hz bell; smaller ones are scaled down)
    (-2401, -10.0, 9.5, 0.45),
    (-1200, -8.0, 6.0, 0.75),
    (-898, -4.5, 5.5, 1.00),
    (-492, -15.0, 3.6, 1.30),
    (0, 0.0, 4.6, 1.00),
    (392, -20.0, 2.4, 1.60),
    (520, -22.0, 2.1, 1.50),
    (703, -5.5, 3.2, 1.40),
    (1236, -8.5, 1.8, 1.80),
    (1718, -13.0, 1.3, 2.00),
    (2108, -16.0, 1.0, 2.10),
    (2438, -19.0, 0.85, 2.20),
]

SHIP = [  # a small brass bell, never tuned: the low partials wander off the ideal ratios
    (-2330, -18.0, 2.0, 1.2),
    (-1240, -13.0, 1.7, 1.6),
    (-905, -9.0, 1.5, 2.0),
    (-455, -19.0, 0.95, 2.3),
    (0, 0.0, 1.4, 1.8),
    (425, -17.0, 0.75, 2.6),
    (578, -21.0, 0.65, 2.3),
    (748, -6.0, 1.0, 2.8),
    (1294, -8.0, 0.7, 3.3),
    (1795, -12.0, 0.5, 3.6),
    (2190, -15.0, 0.4, 4.0),
    (2525, -18.0, 0.32, 4.4),
]


# ---------------------------------------------------------------- the bell
def upper_cloud(nominal, rng, c0, top_hz, lvl0, t600, step0=200.0):
    """the dense, inharmonic modes high up a bell, above the named partials: they crowd closer together the
    higher they go and die away faster. They are the bright clang at the strike."""
    rows = []
    c = float(c0)
    while nominal * 2 ** (c / 1200) < top_hz:
        rel = 2 ** ((c - c0) / 1200)
        rows.append((c, lvl0 - 4.0 * np.log2(rel) + rng.normal(0.0, 2.5),
                     max(0.05, t600 * rel ** -0.85 * rng.uniform(0.8, 1.2)), rng.uniform(1.0, 3.5)))
        c += max(40.0, step0 * rel ** -0.45) * rng.uniform(0.6, 1.4)
    return rows


def bell_modes(nominal, table, rng, cloud=None, detune=3.0, rho=(0.2, 0.5), hum_rho=(0.35, 0.55), swell=0.3,
               t60_scale=1.0, beat_scale=1.0):
    """a bell's modes, from a partial table plus the cloud of uppers. Each becomes a doublet: two sines
    `beat` Hz apart, the weaker rho times the stronger, which start in step at the blow (a beat at its
    loudest) or, for a few of the quieter partials, against each other, so that partial swells up after the
    strike. The weaker one never outlasts the stronger by much, so the warble stays a warble and does not
    deepen into a tremolo as the bell dies away. A bell is no small source next to the length of its sound
    waves, so each mode reaches a listener with a phase of its own; it is fixed for the bell, so a second
    blow adds to the first coherently."""
    rows = list(table)
    n_named = len(rows)
    if cloud:
        rows += upper_cloud(nominal, rng, **cloud)
    K = len(rows)
    c = np.array([r[0] for r in rows], dtype=float)
    lvl = np.array([r[1] for r in rows], dtype=float)
    d = rng.normal(0.0, detune, K)
    d[c == 0] = 0.0  # the nominal stays where the bell was tuned
    f = nominal * 2 ** ((c + d) / 1200)
    beat = np.array([r[3] for r in rows]) * beat_scale * rng.uniform(0.8, 1.25, K)
    t60 = np.array([r[2] for r in rows]) * t60_scale * rng.uniform(0.92, 1.08, K)
    r = rng.uniform(rho[0], rho[1], K)
    r[0] = rng.uniform(*hum_rho)  # the hum beats deepest: the slow wow of a big bell
    sign = np.where((rng.random(K) < swell) & (lvl < -10.0), -1.0, 1.0)
    return dict(nominal=nominal, f=f, amp=db(lvl), ph=rng.uniform(0, 2 * np.pi, K),
                fa=f - beat / 2, fb=f + beat / 2, ta=t60, tb=t60 * rng.uniform(0.85, 1.04, K),
                wa=1.0 / (1.0 + r), wb=sign * r / (1.0 + r), cloud=np.arange(K) >= n_named)


def ring(modes, dur, fc=1500.0, tilt=0.0, gain=1.0, cloud_db=0.0, build=0.002):
    """one blow on a bell: every doublet is set going at the strike and dies away at its own rate.
    fc is where the clapper's contact time starts to roll the blow off (a harder blow has a higher fc);
    tilt is a further dB per octave about the nominal (negative for a softer blow, which leaves the hum and
    prime stronger against the uppers). build is how long the ring takes to reach a listener in full: sound
    from the far side of the bell arrives up to a bell's width later than from the near side, about 5 ms
    for a great bell and 1 ms for a ship's bell (the clapper's own chk is the only instant part)."""
    n = ns(dur)
    out = np.zeros(n)
    nom = modes["nominal"]
    for k in range(len(modes["f"])):
        f = modes["f"][k]
        if f > SR * 0.45:
            continue
        a = modes["amp"][k] * gain / np.sqrt(1.0 + (f / fc) ** 2) * db(tilt * np.log2(f / nom))
        if modes["cloud"][k]:
            a *= db(cloud_db)
        L = min(n, ns(1.4 * max(modes["ta"][k], modes["tb"][k])))
        t = np.arange(L) / SR
        ph = modes["ph"][k]
        out[:L] += a * (modes["wa"][k] * np.sin(2 * np.pi * modes["fa"][k] * t + ph)
                        * np.exp(-6.9078 * t / modes["ta"][k])
                        + modes["wb"][k] * np.sin(2 * np.pi * modes["fb"][k] * t + ph)
                        * np.exp(-6.9078 * t / modes["tb"][k]))
    k = ns(build)
    if k > 1:
        out[:k] *= np.sin(np.linspace(0, np.pi / 2, k)) ** 2
    return out


def blow(dur, rng, thump_hz=(110.0, 52.0), decay=0.07, chk=(900.0, 5000.0), chk_decay=0.0025):
    """the blow itself, apart from the ringing: a dull thump as the bell and its frame take the hit, and a
    short bright chk of contact noise where iron meets bronze. Each comes back peak-normalised."""
    t = tvec(dur)
    th = thump(dur, thump_hz[0], thump_hz[1], rng, decay=decay, click=0.0, noise=0.45, nlp=3 * thump_hz[0])
    ck = lp(bp(white(dur, rng), chk[0], chk[1]), chk[1] * 1.3) * np.exp(-t / chk_decay) * np.clip(t / 0.0004, 0, 1)
    return nrm(th), nrm(ck)


def walls(x, rng, taps):
    """the strike coming back off far walls across the town. Each tap is (delay s, gain dB, low-pass Hz,
    spread s): a facade of windows, ledges and rooftops throws the sound back smeared over a few tens of
    milliseconds, and the air on the way takes the top off."""
    m = Mix()
    m.add(x)
    for d, g, f, spread in taps:
        L = ns(spread)
        k = rng.standard_normal(L) * np.exp(-np.arange(L) / (L / 3.0)) * np.sin(np.linspace(0, np.pi / 2, L)) ** 0.5
        k /= np.sqrt(np.sum(k ** 2))
        m.add(signal.fftconvolve(lp(x, f), k), d, db(g))
    return m.out()


def lead_in(x, t=0.03):
    """a moment of silence before the blow, and the sub-audio rumble taken out with a causal filter, so the
    trim finds a clean start (the zero-phase high-pass in the finishing would otherwise ring ahead of it)"""
    return hp(np.concatenate([np.zeros(ns(t)), x]), 22.0)


TOWER_ECHOES = [(0.34, -24.0, 1500.0, 0.08), (0.71, -29.0, 1000.0, 0.12)]


# ---------------------------------------------------------------- recipes
GREAT_NOMINAL = 220.0  # A3: hum A1 55 Hz, prime A2 110 Hz, tierce C3, quint E3


@recipe("bell_great", variants=2, peak_db=-3.0, max_len=9.0, fade_out=1.8, tail_db=-60.0)
def bell_great(v, rng):
    """the great bell of the keep or cathedral tower striking once (the hour is a run of these): a deep
    bronze bell on A, nominal 220 Hz with the hum two octaves down at 55 Hz. A bright clang of iron on bronze
    over a dull thump, the strike note warbling as it fades and the hum going on long after. Variant 2 is
    struck a touch softer (darker, less clang) and hangs 1% lower."""
    nominal = GREAT_NOMINAL * (1.0, 0.99)[v]
    hard = v == 0
    modes = bell_modes(nominal, GREAT, rng, cloud=dict(c0=2700, top_hz=8000, lvl0=-21.0, t600=0.75))
    dur = 10.5
    tone = ring(modes, dur, fc=1500.0 if hard else 1050.0, tilt=0.0 if hard else -1.5,
                cloud_db=0.0 if hard else -4.0, build=0.004 if hard else 0.0045)
    th, ck = blow(0.5, rng)
    m = Mix(dur)
    m.add(tone)
    m.add(th, 0.0, 0.32 if hard else 0.26)
    m.add(ck, 0.0, 0.16 if hard else 0.09)
    y = lp(m.out(), 7000)
    y = walls(y, rng, TOWER_ECHOES)
    return lead_in(outdoors(y, wet=0.10, seed=401))


@recipe("bell_town", variants=2, peak_db=-3.0, max_len=5.0, fade_out=1.0, tail_db=-60.0)
def bell_town(v, rng):
    """a town or village bell in a modest tower striking once: an older, smaller bronze bell on B (nominal
    494 Hz), brighter and quicker to die away than the great bell. Variant 2 is struck softer and hangs 1%
    lower."""
    nominal = 494.0 * (1.0, 0.99)[v]
    hard = v == 0
    modes = bell_modes(nominal, TOWN, rng, cloud=dict(c0=2720, top_hz=9000, lvl0=-20.0, t600=0.5),
                       t60_scale=0.9)
    dur = 6.0
    tone = ring(modes, dur, fc=2100.0 if hard else 1500.0, tilt=0.0 if hard else -1.5,
                cloud_db=0.0 if hard else -4.0, build=0.0025 if hard else 0.003)
    th, ck = blow(0.4, rng, thump_hz=(170.0, 85.0), decay=0.045, chk=(1200.0, 6500.0), chk_decay=0.002)
    m = Mix(dur)
    m.add(tone)
    m.add(th, 0.0, 0.26 if hard else 0.2)
    m.add(ck, 0.0, 0.15 if hard else 0.08)
    y = lp(m.out(), 8500)
    y = walls(y, rng, [(0.21, -24.0, 2000.0, 0.06), (0.47, -29.0, 1400.0, 0.09)])
    return lead_in(outdoors(y, wet=0.12, seed=402))


# The quarter chime: four bells tuned to A minor pentatonic (low E, A, C, D; just intonation on A440, so
# the A bell sits an octave over the great bell's nominal). The tune is two phrases of four, each ringing
# every bell once: C A D E (rising, then down to the low E, a question) and C D E A (the answer, a fourth up
# from the low E home to A, left to ring).
CHIME_BELLS = {"E": 330.0, "A": 440.0, "C": 528.0, "D": 586.67}
CHIME_LEVEL = {"E": 1.0, "A": 0.92, "C": 0.86, "D": 0.82}  # the bigger bells speak louder
CHIME_TUNE = "CADECDEA"
CHIME_GAPS = [0.68, 0.68, 0.68, 0.80, 0.68, 0.70, 0.74]  # a breath between the phrases, easing into the last


@recipe("bell_chime", variants=1, peak_db=-3.0, max_len=10.0, fade_out=1.5, tail_db=-60.0)
def bell_chime(v, rng):
    """the quarter chime before the hour: four tuned tower bells (E4 A4 C5 D5) ring an eight-note tune of
    their own in two phrases, C A D E and C D E A, the tails overlapping and the last A left to ring"""
    bells = {k: bell_modes(f, CHIME, rng, cloud=dict(c0=2700, top_hz=9000, lvl0=-21.0, t600=0.5),
                           t60_scale=(330.0 / f) ** 0.45, detune=2.0)
             for k, f in CHIME_BELLS.items()}
    dur = 10.5
    m = Mix(dur)
    t = 0.0
    for i, name in enumerate(CHIME_TUNE):
        at = t + (rng.normal(0.0, 0.006) if i else 0.0)
        g = CHIME_LEVEL[name] * db(rng.normal(0.0, 0.7))
        th, ck = blow(0.3, rng, thump_hz=(200.0, 100.0), decay=0.03, chk=(1400.0, 6500.0), chk_decay=0.002)
        m.add(ring(bells[name], dur - at, fc=2300.0, gain=g, build=0.0025), at)
        m.add(th, at, 0.09 * g)
        m.add(ck, at, 0.07 * g)
        if i < len(CHIME_GAPS):
            t += CHIME_GAPS[i]
    y = lp(m.out(), 8000)
    y = walls(y, rng, TOWER_ECHOES)
    return lead_in(outdoors(y, wet=0.10, seed=401))


@recipe("ship_bell", variants=1, peak_db=-3.0, max_len=4.0, fade_out=0.6, tail_db=-60.0)
def ship_bell(v, rng):
    """a small brass ship's bell on a moored ship, rung in two quick pairs (ding-ding ... ding-ding): a crisp
    untuned bell (nominal about 1.18 kHz) with a hard little clapper, in light outdoor air"""
    nominal = 1180.0
    modes = bell_modes(nominal, SHIP, rng, cloud=dict(c0=2800, top_hz=12500, lvl0=-17.0, t600=0.3),
                       rho=(0.2, 0.45), hum_rho=(0.3, 0.5), swell=0.2)
    dur = 4.3
    m = Mix(dur)
    for i, (at, g) in enumerate(zip((0.0, 0.31, 1.19, 1.49), (1.0, 0.86, 0.95, 0.84))):
        at = at + (rng.normal(0.0, 0.008) if i else 0.0)
        th, ck = blow(0.12, rng, thump_hz=(420.0, 260.0), decay=0.012, chk=(2200.0, 8500.0), chk_decay=0.0015)
        m.add(ring(modes, dur - at, fc=4500.0, gain=g, build=0.0009), at)
        m.add(th, at, 0.08 * g)
        m.add(ck, at, 0.14 * g)
    y = lp(m.out(), 11000)
    return lead_in(outdoors(y, wet=0.08, seed=404))
