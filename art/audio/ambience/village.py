"""A village: the country round it by day and by night, its birds and its crickets, its market's murmur, and three things
heard far off across the fields (a dog, a hammer, a child calling) (living-world/villages/design.md 17.3, Part V12b).

Quieter and sparser than the capital's beds: no walls to throw the sound back, and a few dozen folk, not thousands.

- The breeze in the dry stalks (by day) and over the thatch (by night) is noise shaped frame by frame as wind.py's is, a
  dry hiss that swells with each gust and flutters as the stalks knock together, wide and diffuse.
- Hens are a glottal pulse train through a small bird's throat (formants about twice a man's), driven: a short falling 'bok'
  for a cluck, longer for the soft 'craw' of a contented hen, the run of clucks into a squawk of the egg song; with their
  pecking and scratching in the dirt. A cow lowing and the sheep bleating are the same source through bigger throats.
- The birds are sine voices on pitch contours, each species by its own song: the blackbird's slow fluted phrases with a
  squeaky twitter at the end, the robin's thin liquid warble, the wren's loud fast trill, the chaffinch's falling run and
  flourish, the song thrush saying each phrase two or three times, the wood pigeon's 'coo-COO-coo, coo-coo', the sparrows'
  chirping about the houses and the rooks cawing over the field.
- The owl is a breathy near-pure tone (the tawny owl's 'hooo... hu, hu-hu-hu-hooo' and its mate's sharp 'ke-wick'); the
  crickets are a carrier tone near 4.5 kHz broken into pulses, three or four to a chirp, a few near and many far.
- The market's crowd is the townsfolk's own recorded talk (sounds/townsfolk/voice), handed out to people standing about
  the green in twos as the city's crowds are (voices.py's Person and converse), half heard; with feet on the grass, a coin,
  a far handbell, a barrow's squeaking wheel and a hen in a basket.
- The dog barks are glottal bursts through a dog's throat, roughened and driven; the hammers a mallet on a fence post, a
  hammer driving nails, a smith at an anvil; the child the townsfolk's own child voice. Each is heard 40 to 100 metres off:
  the air takes the top, the ground and the trees add a soft tail, and a barn wall throws back an echo.

Every bed is a stereo loop with its tail crossfaded over its head (ambkit). A sound that stands out (a cart going by, an
owl's call, a blackbird's phrase, a cow) is kept clear of the loop's join, so it is never laid over itself there; the rest
(the breeze, the clucks, the chirps, the crickets) runs straight across it.

Played by client/ambience/VillageAmbience.kt: the beds under everything while the player is in a village, crossing over by
the hour (the birds hardest in the morning, the crickets only on warm nights, the market on its day as loud as the folk
about the player are many), the far one-shots placed 26 to 52 blocks off now and then.
"""
import os

import numpy as np

from ambkit import *  # noqa: F401,F403
from ambience.voices import Person, converse, phrase_spans, trim, voice_band
from ambience.wind import bumps, shaped_pair
from make_ship_audio import creak_voice, squeal

VOICES = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "src", "main",
                                       "resources", "assets", "guildmark", "sounds", "townsfolk", "voice"))
DAY_LOOP, NIGHT_LOOP, BIRD_LOOP, CRICKET_LOOP, MARKET_LOOP = 40.0, 40.0, 40.0, 30.0, 30.0
XFADE, PRE = 3.0, 3.0
DAY_AIR, NIGHT_AIR = 0.028, 0.02  # the breeze's RMS in the day bed and the night's, against the sounds in them
CLEAR = 0.4  # how far a sound that stands out is kept from the loop's join and its crossfade


# ---------------------------------------------------------------- time
def moments(rng, t0, t1, gap, first=None):
    """moments from t0 to t1, gap (lo, hi) seconds apart, the first at t0 + first (or somewhere in the first gap)"""
    t = t0 + (rng.uniform(0, gap[1]) if first is None else first)
    out = []
    while t < t1:
        out.append(t)
        t += rng.uniform(*gap)
    return out


def clear_run(rng, loop, make, gap, first=(0.0, 1.5), most=None):
    """sounds that stand out, made one after another by make() and laid gap (lo, hi) seconds apart from a little after the
    crossfade, as many as end before the loop's join (at most `most`), so none is ever laid over itself there: [(start in
    loop time, sound)]"""
    out = []
    t = XFADE + CLEAR + rng.uniform(*first)
    while most is None or len(out) < most:
        y = make()
        if t + len(y) / SR > loop - CLEAR:
            break
        out.append((t, y))
        t += len(y) / SR + rng.uniform(*gap)
    return out


def env_pts(points, dur):
    """a smooth curve through (time, value) points, a cosine between each two"""
    n = ns(dur)
    t = np.arange(n) / SR
    pts = sorted(points)
    out = np.full(n, float(pts[0][1]))
    for (t0, v0), (t1, v1) in zip(pts[:-1], pts[1:]):
        m = (t >= t0) & (t < t1)
        u = (t[m] - t0) / max(t1 - t0, 1e-9)
        out[m] = v0 + (v1 - v0) * (0.5 - 0.5 * np.cos(np.pi * u))
    out[t >= pts[-1][0]] = pts[-1][1]
    return out


# ---------------------------------------------------------------- the country round
def afield(x, dist, seed=611):
    """x as heard dist metres off across open country: the air takes the top end, the ground, the hedges and the trees add a
    soft tail (no street's hard walls). Distances are taken in steps of five metres, so the rooms are few"""
    dist = 5.0 * max(1, round(dist / 5.0))
    y = lp(x, max(1700.0, 17000.0 * np.exp(-dist / 50.0)), order=2)
    return reverb(y, wet=min(0.55, 0.06 + dist / 150.0), rt60=0.9 + dist / 110.0, predelay=0.012 + dist / 6000.0, hf_damp=0.7,
                  seed=seed, early_span=0.08)


def barn_echo(x, delay, gain=0.25, cutoff=2200.0):
    """x with the slap of a barn wall or a house front behind it, delay seconds later, duller"""
    d = ns(delay)
    echo = lp(np.concatenate([np.zeros(d), x]), cutoff, order=2)
    return np.concatenate([x, np.zeros(d)]) + gain * echo


def put(mix, x, at, az, dist, seed, gain=1.0, near=10.0):
    """x placed in the stereo mix at at seconds, a bearing az (-1 left .. 1 right) and dist metres; the farther, the softer"""
    mix.add(pan(afield(x, dist, seed), az), at, gain * (near / max(dist, 2.0)) ** 0.9)


