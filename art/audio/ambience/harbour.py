"""The harbour: water lapping at the quay wall and the jetty piles, a bigger wave slapping the stone, gulls over the
water, and moored boats and timbers creaking.

The water is built from bubbles (a bubble of radius R mm rings at about 3260/R Hz and its pitch rises as it nears
the surface), wet smacks of noise and the hollow knock of air pockets; the gulls are harmonic stacks on chevron
pitch contours through a nasal formant that opens and closes with the beak; the creaks are stick-slip pulse trains
ringing wooden modes.
"""
import numpy as np
from scipy import signal

from ambkit import *  # noqa: F401,F403


# ---------------------------------------------------------------- water
def bubble(r_mm, rng, rise=None):
    """one air bubble ringing in water (Minnaert): about 3260/R Hz for a radius of R mm, damped by viscosity, heat
    and radiation (van den Doel's model), its pitch rising as it nears the surface"""
    r = r_mm * 1e-3
    f0 = 3.26 / r
    d = 0.13 / r + 0.0072 * r ** -1.5
    if rise is None:
        rise = rng.uniform(0.04, 0.22)
    n = max(16, ns(min(0.3, 5.5 / d)))
    t = np.arange(n) / SR
    y = np.sin(phase_from_freq(f0 * (1 + rise * d * t), n)) * np.exp(-d * t)
    k = min(n, ns(0.0004) + 1)
    y[:k] *= np.linspace(0, 1, k)
    return y


def radii(rng, n, lo, hi, beta=1.8):
    """n bubble radii (mm) from a power law between lo and hi: many small bubbles, few big ones"""
    a = 1 - beta
    return (lo ** a + rng.random(n) * (hi ** a - lo ** a)) ** (1 / a)


def drip(rng):
    """a drop falling back into the water: a tiny tick and the bright rising plink of the bubble it pulls under"""
    y = bubble(rng.uniform(1.0, 2.8), rng, rise=rng.uniform(0.3, 0.7))
    tick = hp(white(0.004, rng), 1500) * np.exp(-tvec(0.004) / 0.0007)
    m = Mix(len(y) / SR + 0.005)
    m.add(tick, 0.0, 0.25)
    m.add(y, rng.uniform(0.001, 0.004), 1.0)
    return m.out()


def smack(rng, bright=1.0):
    """the wet smack of a small wave meeting stone or timber: the front hitting along the wall in a quick cluster of
    little impacts, full-bodied rather than clicky"""
    L = 0.1
    t = tvec(L)
    ex = np.zeros(len(t))
    for _ in range(int(rng.integers(3, 8))):
        i = ns(rng.exponential(0.008))
        if i < len(t) - 1:
            ex[i] += rng.uniform(0.3, 1.0)
    tau = rng.uniform(0.004, 0.01)
    k = white(0.04, rng) * np.exp(-tvec(0.04) / tau)
    y = signal.fftconvolve(ex, k)[: len(t)]
    y = bp(y, 160, 1500 + 1500 * bright)
    return eq(y, "peak", rng.uniform(380, 1100), 1.2, 8)


def cavity(rng, f=None, glide=None):
    """water closing over a pocket of air under an overhanging stone or a plank: a hollow, rising knock"""
    f = f or rng.uniform(170, 480)
    glide = rng.uniform(0.08, 0.35) if glide is None else glide
    tau = rng.uniform(0.012, 0.035) * (300.0 / f) ** 0.5
    L = min(0.4, tau * 7)
    t = tvec(L)
    fr = f * (1 + glide * (1 - np.exp(-t / 0.035)))
    y = np.sin(phase_from_freq(fr, len(t))) * np.exp(-t / tau)
    y += 0.2 * np.sin(phase_from_freq(fr * 2.32, len(t))) * np.exp(-t / (tau * 0.45))
    return y * np.clip(t / 0.0015, 0, 1)


def slosh(dur, rng, f_hi=1400.0):
    """water running back off the wall: a soft rush that swells and dies"""
    y = lp(bp(pink(dur, rng), 150, f_hi), f_hi)
    return y * bell(dur, 0.2, 1.4, 2.0)


