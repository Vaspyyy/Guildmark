"""The wind on the walls: wind over the high stone battlements, a gust passing over them, and a heavy banner
flapping hard on its pole.

The wind is noise shaped frame by frame in the frequency domain, so its loudness and brightness swell together;
under it, the low buffeting of the air at the ears, and now and then a faint whistle through a crenel or an arrow
slit (a narrow band of noise that slides with the wind speed, as an edge tone does). The banner is canvas snapping
taut in runs, a dull whump of air behind each crack, rustle in between and the halyard knocking the pole.
"""
import numpy as np
from scipy import signal

from ambkit import *  # noqa: F401,F403


# ---------------------------------------------------------------- air
def _grid(n, nper):
    hop = nper // 4
    return hop, np.fft.rfftfreq(nper, 1 / SR)[:, None], (np.arange(n // hop + 3) * hop / SR)[None, :]


def _istft(z, n, nper, hop):
    _, y = signal.istft(z, fs=SR, window="hann", nperseg=nper, noverlap=nper - hop, boundary=True)
    return fit(y, n)


def _cnoise(rng, shape):
    return rng.standard_normal(shape) + 1j * rng.standard_normal(shape)


def shaped_noise(n, rng, mag, nper=2048):
    """n samples of noise whose short-time spectrum follows mag(f, t): f a column of bin frequencies (Hz), t a row
    of frame times (s). Built frame by frame in the frequency domain and overlap-added, so it can change smoothly"""
    hop, f, t = _grid(n, nper)
    m = mag(f, t)
    return _istft(_cnoise(rng, m.shape) * m, n, nper, hop)


def shaped_pair(n, rng, mag_l, mag_r, nper=2048, spacing=0.2):
    """a left/right pair of shaped noises, alike in the long waves and independent in the short, as a diffuse wind
    reaches two ears spacing metres apart"""
    hop, f, t = _grid(n, nper)
    ml, mr = mag_l(f, t), mag_r(f, t)
    rho = np.exp(-(f / (70.0 / spacing)) ** 1.5)
    zc = _cnoise(rng, ml.shape)
    zl = np.sqrt(rho) * zc + np.sqrt(1 - rho) * _cnoise(rng, ml.shape)
    zr = np.sqrt(rho) * zc + np.sqrt(1 - rho) * _cnoise(rng, ml.shape)
    return np.stack([_istft(zl * ml, n, nper, hop), _istft(zr * mr, n, nper, hop)])


def rush_mag(f, level, centre, tilt):
    """the spectrum of wind rushing past stone: a broad hump at centre (Hz) on a slope falling tilt dB/oct above
    it, nothing much under 60 Hz or over 6 kHz; level scales it"""
    f = np.maximum(f, 1.0)
    o = np.log2(f / centre)
    hump = np.exp(-0.5 * (o / 1.4) ** 2)
    shelf = np.where(o > 0, 10 ** (o * tilt / 20.0), np.exp(-0.5 * (o / 1.8) ** 2))
    edge = 1 / np.sqrt(1 + (60.0 / f) ** 4) / np.sqrt(1 + (f / 6000.0) ** 8)
    return level * (0.55 * hump + 0.45 * shelf) * edge


def narrow(n, rng, freq, width):
    """a narrow band of noise about width Hz wide round freq (Hz, scalar or per sample): a wavering whistle"""
    a = ns(0.05)
    z = lp(white((n + a) / SR, rng), width / 2, order=2) + 1j * lp(white((n + a) / SR, rng), width / 2, order=2)
    z = z[a:a + n]
    z /= np.sqrt(np.mean(np.abs(z) ** 2)) + 1e-12
    return np.real(z * np.exp(1j * phase_from_freq(np.broadcast_to(freq, (n,)), n)))


def buffeting(dur, rng, strength):
    """the air buffeting at the ears: low, irregular thumps of pressure, stronger as the wind gets up"""
    n = ns(dur)
    low = hp(lp(brown(dur, rng), 120, order=4), 25)
    puffs = smooth_random(dur, rng, 2.5) ** 2.5 + 0.25 * smooth_random(dur, rng, 7)
    return nrm(low) * puffs * np.broadcast_to(strength, (n,))


def periodic(t, period, rng, kmin, kmax, slope=1.0):
    """a smooth random curve that repeats every period seconds: harmonics kmin..kmax of 1/period with random
    phases, weighted 1/k**slope. Zero mean and unit spread, whatever t covers"""
    k = np.arange(kmin, kmax + 1)
    a = k ** -slope
    phs = rng.uniform(0, 2 * np.pi, len(k))
    y = sum(ai * np.cos(2 * np.pi * ki * t / period + p) for ai, ki, p in zip(a, k, phs))
    return y / np.sqrt(np.sum(a ** 2) / 2)


def bumps(t, centres, heights, period, rise, fall):
    """a smooth bump at each centre (repeating every period seconds), rising over about rise seconds and falling
    over about fall"""
    y = np.zeros_like(t, dtype=float)
    for c, h in zip(centres, heights):
        d = (t - c + period / 2) % period - period / 2
        y += h * np.exp(-0.5 * (d / np.where(d < 0, rise, fall)) ** 2)
    return y


@recipe("wind_walls", variants=1, stereo=True, loop=24.0, xfade=3.0, rms_db=-27.0)
def wind_walls(v, rng):
    """wind over the high battlements: a broad rush swelling louder and brighter every few seconds, low buffeting at
    the ears, and now and then a faint whistle through a crenel; wide and diffuse"""
    LOOP, PRE = 24.0, 0.5
    total = PRE + LOOP + 3.0 + 0.2
    n = ns(total)
    gaps = np.array([6.6, 9.1, 8.3])
    centres = rng.uniform(0, 4) + np.concatenate([[0], np.cumsum(gaps[:-1])])
    heights = np.array([1.0, 0.75, 0.9])
    phase = [rng.uniform(0, 2 * np.pi, 35) for _ in range(2)]

    def gust(tt, c):
        """the wind's strength, 0..1, in channel c: the swells plus a little unsteadiness of its own"""
        g = 0.18 + 0.72 * bumps(tt + (0.3 if c else -0.3), centres, heights, LOOP, 2.0, 3.0)
        k = np.arange(6, 41)
        w = sum(ki ** -0.9 * np.cos(2 * np.pi * ki * tt / LOOP + p) for ki, p in zip(k, phase[c]))
        return np.clip(g + 0.07 * w / np.sqrt(np.sum(k ** -1.8) / 2), 0.0, 1.1)

    def mag(c):
        def m(f, t):
            g = gust(t - PRE, c)
            return rush_mag(f, (0.25 + 0.75 * g) ** 1.4, 260 * 3.0 ** g, -7.5 + 3.0 * g)
        return m

    st = shaped_pair(n, rng, mag(0), mag(1))
    st /= rms(st)
    tb = np.arange(n) / SR - PRE
    gb = 0.5 * (gust(tb, 0) + gust(tb, 1))
    for c in range(2):
        b = buffeting(total, rng, 0.25 + 0.75 * gust(tb, c))
        st[c] += 0.32 * b / rms(b) * rms(st[c])
    # whistles: three gaps in the stonework, each singing faintly once a loop when a swell lifts the wind
    for f0, az, at, width in ((640.0, -0.7, centres[0], 9.0), (1010.0, 0.45, centres[2], 12.0),
                              (1470.0, 0.8, centres[1], 15.0)):
        win = bumps(tb, [at + rng.uniform(-0.6, 0.6)], [1.0], LOOP, 1.4, 1.8)
        f = f0 * (1 + 0.07 * (gb - 0.6)) * (1 + 0.006 * (2 * smooth_random(total, rng, 1.5) - 1))
        w = narrow(n, rng, f, width) + 0.25 * narrow(n, rng, 2.01 * f, width * 1.5)
        w = w * win * np.clip(gb, 0, 1) ** 2
        st += pan(w, az) * 0.13 * rms(st)
    return st[:, ns(PRE):]


# ---------------------------------------------------------------- one-shots
@recipe("gust", variants=3, peak_db=-4.0, fade_out=0.3, max_len=[2.8, 3.4, 4.0])
def gust(v, rng):
    """a gust building and passing over the battlements: the rush swelling louder and brighter, a light whistle
    through a crenel at its height, then the wind dying away in a sigh"""
    dur = [2.6, 3.2, 3.8][v]
    n = ns(dur)
    tg = np.arange(n) / SR
    sc = bell(dur, [0.5, 0.55, 0.58][v], 1.5, 1.3) * (1 + 0.08 * (2 * smooth_random(dur, rng, 5) - 1))
    sc = np.clip(sc, 0, 1)
    s = lambda t: np.interp(t, tg, sc)
    y = shaped_noise(n, rng, lambda f, t: rush_mag(f, (0.1 + 0.9 * s(t)) ** 1.6, 220 * 3.5 ** s(t), -8 + 3.5 * s(t)))
    y /= rms(y)
    b = buffeting(dur, rng, sc ** 1.5)
    y += 0.25 * b / rms(b)
    # the whistle: an edge tone in a crenel, rising with the wind and there only near the top of the gust
    f0 = [820.0, 1100.0, 700.0][v]
    f = f0 * (0.85 + 0.3 * sc) * (1 + 0.005 * (2 * smooth_random(dur, rng, 2) - 1))
    w = narrow(n, rng, f, 10.0) + 0.3 * narrow(n, rng, 2.01 * f, 16.0)
    y += 0.2 * w * np.clip((sc - 0.55) / 0.45, 0, 1) ** 1.5
    # the sigh: as it dies, a soft breathy band sinking with it
    fall = np.clip((tg - 0.6 * dur) / (0.4 * dur), 0, 1)
    sigh = tvf(pink(dur, rng), "bp", 850 * (320 / 850) ** fall, 2.2) * np.sin(np.pi * fall) * sc ** 0.5
    y += 0.25 * sigh / (rms(sigh) + 1e-12) * rms(y) * 0.5
    return outdoors(y, wet=0.06, seed=440 + v)


def snap(rng, hard=1.0):
    """canvas snapping taut: the crack of the edge whipping over, a dull whump of air behind it, the panel ringing
    for a moment, and sometimes a second fold cracking just after"""
    L = 0.12
    t = tvec(L)
    crack = lp(hp(white(L, rng), 600), 9000) * np.exp(-t / rng.uniform(0.0012, 0.0035))
    whump = lp(white(L, rng), 450) * np.exp(-t / rng.uniform(0.012, 0.025)) * np.clip(t / 0.002, 0, 1)
    ex = fit(white(0.002, rng) * np.exp(-tvec(0.002) / 0.0004), len(t))
    panel = resonators(ex, rng.uniform(140, 420) * np.array([1, 1.6, 2.3, 3.1]), [0.04, 0.03, 0.02, 0.015],
                       [1, 0.6, 0.4, 0.25])
    m = Mix(L + 0.04)
    m.add(nrm(crack), 0.0, hard)
    m.add(nrm(whump), 0.0, 0.7)
    m.add(nrm(panel), 0.0, 0.25)
    if rng.random() < 0.35:
        m.add(nrm(lp(hp(white(L, rng), 600), 9000) * np.exp(-t / 0.002)), rng.uniform(0.008, 0.03), 0.5 * hard)
    return m.out()


def knock(rng):
    """the halyard slapping the wooden pole: a short hollow tock"""
    L = 0.25
    ex = fit(lp(white(0.003, rng), 5000) * np.exp(-tvec(0.003) / 0.0006), ns(L))
    tock = resonators(ex, np.array([380, 910, 1650, 2700]) * rng.uniform(0.9, 1.1), [0.07, 0.05, 0.03, 0.02],
                      [1, 0.7, 0.4, 0.2])
    slap = lp(white(L, rng), 1200) * np.exp(-tvec(L) / 0.006)
    return nrm(tock) + 0.4 * nrm(slap)


@recipe("flag", variants=3, peak_db=-5.0, fade_out=0.3, max_len=[1.5, 2.0, 2.5])
def flag(v, rng):
    """a heavy banner flapping hard on its pole in a strong wind: runs of cracks and snaps as the canvas whips taut,
    rustle in between, the wind behind it and the odd knock of the halyard on the pole"""
    dur = [1.4, 1.9, 2.4][v]
    n = ns(dur)
    t = tvec(dur)
    m = Mix(dur)
    at = rng.uniform(0.0, 0.03)
    while at < dur - 0.15:
        run, rate, k, tr = rng.uniform(0.35, 1.0), rng.uniform(6, 14), 0, 0.0
        while tr < run and at + tr < dur - 0.1:
            g = (0.45 + 0.55 * np.sin(np.pi * tr / run) ** 0.7) * (1.0 if k % 2 == 0 else rng.uniform(0.45, 0.8))
            m.add(snap(rng, hard=rng.uniform(0.7, 1.0)), at + tr, g * np.exp(0.2 * rng.standard_normal()))
            tr += rng.uniform(0.8, 1.25) / rate
            k += 1
        at += run + rng.uniform(0.08, 0.3)
    flap = 0.5 + 0.5 * np.sin(phase_from_freq(rng.uniform(7, 10) * (1 + 0.1 * (2 * smooth_random(dur, rng, 3) - 1)),
                                              n))
    edge = 1 - 0.3 * (0.5 + 0.5 * np.sin(phase_from_freq(rng.uniform(28, 36), n)))
    rustle = bp(white(dur, rng), 500, 6000) * (0.35 + 0.65 * flap ** 2) * edge
    grain = crackle(dur, rng, 600 + 900 * flap, decay=0.0006, f_lo=1500, f_hi=7000)
    air = shaped_noise(n, rng, lambda f, tt: rush_mag(f, 1.0, 600.0, -5.5))
    m.add(nrm(rustle), 0.0, 0.16)
    m.add(nrm(grain), 0.0, 0.08)
    m.add(nrm(air), 0.0, 0.12)
    for _ in range([1, 1, 2][v]):
        m.add(knock(rng), rng.uniform(0.15, dur - 0.35), rng.uniform(0.3, 0.45))
    y = fit(m.out(), n) * np.clip(t / 0.04, 0, 1)
    return outdoors(lp(y, 9000), wet=0.08, seed=450 + v)