def breeze(total, rng, strength, centre, width, body, flutter, flutter_hz=14.0):
    """a breeze through dry stalks, leaves or thatch, total seconds of it in stereo: a hiss gathered round centre (Hz), width
    octaves wide, over a softer, lower body (the air itself), both swelling with strength(t) (0..1, t from the buffer's start);
    the stalks knocking together break it up (flutter, up to flutter_hz), each ear its own, so it is wide"""
    n = ns(total)

    def mag(f, t):
        g = np.clip(strength(t), 0.0, 1.2)
        f = np.maximum(f, 1.0)
        hiss = np.exp(-0.5 * (np.log2(f / centre) / width) ** 2)
        low = body * np.exp(-0.5 * (np.log2(f / 380.0) / 1.3) ** 2)
        edge = 1 / np.sqrt(1 + (80.0 / f) ** 4) / np.sqrt(1 + (f / 12500.0) ** 6)
        return (0.08 + g) ** 1.4 * (hiss * (0.35 + 0.65 * g) + low) * edge

    st = shaped_pair(n, rng, mag, mag, spacing=1.5)
    tt = np.arange(n) / SR
    g = np.clip(strength(tt), 0.0, 1.2)
    for c in range(2):
        st[c] *= 1 - flutter * np.clip(g, 0, 1) * smooth_random(total, rng, flutter_hz) ** 1.5
    return st / (np.sqrt(np.mean(st ** 2)) + 1e-12)


def gust_curve(rng, loop, k, base, depth, rise=2.5, fall=3.5):
    """the breeze's strength over the loop: k gusts, unevenly spaced, on a steady base, repeating every loop seconds (so the
    breeze at the end runs on into the breeze at the start)"""
    centres = np.sort(rng.uniform(0, loop, k))
    heights = rng.uniform(0.55, 1.0, k)
    return lambda t: base + depth * bumps(t - PRE, centres, heights, loop, rise, fall)


# ---------------------------------------------------------------- the farmyard
def cluck(rng, f0=None, dur=None, vowels=("o", "uh"), drive_=1.8, fall=0.25):
    """one hen's 'bok': a short harsh pulse train falling in pitch, through a small bird's throat"""
    dur = dur or rng.uniform(0.055, 0.1)
    f0 = f0 or rng.uniform(290, 420)
    t = tvec(dur)
    f = f0 * (1.1 - fall * t / dur)
    src = glottal(dur, f, rng, jitter=0.04, shimmer=0.2, subharm=0.25, open_q=0.5, breath=0.25)
    y = formants(src, *vowels, scale=rng.uniform(2.1, 2.4), gains=(1.0, 0.8, 0.4, 0.2))
    y = drive(nrm(y), drive_)
    env = (1 - np.exp(-t / 0.004)) * np.exp(-t / (dur * 0.5))
    return hp(nrm(y) * env, 250.0)


def craw(rng, dur=None):
    """a contented hen's soft, drawn-out 'craaw', rising and falling, a little rough"""
    dur = dur or rng.uniform(0.22, 0.36)
    f0 = rng.uniform(330, 420)
    f = env_pts([(0, f0 * 0.9), (dur * 0.35, f0 * 1.15), (dur, f0 * 0.85)], dur)
    src = glottal(dur, f, rng, jitter=0.035, shimmer=0.18, subharm=0.2, open_q=0.55, breath=0.35)
    y = formants(src, "a", "uh", scale=rng.uniform(2.0, 2.3), gains=(1.0, 0.7, 0.35, 0.2))
    return hp(drive(nrm(y), 1.4) * bell(dur, 0.3, 1.0, 1.6), 250.0)


def egg_song(rng):
    """a hen's egg song: clucks gathering pace, 'buk, buk, buk-buk-buk', then a long squawk, 'ba-GAAWK'"""
    m = Mix(2.0)
    at, gap = 0.0, rng.uniform(0.3, 0.38)
    for _ in range(int(rng.integers(4, 7))):
        m.add(cluck(rng, f0=rng.uniform(360, 430), drive_=2.2), at, rng.uniform(0.7, 0.9))
        at += gap
        gap *= rng.uniform(0.72, 0.85)
    d = rng.uniform(0.38, 0.5)
    f0 = rng.uniform(560, 680)
    f = env_pts([(0, f0 * 0.75), (0.06, f0 * 1.08), (d * 0.6, f0), (d, f0 * 0.78)], d)
    src = glottal(d, f, rng, jitter=0.04, shimmer=0.22, subharm=0.35, open_q=0.45, breath=0.3)
    y = drive(nrm(formants(src, "a", "a", scale=2.2, gains=(1.0, 0.9, 0.5, 0.3))), 2.6) * bell(d, 0.15, 1.0, 1.4)
    m.add(cluck(rng, f0=f0 * 0.7, dur=0.06), at + 0.05, 0.8)
    m.add(hp(y, 300.0), at + 0.13, 1.0)
    return m.out()


def peck(rng):
    """a beak on hard earth: a tiny dry tick"""
    y = modal(0.02, rng.uniform(2200, 4200, 2), [0.004, 0.003], [1.0, 0.5], rng)
    return y * rng.uniform(0.5, 1.0)


def scratch(rng):
    """a hen scratching the dirt: two quick rakes of the claws"""
    m = Mix(0.3)
    for at in (0.0, rng.uniform(0.11, 0.16)):
        d = rng.uniform(0.04, 0.07)
        m.add(bp(white(d, rng), 1200, 6000) * bell(d, 0.2, 1.0, 2.0) * rng.uniform(0.6, 1.0), at)
    return m.out()


def hen_talk(rng, f0):
    """a hen about her business: a few clucks, now and then a craw, never loud"""
    m = Mix(2.0)
    at = 0.0
    if rng.random() < 0.22:
        m.add(craw(rng), 0.0, 0.8)
        at = 0.45
    for _ in range(int(rng.integers(1, 6))):
        m.add(cluck(rng, f0=f0 * rng.uniform(0.95, 1.06), vowels=("o", "uh") if rng.random() < 0.6 else ("u", "o")), at,
              rng.uniform(0.5, 1.0))
        at += rng.uniform(0.16, 0.3)
    return m.out()


def farmyard(mix, rng, t0, t1, hens, seed):
    """hens pecking about the yards from t0 to t1 (buffer time): their clucks and craws, pecks and scratching. hens is a list
    of (bearing, metres, how chatty)"""
    for i, (az, dist, chat) in enumerate(hens):
        f0 = rng.uniform(310, 400)
        for at in moments(rng, t0, t1, (3.5 / chat, 10.0 / chat)):
            a = float(np.clip(az + rng.uniform(-0.08, 0.08), -1, 1))
            put(mix, hen_talk(rng, f0), at, a, dist + rng.uniform(-2, 2), seed + i, 0.55)
        for at in moments(rng, t0, t1, (0.4, 2.2)):
            mix.add(pan(lp(peck(rng), 6000), float(np.clip(az + rng.uniform(-0.1, 0.1), -1, 1))), at,
                    0.12 * (6.0 / dist) ** 0.9)
        for at in moments(rng, t0, t1, (4.0, 11.0)):
            mix.add(pan(lp(scratch(rng), 5000), az), at, 0.14 * (6.0 / dist) ** 0.9)