def lap(rng, size=1.0):
    """one small wave meeting the quay wall or a pile: a wet smack, often a hollow knock, a few bubbles, the water
    running back with a drip or two"""
    m = Mix(0.8)
    m.add(nrm(smack(rng)), 0.0, 0.7 * size)
    if rng.random() < 0.55:
        m.add(nrm(cavity(rng)), rng.uniform(0.0, 0.012), rng.uniform(0.3, 0.8) * size)
    for r in radii(rng, rng.poisson(2.0 + 2.0 * size), 2.0, 14.0):
        m.add(bubble(r, rng), rng.exponential(0.05), rng.uniform(0.15, 0.5) * (r / 6.0) ** 0.5)
    for _ in range(rng.poisson(1.0)):
        m.add(drip(rng), 0.1 + rng.exponential(0.15), rng.uniform(0.05, 0.18))
    d = rng.uniform(0.2, 0.45)
    m.add(nrm(slosh(d, rng)), rng.uniform(0.01, 0.04), rng.uniform(0.08, 0.2) * size)
    return m.out()


def gurgle(rng, size=1.0):
    """air escaping from a gap in the stones in a few quick glugs"""
    k = int(rng.integers(3, 8))
    r0 = rng.uniform(3.5, 11.0)
    trend = rng.uniform(-0.35, 0.25)
    m = Mix(0.8)
    at = 0.0
    for i in range(k):
        r = r0 * (1 + trend * i / k) * np.exp(0.12 * rng.standard_normal())
        g = np.sin(np.pi * (i + 0.5) / k) ** 0.5 * rng.uniform(0.6, 1.0)
        m.add(bubble(r, rng, rise=rng.uniform(0.06, 0.3)), at, g * size)
        at += rng.uniform(0.028, 0.075)
    d = at + 0.05
    f = 3260.0 / r0
    m.add(nrm(bp(white(d, rng), 0.6 * f, 2.0 * f)) * bell(d, 0.4, 1.3, 1.5), 0.0, 0.1 * size)
    return m.out()


def suck(rng, size=1.0):
    """water draining back out of a crevice: a short slurp that climbs as the air rushes in, ending in a pop"""
    D = rng.uniform(0.14, 0.3)
    t = tvec(D)
    fa, fb = rng.uniform(250, 420), rng.uniform(650, 1200)
    fc = fa * (fb / fa) ** ((t / D) ** 1.3)
    env = bell(D, 0.65, 1.5, 1.2)
    m = Mix(D + 0.3)
    m.add(nrm(tvf(white(D, rng), "bp", fc, 3.5)) * env, 0.0, 0.45 * size)
    for tt in np.sort(rng.uniform(0, D, rng.poisson(D * 90))):
        i = min(ns(tt), len(fc) - 1)
        f = fc[i] * np.exp(0.15 * rng.standard_normal())
        m.add(bubble(3260.0 / f, rng, rise=rng.uniform(0.1, 0.4)), tt, 0.35 * env[i] * size)
    m.add(bubble(3260.0 / (fb * rng.uniform(0.8, 1.2)), rng, rise=0.3), D, 0.5 * size)
    return m.out()


def clunk(rng, size=1.0):
    """a swell slapping up under the jetty boards: the deep hollow knock of the air pocket trapped there, the
    boards answering"""
    L = 0.5
    t = tvec(L)
    f = rng.uniform(90, 150)
    tau = rng.uniform(0.035, 0.06)
    body = np.sin(phase_from_freq(f * (1 + 0.15 * (1 - np.exp(-t / 0.05))), len(t))) * np.exp(-t / tau)
    body *= np.clip(t / 0.003, 0, 1)
    knock = lp(white(L, rng), 900) * np.exp(-t / 0.012)
    ex = fit(lp(white(0.004, rng), 3000) * np.exp(-tvec(0.004) / 0.001), len(t))
    boards = resonators(ex, np.array([240, 410, 690, 1050]) * rng.uniform(0.85, 1.15), [0.08, 0.06, 0.04, 0.03],
                        [1, 0.7, 0.45, 0.3])
    m = Mix(L + 0.3)
    m.add(nrm(body), 0.0, 1.0 * size)
    m.add(nrm(knock), 0.0, 0.4 * size)
    m.add(nrm(boards), 0.0, 0.3 * size)
    for r in radii(rng, rng.poisson(2.0), 4.0, 14.0):
        m.add(bubble(r, rng), 0.03 + rng.exponential(0.06), rng.uniform(0.15, 0.4) * size)
    return m.out()


