"""The city's voices: market criers calling their wares across the square, the night watchman calling the
hours down a stone street, and the crowds (a busy market square, a quieter street), all made from the Kokoro
takes that make_city_voices.py tts writes (make_ambience.py --raw <dir>).

A crier is a reading turned into a call: the stressed vowel at the end of each phrase is drawn out (a
waveform-similarity overlap-add stretch, which keeps the pitch), a breath goes in between the two calls, the
voice goes up a semitone or two, then it gets a presence lift, compression and a little saturation, the
square's walls and a dozen blocks of air. The watchman is the same idea, slower, with a long street echo at
night and a small brass handbell rung twice before he calls.

The crowds are walla: the takes are cut into phrases at their pauses and people take turns with them, each
person a Kokoro voice shifted by up to two and a half semitones (the market has 36 people in 25 voices),
placed at a distance and a bearing. People talk in pairs for ten seconds or so and then make way for others,
always the same number going at once, so the bed is as dense at its seam as anywhere. Nobody close by is
talking across the loop point, and nobody still talking when the loop ends is also talking when it starts
again, so the crossfade never lays a voice over itself.
"""
from ambkit import *  # noqa: F401,F403

from fractions import Fraction

import numpy as np
from scipy import signal
from scipy.ndimage import uniform_filter1d

import make_city_voices as lines


# ---------------------------------------------------------------- time and pitch
def wsola(x, stretch, win=0.03, tol=0.01):
    """time-stretch x keeping its pitch (waveform-similarity overlap-add): each output frame is the input
    under it, nudged by up to tol seconds to where it best continues the frame before, so the pitch periods
    line up. stretch is output length / input length, a scalar or one value per input sample, so one vowel
    can be drawn out and the rest of the line left alone."""
    x = np.asarray(x, dtype=np.float64)
    n = len(x)
    w_len = 2 * (ns(win) // 2)
    hop = w_len // 2
    tol_n = ns(tol)
    s = np.broadcast_to(np.asarray(stretch, dtype=np.float64), (n,))
    t_out = np.concatenate([[0.0], np.cumsum(s)])
    n_out = int(t_out[-1])
    pad = w_len + tol_n
    xp = np.concatenate([np.zeros(pad), x, np.zeros(pad + w_len)])
    energy = np.concatenate([[0.0], np.cumsum(xp * xp)])
    win_fn = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(w_len) / w_len)  # periodic Hann: sums to 1 at 50% overlap
    frames = n_out // hop + 2
    centres = np.interp(np.arange(frames) * hop, t_out, np.arange(n + 1))
    y = np.zeros((frames + 2) * hop + w_len)
    prev = None
    for k in range(frames):
        a = int(round(centres[k])) - hop + pad
        if prev is None:
            best = a
        else:
            ref = xp[prev + hop: prev + hop + w_len]
            cc = np.correlate(xp[a - tol_n: a + tol_n + w_len], ref, mode="valid")
            starts = np.arange(a - tol_n, a + tol_n + 1)
            e = energy[starts + w_len] - energy[starts]
            best = a - tol_n + int(np.argmax(cc / np.sqrt(e + 1e-9)))
        y[k * hop: k * hop + w_len] += xp[best: best + w_len] * win_fn
        prev = best
    return y[hop: hop + n_out]


def ratio(semitones, max_den=48):
    """a pitch ratio as a small fraction, so the resampling is exact"""
    return Fraction(2 ** (semitones / 12.0)).limit_denominator(max_den)


def resample_by(x, r):
    """play x faster by the fraction r (higher and shorter)"""
    return signal.resample_poly(x, r.denominator, r.numerator)


def repitch(x, semitones, stretch=1.0):
    """shift the pitch by semitones and keep the timing (times any stretch): stretch by the ratio, then
    play it back that much faster"""
    r = ratio(semitones)
    return resample_by(wsola(x, np.asarray(stretch) * float(r)), r)


# ---------------------------------------------------------------- reading a take
def trim(x, floor_db=-50.0, pre=0.02, post=0.08):
    """cut the silence off both ends of a take, keeping a breath of it"""
    a = np.abs(x)
    on = np.nonzero(a > a.max() * db(floor_db))[0]
    if not len(on):
        return x
    return x[max(0, on[0] - ns(pre)): on[-1] + ns(post)]