def low(rng, dur=None):
    """a cow lowing: 'mmmooooo', a deep rough voice through a long throat, rising and falling away"""
    dur = dur or rng.uniform(1.6, 2.2)
    t = tvec(dur)
    f0 = rng.uniform(105, 130)
    f = env_pts([(0, f0 * 0.85), (dur * 0.25, f0 * 1.3), (dur * 0.7, f0 * 1.22), (dur, f0 * 0.8)], dur)
    f = f * (1 + 0.012 * np.sin(2 * np.pi * 5.2 * t))
    src = glottal(dur, f, rng, jitter=0.02, shimmer=0.08, subharm=0.15, open_q=0.6, breath=0.15)
    shape = env_pts([(0, 0.0), (dur * 0.2, 0.0), (dur * 0.45, 1.0), (dur * 0.75, 0.8), (dur, 0.1)], dur)
    y = formants(src, "u", "a", scale=0.72, curve=shape, gains=(1.0, 0.5, 0.25, 0.12))
    return drive(nrm(y), 1.5) * bell(dur, 0.3, 1.2, 1.4)


def bleat(rng, f0, dur):
    """a sheep's 'baa-aa': the quavering voice that gives it away, opening from a nasal 'eh' to 'aa'"""
    t = tvec(dur)
    rate = rng.uniform(8.5, 11.5)
    f = f0 * env_pts([(0, 0.9), (dur * 0.15, 1.05), (dur, 0.92)], dur) * (1 + 0.035 * np.sin(2 * np.pi * rate * t))
    src = glottal(dur, f, rng, jitter=0.03, shimmer=0.25, subharm=0.12, open_q=0.55, breath=0.3)
    y = formants(src, "e", "a", scale=1.15 if f0 > 300 else 1.0, curve=np.clip(t / (0.35 * dur), 0, 1))
    am = 1 - 0.4 * (0.5 + 0.5 * np.sin(2 * np.pi * rate * t + 1.0))
    return drive(nrm(y), 1.3) * am * bell(dur, 0.12, 1.0, 1.5)


def caw(rng):
    """a rook's 'kaah' from over the field: harsh, falling, the throat rattling"""
    d = rng.uniform(0.28, 0.42)
    f0 = rng.uniform(460, 560)
    src = glottal(d, f0 * np.linspace(1.08, 0.86, ns(d)), rng, jitter=0.06, shimmer=0.3, subharm=0.45, open_q=0.45,
                  breath=0.45)
    y = formants(src, "a", "a", scale=1.55, gains=(1.0, 0.8, 0.45, 0.25))
    return drive(nrm(y), 2.6) * bell(d, 0.12, 1.0, 1.6)


def far_cart(rng, dur):
    """a farm cart going along a lane well off: the wheels' low rumble and knocks on the ruts, an axle that creaks once a
    turn, the horse's hooves dull on the earth; swelling as it comes and fading as it goes"""
    m = Mix(dur)
    rumble = lp(brown(dur, rng), 150, order=2)
    m.add(nrm(rumble) * (0.6 + 0.4 * smooth_random(dur, rng, 3.0)), 0.0, 0.5)
    m.add(nrm(crackle(dur, rng, 6.0, decay=0.012, f_lo=60, f_hi=400)), 0.0, 0.45)
    turn = rng.uniform(1.25, 1.5)
    for at in np.arange(rng.uniform(0, turn), dur - 0.4, turn):
        cd = rng.uniform(0.22, 0.32)
        rate = np.linspace(rng.uniform(25, 35), rng.uniform(55, 80), ns(cd))
        c = creak_voice(cd, rng, rate, bell(cd, 0.4, 1.2, 1.5),
                        (np.array([360.0, 640.0, 1050.0, 1580.0]), np.array([0.05, 0.04, 0.03, 0.02]),
                         np.array([1.0, 0.7, 0.45, 0.25])), rub=0.08)
        m.add(nrm(c), at, rng.uniform(0.5, 0.8))
    for at in np.arange(rng.uniform(0, 0.3), dur - 0.2, 1.05):
        for g in (0.0, 0.28, 0.52, 0.79):
            clop = lp(white(0.06, rng), 260) * np.exp(-tvec(0.06) / 0.018) + 0.25 * bp(white(0.06, rng), 500, 1500) * np.exp(
                -tvec(0.06) / 0.006)
            m.add(nrm(clop), at + g + rng.uniform(-0.012, 0.012), rng.uniform(0.35, 0.5))
    return fit(m.out(), ns(dur)) * bell(dur, 0.45, 1.6, 1.6)


# ---------------------------------------------------------------- birds
def note(rng, dur, f, level=1.0, harm=(1.0, 0.1, 0.03), attack=0.006, release=0.015, trill=None, buzz=None):
    """one note of birdsong: a near-pure tone on the pitch f (Hz, or f(u) of u running 0..1 over the note), a few weak
    harmonics, its own small attack and release; trill (rate Hz, depth) shakes the pitch, buzz (rate Hz, depth) the level"""
    n = ns(dur)
    u = np.linspace(0, 1, n, endpoint=False)
    fc = np.asarray(f(u) if callable(f) else np.full(n, float(f)), dtype=float)
    if trill:
        fc = fc * (1 + trill[1] * np.sin(2 * np.pi * trill[0] * u * dur + rng.uniform(0, 6.28)))
    ph = phase_from_freq(fc, n) + rng.uniform(0, 2 * np.pi)
    y = np.zeros(n)
    for k, a in enumerate(harm):
        if (k + 1) * float(np.max(fc)) < 19000:
            y += a * np.sin((k + 1) * ph)
    if buzz:
        y *= 1 - buzz[1] * (0.5 + 0.5 * np.sin(2 * np.pi * buzz[0] * u * dur))
    t = u * dur
    env = np.sin(0.5 * np.pi * np.clip(t / attack, 0, 1)) ** 2 * np.sin(0.5 * np.pi * np.clip((dur - t) / release, 0, 1)) ** 2
    return level * y * env


def glide(a, b, shape=1.0):
    return lambda u: a * (b / a) ** (u ** shape)


def arch(a, peak, b, at=0.4):
    return lambda u: np.where(u < at, a * (peak / a) ** (u / at), peak * (b / peak) ** ((u - at) / (1 - at)))