def drips(rng, size=1.0):
    """water dripping off the stone and the piles after a wave has gone back"""
    m = Mix(0.6)
    at = 0.0
    for _ in range(int(rng.integers(2, 6))):
        m.add(drip(rng), at, rng.uniform(0.4, 1.0) * size)
        at += rng.exponential(0.09) + 0.02
    return m.out()


def micro_bubbles(dur, rng, rate, lo=1.6, hi=6.0, gain=1.0):
    """a field of tiny bubbles popping all over the surface, too many and too small to tell apart"""
    out = np.zeros(ns(dur) + ns(0.1))
    for tt, r in zip(rng.uniform(0, dur, rng.poisson(rate * dur)), radii(rng, rng.poisson(rate * dur) + 1, lo, hi)):
        b = bubble(r, rng) * rng.uniform(0.2, 1.0) * gain
        i = ns(tt)
        out[i:i + len(b)] += b[: len(out) - i]
    return out[: ns(dur)]


def wash(dur, rng, level):
    """the soft continuous wash under it all: small ripples everywhere, too far and too many to tell apart"""
    nz = bp(pink(dur, rng), 200, 1600)
    nz = eq(nz, "peak", 600, 0.8, 4)
    chop = 0.55 + 0.45 * smooth_random(dur, rng, 1.6)
    fizz = micro_bubbles(dur, rng, 45)
    return (nrm(nz) + 0.35 * nrm(fizz)) * chop * level


def swell_curve(t, centres, period, rise=1.8, fall=2.6):
    """the slow swell: a smooth bump at each centre, wrapped so the curve repeats every period seconds (0..1)"""
    y = np.zeros_like(t, dtype=float)
    for c in centres:
        d = (t - c + period / 2) % period - period / 2
        y += np.exp(-0.5 * (d / np.where(d < 0, rise, fall)) ** 2)
    return np.clip(y, 0, 1)


def schedule(rng, t_from, t_to, density, rate, shape=2.0):
    """event times from t_from to t_to: gamma-distributed gaps (more even than chance) run on a clock that goes as
    rate * density(t), so the events come thicker where density is higher"""
    tt = np.linspace(t_from, t_to, int((t_to - t_from) * 100) + 2)
    lam = rate * density(tt)
    cum = np.concatenate([[0.0], np.cumsum((lam[1:] + lam[:-1]) / 2 * np.diff(tt))])
    pts = []
    c = rng.uniform(0, 1)
    while c < cum[-1]:
        pts.append(c)
        c += rng.gamma(shape, 1.0 / shape)
    return np.interp(pts, cum, tt)


def st_rms(x):
    return float(np.sqrt(np.mean(np.square(x)))) + 1e-12