def envelope_db(x, win=0.02):
    """the take's level in dB re its loudest moment, per sample"""
    e = np.sqrt(np.maximum(uniform_filter1d(x * x, size=ns(win)), 1e-20))
    return to_db(e / (e.max() + 1e-12))


def phrase_spans(x, gap=0.11, floor_db=-36.0, min_len=0.12):
    """(start, end) samples of each phrase in a take: speech between pauses at least gap seconds long"""
    on = envelope_db(x) > floor_db
    edges = np.diff(np.concatenate([[0], on.astype(np.int8), [0]]))
    starts, ends = np.nonzero(edges == 1)[0], np.nonzero(edges == -1)[0]
    spans = []
    for a, b in zip(starts, ends):
        if spans and a - spans[-1][1] < ns(gap):
            spans[-1] = (spans[-1][0], b)
        else:
            spans.append((a, b))
    return [(a, b) for a, b in spans if b - a >= ns(min_len)]


def sentence_break(x, floor_db=-28.0, deep_db=-50.0, before=0.3, after=0.4):
    """where a crier's line divides between its two calls: Kokoro leaves only a short gap after a "!", so
    take the longest stretch of near silence that is at least deep_db down and has a call's worth of speech
    on either side (a stop consonant late in the last word must not count). None if there is no clear one."""
    env = envelope_db(x, win=0.015)
    low = env < floor_db
    low[: ns(before)] = False
    low[len(x) - ns(after):] = False
    edges = np.diff(np.concatenate([[0], low.astype(np.int8), [0]]))
    best = None
    for a, b in zip(np.nonzero(edges == 1)[0], np.nonzero(edges == -1)[0]):
        if env[a:b].min() < deep_db and (best is None or b - a > best[1] - best[0]):
            best = (a, b)
    return None if best is None else best[0] + int(np.argmin(env[best[0]:best[1]]))