def blackbird(rng):
    """a blackbird's phrase: four to seven slow, fluted notes, low and rich, then a squeaky twitter, softer"""
    m = Mix(3.2)
    at = 0.0
    for _ in range(int(rng.integers(4, 8))):
        d = rng.uniform(0.08, 0.24)
        f0 = rng.uniform(1450, 2600)
        f1 = f0 * rng.uniform(0.75, 1.3)
        m.add(note(rng, d, arch(f0, max(f0, f1) * rng.uniform(1.0, 1.15), f1, rng.uniform(0.2, 0.6)), rng.uniform(0.6, 1.0),
                   harm=(1.0, 0.12, 0.04), attack=0.012, release=0.03, trill=(rng.uniform(25, 40), 0.008)), at)
        at += d + rng.uniform(0.015, 0.07)
    at += rng.uniform(0.0, 0.05)
    for _ in range(int(rng.integers(2, 6))):
        d = rng.uniform(0.025, 0.06)
        f0 = rng.uniform(4000, 6800)
        m.add(note(rng, d, glide(f0, f0 * rng.uniform(0.7, 1.25)), rng.uniform(0.2, 0.4), harm=(1.0, 0.3, 0.12),
                   attack=0.002, release=0.008), at)
        at += d + rng.uniform(0.008, 0.03)
    return m.out()[:ns(at + 0.05)]


def robin(rng):
    """a robin's warble: thin, high and liquid, quick notes sliding up and down, some trilled, never two alike"""
    m = Mix(3.0)
    at = 0.0
    for _ in range(int(rng.integers(8, 15))):
        d = rng.uniform(0.03, 0.12)
        f0 = rng.uniform(2600, 7200)
        f1 = float(np.clip(f0 * rng.uniform(0.6, 1.6), 2400, 8200))
        tr = (rng.uniform(30, 60), rng.uniform(0.03, 0.08)) if rng.random() < 0.3 else None
        m.add(note(rng, d, glide(f0, f1, rng.uniform(0.6, 1.8)), rng.uniform(0.4, 1.0), harm=(1.0, 0.06), attack=0.004,
                   release=0.01, trill=tr), at)
        at += d + rng.uniform(0.008, 0.06)
    return m.out()[:ns(at + 0.05)]


def wren(rng):
    """a wren's song: very loud for so small a bird, fast, high and hard, runs of a repeated note and a rattling trill"""
    m = Mix(5.0)
    at = 0.0
    for part in range(int(rng.integers(3, 5))):
        if part == 2 or rng.random() < 0.25:
            d = rng.uniform(0.4, 0.7)
            m.add(note(rng, d, rng.uniform(4300, 5400), 0.9, harm=(1.0, 0.15), attack=0.01, release=0.02,
                       trill=(rng.uniform(50, 70), 0.12), buzz=(rng.uniform(50, 70), 0.6)), at)
            at += d + rng.uniform(0.02, 0.06)
            continue
        f0, f1 = rng.uniform(4000, 8000), rng.uniform(3800, 8000)
        d = rng.uniform(0.025, 0.045)
        gap = rng.uniform(0.02, 0.04)
        for _ in range(int(rng.integers(4, 10))):
            m.add(note(rng, d, glide(f0, f1), rng.uniform(0.75, 1.0), harm=(1.0, 0.12), attack=0.002, release=0.006), at)
            at += d + gap
        at += rng.uniform(0.02, 0.06)
    return m.out()[:ns(at + 0.05)]


def chaffinch(rng):
    """a chaffinch's song: a run of notes falling and quickening, a slower run lower down, then the flourish at the end"""
    m = Mix(3.0)
    at, gap = 0.0, rng.uniform(0.11, 0.13)
    top = rng.uniform(5600, 6400)
    for k in range(int(rng.integers(4, 7))):
        f = top * (1 - 0.03 * k)
        m.add(note(rng, 0.04, glide(f, f * 0.82), rng.uniform(0.6, 0.85), harm=(1.0, 0.18), attack=0.003, release=0.01), at)
        at += gap
        gap *= 0.93
    mid = top * rng.uniform(0.7, 0.78)
    for k in range(int(rng.integers(3, 5))):
        f = mid * (1 - 0.04 * k)
        m.add(note(rng, 0.05, arch(f * 0.92, f, f * 0.85), 0.8, harm=(1.0, 0.2), attack=0.003, release=0.012), at)
        at += 0.085
    d = rng.uniform(0.3, 0.4)
    m.add(note(rng, d, arch(3100, rng.uniform(4300, 4800), 2500, 0.3), 1.0, harm=(1.0, 0.22, 0.05), attack=0.01,
               release=0.05), at + 0.02)
    return m.out()[:ns(at + d + 0.08)]


def thrush(rng):
    """a song thrush: a loud clear phrase of a few notes, said two or three times over, then another"""
    m = Mix(4.0)
    at = 0.0
    for _ in range(int(rng.integers(2, 4))):
        motif = []
        for _ in range(int(rng.integers(1, 4))):
            d = rng.uniform(0.04, 0.12)
            f0 = rng.uniform(2000, 5200)
            motif.append((d, f0, f0 * rng.uniform(0.75, 1.3), rng.uniform(0.6, 1.0)))
        for _ in range(int(rng.integers(2, 4))):
            for d, f0, f1, g in motif:
                m.add(note(rng, d, glide(f0, f1), g, harm=(1.0, 0.15, 0.04), attack=0.004, release=0.012), at)
                at += d + 0.02
            at += rng.uniform(0.06, 0.12)
        at += rng.uniform(0.25, 0.45)
    return m.out()[:ns(at)]


def wood_pigeon(rng):
    """a wood pigeon from a far tree: 'coo-COO-coo, coo-coo', soft and husky"""
    m = Mix(3.2)
    f = rng.uniform(410, 500)
    for at, d, g in ((0.0, 0.3, 0.6), (0.42, 0.55, 1.0), (1.05, 0.3, 0.7), (1.55, 0.36, 0.75), (2.0, 0.3, 0.6)):
        y = note(rng, d, arch(f * 0.92, f * 1.05, f * 0.95, 0.3), g, harm=(1.0, 0.28, 0.08), attack=0.04, release=0.08)
        y = y + 0.06 * g * bp(white(d, rng), f * 0.8, f * 2.5) * bell(d, 0.3, 1.2, 1.2)
        m.add(y, at)
    return lp(m.out(), 1600)


def sparrows(rng):
    """sparrows about the eaves: a burst of harsh little chirps, 'cheep, chirrup, cheep'"""
    m = Mix(1.5)
    at = 0.0
    for _ in range(int(rng.integers(2, 7))):
        d = rng.uniform(0.05, 0.11)
        f0 = rng.uniform(2900, 3600)
        m.add(note(rng, d, arch(f0, f0 * rng.uniform(1.15, 1.4), f0 * rng.uniform(0.85, 1.05), rng.uniform(0.2, 0.5)),
                   rng.uniform(0.5, 1.0), harm=(1.0, 0.5, 0.28, 0.12), attack=0.003, release=0.01,
                   buzz=(rng.uniform(140, 240), 0.5)), at)
        at += d + rng.uniform(0.06, 0.2)
    return m.out()[:ns(at)]


def singer(mix, rng, song, loop, az, dist, gap, seed, gain=1.0, first=(0.0, 1.5)):
    """one bird singing its song over the loop from one perch, gap (lo, hi) seconds between phrases, every phrase kept clear
    of the join"""
    for at, y in clear_run(rng, loop, lambda: song(rng), gap, first):
        put(mix, y, at + PRE, float(np.clip(az + rng.uniform(-0.03, 0.03), -1, 1)), dist, seed, gain)