@recipe("harbour_water", variants=1, stereo=True, loop=24.0, xfade=2.5, rms_db=-27.0)
def harbour_water(v, rng):
    """water lapping at the quay wall and round the jetty piles in a sheltered harbour: small slaps, gurgles and
    sucks in the gaps between the stones, spread wide, over a soft wash, all rising and falling with a slow swell"""
    LOOP, PRE = 24.0, 1.5
    total = PRE + LOOP + 2.5 + 0.2
    n = ns(total)
    tb = np.arange(n) / SR - PRE
    gaps = np.array([7.3, 8.9, 7.8])
    centres = rng.uniform(0, 3) + np.concatenate([[0], np.cumsum(gaps[:-1])])
    swell = lambda tt: swell_curve(tt, centres, LOOP)
    density = lambda tt: 0.6 + 0.4 * swell(tt)
    dry, send = StereoMix(total), StereoMix(total)
    kinds = [(lap, 4.5, 1.0), (gurgle, 1.0, 0.5), (suck, 0.4, 0.45), (clunk, 0.12, 0.3), (drips, 0.5, 0.3)]
    for fn, rate, gain in kinds:
        for te in schedule(rng, -PRE - 0.4, LOOP + 2.7, density, rate):
            at = te + PRE
            if at < 0:
                continue
            x = nrm(fn(rng, 1.0)) * (0.7 + 0.3 * swell(te)) * np.exp(0.2 * rng.standard_normal())
            dist = 3.0 + 17.0 * np.sqrt(rng.random())
            st = pan(lp(x, 1600 + 9000 * np.exp(-dist / 7.0)), rng.uniform(-0.95, 0.95))
            g = gain / (1 + dist / 4.0)
            dry.add(st, at, g)
            send.add(st, at, g * (0.3 + dist / 25.0))
    ev = dry.out(n)
    rv = stereo_reverb(send.out(n), wet=1.0, dry=0.0, rt60=1.1, predelay=0.014, hf_damp=0.55, seeds=(401, 402),
                       early_span=0.06)[:, :n]
    w = np.stack([wash(total, rng, 1.0) for _ in range(2)])
    out = ev / st_rms(ev) + 0.45 * rv / st_rms(rv) + 0.5 * w / st_rms(w)
    out = out * (0.55 + 0.45 * swell(tb))
    return out[:, ns(PRE):]


# ---------------------------------------------------------------- splash
@recipe("splash", variants=3, peak_db=-4.0, fade_out=0.25, max_len=2.0)
def splash(v, rng):
    """a bigger wave slapping hard into the quay wall: a hollow slap and thump, the sheet of water thrown up the
    stone, and the spray pattering back down in a fine hiss as the wave drains away"""
    dur = [1.7, 1.5, 1.9][v]
    m = Mix(dur)
    # the wave face hitting the stone and the pocket of air it traps against the wall
    m.add(nrm(smack(rng, bright=1.8)), 0.0, 1.0)
    clap = bp(white(0.03, rng), 900, 8000) * np.exp(-tvec(0.03) / 0.003)
    m.add(nrm(clap), 0.001, 0.35)
    m.add(nrm(thump(0.45, [105, 95, 120][v], [52, 48, 60][v], rng, decay=0.08, click=0.04, noise=0.6, nlp=300)),
          0.0, 0.75)
    m.add(nrm(cavity(rng, f=[160, 140, 190][v], glide=0.25)), 0.002, 0.7)
    # the sheet of water thrown up the wall: a rush that opens up and fades
    g = 0.6
    t = tvec(g)
    genv = (1 - np.exp(-t / 0.015)) * np.exp(-t / 0.13)
    gush = tvf(pink(g, rng), "bp", 600 * 4 ** (1 - np.exp(-t / 0.08)), 0.8) * genv
    m.add(nrm(gush), 0.004, 0.6)
    # the spray falling back: a patter of droplets over a fine hiss, thinning out
    sp = dur - 0.12
    ts = tvec(sp)
    senv = (1 - np.exp(-ts / 0.1)) * np.exp(-ts / [0.36, 0.3, 0.42][v])
    senv /= senv.max()
    rain = crackle(sp, rng, 200 + 4500 * senv, decay=0.0005, f_lo=1200, f_hi=8000, size_var=0.8) * senv
    hiss = lp(hp(white(sp, rng), 2000), 7000) * senv
    m.add(nrm(rain), 0.12, 0.42)
    m.add(nrm(hiss), 0.12, 0.1)
    for tt in np.sort(rng.uniform(0, sp, 70)):
        e = senv[min(ns(tt), len(senv) - 1)]
        if e > 0.15 and rng.random() < e:
            m.add(drip(rng), 0.12 + tt, rng.uniform(0.04, 0.12) * e)
    # the wave draining back off the wall
    m.add(nrm(slosh(dur - 0.3, rng, 1300)), 0.25, 0.22)
    for _ in range(2):
        m.add(nrm(gurgle(rng)), rng.uniform(0.35, 0.9), 0.16)
    y = fit(m.out(), ns(dur))
    return reverb(y, wet=0.16, rt60=1.2, predelay=0.02, hf_damp=0.55, seed=410 + v, early_span=0.06)