def voicing(x, hop=0.005, win=0.04):
    """(frame centres in samples, voicing 0..1): the normalised autocorrelation at the best pitch lag
    between 55 and 400 Hz, on the band under 1 kHz"""
    d = 4
    sr = SR / d
    xl = lp(x, 1000.0, order=4)[::d]
    n_win, n_hop = int(win * sr), int(hop * sr)
    nf = 1 + max(0, (len(xl) - n_win) // n_hop)
    idx = np.arange(n_win)[None, :] + n_hop * np.arange(nf)[:, None]
    idx = np.minimum(idx, len(xl) - 1)
    w = np.hanning(n_win)
    nfft = 1 << int(np.ceil(np.log2(2 * n_win)))
    ac = np.fft.irfft(np.abs(np.fft.rfft(xl[idx] * w, nfft, axis=1)) ** 2, nfft, axis=1)[:, :n_win]
    wac = np.fft.irfft(np.abs(np.fft.rfft(w, nfft)) ** 2, nfft)[:n_win]
    acn = ac / (ac[:, :1] + 1e-12) / np.maximum(wac / wac[0], 0.25)
    lo, hi = int(sr / 400), int(sr / 55)
    centres = ((np.arange(nf) * n_hop + n_win / 2) * d).astype(int)
    return np.minimum(centres, len(x) - 1), np.clip(acn[:, lo:hi].max(axis=1), 0, 1)


def final_vowel(x, look=0.5, onset_db=6.0, coda_db=20.0, shave=0.01):
    """(start, end) samples of the end of a phrase to draw out: the loudest voiced moment of its last look
    seconds (its stressed vowel), from where that vowel rises within onset_db of its peak to where the
    voicing (the vowel and any voiced consonants after it) stops or falls coda_db below it"""
    env = envelope_db(x)
    centres, voiced = voicing(x)
    v = voiced > 0.5
    lev = env[centres]
    k = np.nonzero((centres >= len(x) - ns(look)) & v & (lev > -15.0))[0]
    if not len(k):
        return None
    pk = k[np.argmax(lev[k])]
    lo = hi = pk
    while lo > 0 and v[lo - 1] and lev[lo - 1] > lev[pk] - onset_db:
        lo -= 1
    while hi + 1 < len(centres) and v[hi + 1] and lev[hi + 1] > lev[pk] - coda_db:
        hi += 1
    s, e = centres[lo] + ns(shave), centres[hi] - ns(shave)
    return (s, e) if e - s >= ns(0.05) else None


def ramp_window(n, a, b, ramp=0.015):
    """0 outside a..b, 1 inside, with raised-cosine shoulders ramp seconds wide"""
    w = np.zeros(n)
    r = ns(ramp)
    w[a:b] = 1.0
    k = 0.5 - 0.5 * np.cos(np.pi * np.arange(r) / r)
    lo, hi = max(0, a - r), min(n, b + r)
    w[lo:a] = k[r - (a - lo):]
    w[b:hi] = k[::-1][: hi - b]
    return w


def as_call(parts, semitones, draws, breaths):
    """join the phrases of a line into a call: each one's final vowel drawn out by its draw, all of it
    raised by semitones, a breath of silence between them"""
    out = []
    for i, (x, f) in enumerate(zip(parts, draws)):
        s = np.ones(len(x))
        v = final_vowel(x)
        if v:
            s += (f - 1.0) * ramp_window(len(x), v[0], v[1])
        y = fade(repitch(x, semitones, stretch=s), 0.004, 0.01)
        out.append(y)
        if i < len(parts) - 1:
            out.append(np.zeros(ns(breaths[i])))
    return np.concatenate(out)


# ---------------------------------------------------------------- criers and the watch
def projected(y, presence=4.0, low_cut=110.0, grit=0.3):
    """a voice pushed out to carry: less chest, a presence lift around 2.5 kHz, compressed and lightly
    saturated"""
    y = hp(y, low_cut, order=2)
    y = eq(y, "peak", 320, 1.0, -2.0)
    y = eq(y, "peak", 2500, 0.8, presence)
    y = eq(y, "highshelf", 7500, 0.7, -1.5)
    y = compress(nrm(y), thresh_db=-20, ratio=3.0, attack=0.004, release=0.12)
    y = nrm(y)
    return (1 - grit) * y + grit * drive(y, 2.2, asym=0.03)


def handbell(rng, strikes, prime=1180.0):
    """a small brass handbell swung so the clapper strikes once for each time in strikes: the church-bell
    partials of a little bell, each a slow doublet, ringing on under the call"""
    ratios = [0.5, 1.0, 1.183, 1.506, 2.0, 2.514, 2.662, 3.011, 4.166, 5.433]
    levels = [0.10, 0.65, 0.45, 0.18, 1.0, 0.28, 0.22, 0.2, 0.1, 0.05]
    t60s = [2.4, 2.6, 2.1, 1.5, 1.9, 1.1, 1.0, 0.8, 0.5, 0.35]
    out = Mix(strikes[-1] + 3.0)
    for i, at in enumerate(strikes):
        freqs, t60, amp = [], [], []
        for r, a, t in zip(ratios, levels, t60s):
            beat = rng.uniform(0.6, 1.8)
            freqs += [prime * r - beat / 2, prime * r + beat / 2]
            t60 += [t, t * 0.9]
            amp += [a * 0.55, a * 0.45]
        ring = modal(3.0, freqs, t60, amp, rng, attack=0.0008)
        click = bp(white(0.012, rng), 2500, 8000) * np.exp(-np.arange(ns(0.012)) / ns(0.0012))
        g = 1.0 if i == 0 else rng.uniform(0.7, 0.85)
        out.add(g * nrm(ring), at=at)
        out.add(0.12 * g * nrm(click), at=at)
    return out.out()


HAWKER_STEMS = [h[0] for h in lines.HAWKERS]

# Criers whose pitch lift is fixed rather than drawn: the guild's call is the one that matters, and at less
# than two semitones up "guild" can blur towards "girl" a dozen blocks off
LIFT = {"hawker_09_guild": 2.0}


@recipe("hawker", variants=12, peak_db=-4.0)
def hawker(v, rng):
    """A market crier calling their wares across a busy square, heard from about a dozen blocks away: one of
    twelve criers, each with their own line and voice (fresh bread, apples, fish, hot pies, candles and lamp
    oil, honey cakes, cloth, knife sharpening, the guild's call for brave hands, flowers, potions, chestnuts)."""
    x = 0.7 * nrm(trim(raw_take(HAWKER_STEMS[v])))
    cut = sentence_break(x)
    parts = [x] if cut is None else [x[:cut], x[cut:]]
    draws = [rng.uniform(1.3, 1.45)] * (len(parts) - 1) + [rng.uniform(1.5, 1.7)]
    lift = LIFT.get(HAWKER_STEMS[v], rng.uniform(1.0, 2.0))
    y = as_call(parts, lift, draws, [rng.uniform(0.12, 0.2)])
    y = projected(y)
    y = square(y, wet=0.14, seed=320 + v)
    return far(y, rng.uniform(10.0, 15.0), seed=340 + v)


def watch_call(stem, rng):
    """the watchman's call: his handbell twice, then the hour and "all's well", slow and drawn out, down a
    stone street at night from about 25 blocks"""
    x = 0.7 * nrm(trim(raw_take(stem)))
    parts = [x[max(0, a - ns(0.02)): b + ns(0.05)] for a, b in phrase_spans(x, gap=0.25)] or [x]
    draws = [rng.uniform(1.3, 1.45)] * (len(parts) - 1) + [rng.uniform(1.8, 2.0)]
    call = projected(as_call(parts, 0.5, draws, [rng.uniform(0.3, 0.4)] * len(parts)),
                     presence=3.0, low_cut=90.0, grit=0.2)
    gap = rng.uniform(0.4, 0.46)
    bell = handbell(rng, [0.0, gap])
    mix = Mix().add(db(-9.0) * nrm(bell), at=0.0).add(nrm(call), at=gap + rng.uniform(0.75, 0.9))
    y = street(mix.out(), wet=0.3, rt60=2.4, seed=360)
    y = lp(y, 4800, order=2)
    return far(y, rng.uniform(24.0, 27.0), seed=361)


@recipe("watch_ten", peak_db=-4.0)
def watch_ten(v, rng):
    """The night watchman calling "Ten o'clock, and all's well!" down a stone street, his handbell first."""
    return watch_call("watch_ten", rng)


@recipe("watch_eleven", peak_db=-4.0)
def watch_eleven(v, rng):
    """The night watchman calling "Eleven o'clock, and all's well!" down a stone street, his handbell first."""
    return watch_call("watch_eleven", rng)


@recipe("watch_midnight", peak_db=-4.0)
def watch_midnight(v, rng):
    """The night watchman calling "Midnight, and all's well!" down a stone street, his handbell first."""
    return watch_call("watch_midnight", rng)


@recipe("watch_one", peak_db=-4.0)
def watch_one(v, rng):
    """The night watchman calling "One o'clock, and all's well!" down a stone street, his handbell first."""
    return watch_call("watch_one", rng)


@recipe("watch_two", peak_db=-4.0)
def watch_two(v, rng):
    """The night watchman calling "Two o'clock, and all's well!" down a stone street, his handbell first."""
    return watch_call("watch_two", rng)


@recipe("watch_three", peak_db=-4.0)
def watch_three(v, rng):
    """The night watchman calling "Three o'clock, and all's well!" down a stone street, his handbell first."""
    return watch_call("watch_three", rng)


@recipe("watch_four", peak_db=-4.0)
def watch_four(v, rng):
    """The night watchman calling "Four o'clock, and all's well!" down a stone street, his handbell first."""
    return watch_call("watch_four", rng)


@recipe("watch_five", peak_db=-4.0)
def watch_five(v, rng):
    """The night watchman calling "Five o'clock, and dawn is near!" down a stone street, his handbell first."""
    return watch_call("watch_five", rng)


# ---------------------------------------------------------------- crowds: people and their phrases
def phrases_of(stems):
    """{voice: [phrase, ...]} cut from the takes at their pauses, each levelled to the same speech loudness"""
    out = {}
    for stem, voice in stems:
        x = trim(raw_take(stem))
        for a, b in phrase_spans(x, gap=0.12, min_len=0.3):
            p = fade(x[max(0, a - ns(0.01)): b + ns(0.06)], 0.005, 0.04)
            speech = np.abs(p) > 0.05 * np.max(np.abs(p))
            out.setdefault(voice, []).append(p * db(-20.0) / (np.sqrt(np.mean(p[speech] ** 2)) + 1e-12))
    return out


class Person:
    """one talker in a crowd: a voice's phrases at a pitch of their own, said in order from a starting
    phrase, at a bearing and a distance (or along a path)"""

    def __init__(self, voice, phrases, semitones, first=0):
        self.voice, self.src, self.r, self.i = voice, phrases, ratio(semitones, 32), first
        self.cache, self.said = {}, []
        self.az, self.dist, self.path = 0.0, 10.0, None

    def peek(self):
        k = self.i % len(self.src)
        if k not in self.cache:
            self.cache[k] = resample_by(self.src[k], self.r)
        return self.cache[k]

    def say(self, t, gain):
        self.said.append((t, gain * self.peek()))
        self.i += 1


def converse(rng, people, t0, t1):
    """people taking turns from t0 to t1: a phrase or two each turn, short gaps, now and then a little
    overlap where someone starts before the other has quite finished"""
    t = t0 + rng.uniform(0.0, 0.4)
    who = int(rng.integers(len(people)))
    while True:
        p = people[who]
        for _ in range(1 if rng.random() < 0.65 else 2):
            if t + len(p.peek()) / SR > t1:
                return
            p.say(t, db(rng.uniform(-2.0, 2.0)))
            t += len(p.said[-1][1]) / SR + rng.uniform(0.1, 0.3)
        t += rng.uniform(-0.3, 0.4)
        if len(people) > 1 and rng.random() < 0.85:
            who = (who + int(rng.integers(1, len(people)))) % len(people)


def voice_band(x, dist):
    """what a talker's voice keeps at dist blocks in a crowd: the chest goes, and the bodies and stalls in
    between take more of the top than the air alone would, so the words are only half heard"""
    return lp(hp(x, 130.0, order=2), 7500.0 * np.exp(-dist / 35.0), order=2)


def place_still(mix, p, t0, seed):
    """add a person standing at one spot to the mix (t0: the mix's start on the timeline)"""
    if not p.said:
        return
    start = p.said[0][0]
    track = Mix()
    for t, x in p.said:
        track.add(x, at=t - start)
    y = far(voice_band(track.out(), p.dist), p.dist, seed=seed)
    mix.add(pan(y, p.az), at=start - t0, gain=(10.0 / p.dist) ** 0.9)


def place_moving(mix, p, t0, seed):
    """add a person walking along p.path (time -> (azimuth, distance)) to the mix, phrase by phrase"""
    for t, x in p.said:
        _, d_mid = p.path(t + 0.5 * len(x) / SR)
        d_mid = float(np.round(d_mid))
        y = far(voice_band(x, d_mid), d_mid, seed=seed)
        az, d = p.path(t + np.arange(len(y)) / SR)
        a = (np.clip(az, -1, 1) + 1) * np.pi / 4
        g = (10.0 / np.maximum(d, 3.0)) ** 0.9
        mix.add(np.stack([np.cos(a) * g * y, np.sin(a) * g * y]), at=t - t0)


def walking(dmin, t_closest, speed, direction):
    """a path straight past the listener: closest (dmin blocks) at t_closest, walking at speed blocks a
    second, left to right for direction 1"""
    def path(t):
        x = direction * speed * (np.asarray(t, dtype=np.float64) - t_closest)
        return 0.95 * (2 / np.pi) * np.arctan2(x, dmin), np.hypot(x, dmin)
    return path


def beside(path, extra):
    """the same path, walked a step further from the listener (the second of two people side by side)"""
    def side(t):
        az, d = path(t)
        return az, d + extra
    return side


# ---------------------------------------------------------------- crowds: feet, carts
def footstep(rng, hard):
    """one footfall on cobbles: a soft heel knock that rings the stone a little, a low thud, then the sole
    rolling off with a small scuff"""
    n = ns(0.2)
    t = np.arange(n) / SR
    exc = rng.standard_normal(n) * np.exp(-t / 0.0015)
    knock = resonators(exc, rng.uniform(600, 2400, 3), rng.uniform(0.012, 0.03, 3), rng.uniform(0.4, 1.0, 3))
    thud = lp(rng.standard_normal(n), 180, order=2) * np.exp(-t / 0.02)
    k = ns(rng.uniform(0.05, 0.09))
    scuff = np.zeros(n)
    scuff[k:] = bp(rng.standard_normal(n - k), 800, 4500) * np.exp(-np.arange(n - k) / SR / 0.015)
    y = (0.5 + 0.5 * hard) * nrm(knock) + 0.8 * nrm(thud) + rng.uniform(0.15, 0.35) * nrm(scuff)
    return y * np.clip(t / 0.001, 0, 1)


def walk(mix, rng, t_from, t_to, path, t0, level=1.0):
    """one person's footsteps from t_from to t_to along path (time -> (azimuth, distance))"""
    period, hard = rng.uniform(0.5, 0.62), rng.uniform(0.2, 0.8)
    t, foot = t_from + rng.uniform(0, period), 0
    while t < t_to:
        az, d = path(t)
        s = lp(footstep(rng, hard), 9000.0 * np.exp(-float(d) / 18.0), order=2)
        mix.add(pan(s, float(az)), at=t - t0, gain=level * db(-1.5 * foot) * 6.0 / max(float(d), 3.0))
        t += period * rng.uniform(0.97, 1.03)
        foot ^= 1


def scuffle(rng):
    """a shoe scuffing or shuffling on the stones"""
    dur = rng.uniform(0.08, 0.3)
    return bp(colored(dur, rng, -1.0), 400, 3000) * bell(dur, rng.uniform(0.2, 0.5))


def far_cart(rng, dur):
    """a cart and horse going by far off, faint: the wheels' low rumble and bumps over the cobbles and the
    hooves at a walk, swelling and fading as it passes"""
    n = ns(dur)
    rumble = nrm(lp(brown(dur, rng), 140, order=2)) * 0.6 + nrm(crackle(dur, rng, 9.0, decay=0.01, f_lo=40, f_hi=300))
    hooves = Mix(dur)
    t = rng.uniform(0, 0.3)
    while t < dur - 0.3:
        for g in (0.0, 0.27, 0.55, 0.81):
            m = ns(0.08)
            tt = np.arange(m) / SR
            clop = bp(white(0.08, rng), 700, 2500) * np.exp(-tt / 0.008) + 0.6 * lp(white(0.08, rng), 220) * np.exp(-tt / 0.025)
            hooves.add(rng.uniform(0.7, 1.0) * clop, at=t + g + rng.uniform(-0.01, 0.01))
        t += 1.1
    y = rumble + 0.5 * nrm(fit(hooves.out(), n))
    return lp(y, 1400, order=2) * bell(dur, 0.5, 1.5, 1.5)


# ---------------------------------------------------------------- crowds: the beds
LOOP, XFADE, PRE = 24.0, 3.0, 4.0


@recipe("crowd_day", stereo=True, loop=LOOP, xfade=XFADE, rms_db=-26.0)
def crowd_day(v, rng):
    """The walla of a busy market square: 36 people chatting in pairs all around, near and far (an even
    murmur, the words only half heard), soft footsteps and shuffles on the cobbles, and now and then a cart
    rumbling by somewhere far off. A 24 s stereo loop."""
    takes = [(f"crowd_day_{i + 1:02d}", t[0]) for i, t in enumerate(lines.CROWD_DAY_TAKES)]
    pool = phrases_of(takes)
    voices = list(pool)
    t0, t1 = -PRE, LOOP + XFADE + 1.0
    # two people in each voice: one near its natural pitch, one about 2.4 semitones away
    first, second = [], []
    for vc in voices:
        s0 = rng.uniform(-1.0, 1.0)
        first.append(Person(vc, pool[vc], s0))
        second.append(Person(vc, pool[vc], s0 + (2.4 if s0 < 0 else -2.4), first=2 * len(pool[vc]) // 3))
    rng.shuffle(first)
    rng.shuffle(second)

    def pick(queue, spare):
        a = (queue or spare).pop(0)
        src = queue if any(p.voice != a.voice for p in queue) else spare
        b = next(p for p in src if p.voice != a.voice)
        src.remove(b)
        return [a, b]

    people = []

    def slot(windows, dist, length, queue, spare):
        for w0, w1, d_rng in windows:
            t = w0
            while w1 - t > 3.0:
                e = t + rng.uniform(*length)
                e = w1 if w1 - e < length[0] else min(e, w1)
                pair = pick(queue, spare)
                az, d = rng.uniform(-0.95, 0.95), rng.uniform(*(d_rng or dist))
                for p in pair:
                    p.az, p.dist = float(np.clip(az + rng.uniform(-0.06, 0.06), -1, 1)), d + rng.uniform(-1, 1)
                converse(rng, pair, t, e)
                people.extend(pair)
                t = e + rng.uniform(0.1, 0.6)

    # near pairs (8-12 blocks) keep away from the loop point; others (12-16) stand in for them there
    for _ in range(2):
        slot([(t0, XFADE + 1.0, (12, 16)), (XFADE + 1.0, LOOP - 1.0, None), (LOOP - 1.0, t1, (12, 16))],
             (8, 12), (8, 11), first, second)
    for _ in range(2):
        slot([(t0, t1, None)], (13, 22), (10, 14), first, second)
    for _ in range(3):
        slot([(t0, t1, None)], (22, 40), (12, 16), second, first)
    mix = StereoMix(t1 - t0)
    for i, p in enumerate(people):
        place_still(mix, p, t0, seed=1000 + i)
    n = ns(t1 - t0)
    voices_mix = mix.out(n)
    # feet: a dozen people crossing the square at any time, and the odd scuff
    feet = StereoMix(t1 - t0)
    t = t0 - 6.0
    while t < t1:
        dur = rng.uniform(6.0, 14.0)
        walk(feet, rng, max(t, t0), min(t + dur, t1),
             walking(rng.uniform(4.0, 18.0), t + dur * rng.uniform(0.3, 0.7), rng.uniform(1.1, 1.5),
                     1 if rng.random() < 0.5 else -1), t0)
        t += rng.uniform(0.5, 1.2)
    t = t0
    while t < t1:
        d = rng.uniform(3.0, 14.0)
        feet.add(pan(scuffle(rng), rng.uniform(-0.9, 0.9)), at=t - t0, gain=rng.uniform(0.3, 0.8) * 4.0 / d)
        t += rng.exponential(0.7)
    feet_mix = feet.out(n)
    bed = voices_mix + db(-16.0) * np.max(np.abs(voices_mix)) / (np.max(np.abs(feet_mix)) + 1e-12) * feet_mix
    cart = far_cart(rng, 14.0)
    cart *= db(-30.0) * np.sqrt(np.mean(bed ** 2)) / (np.sqrt(np.mean(cart ** 2)) + 1e-12)
    bed_mix = StereoMix(t1 - t0).add(bed).add(pan(cart, rng.uniform(-0.6, 0.6)), at=6.0 - t0)
    out = stereo_reverb(eq_bed(bed_mix.out(n)), wet=0.12, rt60=1.7, predelay=0.03, seeds=(371, 372))
    return out[:, ns(PRE): ns(PRE + LOOP + XFADE + 0.2)]


def eq_bed(st):
    """take the mud out of a crowd: the low mids where many voices and their echoes pile up"""
    return np.stack([eq(eq(c, "peak", 300, 0.8, -2.5), "highshelf", 6000, 0.7, -2.0) for c in st])


@recipe("crowd_light", stereo=True, loop=LOOP, xfade=XFADE, rms_db=-28.0)
def crowd_light(v, rng):
    """A quieter town street: a few people passing by in twos and talking as they go, someone chatting on a
    doorstep down the way, a greeting called from further off, their footsteps, and the faint murmur of the
    rest of the town. A 24 s stereo loop."""
    takes = [(f"crowd_light_{i + 1:02d}", t[0]) for i, t in enumerate(lines.CROWD_LIGHT_TAKES)]
    pool = phrases_of(takes)
    t0, t1 = -PRE, LOOP + XFADE + 1.0
    mk = {vc: Person(vc, pool[vc], rng.uniform(-1.0, 1.0)) for vc in pool}
    feet = StereoMix(t1 - t0)
    mix = StereoMix(t1 - t0)
    # two women passing left to right, close (about 6 blocks) mid-loop
    pa = walking(rng.uniform(5.5, 7.0), rng.uniform(10.5, 12.0), 1.3, 1)
    # two men passing right to left further off (about 14 blocks), later
    pb = walking(rng.uniform(13.0, 15.0), rng.uniform(19.0, 21.0), 1.2, -1)
    # a man hurrying past (about 9 blocks), early
    pc = walking(rng.uniform(8.0, 10.0), rng.uniform(6.0, 8.0), 1.6, -1)
    for names, path, (a, b) in ((("af_alloy", "af_river"), pa, (4.5, 18.5)),
                                (("am_echo", "bm_daniel"), pb, (14.5, 26.5))):
        pair = [mk[n] for n in names]
        for k, p in enumerate(pair):
            p.path = beside(path, 0.6 * k)
        converse(rng, pair, a, b)
        for p in pair:
            walk(feet, rng, t0, t1, p.path, t0)
    hurry = mk["am_adam"]
    hurry.path = pc
    hurry.say(rng.uniform(6.0, 7.0), 1.0)
    walk(feet, rng, t0, t1, pc, t0, level=1.2)
    # a doorstep chat at about 17 blocks, at the start of the loop (it ends well before the loop does)
    door = [mk["am_santa"], mk["af_jessica"]]
    az = rng.uniform(-0.65, -0.45)
    for p in door:
        p.az, p.dist = az + rng.uniform(-0.04, 0.04), rng.uniform(16.0, 18.0)
    converse(rng, door, t0, 5.5)
    # a greeting called from about 20 blocks off, late in the loop
    caller = mk["af_nova"]
    caller.az, caller.dist = rng.uniform(0.5, 0.75), rng.uniform(19.0, 21.0)
    caller.say(rng.uniform(21.0, 22.0), 1.2)
    for i, p in enumerate(mk.values()):
        (place_moving if p.path else place_still)(mix, p, t0, seed=1100 + i)
    # the rest of the town, far off: the same voices moved well away in pitch, low and muffled
    town = StereoMix(t1 - t0)
    others = [Person(vc, pool[vc], (2.6 if rng.random() < 0.5 else -2.6) + rng.uniform(-0.3, 0.3),
                     first=int(rng.integers(len(pool[vc])))) for vc in pool]
    for k in range(3):
        t = t0 + rng.uniform(0, 2)
        while t < t1:
            p = others[int(rng.integers(len(others)))]
            x = p.peek()
            p.i += 1
            y = far(lp(hp(x, 150.0), 1500.0, order=2), 55.0, seed=1200 + k)
            town.add(pan(y, rng.uniform(-0.9, 0.9)), at=t - t0, gain=rng.uniform(0.6, 1.0))
            t += len(x) / SR + rng.uniform(0.3, 1.5)
    n = ns(t1 - t0)
    voices_mix = mix.out(n)
    lvl = np.max(np.abs(voices_mix))
    town_mix = town.out(n)
    feet_mix = feet.out(n)
    bed = (voices_mix + db(-22.0) * lvl / (np.max(np.abs(town_mix)) + 1e-12) * town_mix
           + db(-13.0) * lvl / (np.max(np.abs(feet_mix)) + 1e-12) * feet_mix)
    out = stereo_reverb(eq_bed(bed), wet=0.14, rt60=1.3, predelay=0.015, seeds=(381, 382))
    return out[:, ns(PRE): ns(PRE + LOOP + XFADE + 0.2)]