# ---------------------------------------------------------------- night
def hoot(rng, dur, f0, quaver=0.0):
    """one tawny owl's 'hooo': a breathy, near-pure low tone, rising a little and falling away; quaver shakes it"""
    t = tvec(dur)
    f = f0 * env_pts([(0, 0.94), (dur * 0.3, 1.02), (dur, 0.93)], dur)
    y = note(rng, dur, lambda u: np.interp(u, np.linspace(0, 1, len(f)), f), 1.0, harm=(1.0, 0.1, 0.03), attack=0.05,
             release=min(0.25, dur * 0.5))
    if quaver:
        y *= 1 - quaver * (0.5 + 0.5 * np.sin(2 * np.pi * rng.uniform(13, 17) * t))
    breath = bp(white(dur, rng), f0 * 0.8, f0 * 2.2) * bell(dur, 0.3, 1.2, 1.2)
    return y + 0.08 * nrm(breath)


def owl_song(rng):
    """the tawny owl's song: 'hooo', a pause, then 'hu... hu-hu-hu-hoooo', quavering"""
    f0 = rng.uniform(560, 640)
    m = Mix(5.5)
    m.add(hoot(rng, rng.uniform(0.45, 0.6), f0), 0.0)
    at = rng.uniform(2.6, 3.4)
    m.add(hoot(rng, 0.12, f0 * 0.97), at, 0.7)
    at += rng.uniform(0.45, 0.6)
    for _ in range(3):
        m.add(hoot(rng, 0.09, f0 * 1.0), at, 0.75)
        at += 0.15
    m.add(hoot(rng, rng.uniform(0.75, 1.0), f0 * 1.01, quaver=0.55), at + 0.03, 1.0)
    return m.out()


def kewick(rng):
    """the hen owl's 'ke-wick': sharp, high and harsh"""
    m = Mix(0.6)
    f0 = rng.uniform(1300, 1500)
    m.add(note(rng, 0.05, f0 * 0.9, 0.5, harm=(1.0, 0.5, 0.3, 0.15), attack=0.004, release=0.015), 0.0)
    d = rng.uniform(0.22, 0.3)
    y = note(rng, d, arch(f0 * 0.95, f0 * 1.35, f0 * 1.05, 0.35), 1.0, harm=(1.0, 0.6, 0.35, 0.2), attack=0.006,
             release=0.08, buzz=(rng.uniform(90, 120), 0.35))
    m.add(y + 0.1 * bp(white(d, rng), 1500, 4500) * bell(d, 0.2, 1, 2), 0.09)
    return m.out()


def hedge_rustle(rng):
    """something small moving in the hedge bottom at night: a few dry rustles of leaves"""
    d = rng.uniform(0.4, 0.9)
    y = bp(white(d, rng), 1200, 7000) * (smooth_random(d, rng, 18) ** 3)
    return y * bell(d, 0.4, 1.2, 1.2)


def gate_creak(rng):
    """a field gate swinging a little on its hinge in the night air"""
    d = rng.uniform(0.6, 0.9)
    rate = env_pts([(0, 22), (d * 0.5, 48), (d, 30)], d)
    return creak_voice(d, rng, rate, bell(d, 0.4, 1.3, 1.3),
                       (np.array([310.0, 540.0, 880.0, 1310.0]), np.array([0.05, 0.04, 0.03, 0.02]),
                        np.array([1.0, 0.7, 0.4, 0.2])), rub=0.06)


# ---------------------------------------------------------------- crickets
def cricket(dur, rng, carrier, rate, pulses, rest):
    """one field cricket singing: chirps of a few pulses of a carrier tone near 4.5 kHz, rate chirps a second; now and then it
    stops a while (rest is how likely, chirp by chirp)"""
    n = ns(dur)
    y = np.zeros(n)
    plen, pgap = rng.uniform(0.016, 0.021), rng.uniform(0.032, 0.039)
    m = ns(plen)
    u = np.arange(m) / m
    ph = phase_from_freq(carrier * (1.0 - 0.03 * u), m)
    pulse = (np.sin(ph) + 0.06 * np.sin(2 * ph)) * np.sin(np.pi * u) ** 0.7
    t = rng.uniform(0, 1.0 / rate)
    while t < dur:
        for k in range(pulses):
            i = ns(t + k * pgap)
            if i + m > n:
                break
            y[i:i + m] += pulse * (0.8 if k == 0 else 1.0) * rng.uniform(0.9, 1.0)
        t += rng.uniform(0.94, 1.06) / rate
        if rng.random() < rest:
            t += rng.uniform(1.5, 5.0)
    return y


# ---------------------------------------------------------------- the beds
@recipe("village_day", variants=2, stereo=True, loop=DAY_LOOP, xfade=XFADE, rms_db=-29.0)
def village_day(v, rng):
    """A village by day, between the houses and the fields: the breeze in the dry stalks and the hedges, hens about the yards
    clucking and scratching, sparrows at the eaves; well off a cart going along the lane, and once a cow lowing (v1) or the
    sheep in the next field and the rooks over it (v2). A 40 s stereo loop. Birdsong over it (village_birds), and the far
    hammer and the children's shouts, come from the village layer, so that none of them comes round on the loop's beat"""
    total = PRE + DAY_LOOP + XFADE + 0.3
    n = ns(total)
    strength = gust_curve(rng, DAY_LOOP, 5, 0.4, 0.55)
    air = breeze(total, rng, strength, centre=[2900.0, 2600.0][v], width=1.35, body=0.45, flutter=0.5)
    life = StereoMix(total)
    hens = [[(-0.55, 9.0, 1.0), (-0.25, 13.0, 0.8), (0.4, 16.0, 0.7)], [(0.6, 11.0, 0.9), (0.2, 17.0, 0.8), (-0.7, 22.0, 0.6)]][v]
    farmyard(life, rng, 0.0, total - 1.0, hens, seed=620 + 10 * v)
    for at in moments(rng, 0.0, total - 1.5, (3.0, 9.0)):
        put(life, sparrows(rng), at, rng.uniform(-0.9, 0.9), rng.uniform(8, 18), 640 + v, 0.22)
    # well off, things that stand out, each kept clear of the loop's join
    for at, y in clear_run(rng, DAY_LOOP, lambda: far_cart(rng, rng.uniform(11.0, 13.0)), (0, 0),
                           first=[(2.0, 5.0), (12.0, 15.0)][v], most=1):
        put(life, y, at + PRE, [0.5, -0.45][v], 75.0, 650 + v, 1.6)
    if v == 0:
        at = rng.uniform(26.0, 31.0)
        put(life, low(rng), at + PRE, -0.65, 110.0, 660, 0.7)
    else:
        at = rng.uniform(5.0, 8.0)
        for k, (f0, d) in enumerate(((rng.uniform(250, 290), 0.75), (rng.uniform(380, 430), 0.6), (rng.uniform(240, 280), 0.8))):
            put(life, bleat(rng, f0, d), at + PRE, 0.7, 95.0, 661, 0.9)
            at += rng.uniform(1.6, 3.5)
        for at in sorted(rng.uniform(30.0, DAY_LOOP - 2.0, 3)):
            put(life, caw(rng), at + PRE, rng.uniform(-0.8, -0.2), rng.uniform(70, 110), 662, 0.55)
    # the breeze under the farmyard: its lulls well under a hen's clucks, its gusts about as loud
    out = DAY_AIR * air + life.out(n)
    return out[:, ns(PRE):]