# ---------------------------------------------------------------- gulls
def gull_env(f, f1, f2, low):
    """the spectral envelope of a gull's voice: a thin fundamental, a strong nasal formant at f1, a second at f2,
    little above 6 kHz"""
    f = np.maximum(f, 1.0)
    e = 1 / np.sqrt(1 + (low / f) ** 4) / np.sqrt(1 + (f / 6500.0) ** 8)
    return e * (0.22 + np.exp(-((f - f1) / (0.3 * f1)) ** 2) + 0.55 * np.exp(-((f - f2) / (0.22 * f2)) ** 2))


def gull_note(dur, rng, fs, fp, fe, tp=0.25, rough=0.3, rasp=0.3, breath=0.06, onset="k", attack=0.005,
              release=0.03, beak=(1700.0, 2900.0), sag=0.3, fall_shape=1.6, follow=1.0, thin=0.85):
    """one syllable of a gull's call: a harmonic stack on a chevron of pitch (a fast rise from fs to fp, tp of the
    way through, then a fall to fe) through a nasal formant that opens with the beak and closes at the end, louder
    the higher it goes. rough is the subharmonic rattle of a loud call (strongest as it bursts out, then in patches),
    rasp the harsh smear of its upper harmonics, breath the air through it, thin how weak the fundamental is;
    onset 'k' is a hard click, 'h' a breathy start"""
    n = ns(dur)
    t = np.arange(n) / SR
    tpk = tp * dur
    u1 = np.clip(t / tpk, 0, 1)
    u2 = np.clip((t - tpk) / (dur - tpk), 0, 1)
    lf = np.where(t < tpk, np.log(fs) + (np.log(fp) - np.log(fs)) * (1 - (1 - u1) ** 2),
                  np.log(fp) + (np.log(fe) - np.log(fp)) * u2 ** fall_shape)
    wob = 0.008 * (2 * smooth_random(dur, rng, 30) - 1) + 0.006 * (2 * smooth_random(dur, rng, 6) - 1)
    f0 = np.exp(lf) * (1 + wob)
    # the harsh part: the phase shaken by fast noise, so the upper harmonics smear into bands of noise
    burst = np.exp(-t / 0.045)
    thr = rng.uniform(0.45, 0.95)
    patch = np.clip((smooth_random(dur, rng, 8) - thr) / (1 - thr), 0, 1)
    harsh = np.clip(burst + patch, 0, 1)
    jit = lp(white(dur, rng), 260, order=2)
    jit /= np.std(jit) + 1e-12
    ph = phase_from_freq(f0, n) + rng.uniform(0, 2 * np.pi)
    phj = ph + rasp * (0.2 + 0.8 * harsh) * jit
    op = np.clip(t / (0.8 * tpk + 0.01), 0, 1) ** 0.7 * (1 - 0.75 * u2 ** 1.5)
    f1 = beak[0] + (beak[1] - beak[0]) * op
    f2 = 1.4 * f1 + 200
    y = np.zeros(n)
    k = 1
    while k * np.min(f0) < 9000 and k * np.max(f0) < 20000:
        y += gull_env(k * f0, f1, f2, thin * fp) * k ** -0.3 * np.sin(k * phj + rng.uniform(0, 2 * np.pi))
        k += 1
    # period doubling: a rattle an octave down, in the burst and the patches only
    y *= 1 + rough * harsh * np.cos(ph / 2 + rng.uniform(0, 2 * np.pi))
    nz = tvf(white(dur, rng), "bp", f1, 2.5) * (0.5 + 0.5 * np.cos(ph)) ** 2
    y = nrm(y) + breath * (0.6 + 0.8 * harsh) * nrm(nz)
    a = np.sin(np.clip(t / attack, 0, 1) * np.pi / 2) ** 2
    s = (f0 / fp) ** follow * (1 - sag * u2 ** 2)
    rel = np.cos(np.clip((t - (dur - release)) / release, 0, 1) * np.pi / 2) ** 2
    sh = 1 + 0.1 * (2 * smooth_random(dur, rng, 25) - 1)
    y = y * a * s * rel * sh
    if onset == "k":
        kl = 0.006
        click = bp(white(kl, rng), 2200, 7000) * np.exp(-tvec(kl) / 0.0015)
        out = Mix(dur + kl)
        out.add(nrm(click), 0.0, 0.3)
        out.add(y, 0.004, 1.0)
        return out.out()
    if onset == "h":
        hl = 0.03
        h = tvf(white(hl, rng), "bp", np.full(ns(hl), beak[1]), 1.5) * bell(hl, 0.7, 1.5, 1.0)
        out = Mix(dur + hl)
        out.add(nrm(h), 0.0, 0.2)
        out.add(y, hl * 0.6, 1.0)
        return out.out()
    return y


def gull_air(x, wet=0.1, seed=420):
    """a gull up over the water: open air, one slap back off the harbour front"""
    return outdoors(lp(x, 9500), wet=wet, seed=seed)


def gull_far(x, dist, seed):
    """a gull further off over the water: the air takes its top end, the harbour front gives back more of it"""
    return far(lp(x, 1.5e5 / dist, order=2), dist, seed=seed)


def calls(notes, dur):
    """mix (at, gain, note) triples into one buffer dur long"""
    m = Mix(dur)
    for at, g, x in notes:
        m.add(nrm(x), at, g)
    return fit(m.out(), ns(dur))


@recipe("gull", variants=6, peak_db=-4.0, fade_out=0.15, max_len=[1.8, 2.9, 2.2, 1.9, 2.8, 3.0])
def gull(v, rng):
    """gulls over the harbour: a long falling kyaaow; a laughing ha-ha-ha-haaah; two birds bickering kek-kek; a
    mewing call further off; two calls answering each other; a long call series"""
    j = lambda x, s=0.03: x * np.exp(s * rng.standard_normal())
    if v == 0:
        n = gull_note(0.95, rng, j(880), j(1240), j(640), tp=0.12, rough=0.35, breath=0.07, sag=0.45,
                      fall_shape=1.8, release=0.05)
        return gull_air(calls([(0.0, 1.0, n)], 1.8), seed=421)
    if v == 1:
        notes, at = [], 0.0
        for i, gap in enumerate([0.36, 0.3, 0.25, 0.21, 0.18, 0.16]):
            fp = j(1060 + 25 * i, 0.02)
            notes.append((at, 0.75 + 0.04 * i, gull_note(j(0.12, 0.05), rng, 0.76 * fp, fp, 0.8 * fp, tp=0.35,
                                                         rough=0.25, onset="h", attack=0.008)))
            at += gap
        notes.append((at + 0.02, 1.0, gull_note(0.5, rng, j(880), j(1160), j(640), tp=0.18, rough=0.35, onset="h",
                                                 sag=0.5, fall_shape=1.5, release=0.05)))
        return gull_air(calls(notes, 2.9), seed=422)
    if v == 2:
        a = [0.0, 0.11, 0.22, 0.52, 0.62]
        b = [0.30, 0.40, 0.70, 0.79, 0.88, 0.97, 1.32, 1.42]
        notes = []
        for at in a:
            fp = j(1520, 0.025)
            notes.append((at, j(0.9, 0.1), gull_note(j(0.07, 0.08), rng, 0.82 * fp, fp, 0.78 * fp, tp=0.3, rough=0.4,
                                                      attack=0.003, sag=0.3)))
        notes.append((1.06, 1.0, gull_note(0.22, rng, j(1150), j(1500), j(1050), tp=0.25, rough=0.4, sag=0.4)))
        near = calls(notes, 2.2)
        notes = []
        for at in b:
            fp = j(1780, 0.025)
            notes.append((at, j(0.9, 0.1), gull_note(j(0.065, 0.08), rng, 0.82 * fp, fp, 0.8 * fp, tp=0.3, rough=0.35,
                                                      attack=0.003, sag=0.3, beak=(1900, 3200))))
        other = gull_far(calls(notes, 2.2), 18, seed=423)[: ns(2.2)]
        return gull_air(near + 0.6 * nrm(other) * np.max(np.abs(near)), seed=424)
    if v == 3:
        n = gull_note(0.85, rng, j(1000), j(1320), j(900), tp=0.42, rough=0.06, rasp=0.12, breath=0.05, onset="",
                      attack=0.03, beak=(1250, 2100), fall_shape=1.3, release=0.08, thin=0.5)
        return gull_far(calls([(0.0, 1.0, n)], 1.9), 45, seed=425)
    if v == 4:
        a = [(0.0, 1.0, gull_note(0.42, rng, j(900), j(1250), j(760), tp=0.2, rough=0.35)),
             (0.5, 0.85, gull_note(0.36, rng, j(900), j(1220), j(740), tp=0.2, rough=0.3))]
        b = [(1.15, 1.0, gull_note(0.5, rng, j(1000), j(1380), j(820), tp=0.18, rough=0.3, beak=(1800, 3100))),
             (1.72, 0.8, gull_note(0.38, rng, j(1000), j(1340), j(800), tp=0.2, rough=0.25, beak=(1800, 3100)))]
        near = calls(a, 2.8)
        other = gull_far(calls(b, 2.8), 40, seed=426)[: ns(2.8)]
        return gull_air(near + 0.55 * nrm(other) * np.max(np.abs(near)), seed=427)
    notes = [(0.0, 0.4, gull_note(0.12, rng, 600, 720, 560, tp=0.4, rough=0.15, onset="h")),
             (0.2, 0.45, gull_note(0.12, rng, 610, 740, 570, tp=0.4, rough=0.15, onset="h")),
             (0.42, 1.0, gull_note(0.55, rng, j(850), j(1260), j(980), tp=0.1, rough=0.45, fall_shape=1.3, sag=0.3))]
    at, d = 1.05, 0.18
    for i in range(7):
        fp = j(1220 - 30 * i, 0.015)
        notes.append((at, 0.95 - 0.06 * i, gull_note(d, rng, 0.74 * fp, fp, 0.66 * fp, tp=0.22, rough=0.35)))
        at += 0.24 - 0.004 * i
        d -= 0.006
    return gull_air(calls(notes, 3.0), seed=428)