@recipe("village_night", variants=2, stereo=True, loop=NIGHT_LOOP, xfade=XFADE, rms_db=-31.0)
def village_night(v, rng):
    """A village by night: the air moving softly over the thatch and through the hedges, now and then a rustle in the hedge
    bottom, and an owl: a tawny owl calling from the trees beyond the houses (v1), or the hen owl's 'ke-wick' answered from
    far off (v2), with a field gate shifting on its hinge. A 40 s stereo loop, sparse; the crickets (village_crickets) and the
    far dog come from the village layer"""
    total = PRE + NIGHT_LOOP + XFADE + 0.3
    n = ns(total)
    strength = gust_curve(rng, NIGHT_LOOP, 4, 0.15, 0.6, rise=3.5, fall=4.5)
    air = breeze(total, rng, strength, centre=1700.0, width=1.5, body=0.7, flutter=0.25, flutter_hz=8.0)
    life = StereoMix(total)
    for at in moments(rng, 0.0, total - 1.5, (5.0, 14.0)):
        put(life, hedge_rustle(rng), at, rng.uniform(-0.9, 0.9), rng.uniform(6, 14), 670 + v, 0.1)
    if v == 0:
        for at, y in clear_run(rng, NIGHT_LOOP, lambda: owl_song(rng), (12.0, 16.0), first=(2.0, 4.0), most=2):
            put(life, y, at + PRE, -0.35 + rng.uniform(-0.1, 0.1), 45.0, 680, 0.45)
    else:
        for k, (at, y) in enumerate(clear_run(rng, NIGHT_LOOP, lambda: kewick(rng), (5.0, 9.0), first=(1.0, 3.0), most=4)):
            put(life, y, at + PRE, 0.55, 30.0, 681, 0.32)
            if k == 1:  # her mate answers from far off
                song = owl_song(rng)
                if at + 2.2 + len(song) / SR < NIGHT_LOOP - CLEAR:
                    put(life, song, at + 2.2 + PRE, -0.75, 85.0, 682, 0.6)
        at = rng.uniform(NIGHT_LOOP * 0.7, NIGHT_LOOP - 2.5)
        put(life, gate_creak(rng), at + PRE, -0.3, 20.0, 683, 0.07)
    # the air under the owl: its gusts well under the owl's call
    out = NIGHT_AIR * air + life.out(n)
    return out[:, ns(PRE):]


@recipe("village_birds", variants=1, stereo=True, loop=BIRD_LOOP, xfade=XFADE, rms_db=-30.0)
def village_birds(v, rng):
    """Birdsong about a village, the morning's and the evening's (the layer plays it hardest in the morning chorus): a
    blackbird on a roof ridge, a robin in the hedge, a wren loud in the bushes, a song thrush in a tree beyond, a chaffinch and
    a far wood pigeon, the sparrows at the eaves. A 40 s stereo loop; every bird's phrases are kept clear of its join"""
    total = PRE + BIRD_LOOP + XFADE + 0.3
    n = ns(total)
    mix = StereoMix(total)
    singer(mix, rng, blackbird, BIRD_LOOP, -0.4, 22.0, (2.6, 4.5), 700, 0.85)
    singer(mix, rng, robin, BIRD_LOOP, 0.55, 14.0, (3.5, 6.5), 701, 0.5, first=(1.0, 3.0))
    singer(mix, rng, wren, BIRD_LOOP, 0.8, 20.0, (11.0, 15.0), 702, 0.55, first=(3.0, 6.0))
    singer(mix, rng, thrush, BIRD_LOOP, -0.75, 38.0, (6.0, 9.0), 703, 0.85, first=(6.0, 9.0))
    singer(mix, rng, chaffinch, BIRD_LOOP, 0.15, 32.0, (5.0, 8.0), 704, 0.6, first=(0.5, 2.0))
    at = rng.uniform(14.0, 18.0)
    put(mix, wood_pigeon(rng), at + PRE, -0.1, 70.0, 705, 1.2)
    for at in moments(rng, 0.0, total - 1.5, (2.0, 6.0)):
        put(mix, sparrows(rng), at, rng.uniform(-0.9, 0.9), rng.uniform(10, 20), 706, 0.3)
    # the rest of the chorus, far off in the gardens and the trees round, phrases of every kind running on across the join
    songs = [blackbird, robin, chaffinch, thrush, robin, blackbird, wren]
    for at in moments(rng, 0.0, total - 4.5, (0.7, 2.0)):
        put(mix, songs[int(rng.integers(len(songs)))](rng), at, rng.uniform(-1, 1), rng.uniform(60, 120), 707, 0.5)
    return mix.out(n)[:, ns(PRE):]


@recipe("village_crickets", variants=1, stereo=True, loop=CRICKET_LOOP, xfade=XFADE, rms_db=-32.0)
def village_crickets(v, rng):
    """Crickets in the grass of a warm night: four near, each chirping at its own pace and pitch and stopping now and then,
    and many more far off in the fields, a soft shimmer. A 30 s stereo loop"""
    total = PRE + CRICKET_LOOP + XFADE + 0.3
    n = ns(total)
    mix = StereoMix(total)
    for k in range(4):
        y = cricket(total, rng, rng.uniform(4300, 4900), rng.uniform(2.2, 3.4), int(rng.integers(3, 5)), 0.03)
        put(mix, y, 0.0, [-0.7, -0.15, 0.35, 0.8][k] + rng.uniform(-0.1, 0.1), rng.uniform(4, 12), 720 + k, 0.5)
    far_mix = StereoMix(total)
    for k in range(14):
        y = cricket(total, rng, rng.uniform(4100, 5000), rng.uniform(2.0, 3.6), int(rng.integers(3, 5)), 0.015)
        far_mix.add(pan(lp(y, 6000), rng.uniform(-1, 1)), 0.0, rng.uniform(0.5, 1.0))
    fm = stereo_reverb(far_mix.out(n), wet=0.6, rt60=1.4, predelay=0.03, hf_damp=0.75, seeds=(731, 732))[:, :n]
    near = mix.out(n)
    out = near / (np.sqrt(np.mean(near ** 2)) + 1e-12) + 0.55 * fm / (np.sqrt(np.mean(fm ** 2)) + 1e-12)
    return out[:, ns(PRE):]