# ---------------------------------------------------------------- creaks
def slip_train(dur, rng, rate, amp, jitter=0.06):
    """stick-slip friction: the surfaces stick, the load builds, they slip with a tick. rate (Hz, per sample) is how
    often; each tick is bigger the longer it stuck, and slow ticks wander more than a fast creak. Returns the train
    of ticks"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    amp = np.broadcast_to(np.asarray(amp, dtype=float), (n,))
    cyc = np.cumsum(rate) / SR
    idx = np.nonzero(np.diff(np.floor(cyc)) > 0)[0] + 1
    out = np.zeros(n)
    prev = None
    for i in idx:
        per = SR / max(rate[i], 1.0)
        jit = jitter * min(2.0, np.sqrt(40.0 / max(rate[i], 1.0)))
        j = int(i + jit * per * rng.standard_normal())
        if not 0 <= j < n:
            continue
        gap = 1.0 if prev is None else max(0.2, (j - prev) / per)
        out[j] += amp[i] * np.sqrt(gap) * np.exp(0.25 * rng.standard_normal())
        prev = j
    return out


def wood_modes(rng, f_lo, f_hi, count=28, t60_lo=0.08, t60_hi=0.02, peaks=()):
    """the resonances of a timber: many modes between f_lo and f_hi (a big irregular body has dense modes), the
    low ones ringing longest; peaks are the frequencies its tone gathers round"""
    f = np.sort(np.exp(rng.uniform(np.log(f_lo), np.log(f_hi), count)))
    u = np.log(f / f_lo) / np.log(f_hi / f_lo)
    t60 = t60_lo * (t60_hi / t60_lo) ** u * rng.uniform(0.7, 1.3, count)
    g = rng.uniform(0.3, 1.0, count) * (1 - 0.6 * u)
    for p in peaks:
        g = g * (1 + 2.0 * np.exp(-(np.log(f / p) / 0.15) ** 2))
    return f, t60, g


def creak_voice(dur, rng, rate, amp, modes, jitter=0.06, grit=0.03):
    """a creak: stick-slip ticks ringing the timber's modes, with a little rubbing hiss between them"""
    n = ns(dur)
    ticks = slip_train(dur, rng, rate, amp, jitter)
    kern = white(0.0006, rng) * np.exp(-tvec(0.0006) / 0.00015)
    ex = signal.fftconvolve(ticks, kern)[:n]
    y = resonators(ex, *modes)
    rub = bp(white(dur, rng), 2000, 6000) * np.broadcast_to(amp, (n,)) * np.sqrt(np.broadcast_to(rate, (n,)) / 100.0)
    return nrm(y) + grit * nrm(rub)