# ---------------------------------------------------------------- the market
MARKET_FROM = ("talk.market.", "talk.home.", "talk.town.", "talk.doing.", "talk.sky.")  # everyday talk, as the queue's is


def talk_pool(timbre, rng, k):
    """{voice: phrases} from k of the townsfolk's recorded talk lines in that voice (named families only, so new lines
    elsewhere leave the bed as it is), cut at their pauses and levelled alike"""
    d = os.path.join(VOICES, timbre)
    names = sorted(f for f in os.listdir(d) if f.startswith(MARKET_FROM) and f.endswith(".ogg"))
    out = []
    for i in sorted(rng.choice(len(names), size=min(k, len(names)), replace=False)):
        x = trim(read_audio(os.path.join(d, names[i]))[0])
        for a, b in phrase_spans(x, gap=0.12, min_len=0.3):
            p = fade(x[max(0, a - ns(0.01)): b + ns(0.06)], 0.005, 0.04)
            speech = np.abs(p) > 0.05 * np.max(np.abs(p))
            out.append(p * db(-20.0) / (np.sqrt(np.mean(p[speech] ** 2)) + 1e-12))
    return out


def soft_step(rng):
    """a footfall on the green: the earth's dull thud and the grass brushed by the shoe"""
    d = 0.12
    t = tvec(d)
    thud = lp(white(d, rng), 200, order=2) * np.exp(-t / 0.02)
    swish = bp(white(d, rng), 900, 5000) * np.exp(-t / 0.03) * rng.uniform(0.2, 0.45)
    return nrm(thud) + nrm(swish) * 0.6


def coins(rng):
    """a few coins counted into a palm: small bright rings, one on another"""
    m = Mix(0.6)
    at = 0.0
    for _ in range(int(rng.integers(2, 5))):
        f = rng.uniform(3200, 5200)
        m.add(modal(0.25, [f, f * 1.48, f * 2.12], [0.12, 0.08, 0.05], [1.0, 0.6, 0.35], rng), at, rng.uniform(0.5, 1.0))
        at += rng.uniform(0.05, 0.12)
    return m.out()


def handbell_far(rng):
    """a small brass handbell rung three times somewhere across the green"""
    m = Mix(2.0)
    f0 = rng.uniform(1150, 1250)
    for at in (0.0, 0.17, 0.34):
        m.add(modal(1.4, f0 * np.array([1.0, 2.02, 2.97, 4.13]), [1.2, 0.8, 0.6, 0.4], [1.0, 0.3, 0.55, 0.3], rng), at)
    return m.out()


def barrow(rng, dur):
    """a wheelbarrow pushed across the green, its dry wheel squeaking once a turn, the wheel rumbling on the earth"""
    m = Mix(dur)
    m.add(nrm(lp(brown(dur, rng), 220, order=2)), 0.0, 0.35)
    turn = rng.uniform(0.5, 0.6)
    f = rng.uniform(950, 1250)
    for at in np.arange(rng.uniform(0, turn), dur - 0.2, turn):
        d = rng.uniform(0.08, 0.13)
        m.add(nrm(squeal(d, rng, f * rng.uniform(0.97, 1.03), bell(d, 0.4, 1.0, 1.5), harmonics=6, peak_hz=2400)), at,
              rng.uniform(0.6, 1.0))
    return fit(m.out(), ns(dur)) * bell(dur, 0.5, 1.4, 1.4)


@recipe("village_market", variants=1, stereo=True, loop=MARKET_LOOP, xfade=XFADE, rms_db=-29.0)
def village_market(v, rng):
    """A village's market day on the green: a dozen folk standing about the stalls in twos and threes, chatting (the words only
    half heard), their feet on the grass, a coin or two changing hands, a handbell across the green, a barrow's squeaking wheel
    going by and a hen complaining in a basket. A 30 s stereo loop, far smaller than the capital's square"""
    total = PRE + MARKET_LOOP + XFADE + 1.0
    n = ns(total)
    t0, t1 = -PRE, MARKET_LOOP + XFADE + 1.0
    pools = {tb: talk_pool(tb, rng, 8) for tb in ("man", "woman", "old")}
    people = []

    def pair(names, dist, a, b):
        az = rng.uniform(-0.95, 0.95)
        two = []
        for tb in names:
            p = Person(tb, pools[tb], rng.uniform(-2.2, 2.2), first=int(rng.integers(len(pools[tb]))))
            p.az, p.dist = float(np.clip(az + rng.uniform(-0.06, 0.06), -1, 1)), dist + rng.uniform(-1, 1)
            two.append(p)
        converse(rng, two, a, b)
        people.extend(two)

    # near pairs (9-13 blocks) keep clear of the loop point; farther ones talk across it
    for names in (("woman", "man"), ("old", "woman")):
        a = XFADE + CLEAR + rng.uniform(0.0, 3.0)
        pair(names, rng.uniform(9, 13), a, a + rng.uniform(9.0, 12.0))
        a2 = a + rng.uniform(13.0, 15.0)
        pair(names[::-1], rng.uniform(9, 13), a2, min(MARKET_LOOP - CLEAR, a2 + rng.uniform(8.0, 11.0)))
    # farther off, folk talking a while and going quiet, across the join
    for names in (("man", "man"), ("woman", "old"), ("woman", "woman"), ("man", "old")):
        a = t0 + rng.uniform(0.0, 4.0)
        while a < t1 - 3.0:
            b = min(t1, a + rng.uniform(6.0, 12.0))
            pair(names, rng.uniform(15, 26), a, b)
            a = b + rng.uniform(2.0, 6.0)
    mix = StereoMix(total)
    for i, p in enumerate(people):
        if not p.said:
            continue
        start = p.said[0][0]
        track = Mix()
        for t, x in p.said:
            track.add(x, at=t - start)
        y = afield(voice_band(track.out(), p.dist), p.dist, seed=740 + i)
        mix.add(pan(y, p.az), at=start - t0, gain=(10.0 / p.dist) ** 0.9)
    voices = mix.out(n)
    things = StereoMix(total)
    for at in moments(rng, 0.0, total - 0.5, (0.4, 1.6)):
        d = rng.uniform(5.0, 16.0)
        things.add(pan(lp(soft_step(rng), 7000 * np.exp(-d / 20.0) + 1500), rng.uniform(-0.9, 0.9)), at, 0.2 * 6.0 / d)
    for at, y in clear_run(rng, MARKET_LOOP, lambda: coins(rng), (9.0, 13.0), first=(1.0, 4.0), most=2):
        put(things, y, at + PRE, rng.uniform(-0.5, 0.5), rng.uniform(7, 10), 760, 0.35)
    for at, y in clear_run(rng, MARKET_LOOP, lambda: handbell_far(rng), (0, 0), first=(13.0, 16.0), most=1):
        put(things, y, at + PRE, -0.6, 35.0, 761, 0.45)
    for at, y in clear_run(rng, MARKET_LOOP, lambda: barrow(rng, rng.uniform(5.0, 6.0)), (0, 0), first=(3.0, 6.0), most=1):
        put(things, y, at + PRE, 0.3, 12.0, 762, 0.4)
    at = rng.uniform(20.0, MARKET_LOOP - 3.0)
    for k in range(3):
        put(things, cluck(rng, f0=rng.uniform(340, 420), drive_=2.4), at + PRE, 0.65, 10.0, 763, 0.35)
        at += rng.uniform(0.18, 0.3)
    th = things.out(n)
    lvl = np.sqrt(np.mean(voices ** 2)) + 1e-12
    bed = voices / lvl + 0.45 * th / (np.sqrt(np.mean(th ** 2)) + 1e-12)
    bed = np.stack([hp(c, 110.0) for c in bed])
    out = stereo_reverb(bed, wet=0.06, rt60=1.0, predelay=0.02, hf_damp=0.65, seeds=(771, 772))
    return out[:, ns(PRE): ns(PRE + MARKET_LOOP + XFADE + 0.2)]