@recipe("creak", variants=3, peak_db=-6.0, fade_out=0.12, max_len=3.0)
def creak(v, rng):
    """moored boats and jetty timbers working: a slow stretched creak of a timber under load, a boat's hull rolling
    against a pile and back, and a mooring rope groaning round a bollard"""
    if v == 0:
        dur = 2.3
        rate = env_points([(0, 9), (0.25, 22), (0.6, 70), (1.0, 115), (1.35, 125), (1.7, 80), (1.95, 35), (2.2, 12)],
                          dur, 1.0)
        rate *= 1 + 0.06 * (2 * smooth_random(dur, rng, 4) - 1)
        amp = env_points([(0, 0.25), (0.5, 1.0), (1.6, 0.9), (2.15, 0.15), (2.3, 0.0)], dur)
        y = creak_voice(dur, rng, rate, amp, wood_modes(rng, 200, 3200, peaks=(480, 1100)), jitter=0.07)
    elif v == 1:
        dur = 2.9
        m = Mix(dur)
        for at, d, pts in [(0.0, 0.9, [(0, 15), (0.4, 90), (0.9, 40)]),
                           (1.2, 1.6, [(0, 12), (0.4, 60), (0.9, 140), (1.3, 60), (1.6, 15)])]:
            rate = env_points(pts, d) * (1 + 0.07 * (2 * smooth_random(d, rng, 5) - 1))
            amp = bell(d, 0.5, 1.2, 1.4) * 0.8 + 0.2 * np.sin(np.pi * np.clip(tvec(d) / d, 0, 1)) ** 0.3
            modes = wood_modes(rng, 110, 2400, count=30, t60_lo=0.12, peaks=(180, 620))
            m.add(creak_voice(d, rng, rate, amp, modes, jitter=0.06), at, 0.7 if at == 0 else 1.0)
        y = fit(m.out(), ns(dur))
    else:
        dur = 2.4
        rate = env_points([(0, 35), (0.5, 60), (1.2, 95), (1.8, 80), (2.4, 50)], dur)
        rate *= 1 + 0.12 * (2 * smooth_random(dur, rng, 6) - 1)
        amp = bell(dur, 0.5, 1.2, 1.4) * 0.85 + 0.15 * np.sin(np.pi * np.clip(tvec(dur) / dur, 0, 1)) ** 0.3
        modes = wood_modes(rng, 90, 1800, count=22, t60_lo=0.07, peaks=(150, 420))
        y = creak_voice(dur, rng, rate, amp, modes, jitter=0.12, grit=0.03)
        fibre = crackle(dur, rng, 300 + 400 * amp, decay=0.0008, f_lo=1200, f_hi=5000) * amp
        y = nrm(y) + 0.12 * nrm(fibre)
    return outdoors(lp(y, 6000), wet=0.1, seed=430 + v)