# ---------------------------------------------------------------- heard far off
def bark(rng, f0, size=1.0):
    """one bark: a rough, driven burst of voice through a dog's throat, thrown up and falling"""
    d = rng.uniform(0.13, 0.2) * size
    t = tvec(d)
    f = env_pts([(0, f0 * 0.85), (0.03, f0 * 1.15), (d, f0 * 0.72)], d)
    src = glottal(d, f, rng, jitter=0.05, shimmer=0.3, subharm=0.35, open_q=0.45, breath=0.5)
    y = formants(src, "a", "uh", scale=1.2 / size, gains=(1.0, 0.75, 0.4, 0.2))
    y = drive(nrm(y), 3.0) * (1 - np.exp(-t / 0.006)) * np.exp(-t / (d * 0.55))
    rasp = bp(white(d, rng), 600, 3200) * np.exp(-t / 0.02)
    return nrm(y) + 0.25 * nrm(rasp)


@recipe("dog_far", variants=3, peak_db=-6.0, rms_db=-27.0, fade_out=0.3, max_len=2.0, tail_db=-58.0)
def dog_far(v, rng):
    """a farm dog barking far off across the fields, its bark coming back off a barn: v1 twice, v2 three quick barks and one
    more, v3 a big dog, slow, twice"""
    f0, size, plan, dist, echo = [(rng.uniform(380, 440), 1.0, (0.0, 0.36), 70.0, 0.21),
                                  (rng.uniform(460, 520), 0.85, (0.0, 0.24, 0.48, 1.05), 85.0, 0.17),
                                  (rng.uniform(260, 300), 1.3, (0.0, 0.75), 95.0, 0.26)][v]
    m = Mix(1.6)
    for at in plan:
        m.add(bark(rng, f0 * rng.uniform(0.96, 1.04), size), at, rng.uniform(0.85, 1.0))
    y = barn_echo(m.out(), echo, 0.3)
    return afield(lp(y, 4000), dist, seed=790 + v)


def mallet(rng, f0):
    """a wooden mallet on a fence post: a dull knock, the post's modes"""
    r = np.array([1.0, 1.62, 2.41, 3.37])
    return modal(0.25, f0 * r, [0.06, 0.05, 0.035, 0.025], [1.0, 0.8, 0.5, 0.3], rng) + 0.3 * lp(white(0.25, rng), 400) * \
        np.exp(-tvec(0.25) / 0.015)


def nail(rng, f, hard=1.0):
    """a hammer on a nail going into a board: the iron's 'tink' and the board's knock"""
    tink = modal(0.2, [f, f * 2.76, f * 5.4], [0.1, 0.06, 0.03], [1.0, 0.5, 0.2], rng)
    knock = modal(0.2, rng.uniform(300, 380) * np.array([1.0, 1.6, 2.4]), [0.05, 0.035, 0.02], [1.0, 0.6, 0.3], rng)
    return 0.6 * tink * hard + knock * (0.6 + 0.4 * hard)


def anvil(rng, hard=1.0):
    """a smith's hammer on the anvil: the bright ring and the hammer's own rebound tap"""
    f = rng.uniform(1100, 1250)
    return modal(1.2, f * np.array([1.0, 2.62, 3.71, 5.3]), [0.7, 0.5, 0.35, 0.2], [1.0, 0.6, 0.4, 0.25], rng) * hard


@recipe("hammer_far", variants=3, peak_db=-5.0, rms_db=-30.0, fade_out=0.3, max_len=2.0, tail_db=-58.0)
def hammer_far(v, rng):
    """someone at work far off, a barn wall throwing the blows back: v1 a mallet driving in a fence post, three blows; v2 a
    hammer nailing a board, light taps and two hard blows; v3 a smith at the anvil, a ring, a light tap and a ring"""
    m = Mix(2.0)
    if v == 0:
        f0 = rng.uniform(170, 210)
        for k, at in enumerate((0.0, 0.62, 1.22)):
            m.add(mallet(rng, f0 * (1 + 0.03 * k)), at, rng.uniform(0.85, 1.0))
        dist, echo = 60.0, 0.19
    elif v == 1:
        f = rng.uniform(2300, 2700)
        at = 0.0
        for k in range(4):
            m.add(nail(rng, f * (1 + 0.02 * k), 0.45), at, 0.55)
            at += rng.uniform(0.2, 0.26)
        for k in range(2):
            m.add(nail(rng, f * 1.1, 1.0), at, 1.0)
            at += rng.uniform(0.38, 0.45)
        dist, echo = 50.0, 0.15
    else:
        m.add(anvil(rng), 0.0, 1.0)
        m.add(anvil(rng, 0.35), 0.42, 0.5)
        m.add(anvil(rng), 0.62, 0.95)
        dist, echo = 70.0, 0.22
    y = barn_echo(m.out(), echo, 0.28)
    return afield(y, dist, seed=800 + v)


@recipe("child_far", variants=3, peak_db=-6.0, rms_db=-27.0, fade_out=0.3, max_len=2.0, tail_db=-58.0)
def child_far(v, rng):
    """a child calling out at play somewhere past the hedges, the words hardly made out: 'You're it!', 'Can't catch me!',
    'Again! Again!' (the townsfolk's own child voice, child_0, child_1 and child_3)"""
    name = ["child_0", "child_1", "child_3"][v]
    x = trim(read_audio(os.path.join(VOICES, "child", name + ".ogg"))[0])
    y = barn_echo(lp(hp(x, 250.0), 3000.0), [0.17, 0.2, 0.15][v], 0.22)
    return afield(y, [45.0, 55.0, 50.0][v], seed=810 + v)
