"""Guildmark's cog: every sound of a single-masted trading ship (oak hull, one square sail on its yard, a stern castle with
its wheel, hemp rope, an iron anchor, a bronze bell, the hold's hatch) and of the sea round her, made from nothing.

usage (from the repo root):
    python3 -B art/audio/make_ship_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b] [--mp3 FILE]
        [--fragments DIR]

Writes the sounds as mono 44.1 kHz Ogg Vorbis to <assets>/sounds/ship/<event>.ogg, or <event>_<n>.ogg when an event has
variants: 19 events, 40 files. --only takes event names (creak, wake, ...), --wav also writes WAV copies, --mp3 joins what
was rendered into one listen-through file (a chapter mark at each sound; each loop plays for ten seconds, so that its join
is heard once), --fragments writes sounds_entries.json (the sounds.json entries of the events, keyed ship.<event>) and
lang_sounds.json (their subtitles) for merging: the real sounds.json and en_us.json are not touched. The files are listed
in sounds.json by hand, as the other effects' are.

  creak        6 files  1.4-2.5 s   old oak working in a seaway: a long low groan as a swell lifts her, two squeaks of
                                    planks over a rough groan, the mast working in its partners, a sticking ratchet of a
                                    creak, a moan that slides like a vowel, the hull settling with three pops and a sigh
  sail_fill    3 files  1.15-1.2 s  the wind filling the square sail: the canvas ruffling, the deep whump as it goes taut,
                                    the sheet ringing like a drum skin, the yard groaning and the hemp stretching under the
                                    load (a hard fill, a soft slow one, two fills)
  luff         4 files  0.7-0.95 s  slack canvas flapping and cracking, bursts for the game to string together (three
                                    slowing flaps, a big crack and a quick run, four uneven flaps and a block rattling, two
                                    heavy flaps and the reef points pattering)
  hoist        3 files  1.55-1.6 s  the sail hauled up or let down: rope through wooden blocks, the sheaves squeaking,
                                    canvas rustling and unrolling (hauled hand over hand, let down in long folds, a steady
                                    two-block haul)
  wake         loop     8.000 s     water rushing along the hull, hissing at the bow, glugging in the churn, a wave slapping
                                    the planks now and then (the game raises it with speed)
  lap          loop     8.400 s     small waves lapping the planks of a hull at rest: wet laps, gentle knocks, air glugging
                                    out from under the planks, drips
  rigging      loop     7.600 s     wind in the rigging: a low rush, four taut lines moaning in it, thrumming, a flutter and
                                    the odd tick in the gusts (the game raises it with the wind)
  anchor_drop  1 file   2.7 s       the rope rattling out through the hawse, the iron clanking down the side, a heavy
                                    splash, the rope running on after it and fetching up with a creak
  anchor_raise 1 file   2.7 s       the capstan's pawls clicking round, wet rope hauled in and streaming, the anchor
                                    breaking surface and clunking home against the cathead
  bell         2 files  3.1 s       the ship's bell, bronze, struck twice (ding-ding), bright, ringing on; the second blow
                                    lands on a bell still ringing (two gaps, two strengths of blow)
  scrape       3 files  1.25-1.3 s  the hull grinding aground: over sand, over rock, over shingle with a heavy stop
  bump         3 files  0.5-0.6 s   the hollow, heavy knock of oak against a quay or a stone (dull, sharp, and a rebound)
  launch       1 file   2.15 s      the cog put into the water: the displacement splash, the rush, slaps off the planks, the
                                    timbers settling with a groan
  pack         1 file   1.6 s       the ship struck down: canvas folded with a rush, ropes coiled, the heavy lid shut and
                                    latched
  wheel        3 files  0.34-0.38 s the helm turned: quiet wooden clicks of the spokes and the pawl (they come often: peak
                                    -5 dBFS)
  repair       3 files  0.62-0.7 s  a shipwright's mallet driving a treenail: two solid knocks
  wreck        1 file   3.2 s       the ship breaking up: timbers splintering, the mast snapping and going over, the sail
                                    ripping, lines parting, a great splash, floating wreckage after
  hold_open    1 file   0.75 s      the iron-banded hatch heaved open: the hasp, a creak and a squeal of hinges, the lid
                                    against its stop
  hold_close   1 file   0.61 s      the hatch dropped shut: the thud on the coaming, the hold booming, the bands rattling,
                                    the hasp

Nothing is sampled. Each event is one `@recipe` built from real materials' modes, grains and envelopes with sfxkit.py: the
creaks are stick-slip friction (a train of slips, each ringing the oak hull's modes, a sliding vowel over it); the water
is Minnaert bubbles (a bubble of radius R mm rings at about 3260/R Hz) in swarms with power-law radii, smacks, hollow
cavity knocks and the plank's own tone; hemp is a rasp of fibres with the strand lay's slow pattern; canvas is crinkling
grains, swishes and slaps; wood is the modes of heavy oak (a dull knock, not a ring); iron is a free bar's modes, in pairs
that beat; the bell is the partials of cast bronze (hum, prime, tierce, quint, nominal and the uppers that die fast), each
a doublet. The low thumps are driven a little so that their harmonics carry on a phone's speaker; the layers of a mix are
levelled by loudness, not by peak.

The three loops are built in a circle: laid out over three periods that repeat each other (noise made once and tiled, slow
curves of whole cycles of the period, events placed at the same moment in each), run through the filters, and the middle
period kept, so that what rings on past the end has already rung on past the end before the start, and the sample after
the last is the first. The file is read back after the encoder to check its length and its join (the step across it
against the usual step, the highs round it against the highs elsewhere). Each loop starts at a quiet rising zero crossing.

Levels. The one-shots peak at -3 dBFS (-4 for the long creaks, luffs and hoists, -5 for the often-heard wheel clicks) and
are set, as the townsfolk's effects are, by the loudest 100 ms (K-weighted LUFS): about -12 for the anchor's fall, the
bell, the launch and the wreck, -14 to -17 for the rest, -23 for the wheel; a sharp sound is soft-limited just enough to
get there (never by more than limit_db), a louder one turned down. The loops are set by RMS, under the one-shots (wake
-24, lap -28, rigging -27 dBFS, peaks held under -7, -8 and -8). Every file starts from zero and fades out, true peaks
stay under -1 dBTP, and every random number is seeded, so a rerun gives the same files.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import time

import numpy as np
from scipy import signal
from scipy.ndimage import uniform_filter1d

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403

RECIPES = {}
SHIP_DIR = "ship"


# =========================================================================== registry and finishing
def recipe(name, variants=1, peak_db=-3.0, fade_out=0.03, max_len=None, tail_db=-62.0, loud=None, limit_db=6.0,
           loop=None, rms_db=None, ceiling_db=-6.0, subtitle="", reach=None):
    """one sound event, written as ship/<name>.ogg (one variant) or ship/<name>_<n>.ogg for n = 1..variants.
    peak_db: where a one-shot's peak sits. loud: how loud a one-shot is, as the townsfolk's effects are set: the loudest 100 ms
    in LUFS (K-weighted; a number, or one for each variant). A variant that is louder than that at its peak is turned down to
    it; a sharp one is soft-limited just enough to reach it, never by more than limit_db, so that a knock stays a knock.
    loop: the length in seconds of a seamless loop (the recipe is handed a Ring and gives back the three periods it laid out,
    see Ring), rms_db its RMS with its peaks held under ceiling_db. subtitle: the caption in the language file (none for the
    loops and the quiet details). reach: how many blocks the sound carries in the game, when not the usual 16 (more for the big
    sounds, fewer for the quiet clicks of the wheel)"""
    def deco(fn):
        RECIPES[name] = dict(fn=fn, variants=variants, peak_db=peak_db, fade_out=fade_out, max_len=max_len,
                             tail_db=tail_db, loud=loud, limit_db=limit_db, loop=loop, rms_db=rms_db, ceiling_db=ceiling_db,
                             subtitle=subtitle, reach=reach)
        return fn
    return deco


def sound_path(name, v, variants):
    """('creak', 2, 6) -> 'ship/creak_3'; a sound with one file is 'ship/anchor_drop'"""
    return f"{SHIP_DIR}/{name}_{v + 1}" if variants > 1 else f"{SHIP_DIR}/{name}"


def nrm(x):
    return x / (np.max(np.abs(x)) + 1e-12)


def lead_in(x, t=0.002):
    """x with a couple of milliseconds of silence in front of it, so that the file's zero-phase high-pass pads nothing
    with a thump (finalize() trims the silence off again)"""
    return np.concatenate([np.zeros(ns(t)), np.asarray(x, dtype=float)])


def late(x, at, n=None):
    y = np.concatenate([np.zeros(ns(at)), x])
    return fit(y, n) if n else y


def short_lufs(x, win=0.1, hop=0.005):
    """how loud x is as a short sound: its loudest 100 ms, K-weighted, in LUFS (the townsfolk's effects are set by this)"""
    k = kweight(np.concatenate([np.asarray(x, dtype=float), np.zeros(ns(win))]))
    w, h = ns(win), max(1, ns(hop))
    c = np.concatenate([[0.0], np.cumsum(k * k)])
    starts = np.arange(0, max(1, len(k) - w), h)
    return float(np.max(-0.691 + 10 * np.log10(np.maximum((c[starts + w] - c[starts]) / w, 1e-20))))


def render_effect(name, v):
    """one variant of a one-shot, finished: trimmed, faded, its peak at peak_db, and its loudness (the loudest 100 ms) at the
    recipe's target, turned down to it or soft-limited up to it"""
    r = RECIPES[name]
    x = lead_in(r["fn"](v, rng_for("ship/" + name, v)))
    ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]
    target = r["loud"][v] if isinstance(r["loud"], (list, tuple)) else r["loud"]

    def finish(y, peak_db=r["peak_db"]):
        y = finalize(y, peak_db=peak_db, fade_out=r["fade_out"], max_len=ml, tail_db=r["tail_db"])
        # (the zero-phase high-pass leaves a faint run-up before a hard onset: the file starts from true zero)
        k = min(len(y), ns(0.001))
        y[:k] *= np.sin(np.linspace(0.0, np.pi / 2, k, endpoint=False)) ** 2
        return y

    y = finish(x)
    if target is None:
        return y
    now = short_lufs(y)
    if now >= target:  # (already louder than asked: the whole sound is turned down)
        return finish(x, r["peak_db"] - (now - target))
    x = x / (np.max(np.abs(x)) + 1e-12)
    lo, hi = db(-r["limit_db"]), 1.0
    for _ in range(12):  # the soft limit that gives the asked loudness: lower ceiling, denser sound
        c = float(np.sqrt(lo * hi))
        y = finish(limiter(x, c, 0.002, 0.05))
        if short_lufs(y) < target:
            hi = c
        else:
            lo = c
    return finish(limiter(x, float(np.sqrt(lo * hi)), 0.002, 0.05))


# =========================================================================== seamless loops
class Ring:
    """A seamless loop, built in a circle. The loop is `period` seconds long; everything is laid out over three periods
    that repeat each other exactly (noise made once and tiled, slow curves made of whole cycles of the period, events
    placed at the same moment in every period), run through whatever filters and rooms it needs, and the middle period
    is kept. Whatever rings on from the first period into the second has rung on from the last into the first as well,
    so the sample after the last one is exactly the first, with no crossfade and no seam to hide."""

    def __init__(self, period):
        self.n = ns(period)
        self.N = 3 * self.n
        self.P = self.n / SR
        self.t = np.arange(self.N) / SR

    def tile(self, x1):
        return np.tile(np.asarray(x1, dtype=float), 3)

    def noise(self, rng, slope=0.0, fmin=20.0):
        """periodic noise, unit spread, with a spectral slope in dB per octave (0 white, -3 pink, -6 brown)"""
        spec = np.fft.rfft(rng.standard_normal(self.n))
        f = np.fft.rfftfreq(self.n, 1 / SR)
        f[0] = f[1]
        spec *= (np.maximum(f, fmin) / 1000.0) ** (slope / 6.0206)
        y = np.fft.irfft(spec, self.n)
        return self.tile(y / (np.std(y) + 1e-12))

    def curve(self, rng, kmin, kmax, slope=1.0):
        """a smooth random curve of zero mean and unit spread that repeats every period: whole cycles kmin..kmax of
        the period (one cycle is 1/period Hz), each weighted by 1/k**slope, with random phases"""
        k = np.arange(kmin, kmax + 1)
        a = k ** -float(slope)
        ph = rng.uniform(0, 2 * np.pi, len(k))
        y = np.zeros(self.N)
        for ai, ki, pi in zip(a, k, ph):
            y += ai * np.cos(2 * np.pi * ki * self.t / self.P + pi)
        return y / np.sqrt(np.sum(a ** 2) / 2)

    def bumps(self, centres, heights, rise, fall):
        """a smooth bump at each centre (seconds into the period), rising over about `rise` seconds and falling over
        about `fall`, wrapped round the period; 0..1"""
        y = np.zeros(self.N)
        for c, h in zip(centres, heights):
            d = (self.t - c + self.P / 2) % self.P - self.P / 2
            y += h * np.exp(-0.5 * (d / np.where(d < 0, rise, fall)) ** 2)
        return y

    def new(self):
        return np.zeros(self.N)

    def circ(self, x):
        """an array laid out over one period and a bit more (the ring of its last events running past the end): the part past
        the end is folded back onto the start, and the period is repeated three times"""
        x = np.asarray(x, dtype=float)
        y = np.zeros(self.n)
        m = min(len(x), self.n)
        y[:m] = x[:m]
        k = len(x) - self.n
        if k > 0:
            y[:k] += x[self.n:self.n + k]
        return self.tile(y)

    def place(self, buf, ev, at, gain=1.0):
        """add the event ev (a short array) to buf at `at` seconds into the period, in every one of the three periods;
        the part that runs past the end of the buffer is dropped, the part that runs past the end of a period is the
        start of the next one's ring"""
        ev = np.asarray(ev, dtype=float)
        for k in range(3):
            i = ns(at) + k * self.n
            if i >= self.N:
                continue
            m = min(len(ev), self.N - i)
            buf[i:i + m] += gain * ev[:m]
        return buf

    def times(self, rng, rate, shape=2.0, start=None, stop=None):
        """moments (seconds, within one period) of events at about `rate` a second, with a gamma-distributed gap between
        them (more even than chance: shape 1 would be pure chance); rate may be a curve over the period (three periods
        long, the middle one is read)"""
        stop = self.P if stop is None else stop
        r_at = (lambda t: rate) if np.isscalar(rate) else (lambda t: rate[self.n + min(self.n - 1, int(t * SR))])
        t = (rng.uniform(0, 1.0 / r_at(0.0)) if start is None else start)
        out = []
        while t < stop:
            out.append(t)
            t += rng.gamma(shape, 1.0 / (shape * max(r_at(t), 1e-3)))
        return np.array(out)

    def middle(self, x):
        return x[self.n:2 * self.n]


def loop_start(y):
    """turn the loop y round so that it starts where the sound is quiet and passes through zero rising: the game starts the
    loop from silence, so that start is not a click (the loop itself is circular, so turning it changes no join)"""
    env = np.sqrt(uniform_filter1d(y * y, size=ns(0.012)))
    lo = lp(y, 500.0, 2)
    cross = np.nonzero((lo[:-1] < 0) & (lo[1:] >= 0))[0] + 1
    if not len(cross):
        return y
    cost = env[cross] / (env.max() + 1e-12) + 6.0 * np.abs(y[cross]) / (env.max() + 1e-12)
    k = int(cross[int(np.argmin(cost))])
    return np.roll(y, -k)


def render_loop(name, v):
    """a loop, finished: the middle period of the three the recipe laid out, high-passed, levelled to its RMS with its
    peaks held under the ceiling, and turned to start at a quiet zero crossing"""
    r = RECIPES[name]
    ring = Ring(r["loop"])
    x = r["fn"](ring, rng_for("ship/" + name, v))
    x = hp(x, 25.0)
    for _ in range(4):
        y = ring.middle(x)
        x = x * (db(r["rms_db"]) / (rms(y) + 1e-12))
        y = ring.middle(x)
        if np.max(np.abs(y)) <= db(r["ceiling_db"]) * 1.001:
            break
        x = limiter(x, ceiling=db(r["ceiling_db"]), lookahead=0.003, release=0.08)
    return loop_start(ring.middle(x))


# =========================================================================== small helpers
def rn(x):
    """x scaled to unit RMS (for continuous textures; nrm() is for blows)"""
    return x / (rms(x) + 1e-12)


def kweight(x):
    """BS.1770 K-weighting: a +4 dB shelf above 1.5 kHz and a 38 Hz high-pass (the ear's idea of loudness)"""
    def biquad(gain_db, q, fc, shelf):
        A = 10 ** (gain_db / 40.0)
        w0 = 2 * np.pi * fc / SR
        alpha = np.sin(w0) / (2 * q)
        c = np.cos(w0)
        if shelf:
            b = [A * ((A + 1) + (A - 1) * c + 2 * np.sqrt(A) * alpha), -2 * A * ((A - 1) + (A + 1) * c),
                 A * ((A + 1) + (A - 1) * c - 2 * np.sqrt(A) * alpha)]
            a = [(A + 1) - (A - 1) * c + 2 * np.sqrt(A) * alpha, 2 * ((A - 1) - (A + 1) * c),
                 (A + 1) - (A - 1) * c - 2 * np.sqrt(A) * alpha]
        else:
            b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
            a = [1 + alpha, -2 * c, 1 - alpha]
        return np.array(b) / a[0], np.array(a) / a[0]
    y = signal.lfilter(*biquad(4.0, 1 / np.sqrt(2), 1500.0, True), x)
    return signal.lfilter(*biquad(0.0, 0.5, 38.0, False), y)


def ln(x, ref=0.1):
    """x levelled by its loudness (K-weighted RMS over at least 100 ms: the ear adds up what comes within that), not by its
    peak, so that a spray of clicks and a rush given the same gain sound as loud as each other"""
    k = kweight(np.asarray(x, dtype=float))
    r = float(np.sqrt(np.sum(k * k) / max(len(k), ns(0.1))))
    return x * (ref / r) if r > 1e-9 else x


def tame(x, crest_db=12.0):
    """soft-clip x so that no peak stands more than crest_db over its RMS: the few biggest clicks of a crackle would
    otherwise be so far above the rest that they set the level of the whole sound"""
    ceil = float(np.sqrt(np.mean(np.square(x)))) * db(crest_db) + 1e-12
    return np.tanh(x / ceil) * ceil


class Bus(Mix):
    """a Mix whose layers come in levelled by loudness (ln): a layer's gain is how loud it is next to the others"""

    def add(self, x, at=0.0, gain=1.0, end_fade=0.006):
        return super().add(ln(x), at, gain, end_fade)


def spread(dur, rng, rate, depth):
    """a slow random drift about 1 (within +-depth): a pitch or a level that is never quite still"""
    return 1 + depth * (2 * smooth_random(dur, rng, rate) - 1)


def rise_to(t, t0, span):
    """0 before t0, climbing to 1 over `span` seconds (a smooth ease)"""
    return np.sin(np.pi / 2 * np.clip((t - t0) / max(span, 1e-6), 0, 1)) ** 2


def hann_burst(dur, rng, lo, hi):
    """a short burst of band-limited noise under a Hann window: the instant of contact in a blow"""
    n = ns(dur)
    return bp(white(dur, rng), lo, hi) * np.hanning(n + 2)[1:-1]


def at_sea(x, wet=0.08, seed=1701):
    """out on the water: the sea throws back a quick slap off its surface and the hull, and a short tail"""
    return reverb(x, wet=wet, rt60=0.8, predelay=0.012, hf_damp=0.75, seed=seed, early_span=0.04)


def below_deck(x, wet=0.2, rt60=0.4, seed=1703):
    """the hold under its hatch: a small close room of oak and tarred plank, a quick dull tail"""
    return reverb(x, wet=wet, rt60=rt60, predelay=0.004, hf_damp=0.65, seed=seed, early_span=0.014)


# =========================================================================== water
def bubble(r_mm, rng, rise=None, max_dur=0.35):
    """one air bubble ringing in water (Minnaert): a bubble of radius R mm sounds at about 3260/R Hz, damped by the
    water's viscosity, by heat and by the sound it radiates (van den Doel's constants), its pitch creeping up as it
    nears the surface. A small bubble is a bright tick, a big one a soft 'bloop'"""
    r = r_mm * 1e-3
    f0 = 3.26 / r
    d = 0.13 / r + 0.0072 * r ** -1.5
    if rise is None:
        rise = rng.uniform(0.05, 0.2)
    n = max(16, ns(min(max_dur, 6.0 / d)))
    t = np.arange(n) / SR
    y = np.sin(phase_from_freq(f0 * (1 + rise * d * t), n)) * np.exp(-d * t)
    k = min(n, ns(0.0003) + 1)
    y[:k] *= np.linspace(0, 1, k)
    return y


def bubbles(dur, rng, rate, r_lo, r_hi, beta=1.8, level=1.0, weight=0.5, rise=None, keep_tail=False):
    """a swarm of bubbles forming and rising, `rate` of them a second (a number or a curve over the time): radii from a
    power law between r_lo and r_hi mm (many small ones, few big ones), louder the bigger they are"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    idx = np.nonzero(rng.random(n) < rate / SR)[0]
    a = 1 - beta
    rs = (r_lo ** a + rng.random(len(idx)) * (r_hi ** a - r_lo ** a)) ** (1 / a)
    out = np.zeros(n + ns(0.4))
    for i, r in zip(idx, rs):
        b = bubble(r, rng, rise=rise) * (r / 3.0) ** weight * np.exp(0.3 * rng.standard_normal())
        out[i:i + len(b)] += b
    return level * (out if keep_tail else out[:n])


def grain_cloud(dur, rng, rate, decay=0.0008, f_lo=800.0, f_hi=6000.0, size_var=0.8, kernels=5, amp=None):
    """small dry impacts all over (fibres, grit, creases, drops): random grains, `rate` of them a second, each one of a few
    different short noise kernels so that no two clicks are the same, band-limited"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    idx = np.nonzero(rng.random(n) < rate / SR)[0]
    if not len(idx):
        return np.zeros(n)
    sel = rng.integers(0, kernels, len(idx))
    mag = rng.standard_normal(len(idx)) * np.exp(size_var * rng.standard_normal(len(idx)))
    if amp is not None:
        mag = mag * np.broadcast_to(np.asarray(amp, dtype=float), (n,))[idx]
    out = np.zeros(n)
    k = max(8, ns(decay * 6))
    for j in range(kernels):
        kern = np.exp(-np.arange(k) / max(1, ns(decay))) * rng.standard_normal(k)
        imp = np.zeros(n)
        m = sel == j
        imp[idx[m]] = mag[m]
        out += signal.fftconvolve(imp, kern)[:n]
    return bp(out, f_lo, min(f_hi, SR * 0.45))


def smack(rng, size=1.0, bright=1.0):
    """the wet smack of a wave front meeting timber: a quick cluster of little impacts along the planks, full-bodied
    rather than clicky"""
    L = 0.12 * size
    t = tvec(L)
    ex = np.zeros(len(t))
    for _ in range(int(rng.integers(3, 8))):
        i = ns(rng.exponential(0.008 * size))
        if i < len(t) - 1:
            ex[i] += rng.uniform(0.3, 1.0)
    k = white(0.04, rng) * np.exp(-tvec(0.04) / (rng.uniform(0.004, 0.01) * size ** 0.5))
    y = signal.fftconvolve(ex, k)[: len(t)]
    y = bp(y, 160 / size ** 0.5, 1400 + 1800 * bright)
    return eq(y, "peak", rng.uniform(380, 1000) / size ** 0.5, 1.2, 8)


def cavity(rng, f=None, glide=None, tau=None):
    """water closing over a pocket of air under a plank or a counter: a hollow, rising knock"""
    f = f or rng.uniform(170, 480)
    glide = rng.uniform(0.08, 0.35) if glide is None else glide
    tau = tau or rng.uniform(0.012, 0.035) * (300.0 / f) ** 0.5
    L = min(0.5, tau * 7)
    t = tvec(L)
    fr = f * (1 + glide * (1 - np.exp(-t / 0.035)))
    y = np.sin(phase_from_freq(fr, len(t))) * np.exp(-t / tau)
    y += 0.2 * np.sin(phase_from_freq(fr * 2.32, len(t))) * np.exp(-t / (tau * 0.45))
    return y * np.clip(t / 0.0015, 0, 1)


def slosh(dur, rng, f_hi=1400.0, peak=0.2):
    """water running back off the planks: a soft rush that swells and dies"""
    y = lp(bp(pink(dur, rng), 150, f_hi), f_hi)
    return y * bell(dur, peak, 1.4, 2.0)


def drip(rng):
    """a drop falling back into the water: a tiny tick and the bright rising plink of the bubble it pulls under"""
    y = bubble(rng.uniform(1.0, 2.8), rng, rise=rng.uniform(0.3, 0.7))
    tick = hp(white(0.004, rng), 1500) * np.exp(-tvec(0.004) / 0.0007)
    m = Mix(len(y) / SR + 0.005)
    m.add(tick, 0.0, 0.25)
    m.add(y, rng.uniform(0.001, 0.004), 1.0)
    return m.out()


def hull_slap(rng, size=1.0, wet=1.0, cav=1.0, plank=1.0):
    """a wave slapping the planks of the hull: the smack of the front, the hollow knock of the air trapped between plank and
    water (the hull is a drum), a few bubbles and the water running back with a drip or two. size scales it up; cav and plank
    are how much of the hollow knock and of the plank's own tone there is"""
    m = Mix(0.9)
    m.add(nrm(smack(rng, size)), 0.0, 0.7 * wet)
    m.add(nrm(cavity(rng, f=rng.uniform(150, 360) / size ** 0.4)), rng.uniform(0.0, 0.012), rng.uniform(0.45, 0.9) * wet * cav)
    m.add(nrm(strike(0.3, rng, np.array([205, 330, 510]) / size ** 0.3, [0.09, 0.06, 0.04], [1, 0.6, 0.35], lo=100, hi=1800,
                     ex=0.004)), 0.0, 0.3 * wet * plank)  # the plank answering with its own tone
    for r in 3.0 + 9.0 * rng.random(int(rng.poisson(1.5 + 2.0 * size))):
        m.add(bubble(r, rng), rng.exponential(0.05), rng.uniform(0.15, 0.5) * (r / 6.0) ** 0.5 * wet)
    for _ in range(rng.poisson(0.8 * size)):
        m.add(drip(rng), 0.08 + rng.exponential(0.12), rng.uniform(0.05, 0.16) * wet)
    m.add(nrm(slosh(rng.uniform(0.2, 0.45) * size ** 0.5, rng)), rng.uniform(0.01, 0.04), rng.uniform(0.08, 0.2) * size * wet)
    return m.out()


def splash(dur, rng, size=1.0, hard=1.0, crown=True):
    """something heavy dropped in the sea. size 1 is a man's weight of iron (an anchor), 3 or more is a ship's boat or a mast.
    The entry slap (water torn apart in a flat burst), the hollow bloop of the cavity it opens and the big bubbles that
    breaks into, a cloud of smaller bubbles streaming up, the crown thrown up and falling back as spray, a rush of
    displaced water that swells and slumps"""
    n = ns(dur)
    t = tvec(dur)
    m = Mix(dur)
    m.add(nrm(bp(white(dur, rng), 250, 7500) * np.exp(-t / (0.028 * size ** 0.5)) * np.clip(t / 0.0012, 0, 1)), 0.0, 0.8 * hard)
    m.add(nrm(hp(white(0.01, rng), 2500) * np.exp(-tvec(0.01) / 0.0018)), 0.0, 0.25 * hard)
    m.add(nrm(thump(min(dur, 0.6), 130 / size ** 0.5, 48 / size ** 0.3, rng, decay=0.07 * size ** 0.5, click=0.0, noise=0.7,
                    nlp=420 / size ** 0.3)), 0.0, 0.85)
    for r in rng.uniform(14.0, 38.0, 2) * size ** 0.4:  # the big hollow bloops
        m.add(nrm(bubble(r, rng, rise=0.35, max_dur=0.5)), rng.uniform(0.0, 0.06), 0.55)
    swarm = bubbles(dur, rng, 700 * size * np.exp(-t / (0.18 * size ** 0.5)) + 20, 0.9, 11.0, beta=1.9, level=1.0)
    m.add(nrm(swarm), 0.01, 0.55)
    gd = min(dur, 0.9 * size ** 0.5)
    g = tvec(gd)
    rush = tvf(pink(gd, rng), "bp", 500 * 3.5 ** (1 - np.exp(-g / 0.1)), 0.8) * (1 - np.exp(-g / 0.02)) \
        * np.exp(-g / (0.2 * size ** 0.5))
    m.add(nrm(rush), 0.005, 0.6)
    if crown:
        sp = dur - 0.12
        ts = tvec(sp)
        senv = (1 - np.exp(-ts / 0.12)) * np.exp(-ts / (0.3 * size ** 0.5))
        senv /= senv.max()
        rain = grain_cloud(sp, rng, 260 * size * senv + 5, decay=0.0005, f_lo=1200, f_hi=8000, size_var=0.8) * senv
        m.add(nrm(rain), 0.1, 0.4)
        for tt in np.sort(rng.uniform(0, sp * 0.8, int(30 * size))):
            e = senv[min(ns(tt), len(senv) - 1)]
            if rng.random() < e:
                m.add(drip(rng), 0.1 + tt, rng.uniform(0.04, 0.13) * e)
    return fit(m.out(), n)


# =========================================================================== timber, rope and cloth
def slip_train(rate, rng, jitter=0.08, alt=0.0, spread_=0.35):
    """stick-slip friction as a train of ticks: the surfaces stick, the load builds, they slip. `rate` (Hz, one per sample)
    is how often; each tick is bigger the longer the surfaces stuck, and a slow creak wanders more in its timing than a fast
    one. alt makes every second tick small (a groan that skips every other beat)"""
    n = len(rate)
    idx = np.nonzero(np.diff(np.floor(np.cumsum(rate) / SR)) > 0)[0] + 1
    out = np.zeros(n)
    last = None
    for j, i in enumerate(idx):
        per = SR / max(rate[i], 1.0)
        pos = int(i + jitter * min(2.0, np.sqrt(40.0 / max(rate[i], 1.0))) * per * rng.standard_normal())
        if not 0 <= pos < n:
            continue
        gap = 1.0 if last is None else min(3.0, max(0.25, (pos - last) / per))
        out[pos] += np.sqrt(gap) * np.exp(spread_ * rng.standard_normal()) * (1 + alt * (1 if j % 2 else -1))
        last = pos
    return out


def timber_modes(rng, f_lo, f_hi, count=26, t60_lo=0.09, t60_hi=0.02, peaks=()):
    """the resonances of a big wooden body: many modes between f_lo and f_hi (a big irregular timber is dense with them),
    the low ones ringing longest, and `peaks`, the frequencies its tone gathers round"""
    f = np.sort(np.exp(rng.uniform(np.log(f_lo), np.log(f_hi), count)))
    u = np.log(f / f_lo) / np.log(f_hi / f_lo)
    t60 = t60_lo * (t60_hi / t60_lo) ** u * rng.uniform(0.7, 1.3, count)
    g = rng.uniform(0.3, 1.0, count) * (f / f_lo) ** -0.7
    for p in peaks:
        g = g * (1 + 2.2 * np.exp(-(np.log(f / p) / 0.18) ** 2))
    return f, t60, g


def creak_voice(dur, rng, rate, amp, modes, vowel=None, jitter=0.08, alt=0.0, rub=0.12, kick=0.0011, bright=3200.0,
                unsteady=0.35):
    """a timber creaking. Each slip of the stick-slip train (rate: Hz, a number or a curve) rings the body's modes; `vowel`
    is a list of (centre Hz curve, Q, gain) sliding resonances the same ticks pass through, the 'ooo-aah' of a groan as the
    load shifts; a rubbing hiss rides with the ticks; amp is the loudness, which is never steady (unsteady: how much it is
    broken up, nine times a second or so, as the surfaces catch and let go)"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,)).copy()
    amp = np.broadcast_to(np.asarray(amp, dtype=float), (n,)) * (1 - unsteady * smooth_random(dur, rng, 9) ** 1.3)
    ticks = slip_train(rate, rng, jitter, alt)
    kern = bp(white(kick * 10, rng), 200, bright) * np.exp(-tvec(kick * 10) / kick)
    ex = signal.fftconvolve(ticks, kern)[:n]
    y = rn(resonators(ex, *modes))
    for fc, q, g in (vowel or []):
        y = y + g * rn(tvf(ex, "bp", fc, q))
    hiss = bp(colored(dur, rng, -1.5), 350, 3500) * np.sqrt(np.clip(rate, 1, 300) / 100.0)
    y = y + rub * rn(hiss)
    return y * amp


def squeal(dur, rng, f, amp, harmonics=8, tilt=1.2, chatter=0.35, wobble=0.012, grit=0.05, peak_hz=None):
    """a squeak: wood on wood or rope in a dry block, the surfaces catching and letting go so fast that it is a note. A
    stack of harmonics on the pitch f (a number or a curve), the pitch wavering a little and chattering, the level
    broken up by the catch and slip, a hiss of rubbing. peak_hz is where the squeak's tone gathers"""
    n = ns(dur)
    f = np.broadcast_to(np.asarray(f, dtype=float), (n,))
    amp = np.broadcast_to(np.asarray(amp, dtype=float), (n,))
    fw = f * (1 + wobble * (2 * smooth_random(dur, rng, 7) - 1) + 0.004 * (2 * smooth_random(dur, rng, 60) - 1))
    ph = phase_from_freq(fw, n)
    y = np.zeros(n)
    for k in range(1, harmonics + 1):
        if k * float(np.max(f)) > 15000:
            break
        g = k ** -tilt
        if peak_hz:
            g *= 1 + 2.0 * np.exp(-(np.log(k * f / peak_hz) / 0.5) ** 2)
        y += g * np.sin(k * ph + rng.uniform(0, 2 * np.pi))
    y = y * (1 - chatter * smooth_random(dur, rng, 55) ** 1.5)
    y = rn(y) + grit * rn(hp(white(dur, rng), 1500))
    return y * amp


def rope_run(dur, rng, speed, lay=26.0, lo=500.0, hi=5500.0, wet=0.0, fibres=1.0):
    """rope running over wood or through a block at `speed` (0..1, a curve): the rasp of hemp fibres over the surface, its
    loudness rising with the speed; the strands' lay, a long pattern running past, broken in the level at a rate that goes
    with the speed; the single fibres snapping free as ticks. wet takes the top off it and brings a squelch"""
    n = ns(dur)
    speed = np.broadcast_to(np.asarray(speed, dtype=float), (n,))
    band = bp(colored(dur, rng, -1.0), lo, hi * (1 - 0.45 * wet))
    ph = phase_from_freq(lay * np.clip(speed, 0.05, 1.0), n) + rng.uniform(0, 6.28)
    am = 0.5 + 0.5 * (0.5 + 0.5 * np.sin(ph)) ** 1.5 * (0.7 + 0.3 * smooth_random(dur, rng, 20))
    tick = grain_cloud(dur, rng, (30 + 1400 * speed ** 1.3) * fibres, decay=0.0004, f_lo=lo * 1.4, f_hi=hi * (1 - 0.4 * wet),
                       size_var=0.7)
    y = rn(band) * am + 0.55 * rn(tick) * (1 - 0.4 * wet)
    return y * speed ** 0.9


def crinkle(dur, rng, density, lo=700.0, hi=5500.0, size_var=1.0):
    """stiff canvas creasing and unfolding: separate dry little cracks, as many a second as `density` says"""
    return grain_cloud(dur, rng, density, decay=0.0007, f_lo=lo, f_hi=hi, size_var=size_var, kernels=6)


def swish(dur, rng, env, lo=300.0, hi=3500.0, flutter=0.35, rate=40.0):
    """cloth moved through the air: a bandpassed rush, under the envelope `env`, ruffled by the edge catching the air"""
    y = bp(colored(dur, rng, -1.5), lo, hi) * env
    return y * (1 - flutter + flutter * smooth_random(dur, rng, rate))


def strike(dur, rng, freqs, t60s, amps, lo=250.0, hi=7000.0, ex=0.002, detune=0.015):
    """a struck body: a short noise excitation through a bank of resonators (wood, a plank, a cask)"""
    f = np.asarray(freqs, dtype=float) * rng.uniform(1 - detune, 1 + detune, len(freqs))
    e = hann_burst(ex, rng, lo, hi)
    return resonators(fit(e, ns(dur)), f, t60s, amps)


def oak(dur, rng, f0, t60=0.1, hard=1.0, ex=0.0025, lo=200.0):
    """a block of oak struck: the modes of a heavy timber (1, 1.62, 2.41, 3.37, 4.6 and 6.1 times the lowest, the upper ones
    dying first), a dull knock and not a ring; `hard` is how far up the blow reaches"""
    r = np.array([1.0, 1.62, 2.41, 3.37, 4.6, 6.1])
    return strike(dur, rng, f0 * r, t60 * np.array([1.0, 0.8, 0.6, 0.45, 0.32, 0.22]),
                  np.array([1.0, 0.85, 0.6, 0.4, 0.28, 0.18]) * np.array([1, 1, hard, hard ** 1.5, hard ** 2, hard ** 2.5]),
                  lo=lo, hi=2500 + 5500 * hard, ex=ex)


def iron_ring(dur, rng, f0s, t60=0.5, gains=None, detune=0.012):
    """forged iron struck: the modes of a free bar (1, 2.756, 5.404, 8.933 and 13.34 times the lowest, the upper ones dying
    first) for each of the pieces that ring (shank, arm, ring), every mode a close pair that beats"""
    gains = np.ones(len(f0s)) if gains is None else gains
    r = np.array([1.0, 2.756, 5.404, 8.933, 13.344])
    t60s = t60 * np.array([1.0, 0.7, 0.45, 0.3, 0.2])
    a = np.array([1.0, 0.8, 0.55, 0.35, 0.2])
    out = np.zeros(ns(dur))
    for f0, g in zip(f0s, gains):
        fr = f0 * r * rng.uniform(1 - detune, 1 + detune, len(r))
        out += g * modal(dur, fr, t60s, a, rng)
        out += 0.5 * g * modal(dur, fr * (1 + rng.uniform(0.002, 0.007)), t60s * 0.9, a, rng)
    return out


def saturate(x, amount=2.5):
    """a low thump driven so that it has harmonics: its upper partials carry the weight of a blow on a small speaker"""
    return drive(nrm(x), amount)


# <<creak
# =========================================================================== the timbers
def path(points, dur, smooth=0.03):
    """a curve through (time, value) points, its corners rounded over `smooth` seconds"""
    y = env_points(points, dur)
    k = ns(smooth)
    return uniform_filter1d(y, size=k, mode="nearest") if k > 1 else y


def hull_modes(f_lo=85.0, f_hi=2600.0, peaks=(170, 430), count=30):
    """the cog's own timber voice: the same modes in every creak (one boat), gathered round a different pair of tones by each"""
    return timber_modes(rng_for("ship", "hull"), f_lo, f_hi, count, 0.1, 0.02, peaks=peaks)


def pop(rng, f0, size=1.0, hard=1.0):
    """a timber giving a little under load: a sharp crack as tight fibres let go and the dull ring of the plank behind it"""
    m = Mix(0.35)
    m.add(nrm(hann_burst(0.003, rng, 700, 7000)), 0.0, 0.6 * hard)
    m.add(nrm(oak(0.35, rng, f0, t60=0.12 * size, hard=hard)), 0.0, 1.0)
    m.add(nrm(thump(0.2, f0 * 0.55, f0 * 0.3, rng, decay=0.04, click=0.0, noise=0.4, nlp=f0 * 2.2)), 0.0, 0.5)
    return m.out()


@recipe("creak", variants=6, peak_db=-4.0, fade_out=0.2, max_len=[2.4, 1.5, 2.1, 1.7, 2.5, 1.9], tail_db=-60.0,
        loud=[-16.0, -16.0, -16.0, -17.0, -16.0, -17.0], limit_db=9.0, subtitle="Timbers creak", reach=32)
def creak(v, rng):
    """old oak working in a seaway, six ways. 1 the hull groaning long and low as a swell lifts her, a hesitant tick or two
    gathering into a rough groan that slides from 'oo' to 'ah' and lets go; 2 two squeaks of planks rubbing, over a rough
    groan under them and ending in a dull knock; 3 the mast working in its partners, very low and ragged, hemp fibres
    crackling in the strain and a block squeaking at the end; 4 a slow ratchet, a door of a creak that sticks, lets go and
    catches again; 5 a long moan, a pitch that slides up and down with the vowel of a voice, a rope squealing along a spar at
    its height; 6 the hull settling, three pops of timber letting go and a creaking sigh after them. Every one is made of
    stick-slip friction (a train of slips ringing the same oak modes), so they are one ship"""
    if v == 0:
        dur = 2.6
        t = tvec(dur)
        rate = path([(0, 6), (0.3, 12), (0.7, 45), (1.2, 85), (1.6, 105), (2.0, 70), (2.3, 30), (2.5, 8)], dur) \
            * spread(dur, rng, 5, 0.06)
        amp = bell(dur, 0.5, 1.3, 1.6) * 0.85 + 0.15 * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.3
        vowel = [(path([(0, 230), (1.2, 420), (2.6, 300)], dur), 4.0, 0.8),
                 (path([(0, 700), (1.3, 980), (2.6, 760)], dur), 5.0, 0.5)]
        y = creak_voice(dur, rng, rate, amp, hull_modes(110, 2600, peaks=(190, 430)), vowel, jitter=0.1, alt=0.15)
        thin = creak_voice(dur, rng, rate * 2.1 * spread(dur, rng, 3, 0.05), amp ** 1.5,
                           hull_modes(300, 2600, peaks=(800, 1500)),
                           jitter=0.14)
        boom = lp(brown(dur, rng), 150, 4) * amp ** 1.5
        y = nrm(y) + 0.3 * nrm(thin) + 0.12 * nrm(boom)
        return at_sea(lp(y, 6000), 0.06, seed=1710)
    if v == 1:
        dur = 1.55
        m = Mix(dur)
        for at, d, pts, g in [(0.02, 0.34, [(0, 540), (0.12, 690), (0.34, 610)], 1.0),
                              (0.46, 0.56, [(0, 600), (0.2, 880), (0.56, 740)], 0.85)]:
            a = bell(d, 0.45, 1.2, 1.5)
            m.add(squeal(d, rng, path(pts, d), a, harmonics=9, tilt=1.1, chatter=0.5, wobble=0.02, peak_hz=1900), at, g)
            m.add(squeal(d, rng, path(pts, d) * 1.498, a ** 1.5, harmonics=6, tilt=1.3, chatter=0.5, wobble=0.025,
                         peak_hz=2400), at + 0.01, 0.3 * g)
            rate = path([(0, 70), (d * 0.5, 115), (d, 80)], d)
            m.add(creak_voice(d, rng, rate, a * 0.8, hull_modes(peaks=(260, 700)), jitter=0.1), at, 0.55 * g)
        m.add(pop(rng, 245, 0.8, 0.7), 1.1, 0.4)
        return at_sea(lp(fit(m.out(), ns(dur)), 6000), 0.06, seed=1711)
    if v == 2:
        dur = 2.2
        t = tvec(dur)
        rate = path([(0, 4), (0.25, 9), (0.6, 22), (1.1, 38), (1.5, 30), (1.9, 14), (2.1, 5)], dur) * spread(dur, rng, 4, 0.1)
        amp = bell(dur, 0.5, 1.4, 1.5) * 0.9 + 0.1 * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.3
        vowel = [(path([(0, 140), (1.2, 215), (2.2, 160)], dur), 3.5, 1.0),
                 (path([(0, 420), (1.2, 560), (2.2, 460)], dur), 4.0, 0.5)]
        y = creak_voice(dur, rng, rate, amp, hull_modes(70, 1500, peaks=(105, 230, 470)), vowel, jitter=0.16, alt=0.3)
        fibres = grain_cloud(dur, rng, path([(0.1, 100), (0.9, 550), (1.5, 400), (2.1, 60)], dur), decay=0.0006, f_lo=1100,
                             f_hi=5200, size_var=0.9) * amp
        y = nrm(y) + 0.14 * nrm(fibres)
        m = Mix(dur)
        m.add(y, 0.0, 1.0)
        d = 0.3
        m.add(squeal(d, rng, path([(0, 1080), (0.12, 1180), (d, 1040)], d), bell(d, 0.4, 1.2, 1.6), harmonics=7, chatter=0.5,
                     wobble=0.02, peak_hz=2300), 1.75, 0.13)
        return at_sea(lp(fit(m.out(), ns(dur)), 6000), 0.06, seed=1712)
    if v == 3:
        dur = 1.7
        rate = path([(0, 11), (0.3, 20), (0.6, 15), (0.78, 3), (0.86, 22), (1.2, 40), (1.45, 24), (1.62, 6)], dur, 0.012) \
            * spread(dur, rng, 6, 0.12)
        amp = path([(0, 0.2), (0.15, 0.9), (0.62, 0.8), (0.78, 0.1), (0.86, 1.0), (1.3, 0.8), (1.65, 0.0)], dur, 0.02)
        vowel = [(path([(0, 520), (0.8, 700), (1.7, 560)], dur), 6.0, 0.9)]
        y = creak_voice(dur, rng, rate, amp, hull_modes(160, 2600, peaks=(280, 640)), vowel, jitter=0.2, alt=0.25, rub=0.3)
        thin = creak_voice(dur, rng, rate * 2.3 * spread(dur, rng, 3, 0.05), amp ** 1.5,
                           hull_modes(300, 2600, peaks=(900, 1700)),
                           jitter=0.2, rub=0.0)
        m = Mix(dur)
        m.add(nrm(y), 0.0, 1.0)
        m.add(nrm(thin), 0.0, 0.35)
        for at, g in [(0.0, 0.3), (0.8, 0.4), (1.52, 0.25)]:
            m.add(pop(rng, rng.uniform(300, 380), 0.5, 0.8), at, g)
        return at_sea(lp(fit(m.out(), ns(dur)), 6000), 0.06, seed=1713)
    if v == 4:
        dur = 2.6
        rate = path([(0, 45), (0.5, 70), (1.0, 100), (1.5, 140), (1.9, 150), (2.3, 110), (2.55, 70)], dur) \
            * spread(dur, rng, 4, 0.03)
        amp = bell(dur, 0.55, 1.4, 1.5)
        vowel = [(path([(0, 300), (1.4, 560), (2.6, 360)], dur), 7.0, 1.2),
                 (path([(0, 900), (1.4, 1500), (2.6, 1000)], dur), 8.0, 0.6),
                 (path([(0, 1700), (1.4, 2200), (2.6, 1800)], dur), 8.0, 0.25)]
        y = creak_voice(dur, rng, rate, amp, hull_modes(160, 2600, peaks=(300, 900)), vowel, jitter=0.07, alt=0.05, rub=0.2)
        m = Mix(dur)
        m.add(y, 0.0, 1.0)
        d = 0.7
        m.add(squeal(d, rng, path([(0, 960), (0.35, 1240), (d, 1100)], d), bell(d, 0.5, 1.3, 1.5), harmonics=8, chatter=0.5,
                     wobble=0.02, peak_hz=2400), 1.3, 0.2)
        return at_sea(lp(fit(m.out(), ns(dur)), 6000), 0.06, seed=1714)
    dur = 2.0
    m = Mix(dur)
    for at, f0, g, size in [(0.05, 190, 0.8, 1.0), (0.31, 255, 0.5, 0.8), (0.47, 330, 0.6, 0.7)]:
        m.add(pop(rng, f0 * rng.uniform(0.95, 1.05), size, 1.0), at, g)
    d = 1.25
    rate = path([(0, 8), (0.25, 36), (0.6, 60), (1.0, 30), (1.25, 8)], d)
    m.add(creak_voice(d, rng, rate, bell(d, 0.4, 1.3, 1.6) * 0.8, hull_modes(140, 2600, peaks=(220, 520)),
                      [(path([(0, 340), (1.2, 270)], d), 4.5, 0.7)], jitter=0.1), 0.6, 1.0)
    return at_sea(lp(fit(m.out(), ns(dur)), 6000), 0.06, seed=1715)
# creak>>


# <<sail
# =========================================================================== the sail
def whump(rng, size=1.0, hard=1.0, dur=0.8):
    """canvas coming taut with a deep 'whump': the air behind it pushed hard, a thump that falls in pitch (driven, so that its
    harmonics carry on a small speaker), a pop of the stiff cloth, a crack at the instant it is tight and the whole sheet
    ringing like a loose drum skin. size is how big the sail is"""
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    body = sine_sweep(dur, 125 / size ** 0.3, 50 / size ** 0.3, 5.0) * np.exp(-t / (0.16 * size ** 0.4)) \
        * np.clip(t / 0.004, 0, 1)
    m.add(saturate(body, 2.6), 0.0, 0.55)
    air = lp(white(dur, rng), 380) * np.exp(-t / (0.11 * size ** 0.4)) * np.clip(t / 0.003, 0, 1)
    m.add(air, 0.0, 0.5)
    pop_ = bp(white(dur, rng), 350, 2400) * np.exp(-t / 0.03) * np.clip(t / 0.0015, 0, 1)
    m.add(pop_, 0.0, 0.9 * hard)
    m.add(hann_burst(0.004, rng, 900, 6500), 0.0, 0.2 * hard)
    panel = strike(dur, rng, np.array([78, 121, 168, 224, 291]) * size ** -0.2, [0.3, 0.25, 0.2, 0.16, 0.12],
                   [1, 0.7, 0.5, 0.35, 0.25], lo=60, hi=900, ex=0.004)
    m.add(panel, 0.0, 0.5)
    return fit(m.out(), n)


def strain(dur, rng, power=1.0, rate=(30, 95)):
    """the yard, the sheets and the braces taking the load as the sail fills: tarred hemp stretching and crackling, the yard
    groaning in its parrel, the taut shrouds thrumming under it all. power is how hard it is pulled, rate the stick-slip
    rate of the groan at the start and at the height"""
    n = ns(dur)
    t = tvec(dur)
    m = Mix(dur)
    r = path([(0, rate[0]), (dur * 0.35, rate[1]), (dur * 0.8, rate[1] * 0.75), (dur, rate[0])], dur) \
        * spread(dur, rng, 5, 0.06)
    a = bell(dur, 0.3, 1.2, 1.7) * power
    m.add(creak_voice(dur, rng, r, a, hull_modes(160, 2400, peaks=(310, 700)),
                      [(path([(0, 420), (dur * 0.4, 700), (dur, 520)], dur), 5.0, 0.8)], jitter=0.1, alt=0.2), 0.0, 1.0)
    fib = grain_cloud(dur, rng, 700 * power * np.exp(-t / (dur * 0.4)) + 30, decay=0.0005, f_lo=1100, f_hi=5500,
                      size_var=0.9) * a
    m.add(nrm(fib), 0.0, 0.25)
    thrum = strike(min(dur, 0.9), rng, [88, 176, 265, 357, 450, 545], [0.55, 0.42, 0.32, 0.25, 0.2, 0.16],
                   [1, 0.7, 0.5, 0.35, 0.25, 0.18], lo=60, hi=1500, ex=0.006)
    m.add(nrm(thrum), 0.0, 0.3 * power)
    return fit(m.out(), n)


@recipe("sail_fill", variants=3, peak_db=-3.0, fade_out=0.25, max_len=[1.15, 1.2, 1.15], tail_db=-60.0, loud=-14.5,
        limit_db=6.0, subtitle="Sail fills with wind", reach=40)
def sail_fill(v, rng):
    """the wind catching the square sail. A ruffle of canvas rising as it starts to belly, then the whump: a deep thump as the
    cloth goes taut, the sheet ringing like a loose drum, the crack of the edge, and under and after it the yard groaning
    and the hemp of the sheets and braces stretching, crackling, humming under the load. 1 a hard fill, 2 a soft one that
    swells slowly into a long groan, 3 two fills, a small one and the big one on its heels"""
    dur = 1.3
    m = Mix(dur)
    plan = [dict(at=0.17, size=1.0, hard=1.0, ruffle=0.17, power=1.0),
            dict(at=0.3, size=1.15, hard=0.7, ruffle=0.3, power=0.9),
            dict(at=0.4, size=1.0, hard=1.0, ruffle=0.4, power=1.0)][v]
    at = plan["at"]
    rd = plan["ruffle"] + 0.05
    t = tvec(rd)
    env = (np.clip(t / plan["ruffle"], 0, 1) ** 2.2) * np.clip((rd - t) / 0.05, 0, 1)
    ruffle = swish(rd, rng, env, lo=380, hi=2300, flutter=0.6, rate=17) * (0.6 + 0.4 * smooth_random(rd, rng, 31)) \
        + 0.5 * nrm(crinkle(rd, rng, 60 + 450 * env, 800, 5000)) * env
    m.add(nrm(ruffle), 0.0, 0.5)
    if v == 2:  # the first, small fill
        m.add(whump(rng, 0.55, 0.6, 0.5), 0.0, 0.55)
    m.add(whump(rng, plan["size"], plan["hard"]), at, 1.0)
    m.add(nrm(strain(1.0 if v != 1 else 1.1, rng, plan["power"], rate=(28, 90) if v != 1 else (22, 70))), at + 0.02, 0.3)
    st = tvec(0.7)
    settle = swish(0.7, rng, np.exp(-st / 0.2) * np.clip(st / 0.05, 0, 1), lo=250, hi=2000, flutter=0.6, rate=12)
    settle = settle + 0.6 * nrm(crinkle(0.7, rng, 200 * np.exp(-st / 0.25), 700, 4500)) * np.exp(-st / 0.25)
    m.add(nrm(settle), at + 0.12, 0.22)
    return at_sea(fit(m.out(), ns(dur)), 0.07, seed=1720 + v)


def flap(rng, size=1.0, hard=1.0):
    """one flap of slack canvas in the wind: the cloth bellies out and reverses with a slap of heavy stuff on air (a dense
    mid-range 'fwap'), the edge whips over with a dull crack (canvas is no flag) and often a second small one as it rebounds,
    a low whump of air under it, the sheet ringing a moment and a ripple running out along it"""
    d = 0.3 * size ** 0.5
    t = tvec(d)
    m = Mix(d)
    slap = bp(white(d, rng), 260, 2300) * np.exp(-t / (0.03 * size ** 0.7)) * np.clip(t / 0.0012, 0, 1)
    m.add(nrm(slap), 0.0, 0.9)
    ct = tvec(0.03)
    m.add(nrm(bp(white(0.03, rng), 900, 5600) * np.exp(-ct / rng.uniform(0.0025, 0.005))), 0.0, 1.0 * hard)
    if rng.random() < 0.6:
        m.add(nrm(bp(white(0.03, rng), 700, 4200) * np.exp(-ct / 0.003)), rng.uniform(0.012, 0.04), 0.4 * hard)
    body = sine_sweep(d, 105 / size ** 0.3, 62 / size ** 0.3, 4.0) * np.exp(-t / (0.022 * size)) * np.clip(t / 0.003, 0, 1)
    m.add(saturate(body, 2.5), 0.0, 0.3)
    panel = strike(d, rng, np.array([150, 240, 350, 520, 760]) * size ** -0.2, [0.07, 0.055, 0.04, 0.03, 0.02],
                   [1, 0.8, 0.6, 0.4, 0.25], lo=120, hi=2200, ex=0.003)
    m.add(nrm(panel), 0.0, 0.3)
    rip = grain_cloud(d, rng, 900 * np.exp(-t / 0.06) + 20, decay=0.0006, f_lo=900, f_hi=5000, size_var=1.0)
    m.add(nrm(rip), 0.012, 0.3)
    return m.out()


@recipe("luff", variants=4, peak_db=-4.0, fade_out=0.12, max_len=[0.85, 0.95, 0.85, 0.7], tail_db=-60.0, loud=-16.5,
        limit_db=8.0, subtitle="Sail flaps", reach=32)
def luff(v, rng):
    """slack canvas flapping and cracking in the wind, a burst for the game to string together. 1 three flaps slowing to a
    stop and a ripple, 2 one big crack and a quick run of smaller flaps like a flag, 3 four uneven flaps and a block rattling
    on the yard, 4 two heavy slow flaps and the reef points pattering on the cloth"""
    dur = [0.9, 1.0, 0.9, 0.75][v]
    m = Mix(dur)
    if v == 0:
        for at, size, hard, g in [(0.0, 1.0, 1.0, 1.0), (0.22, 0.9, 0.9, 0.8), (0.42, 0.8, 0.8, 0.6)]:
            m.add(flap(rng, size, hard), at + rng.uniform(-0.01, 0.01), g)
        rd = 0.3
        env = bell(rd, 0.3, 1.2, 1.6) * (0.5 + 0.5 * smooth_random(rd, rng, 18))
        m.add(nrm(swish(rd, rng, env, 500, 4000, 0.5, 30) + 0.5 * crinkle(rd, rng, 500 * env + 20, 900, 5500) * env), 0.55, 0.3)
    elif v == 1:
        m.add(flap(rng, 1.3, 1.0), 0.0, 1.0)
        at = 0.2
        for k in range(8):
            m.add(flap(rng, 0.55 - 0.03 * k, 0.8 - 0.05 * k), at, 0.6 * 0.85 ** k)
            at += rng.uniform(0.065, 0.09)
    elif v == 2:
        for at, size, hard, g in [(0.0, 0.8, 0.7, 0.6), (0.15, 1.0, 0.9, 0.85), (0.34, 1.2, 1.0, 1.0), (0.48, 0.7, 0.7, 0.55)]:
            m.add(flap(rng, size, hard), at, g)
        for at in (0.6, 0.64, 0.7, 0.73):  # the block rattling on the yard
            m.add(nrm(oak(0.12, rng, rng.uniform(620, 780), t60=0.03, hard=1.0)), at, rng.uniform(0.25, 0.45))
    else:
        for at, size, hard, g in [(0.0, 1.5, 1.0, 1.0), (0.34, 1.35, 0.9, 0.8)]:
            m.add(flap(rng, size, hard), at, g)
        pt = 0.05
        while pt < 0.6:  # reef points: little knotted lines slapping the cloth, quick and light
            m.add(nrm(strike(0.03, rng, [rng.uniform(900, 1700), rng.uniform(2300, 3300)], [0.012, 0.008], [1, 0.5], lo=600,
                             hi=6000, ex=0.0007)), pt, rng.uniform(0.12, 0.3))
            pt += rng.uniform(0.012, 0.035)
    return at_sea(fit(m.out(), ns(dur)), 0.06, seed=1730 + v)
# sail>>


# <<hoist


# =========================================================================== rope and blocks
def pulls(dur, times, width, heights, floor=0.0):
    """the speed of a rope hauled hand over hand (0..1.2): an arch for each pull, `floor` the creep between them"""
    t = tvec(dur)
    y = np.full(len(t), float(floor))
    for c, h in zip(times, heights):
        u = np.clip((t - c) / width, 0, 1)
        y += h * np.sin(np.pi * u) ** 1.5
    return np.clip(y, 0, 1.2)


def beats(rate, rng, jitter=0.15):
    """moments (seconds) of events that come `rate` times a second (a curve, Hz per sample), a little unevenly"""
    idx = np.nonzero(np.diff(np.floor(np.cumsum(rate) / SR)) > 0)[0] + 1
    out = []
    for i in idx:
        per = SR / max(rate[i], 1.0)
        out.append(max(0.0, (i + jitter * per * rng.standard_normal()) / SR))
    return np.array(out)


def flump(rng, size=1.0):
    """a fold of canvas dropping and settling on itself: a soft whoosh of air, a muffled thud, a few creases letting go"""
    d = 0.3 * size ** 0.5
    t = tvec(d)
    m = Mix(d)
    m.add(nrm(lp(white(d, rng), 650) * np.exp(-t / (0.04 * size)) * np.clip(t / 0.006, 0, 1)), 0.0, 0.9)
    m.add(saturate(sine_sweep(d, 105, 64, 4.0) * np.exp(-t / (0.035 * size)) * np.clip(t / 0.004, 0, 1), 2.4), 0.0, 0.26)
    m.add(nrm(swish(d, rng, bell(d, 0.2, 1.2, 1.6), 300, 2600, 0.5, 30)), 0.0, 0.35)
    m.add(nrm(crinkle(d, rng, 500 * np.exp(-t / 0.08) + 10, 700, 4500)), 0.01, 0.3)
    return m.out()


@recipe("hoist", variants=3, peak_db=-4.0, fade_out=0.2, max_len=[1.6, 1.55, 1.55], tail_db=-60.0, loud=-15.0,
        limit_db=6.0, subtitle="Rope runs through blocks", reach=24)
def hoist(v, rng):
    """the sail hauled up or let down: rope running through wooden blocks, the sheaves squeaking and knocking round on their
    pins, canvas rustling and unrolling. 1 the sail hauled up hand over hand, four pulls and the canvas gathering, the yard
    knocking home at the masthead; 2 the sail let down, the rope running out fast, the sheave squealing down in pitch and
    the canvas unrolling in long crackling folds that drop and flump; 3 a steady haul over two blocks, the parrel's wooden
    trucks rattling up the mast and the rope made fast on its pin"""
    dur = 1.7
    t = tvec(dur)
    m = Bus(dur)

    def ticks(sp, f_lo, f_hi, rate, g, jit=0.15):
        for tk in beats(rate, rng, jit):
            i = min(len(sp) - 1, ns(tk))
            if sp[i] > 0.08 and tk < dur - 0.08:
                m.add(oak(0.07, rng, rng.uniform(f_lo, f_hi), t60=0.022, hard=1.0), tk, g * sp[i])

    if v == 0:
        sp = pulls(dur, [0.06, 0.42, 0.78, 1.12], 0.3, [0.75, 0.9, 0.9, 0.7])
        m.add(tame(rope_run(dur, rng, sp, lay=24.0, lo=400, hi=3400), 10), 0.0, 0.5)
        m.add(squeal(dur, rng, 760 + 520 * sp, sp ** 1.8 * 0.5, harmonics=6, tilt=1.3, chatter=0.55, wobble=0.02, peak_hz=2100),
              0.0, 0.5)
        m.add(squeal(dur, rng, 1330 + 400 * sp, sp ** 2.2 * 0.5, harmonics=5, tilt=1.4, chatter=0.6, wobble=0.025,
                     peak_hz=2600), 0.03, 0.2)
        ticks(sp, 650, 760, 6 + 18 * sp, 0.12)
        env = rise_to(t, 0.0, 1.3) * np.clip((dur - t) / 0.3, 0, 1)
        can = swish(dur, rng, env * 0.8, 300, 2600, 0.5, 25) + 0.7 * rn(crinkle(dur, rng, 60 + 380 * env, 500, 3800)) * env
        m.add(tame(can, 10), 0.0, 0.35)
        m.add(oak(0.25, rng, 300, t60=0.09), 1.38, 0.6)
        m.add(creak_voice(0.3, rng, path([(0, 40), (0.15, 70), (0.3, 30)], 0.3), bell(0.3, 0.4, 1.2, 1.6) * 0.6,
                          hull_modes(160, 2400, peaks=(380, 800)), jitter=0.1), 1.33, 0.25)
    elif v == 1:
        sp = pulls(dur, [0.05], 1.0, [1.0], 0.0)
        sp = np.minimum(sp, np.clip(t / 0.06, 0, 1)) * np.clip((1.1 - t) / 0.35, 0, 1) ** 0.8
        m.add(tame(rope_run(dur, rng, np.clip(sp * 1.05, 0, 1.2), lay=34.0, lo=400, hi=3800), 10), 0.0, 0.55)
        m.add(squeal(dur, rng, 700 + 650 * sp ** 0.7, sp ** 1.6 * 0.5, harmonics=6, tilt=1.3, chatter=0.5, wobble=0.02,
                     peak_hz=2200), 0.0, 0.5)
        ticks(sp, 620, 760, 8 + 24 * sp, 0.1)
        dens = path([(0.05, 120), (0.3, 950), (0.8, 600), (1.3, 140), (1.6, 20)], dur)
        env = bell(dur, 0.35, 1.2, 1.4) * 0.9
        can = swish(dur, rng, env, 280, 3000, 0.55, 28) + 0.9 * rn(crinkle(dur, rng, dens, 500, 3800)) * env
        m.add(tame(can, 10), 0.0, 0.5)
        for at, sz, g in [(0.48, 0.8, 0.5), (0.8, 1.0, 0.7), (1.17, 1.3, 1.0)]:
            m.add(flump(rng, sz), at, g)
    else:
        sp = pulls(dur, [0.1, 0.38, 0.66, 0.94, 1.2], 0.4, [0.45, 0.5, 0.5, 0.5, 0.4], 0.28) * np.clip((1.45 - t) / 0.1, 0, 1)
        m.add(tame(rope_run(dur, rng, sp, lay=28.0, lo=400, hi=3400), 10), 0.0, 0.5)
        for f0, g, ph in [(820, 0.45, 0.0), (1180, 0.3, 0.2)]:
            sw = np.clip(0.5 + 0.6 * np.sin(2 * np.pi * (t / 0.56) + ph * 2 * np.pi), 0, 1.2)
            m.add(squeal(dur, rng, f0 * (1 + 0.3 * sp), sp ** 1.6 * sw * 0.5, harmonics=6, tilt=1.3, chatter=0.55, wobble=0.02,
                         peak_hz=2300), 0.0, g)
        for tk in beats(34 * sp, rng, 0.45):  # the parrel's trucks rattling up the mast
            if tk < dur - 0.08:
                i = min(len(sp) - 1, ns(tk))
                m.add(oak(0.07, rng, rng.uniform(520, 980), t60=0.025, hard=1.0), tk, rng.uniform(0.07, 0.2) * sp[i])
        env = rise_to(t, 0.0, 0.8) * np.clip((dur - t) / 0.4, 0, 1)
        can = swish(dur, rng, env * 0.6, 300, 2600, 0.5, 25) + 0.6 * rn(crinkle(dur, rng, 40 + 260 * env, 500, 3800)) * env
        m.add(tame(can, 10), 0.0, 0.22)
        m.add(oak(0.18, rng, 410, t60=0.07), 1.45, 0.55)  # the rope made fast on its pin
        m.add(hann_burst(0.006, rng, 700, 4500), 1.45, 0.2)
        m.add(rope_run(0.16, rng, np.clip(1 - tvec(0.16) / 0.16, 0, 1) * 0.5, lay=18.0, lo=400, hi=3000), 1.46, 0.15)
    return at_sea(lp(fit(m.out(), ns(dur)), 5600), 0.06, seed=1740 + v)
# hoist>>


# <<loops
# =========================================================================== the three loops
def gurgle(rng, size=1.0):
    """air working out from under the planks in a few quick glugs, the bubbles a little different in size and the pitch drifting
    as the pocket empties"""
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
    return m.out()


def periodic_phase(ring, f):
    """the phase (radians) of an oscillator whose pitch is the curve f (Hz, over the three periods): the curve is nudged by a
    hair so that a whole number of cycles fits in a period, and then the oscillator ends each period exactly where it began"""
    f = np.asarray(f, dtype=float)
    c = np.sum(f[:ring.n]) / SR
    return 2 * np.pi * np.cumsum(f * (round(c) / c)) / SR


@recipe("wake", loop=8.0, rms_db=-24.0, ceiling_db=-7.0, reach=24)
def wake(ring, rng):
    """water rushing along the hull at speed: a broad wash of rushing water (five bands of it, each ruffled on its own, all of
    them swelling as the seas lift the bow), the hiss of the bow spilling over, a haze of tiny bubbles and the glug of bigger
    ones in the churn, a hollow rumble in the hull, and now and then a wave slapping the planks"""
    P = ring.P
    n1 = ring.n
    swell = 0.5 + 0.5 * np.tanh(1.3 * ring.curve(rng, 2, 4, 1.0))
    sw1 = ring.middle(swell)
    wash = np.zeros(ring.N)
    for lo, hi, g in [(220, 480, 0.5), (480, 1000, 0.9), (1000, 2000, 1.0), (2000, 4200, 0.8), (4200, 9000, 0.5)]:
        nz = bp(ring.noise(rng, -3.0), lo, hi, 2)
        mod = np.exp(0.38 * ring.curve(rng, 25, 120, 0.8) + 0.25 * ring.curve(rng, 1, 6, 1.0)) * (0.55 + 0.7 * swell)
        wash += g * rn(nz) * mod
    hiss = hp(ring.noise(rng, -1.5), 3800, 2) * (0.25 + 0.9 * swell ** 1.6) * np.exp(0.3 * ring.curve(rng, 20, 90, 0.8))
    fizz = ring.circ(bubbles(P, rng, 1500 * (0.45 + 0.9 * sw1), 0.45, 1.5, beta=2.0, level=1.0, weight=0.4, keep_tail=True))
    mid = ring.circ(bubbles(P, rng, 90 * (0.5 + 0.8 * sw1), 1.5, 6.0, beta=1.9, level=1.0, weight=0.5, keep_tail=True))
    big = ring.circ(bubbles(P, rng, 9 * (0.4 + 1.0 * sw1), 6.0, 20.0, beta=1.7, level=1.0, weight=0.6, keep_tail=True))
    rumble = eq(lp(ring.noise(rng, -6.0), 220, 4), "peak", 115, 1.3, 5) * (0.6 + 0.8 * swell)
    slaps = ring.new()
    for at in ring.times(rng, 0.5, shape=2.0):
        ring.place(slaps, nrm(hull_slap(rng, rng.uniform(0.7, 1.5), 1.0)), at, rng.uniform(0.5, 1.0))
    x = 1.0 * rn(wash) + 0.3 * rn(hiss) + 0.45 * rn(fizz) + 0.4 * rn(mid) + 0.35 * rn(big) + 0.2 * rn(rumble) + 0.55 * rn(slaps)
    return lp(eq(x, "highshelf", 3000, 0.7, -9.0), 9500, 2)  # (water is not white: the top of the rush is let down)


@recipe("lap", loop=8.4, rms_db=-28.0, ceiling_db=-8.0)
def lap(ring, rng):
    """small waves lapping and slapping a wooden hull at rest: a soft swash under everything, gentle laps (a wet smack, the
    hollow knock of the air between plank and water, the plank answering, a few bubbles, the water running back) that come
    thicker and softer as the swell passes, air glugging out from under the planks, and now and then a bigger wave giving the
    hull a slow, hollow knock"""
    P = ring.P
    swell = 0.5 + 0.5 * np.tanh(1.2 * ring.curve(rng, 2, 4, 1.0))
    sw1 = ring.middle(swell)
    swash = bp(ring.noise(rng, -3.0), 180, 2200, 2) * (0.3 + 0.7 * swell) * np.exp(0.3 * ring.curve(rng, 8, 40, 1.0))
    laps = ring.new()
    for at in ring.times(rng, 2.2, shape=2.5):
        w = sw1[min(ring.n - 1, int(at * SR))]
        ring.place(laps, hull_slap(rng, rng.uniform(0.35, 0.8) * (0.7 + 0.5 * w), 0.7, cav=0.4, plank=2.2), at,
                   (0.35 + 0.65 * w) * np.exp(0.2 * rng.standard_normal()))
    knocks = ring.new()
    for at in ring.times(rng, 0.28, shape=2.0):
        kn = Mix(0.9)
        kn.add(nrm(knock(rng, rng.uniform(0.8, 1.0), rng.uniform(1.3, 1.8), 0.3, 0.5, 0.7)), 0.0, 1.0)
        kn.add(nrm(hull_slap(rng, rng.uniform(1.1, 1.5), 0.8, cav=0.3, plank=1.5)), 0.0, 0.5)
        ring.place(knocks, kn.out(), at, rng.uniform(0.6, 1.0))
    gur = ring.new()
    for at in ring.times(rng, 0.85, shape=2.0):
        ring.place(gur, nrm(gurgle(rng)), at, rng.uniform(0.3, 1.0))
    drips = ring.new()
    for at in ring.times(rng, 1.1, shape=1.5):
        ring.place(drips, drip(rng), at, rng.uniform(0.2, 0.8))
    x = 0.7 * rn(swash) + 1.0 * rn(laps) + 0.5 * rn(knocks) + 0.28 * rn(gur) + 0.25 * rn(drips)
    return lp(x, 9500, 2)


@recipe("rigging", loop=7.6, rms_db=-27.0, ceiling_db=-8.0, reach=24)
def rigging(ring, rng):
    """wind in the rigging: a low rush of wind, four taut lines whistling in it (a thin moan that slides up and down as the
    gust strengthens, breathy, with its octave faint above), the lines themselves thrumming in the gusts, and in the strongest
    a flutter of loose ends and the odd tick of a rope against the mast"""
    P = ring.P
    gust = 0.5 + 0.5 * np.tanh(1.4 * ring.curve(rng, 1, 3, 1.2))
    wind = bp(ring.noise(rng, -4.0), 140, 1500, 2) * (0.35 + 0.65 * gust) ** 1.3 * np.exp(0.25 * ring.curve(rng, 6, 30, 1.0))
    air = bp(ring.noise(rng, -2.0), 1800, 6000, 2) * gust ** 2.0 * np.exp(0.3 * ring.curve(rng, 20, 80, 0.8))
    whistles = np.zeros(ring.N)
    for f0, g, lag in [(212, 0.9, 0.0), (330, 0.8, 0.7), (487, 0.55, 1.5), (655, 0.4, 2.1)]:
        gu = 0.5 + 0.5 * np.tanh(1.4 * np.roll(ring.curve(rng, 1, 3, 1.2), int(lag * SR)))
        f = f0 * (1 + 0.1 * (gu - 0.5) + 0.012 * ring.curve(rng, 6, 28, 1.0))
        ph = periodic_phase(ring, f)
        amp = np.clip(gu - 0.18, 0, 1) ** 1.6 * (0.55 + 0.45 * (0.5 + 0.5 * np.tanh(ring.curve(rng, 4, 20, 1.0))))
        tone = np.sin(ph + rng.uniform(0, 6.28)) + 0.22 * np.sin(2 * ph + rng.uniform(0, 6.28))
        breath = bp(ring.noise(rng, 0.0), f0 * 0.93, f0 * 1.07, 2)
        whistles += g * amp * (rn(tone) + 0.5 * rn(breath))
    thrum = np.zeros(ring.N)
    for f1, g in [(97.0, 1.0), (136.0, 0.8)]:
        ex = ring.noise(rng, -2.0) * (0.2 + 0.8 * gust ** 1.5) * np.exp(0.4 * ring.curve(rng, 10, 50, 1.0))
        thrum += g * resonators(ex, f1 * np.arange(1, 8) * 1.0005 ** np.arange(7), [0.9, 0.75, 0.6, 0.5, 0.4, 0.3, 0.25],
                                [1, 0.8, 0.6, 0.45, 0.3, 0.2, 0.12])
    flutter = ring.new()
    wins = ring.bumps([P * 0.28, P * 0.72], [1.0, 0.7], 0.3, 0.5)
    ph = periodic_phase(ring, 14.0 * (1 + 0.25 * ring.curve(rng, 4, 20, 1.0)))
    flutter = bp(ring.noise(rng, -1.0), 1500, 5200, 2) * wins * (0.35 + 0.65 * (0.5 + 0.5 * np.sin(ph)) ** 2)
    ticks = ring.new()
    for at in (P * 0.3, P * 0.33, P * 0.74):
        ring.place(ticks, oak(0.12, rng, rng.uniform(380, 460), t60=0.05), at, rng.uniform(0.4, 0.8))
    x = 1.0 * rn(wind) + 0.3 * rn(air) + 1.1 * rn(whistles) + 0.32 * rn(thrum) + 0.28 * rn(flutter) + 0.1 * rn(ticks)
    return lp(x, 8000, 2)
# loops>>


# <<anchor
# =========================================================================== iron, rope and bronze
# The cog's bell: cast bronze, struck by an iron clapper. The table gives each mode as (multiple of the strike note, level in
# dB, T60 in seconds, the beat of its doublet in Hz): the hum and prime an octave and two down, the tierce between them and
# the strike note (the minor third that makes a bell sound like a bell), then the quint, the nominal itself, and the uppers,
# which crowd together, grow inharmonic and die quickly. No casting is round, so every mode is a pair of sines a little apart.
BRONZE = [
    (0.25, -17.0, 4.6, 0.55), (0.5, -9.0, 4.2, 0.8), (0.595, -7.0, 3.7, 1.1), (0.75, -15.0, 2.6, 1.3), (1.0, 0.0, 3.6, 1.2),
    (1.19, -18.0, 2.1, 1.7), (1.5, -7.0, 3.0, 1.6), (2.0, -9.0, 2.3, 2.0), (2.35, -15.0, 1.5, 2.3), (2.62, -12.0, 1.35, 2.5),
    (3.0, -14.0, 1.15, 2.8), (3.75, -16.0, 0.9, 3.2), (4.5, -18.0, 0.7, 3.6), (5.4, -20.0, 0.5, 4.0), (6.6, -22.0, 0.4, 4.4),
    (8.0, -25.0, 0.3, 4.8),
]


def bell_modes(f_nom, rng, table=BRONZE, detune=0.0025):
    """the modes of the bell as arrays: frequencies, levels, T60s, beats, and the phase each reaches the listener with
    (fixed for the bell, so a second blow adds to the first the way a second blow does)"""
    r = np.array([row[0] for row in table])
    f = f_nom * r * (1 + detune * rng.standard_normal(len(r)) * (r != 1.0))
    return dict(f=f, a=db(np.array([row[1] for row in table])), t60=np.array([row[2] for row in table]),
                beat=np.array([row[3] for row in table]) * rng.uniform(0.85, 1.2, len(r)),
                ph=rng.uniform(0, 2 * np.pi, len(r)), rho=rng.uniform(0.25, 0.55, len(r)))


def bell_blow(dur, modes, hard=1.0, fc=4200.0, build=0.0009):
    """one blow on the bell: every mode started from rest at the strike and dying at its own rate. fc is where the clapper's
    contact time rolls the blow off (a harder blow has a higher one); build is the few thousandths of a second the whole bell
    takes to reach the listener in full"""
    n = ns(dur)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for f, a, t60, beat, ph, rho in zip(modes["f"], modes["a"], modes["t60"], modes["beat"], modes["ph"], modes["rho"]):
        if f > SR * 0.45:
            continue
        L = min(n, ns(1.1 * t60))
        tt = t[:L]
        g = hard * a / np.sqrt(1.0 + (f / (fc * hard ** 0.5)) ** 2)
        dec = np.exp(-6.9078 * tt / t60)
        out[:L] += g * dec * (np.sin(2 * np.pi * (f - beat / 2) * tt + ph) + rho * np.sin(2 * np.pi * (f + beat / 2) * tt + ph))
    k = ns(build)
    out[:k] *= np.sin(np.linspace(0, np.pi / 2, k)) ** 2
    return out


def clapper(rng, hard=1.0):
    """the instant of the blow itself, apart from the ringing: iron meeting bronze (a bright 'tik'), and the dull knock of the
    bell and its frame taking it"""
    t = tvec(0.04)
    tik = bp(white(0.04, rng), 2600, 9500) * np.exp(-t / 0.0014) * np.clip(t / 0.0002, 0, 1)
    knock = thump(0.06, 330, 210, rng, decay=0.01, click=0.0, noise=0.3, nlp=900)
    return nrm(tik), nrm(knock)


@recipe("bell", variants=2, peak_db=-3.0, fade_out=0.8, max_len=3.1, tail_db=-62.0, loud=-12.0, limit_db=3.0,
        subtitle="Ship's bell rings", reach=64)
def ship_bell(v, rng):
    """the ship's bell, bronze, struck twice with an iron clapper ('ding-ding'): bright, the strike note a high A (880 Hz) over
    the hum and prime of a bell, the uppers clanging at the blow and falling away quickly, the low modes ringing on and on, the
    two beating against each other as they die. The second blow lands on a bell still ringing from the first. The two files are
    the same bell struck a little differently: the second blow a touch softer, then a touch later and harder"""
    dur = 3.4
    modes = bell_modes(880.0, rng_for("ship", "bell"))
    gap, g2, h2 = [(0.38, 0.9, 0.95), (0.46, 0.8, 1.05)][v]
    m = Mix(dur)
    for at, g, hard in [(0.0, 1.0, 1.0), (gap + rng.uniform(-0.008, 0.008), g2, h2)]:
        tik, knock = clapper(rng, hard)
        m.add(bell_blow(dur - at, modes, hard=g * hard), at, 1.0)
        m.add(tik, at, 0.1 * g)
        m.add(knock, at, 0.05 * g)
    y = lp(m.out(), 11000)
    return at_sea(y, 0.07, seed=1760 + v)


def clank(dur, rng, f0s, gains, t60=0.45, wood=1.0, hard=1.0, low=1.0):
    """heavy iron against oak: the iron ringing (the bars of the anchor, its arms and its ring, each a pair of modes
    beating, the upper ones dying first), the oak taking the blow with a dull knock and a thump, the contact itself"""
    n = ns(dur)
    m = Mix(dur)
    m.add(nrm(iron_ring(dur, rng, f0s, t60=t60, gains=gains)), 0.0, 0.9)
    m.add(nrm(oak(min(dur, 0.4), rng, 150, t60=0.1, hard=hard)), 0.0, 0.7 * wood)
    m.add(saturate(thump(min(dur, 0.3), 120, 62, rng, decay=0.05, click=0.0, noise=0.4, nlp=400), 2.0), 0.0, 0.5 * wood * low)
    m.add(nrm(hann_burst(0.003, rng, 1500, 8000)), 0.0, 0.35 * hard)
    return fit(m.out(), n)


@recipe("anchor_drop", peak_db=-3.0, fade_out=0.5, max_len=2.7, tail_db=-62.0, loud=-12.0, limit_db=6.0,
        subtitle="Anchor splashes down", reach=48)
def anchor_drop(v, rng):
    """the anchor let go. The stopper knocked free of the cathead, the anchor sliding off and the rope beginning to run out
    through the hawse (hemp tearing over oak, faster and faster, a hollow roar in the pipe of the hawse), the iron falling
    down the ship's side and clanking against the planks, a heavy splash, and the rope still running out after it, slower as the
    anchor sinks, a string of big bubbles coming up, and at the last the rope fetching up taut with a creak and the hull
    answering"""
    dur = 2.8
    t = tvec(dur)
    m = Bus(dur)
    m.add(oak(0.2, rng, 410, t60=0.07, hard=1.0), 0.0, 0.5)
    m.add(hann_burst(0.004, rng, 800, 5000), 0.0, 0.25)
    sp = np.clip((t - 0.06) / 0.4, 0, 1) ** 1.3
    sp = np.where(t < 0.9, sp * (0.55 + 0.45 * np.clip((t - 0.06) / 0.8, 0, 1)), 0.0)
    after = np.where(t >= 0.9, 1.0 * np.exp(-(t - 0.9) / 0.65) * np.clip((2.1 - t) / 0.15, 0, 1), 0.0)
    sp = np.clip(sp + after * 0.9, 0, 1.15)
    m.add(tame(rope_run(dur, rng, sp, lay=46.0, lo=380, hi=4200, fibres=1.3), 10), 0.0, 0.8)
    pipe = resonators(grain_cloud(dur, rng, 500 * sp + 5, decay=0.0007, f_lo=100, f_hi=900, size_var=0.6) * sp,
                      [165, 320, 540, 820], [0.06, 0.05, 0.04, 0.03], [1, 0.8, 0.5, 0.3])
    m.add(tame(pipe, 10), 0.0, 0.5)
    m.add(grain_cloud(0.4, rng, path([(0, 30), (0.2, 110), (0.4, 30)], 0.4), decay=0.001, f_lo=200, f_hi=2600, size_var=0.8),
          0.12, 0.3)
    for at, f0s, g in [(0.46, [330, 640, 1180], 1.0), (0.55, [410, 790, 1400], 0.55), (0.64, [290, 560, 1030], 0.35)]:
        m.add(clank(0.9, rng, f0s, [1.0, 0.8, 0.6], t60=0.45 * g ** 0.3, wood=g, hard=g), at, 1.1 * g)
    m.add(tame(grain_cloud(0.3, rng, 400, decay=0.0008, f_lo=900, f_hi=5500, size_var=0.8) * bell(0.3, 0.3, 1.2, 1.5), 10),
          0.5, 0.25)
    m.add(splash(1.7, rng, size=1.4, hard=1.0), 0.9, 1.15)
    bt = tvec(1.2)
    m.add(bubbles(1.2, rng, 26 * np.exp(-bt / 0.45) + 1.5, 5.0, 16.0, beta=1.6, level=1.0, weight=0.6, rise=0.4), 1.05, 0.3)
    m.add(creak_voice(0.5, rng, path([(0, 30), (0.2, 70), (0.5, 40)], 0.5), bell(0.5, 0.45, 1.2, 1.5),
                      hull_modes(130, 2400, peaks=(250, 600)), [(path([(0, 350), (0.5, 520)], 0.5), 5.0, 0.8)], jitter=0.1),
          2.05, 0.4)
    m.add(oak(0.25, rng, 300, t60=0.08, hard=1.0), 2.04, 0.35)
    m.add(hull_slap(rng, 1.0, 0.8), 2.2, 0.25)
    return at_sea(fit(m.out(), ns(dur)), 0.06, seed=1770)


def pawl(rng, load=1.0):
    """a capstan's pawl dropping into the next notch of the pawl-rim: a small bright tick of iron on iron as it rides up, and
    the clack as it falls home (the iron ringing, the heavy oak head of the capstan answering with a hollow knock, the drum's
    weight shifting with a low thud)"""
    m = Mix(0.3)
    m.add(nrm(hann_burst(0.002, rng, 2500, 9000)), 0.0, 0.3)
    m.add(nrm(iron_ring(0.12, rng, [1650 * rng.uniform(0.96, 1.04), 2480], t60=0.045, gains=[1.0, 0.6])), 0.004, 0.8)
    m.add(nrm(oak(0.22, rng, 290 * rng.uniform(0.95, 1.05), t60=0.08, hard=1.0, lo=260)), 0.005, 0.9 * load)
    m.add(saturate(thump(0.1, 200, 120, rng, decay=0.02, click=0.0, noise=0.4, nlp=600), 2.0), 0.005, 0.1 * load)
    return m.out()


@recipe("anchor_raise", peak_db=-3.0, fade_out=0.45, max_len=2.8, tail_db=-62.0, loud=-14.0, limit_db=6.0,
        subtitle="Anchor is weighed", reach=32)
def anchor_raise(v, rng):
    """weighing anchor: the capstan's pawls clicking round (eight clicks, slow and heavy at first under the load of the anchor,
    quickening as it comes free), a heave of wet rope between each, rasping in over the oak with a squelch and the water
    streaming off it, the capstan and the cathead groaning under the strain, the anchor breaking surface in a rush of water,
    and clunking home against the cathead, iron on oak, with the rope falling slack, three last quick clicks and the
    drips"""
    dur = 2.9
    t = tvec(dur)
    m = Bus(dur)
    click_t = [0.02, 0.34, 0.64, 0.91, 1.16, 1.39, 1.59, 1.77]
    sp = np.zeros(len(t))
    for k, ct in enumerate(click_t):
        w = 0.28 if k < 4 else 0.2
        u = np.clip((t - (ct - w * 0.6)) / w, 0, 1)
        sp += (0.9 - 0.04 * k) * np.sin(np.pi * u) ** 1.5
    sp = np.clip(sp, 0, 1.1) * np.clip((1.95 - t) / 0.15, 0, 1)
    m.add(tame(rope_run(dur, rng, sp, lay=22.0, lo=400, hi=3400, wet=0.8, fibres=0.8), 10), 0.0, 0.7)
    sq = swish(dur, rng, np.clip(sp, 0, 1) ** 1.3, 450, 2600, 0.6, 30)
    m.add(tame(sq, 10), 0.0, 0.35)
    for ct in click_t:
        m.add(pawl(rng, 1.0), ct + rng.uniform(-0.01, 0.01), 1.0 * rng.uniform(0.85, 1.0))
    wet = grain_cloud(dur, rng, (60 + 260 * sp) * np.clip((2.3 - t) / 0.4, 0, 1), decay=0.0005, f_lo=1500, f_hi=7000,
                      size_var=0.9)
    m.add(tame(wet, 10) * np.clip((2.3 - t) / 0.4, 0, 1), 0.0, 0.25)
    for tt in np.sort(rng.uniform(0.05, 2.5, 26)):
        m.add(drip(rng), tt, rng.uniform(0.04, 0.12))
    cd = 1.8
    m.add(creak_voice(cd, rng, path([(0, 14), (0.3, 24), (0.9, 38), (1.4, 44), (1.8, 20)], cd),
                      bell(cd, 0.6, 1.3, 1.5) * 0.9, hull_modes(100, 2000, peaks=(180, 420)),
                      [(path([(0, 260), (1.8, 420)], cd), 4.0, 0.8)], jitter=0.12, alt=0.2), 0.05, 0.5)
    gd = 0.5
    gt = tvec(gd)
    gush = swish(gd, rng, (1 - np.exp(-gt / 0.03)) * np.exp(-gt / 0.18), 350, 4500, 0.4, 35)
    m.add(gush + 0.5 * rn(bubbles(gd, rng, 220 * np.exp(-gt / 0.2) + 10, 1.5, 9.0, beta=1.8, level=1.0)), 1.72, 0.55)
    at = 2.0
    m.add(clank(0.9, rng, [310, 600, 1120], [1.0, 0.8, 0.55], t60=0.5, wood=1.0, hard=1.0, low=0.4), at, 1.2)
    m.add(oak(0.4, rng, 230, t60=0.12, hard=0.7), at, 0.4)
    for k, ct in enumerate([at + 0.08, at + 0.14, at + 0.2]):
        m.add(pawl(rng, 0.7), ct, 0.5 * 0.8 ** k)
    m.add(tame(rope_run(0.3, rng, np.exp(-tvec(0.3) / 0.08) * 0.5, lay=14.0, lo=350, hi=2800, wet=0.8), 10), at + 0.05, 0.3)
    return at_sea(fit(m.out(), ns(dur)), 0.06, seed=1771)
# anchor>>


# <<ground
# =========================================================================== aground, alongside, afloat, struck down
def sand_bed(dur, rng, load, lo=140.0, hi=2500.0, grain=1.0):
    """sand and shingle sliding under a keel (load: a curve from 0 to 1, how hard she is pressed): a hushed, rough rasp
    (broadband noise whose level ripples as the grains rearrange, sixty or seventy times a second) and the grains
    themselves, ticking"""
    band = bp(colored(dur, rng, -2.0), lo, hi)
    ripple = 0.5 + 0.5 * smooth_random(dur, rng, 65) ** 1.2
    ticks = grain_cloud(dur, rng, (500 + 2600 * load) * grain, decay=0.0006, f_lo=1400, f_hi=7000, size_var=0.7)
    return (rn(band) * ripple + 0.35 * rn(ticks)) * load


def knock(rng, f=1.0, size=1.0, hard=1.0, dur=0.55, hollow=1.0):
    """a heavy blow on a hollow oak hull from outside (a quay, another keel, a stone): the contact, a short dull burst; the
    planking answering with its knock (the modes of heavy oak); the hull as a drum round a great volume of air, a deep 'oom'
    that sags a little in pitch as it dies, driven so that its harmonics carry on a phone's speaker; and the air in the hull
    ringing with its own few modes. f moves the pitch of the whole, size how big and slow the hull is, hard how far up the
    blow reaches"""
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(hann_burst(0.004, rng, 450, 2800 + 2800 * hard), 0.0, 0.17 * hard)
    m.add(oak(min(dur, 0.4), rng, 250 * f / size ** 0.2, t60=0.13 * size ** 0.5, hard=hard, lo=170), 0.0, 1.0)
    body = sine_sweep(dur, 132 * f / size ** 0.25, 86 * f / size ** 0.25, 5.0) * np.exp(-t / (0.06 * size ** 0.5)) \
        * np.clip(t / 0.003, 0, 1)
    m.add(saturate(body, 3.0), 0.0, 0.5 * hollow)
    air = resonators(fit(hann_burst(0.006, rng, 150, 1700), n), np.array([176, 331, 497, 721, 1010]) * f / size ** 0.2,
                     np.array([0.16, 0.12, 0.09, 0.065, 0.045]) * size ** 0.5, [1.0, 0.7, 0.45, 0.28, 0.16])
    m.add(air, 0.0, 1.1 * hollow)
    return m.out()


@recipe("scrape", variants=3, peak_db=-3.0, fade_out=0.22, max_len=[1.3, 1.25, 1.3], tail_db=-60.0, loud=-16.0, limit_db=8.0,
        subtitle="Hull scrapes the bottom", reach=32)
def scrape(v, rng):
    """the hull grinding over the bottom, aground, three beds. 1 sand: she takes the ground with a hush and drags, a rough
    rasp of grains under a juddering rumble as the keel rides the ripples of the sand, slowing till she settles with a sag and
    a soft knock; 2 rock: the keel biting on stone, ragged bursts of grinding that catch and let go, a grating tone as it
    rasps along an edge, hard knocks of the hull on the stone and a splinter cracking; 3 shingle: a coarse heavy crunch of
    gravel under the planks, and then she stops short, a heavy thud, the whole ship shuddering and the timbers groaning
    under the strain"""
    dur = 1.5
    t = tvec(dur)
    m = Bus(dur)
    if v == 0:
        load = rise_to(t, 0.0, 0.28) * np.clip((1.02 - t) / 0.3, 0, 1) ** 0.8 * (0.85 + 0.15 * smooth_random(dur, rng, 5))
        m.add(tame(sand_bed(dur, rng, load), 10), 0.0, 1.0)
        rate = 9 + 9 * load * (0.8 + 0.4 * smooth_random(dur, rng, 4))
        m.add(creak_voice(dur, rng, rate, load ** 1.2, hull_modes(75, 1300, peaks=(125, 260)), jitter=0.25, rub=0.0,
                          kick=0.0014, bright=1500, unsteady=0.4), 0.0, 0.7)
        m.add(lp(brown(dur, rng), 170, 4) * load ** 1.5, 0.0, 0.25)
        m.add(knock(rng, 0.85, 1.5, 0.3, 0.6), 0.98, 0.4)
        m.add(creak_voice(0.35, rng, path([(0, 12), (0.15, 30), (0.35, 10)], 0.35), bell(0.35, 0.4, 1.2, 1.5),
                          hull_modes(110, 1800, peaks=(190, 430)),
                          [(path([(0, 240), (0.35, 330)], 0.35), 4.0, 0.8)], jitter=0.12), 0.92, 0.2)
    elif v == 1:
        load = np.clip(rise_to(t, 0.0, 0.1) * (0.35 + 0.65 * smooth_random(dur, rng, 6) ** 0.8), 0, 1) \
            * np.clip((1.12 - t) / 0.25, 0, 1)
        bed = bp(colored(dur, rng, -1.5), 160, 3800) * (0.3 + 0.7 * smooth_random(dur, rng, 26) ** 1.6)
        m.add(tame(rn(bed) * load, 10), 0.0, 0.8)
        m.add(creak_voice(dur, rng, (35 + 45 * smooth_random(dur, rng, 8)) * np.ones(len(t)), load ** 1.1,
                          hull_modes(120, 3000, peaks=(260, 700)),
                          [(path([(0, 520), (0.6, 900), (1.4, 640)], dur), 5.0, 0.9)], jitter=0.3, alt=0.3, rub=0.35,
                          kick=0.0008, bright=5200, unsteady=0.5), 0.0, 0.8)
        m.add(tame(grain_cloud(dur, rng, 900 * load + 20, decay=0.0014, f_lo=500, f_hi=4500, size_var=1.1), 10) * load,
              0.0, 0.35)
        sq = squeal(0.55, rng, path([(0, 1380), (0.2, 1760), (0.55, 1500)], 0.55), bell(0.55, 0.4, 1.2, 1.5) * 0.7,
                    harmonics=6, tilt=1.1, chatter=0.7, wobble=0.03, grit=0.25, peak_hz=2400)
        m.add(sq, 0.32, 0.14)
        for at, f, g, hard in [(0.04, 0.95, 0.9, 1.0), (0.31, 1.15, 0.55, 0.9), (0.52, 0.9, 0.7, 1.0), (0.93, 1.05, 0.6, 0.8)]:
            m.add(knock(rng, f, 0.9, hard, 0.4, 0.5), at, g)
        m.add(pop(rng, 340, 0.5, 1.0), 0.74, 0.3)
    else:
        load = rise_to(t, 0.0, 0.18) * np.clip((0.74 - t) / 0.05, 0, 1) \
            + 0.18 * np.exp(-np.maximum(t - 0.74, 0) / 0.2) * (t > 0.74)
        load = np.clip(load * (0.75 + 0.25 * smooth_random(dur, rng, 7)), 0, 1)
        gr = grain_cloud(dur, rng, 1900 * load + 15, decay=0.0013, f_lo=320, f_hi=3600, size_var=1.0, kernels=7)
        m.add(tame(gr, 10) * (0.35 + 0.65 * load), 0.0, 1.0)
        m.add(tame(rn(bp(colored(dur, rng, -2.5), 90, 1800)) * load, 10), 0.0, 0.6)
        m.add(creak_voice(dur, rng, (13 + 14 * load) * np.ones(len(t)), load, hull_modes(70, 1000, peaks=(115, 240)),
                          jitter=0.3, rub=0.0, kick=0.0016, bright=1300), 0.0, 0.5)
        m.add(knock(rng, 0.9, 1.5, 1.0, 0.6), 0.75, 1.5)
        m.add(saturate(thump(0.4, 100, 52, rng, decay=0.1, click=0.0, noise=0.5, nlp=380), 2.2), 0.75, 0.25)
        m.add(tame(grain_cloud(0.6, rng, 800 * np.exp(-tvec(0.6) / 0.15), decay=0.0012, f_lo=500, f_hi=4200, size_var=1.0), 10)
              * 0.7, 0.78, 0.45)
        m.add(creak_voice(0.6, rng, path([(0, 14), (0.18, 38), (0.35, 52), (0.6, 12)], 0.6), bell(0.6, 0.4, 1.2, 1.5),
                          hull_modes(110, 2200, peaks=(200, 440)), [(path([(0, 260), (0.6, 400)], 0.6), 4.0, 0.9)],
                          jitter=0.12, alt=0.15), 0.8, 0.75)
    return at_sea(fit(m.out(), ns(dur)), 0.05, seed=1780 + v)


@recipe("bump", variants=3, peak_db=-3.0, fade_out=0.12, max_len=[0.55, 0.5, 0.6], tail_db=-60.0, loud=-16.0, limit_db=11.0,
        subtitle="Hull knocks", reach=32)
def bump(v, rng):
    """the hollow, heavy knock of the cog's oak against something hard (a quay, a stone, another hull). 1 a dull, deep thump
    as she nudges the pier, 2 a sharper knock, the planks answering high and quick, 3 a knock and the rebound, a second softer
    one on its heels as she bounces off and comes back. All of them a thumped oak hull with a great hollow of air in it (a
    deep 'oom' that sags as it dies, driven so that its harmonics carry on a phone), the planks' knock over it, and the water
    slapping a little at the hull afterwards"""
    dur = 0.65
    m = Bus(dur)
    if v == 0:
        m.add(knock(rng, 0.92, 1.25, 0.55, 0.6, 1.0), 0.0, 1.0)
        m.add(hull_slap(rng, 0.45, 0.8), 0.13, 0.08)
    elif v == 1:
        m.add(knock(rng, 1.1, 0.95, 0.9, 0.5, 1.1), 0.0, 1.0)
        m.add(pop(rng, 330, 0.5, 1.0), 0.03, 0.06)
        m.add(hull_slap(rng, 0.4, 0.8), 0.1, 0.07)
    else:
        m.add(knock(rng, 1.0, 1.1, 0.85, 0.5, 1.0), 0.0, 1.0)
        m.add(knock(rng, 1.12, 0.8, 0.6, 0.4, 0.8), 0.19, 0.6)
        m.add(hull_slap(rng, 0.4, 0.8), 0.32, 0.06)
    return at_sea(fit(m.out(), ns(dur)), 0.05, seed=1790 + v)


@recipe("launch", peak_db=-3.0, fade_out=0.4, max_len=2.15, tail_db=-62.0, loud=-12.0, limit_db=6.0,
        subtitle="Ship splashes down", reach=64)
def launch(v, rng):
    """the cog put into the water: the great flat slap of her hull's weight on the sea, a long deep rush of displaced water
    and the big bloops of the air it traps, the sea thrown up round her falling back as spray, the water slapping back off
    the planks in waves that grow smaller, and the timbers taking her weight: a groan that rises as she settles, a pop or two
    of oak giving, and then she bobs, the lapping dying away along her side"""
    dur = 2.4
    t = tvec(dur)
    m = Bus(dur)
    m.add(splash(2.0, rng, size=3.4, hard=0.95), 0.0, 1.0)
    wd = 1.6
    wt = tvec(wd)
    m.add(tvf(pink(wd, rng), "bp", 230 * 3.0 ** (1 - np.exp(-wt / 0.35)), 0.7) * (1 - np.exp(-wt / 0.04)) * np.exp(-wt / 0.45),
          0.02, 0.6)
    m.add(saturate(sine_sweep(0.7, 64, 36, 3.0) * np.exp(-tvec(0.7) / 0.18), 2.0), 0.0, 0.55)
    for at, size, g in [(0.32, 1.7, 0.7), (0.62, 1.4, 0.55), (0.98, 1.2, 0.4), (1.45, 0.9, 0.25), (1.85, 0.7, 0.15)]:
        m.add(hull_slap(rng, size, 1.0), at + rng.uniform(-0.02, 0.02), g)
    cd = 1.6
    m.add(creak_voice(cd, rng, path([(0, 8), (0.3, 26), (0.7, 48), (1.1, 34), (1.6, 8)], cd) * spread(cd, rng, 4, 0.08),
                      bell(cd, 0.5, 1.3, 1.5), hull_modes(100, 2400, peaks=(170, 400)),
                      [(path([(0, 220), (0.8, 380), (1.6, 260)], cd), 4.0, 0.9),
                       (path([(0, 700), (0.8, 950), (1.6, 750)], cd), 5.0, 0.5)],
                      jitter=0.12, alt=0.15), 0.5, 0.6)
    for at, f0, g in [(0.55, 205, 0.5), (0.9, 275, 0.35)]:
        m.add(pop(rng, f0, 1.0, 0.9), at, g)
    m.add(slosh(1.2, rng, 1300, 0.3), 1.0, 0.25)
    return at_sea(fit(m.out(), ns(dur)), 0.07, seed=1800)


@recipe("pack", peak_db=-3.0, fade_out=0.25, max_len=1.6, tail_db=-60.0, loud=-16.0, limit_db=6.0,
        subtitle="Ship is packed away", reach=24)
def pack(v, rng):
    """the ship struck down into a chest: the sail dropped and folded (a rush of canvas, a flump as each fold lays on the last),
    the ropes coiled (hemp hauled round hand over hand and laid in loops, a soft thud with each loop), and the lid brought
    down on it all, a heavy oak lid onto the box with a hollow thud, the iron hasp dropped over and latched with a clink"""
    dur = 1.8
    t = tvec(dur)
    m = Bus(dur)
    env = rise_to(t, 0.0, 0.25) * np.clip((0.6 - t) / 0.2, 0, 1)
    can = swish(dur, rng, env, 300, 3000, 0.55, 28) + 0.8 * rn(crinkle(dur, rng, 40 + 520 * env, 600, 4200)) * env
    m.add(tame(can, 10), 0.0, 0.6)
    for at, sz, g in [(0.2, 0.9, 0.7), (0.4, 1.2, 1.0)]:
        m.add(flump(rng, sz), at, g)
    sp = pulls(dur, [0.5, 0.67, 0.84], 0.2, [0.7, 0.8, 0.8], 0.0) * np.clip((1.0 - t) / 0.08, 0, 1)
    m.add(tame(rope_run(dur, rng, sp, lay=30.0, lo=450, hi=3600, fibres=1.0), 10), 0.0, 0.5)
    for at, f0, g in [(0.62, 190, 0.5), (0.8, 205, 0.55), (0.96, 198, 0.6)]:  # a coil laid down on the rest
        m.add(nrm(bp(white(0.1, rng), 120, 1100) * np.exp(-tvec(0.1) / 0.025)), at, g * 0.7)
        m.add(saturate(sine_sweep(0.12, f0, f0 * 0.6, 4.0) * np.exp(-tvec(0.12) / 0.03), 2.4), at, g * 0.35)
    lid = 1.12
    m.add(knock(rng, 1.1, 0.8, 0.8, 0.5, 0.9), lid, 1.4)
    m.add(tame(hann_burst(0.05, rng, 150, 900) * np.exp(-tvec(0.05) / 0.012), 10), lid - 0.005, 0.3)
    m.add(iron_ring(0.35, rng, [2300 * rng.uniform(0.97, 1.03), 3100], t60=0.12, gains=[1.0, 0.5]), lid + 0.16, 0.18)
    m.add(nrm(hann_burst(0.002, rng, 2500, 8000)), lid + 0.16, 0.12)
    m.add(iron_ring(0.2, rng, [1900, 2650], t60=0.06, gains=[1.0, 0.6]), lid + 0.26, 0.1)
    return at_sea(fit(m.out(), ns(dur)), 0.05, seed=1810)
# ground>>


# <<deck
# =========================================================================== the deck, the yard, the hold
@recipe("wheel", variants=3, peak_db=-5.0, fade_out=0.08, max_len=[0.36, 0.34, 0.34], tail_db=-56.0, loud=-23.0, limit_db=6.0,
        subtitle="", reach=12)
def wheel(v, rng):
    """the helm turned: the spokes of the oak wheel passing the pawl, quiet little wooden clicks, each a hard bright 'tick' of
    wood on wood, a hollow wooden knock under it, and the iron of the pawl's tongue. 1 three clicks as the wheel is eased over,
    2 two slower, heavier ones and the rope on the drum rubbing between, 3 four quick ones, quickening, the last a good knock
    as the spoke fetches up against the stop"""
    dur = 0.42
    m = Bus(dur)

    def click(at, g, f=1.0, heavy=0.0):
        m.add(strike(0.06, rng, np.array([1180, 1960, 3050]) * f * rng.uniform(0.93, 1.07), [0.016, 0.011, 0.007],
                     [1, 0.6, 0.3], lo=600, hi=6500, ex=0.0008), at, 0.5 * g)
        m.add(oak(0.2, rng, rng.uniform(330, 400) * f, t60=0.09 + 0.05 * heavy, hard=1.0, lo=220), at,
              1.2 * g * (1 + 0.4 * heavy))
        m.add(iron_ring(0.07, rng, [2350 * f * rng.uniform(0.96, 1.04)], t60=0.025, gains=[1.0]), at + 0.002, 0.1 * g)

    if v == 0:
        for at, g in [(0.0, 1.0), (0.105, 0.85), (0.21, 0.9)]:
            click(at + rng.uniform(-0.004, 0.004), g)
    elif v == 1:
        for at, g in [(0.0, 1.0), (0.17, 0.9)]:
            click(at + rng.uniform(-0.004, 0.004), g, 0.92, 0.6)
        dt = tvec(0.2)
        m.add(rope_run(0.2, rng, 0.45 * bell(0.2, 0.5, 1.3, 1.5) * np.sin(np.pi * np.clip(dt / 0.2, 0, 1)) ** 0.5,
                       lay=18.0, lo=450, hi=3000, fibres=0.5), 0.0, 0.1)
    else:
        for at, g in [(0.0, 0.8), (0.08, 0.75), (0.15, 0.8), (0.215, 1.0)]:
            click(at + rng.uniform(-0.003, 0.003), g, 1.0 + 0.04 * at, 1.0 if at > 0.18 else 0.0)
    return at_sea(fit(m.out(), ns(dur)), 0.04, seed=1820 + v)


def mallet(rng, f=1.0, hard=1.0, seated=0.0, dur=0.4):
    """a shipwright's wooden mallet driving an oak treenail home in the planking: wood on wood, a hard bright 'tock' as the
    mallet meets the peg's head; the plank taking it, its knock higher and drier the further the peg is seated (the wood
    stiffens round it); the hull answering below, hollow. seated is 0 for a peg that is just started, 1 for one nearly
    home"""
    t = tvec(dur)
    m = Bus(dur)
    m.add(strike(0.05, rng, np.array([1250, 2150, 3300]) * f * (1 + 0.1 * seated), [0.012, 0.008, 0.005], [1, 0.55, 0.3],
                 lo=600, hi=6500, ex=0.0008), 0.0, 0.5 * hard)
    m.add(hann_burst(0.003, rng, 500, 4500), 0.0, 0.15 * hard)
    m.add(oak(min(dur, 0.35), rng, (300 + 110 * seated) * f, t60=0.12 * (1 - 0.5 * seated), hard=hard, lo=180), 0.0, 1.0)
    body = sine_sweep(dur, 150 * f, 98 * f, 5.0) * np.exp(-t / (0.055 * (1 - 0.35 * seated))) * np.clip(t / 0.003, 0, 1)
    m.add(saturate(body, 3.0), 0.0, 0.25 * (1 - 0.4 * seated))
    air = resonators(fit(hann_burst(0.006, rng, 150, 1700), ns(dur)), np.array([196, 351, 540, 760]) * f,
                     np.array([0.13, 0.09, 0.065, 0.045]) * (1 - 0.4 * seated), [1.0, 0.7, 0.4, 0.2])
    m.add(air, 0.0, 0.8)
    return m.out()


@recipe("repair", variants=3, peak_db=-3.0, fade_out=0.12, max_len=[0.66, 0.62, 0.68], tail_db=-60.0, loud=-16.5, limit_db=11.0,
        subtitle="Mallet drives a peg", reach=24)
def repair(v, rng):
    """the shipwright's mallet driving a treenail into the hull: two solid knocks. 1 two even blows, the second higher and
    drier as the peg seats; 2 a lighter one to start it and a hard one on its heels, the planks ringing out under it; 3 two
    blows, the second a rebound off the peg in a quick double 'tock-tock'. Wood on wood, the plank, the hull's hollow
    under it"""
    dur = 0.75
    m = Bus(dur)
    if v == 0:
        m.add(mallet(rng, 1.0, 1.0, 0.0), 0.0, 1.0)
        m.add(mallet(rng, 1.05, 0.95, 0.8), 0.31 + rng.uniform(-0.01, 0.01), 0.9)
    elif v == 1:
        m.add(mallet(rng, 0.97, 0.7, 0.0), 0.0, 0.8)
        m.add(mallet(rng, 1.0, 1.0, 0.45), 0.27, 1.0)
        m.add(hull_slap(rng, 0.4, 0.8), 0.4, 0.05)
    else:
        m.add(mallet(rng, 1.0, 1.0, 0.1), 0.0, 1.0)
        m.add(mallet(rng, 1.08, 0.95, 0.75), 0.28, 0.9)
        m.add(mallet(rng, 1.1, 0.5, 0.9, 0.2), 0.335, 0.4)
    return at_sea(fit(m.out(), ns(dur)), 0.05, seed=1830 + v)


@recipe("wreck", peak_db=-3.0, fade_out=0.6, max_len=3.2, tail_db=-62.0, loud=-12.5, limit_db=9.0,
        subtitle="Ship breaks apart", reach=64)
def wreck(v, rng):
    """the ship breaking up. A crack as the first timbers go, the hull rending with a growl of tearing oak under a hail of
    splinters (pops of every size, a rattle of small cracks), the mast groaning very low as it takes the strain and then
    snapping like a great bone, the sail ripping from its yard and the standing rigging parting with twangs (taut hemp
    letting go and ringing down in pitch), the mast going over with a long falling rush and hitting the sea in a great
    splash, a second heavier splash of yard and rigging, and then the aftermath: floating timbers knocking together, water
    roaring into the hull and dying away, bubbles coming up, a last groan from the wreck and drips"""
    dur = 3.3
    t = tvec(dur)
    m = Bus(dur)
    # the first crack
    m.add(hann_burst(0.008, rng, 600, 7500), 0.0, 0.3)
    m.add(oak(0.5, rng, 150, t60=0.16, hard=1.0, lo=100), 0.0, 1.0)
    m.add(saturate(sine_sweep(0.5, 105, 56, 4.0) * np.exp(-tvec(0.5) / 0.09), 2.6), 0.0, 0.4)
    m.add(bp(white(0.12, rng), 250, 4200) * np.exp(-tvec(0.12) / 0.035) * np.clip(tvec(0.12) / 0.002, 0, 1), 0.0, 0.5)
    # the hull rending: a growl of tearing oak, and splinters
    rd = 1.15
    m.add(creak_voice(rd, rng, path([(0, 55), (0.15, 100), (0.5, 80), (0.9, 55), (1.15, 30)], rd) * spread(rd, rng, 6, 0.1),
                      bell(rd, 0.25, 1.1, 1.6), hull_modes(70, 3200, peaks=(150, 340)),
                      [(path([(0, 400), (0.5, 800), (1.15, 500)], rd), 4.0, 0.9)], jitter=0.3, alt=0.3, rub=0.4, kick=0.0009,
                      bright=5200, unsteady=0.5), 0.02, 0.9)
    rate = path([(0.0, 80), (0.3, 55), (0.8, 18), (1.3, 6)], 1.4)
    for tk in beats(rate, rng, 0.9):
        if tk < 1.3:
            m.add(pop(rng, rng.uniform(170, 900), rng.uniform(0.35, 1.0), 1.0), 0.03 + tk,
                  rng.uniform(0.05, 0.28) * (1.2 - tk / 1.3))
    m.add(tame(grain_cloud(1.2, rng, path([(0, 700), (0.4, 450), (1.2, 40)], 1.2), decay=0.0012, f_lo=600, f_hi=5000,
                           size_var=1.0), 10), 0.0, 0.45)
    # the mast: a low groan under strain, then it goes
    gd = 0.85
    m.add(creak_voice(gd, rng, path([(0, 8), (0.35, 20), (0.7, 34), (0.85, 40)], gd), bell(gd, 0.8, 1.6, 1.0),
                      hull_modes(45, 800, peaks=(85, 170)), [(path([(0, 120), (0.85, 190)], gd), 3.5, 1.0)], jitter=0.15,
                      alt=0.2, rub=0.1, kick=0.002, bright=1500, unsteady=0.4), 0.25, 0.9)
    snap = 0.95
    m.add(hann_burst(0.01, rng, 500, 8000), snap, 0.3)
    m.add(oak(0.6, rng, 118, t60=0.2, hard=1.0, lo=80), snap, 1.1)
    m.add(saturate(sine_sweep(0.6, 100, 52, 4.0) * np.exp(-tvec(0.6) / 0.1), 2.8), snap, 0.35)
    for k in range(5):  # the fibres letting go one after another
        m.add(pop(rng, rng.uniform(180, 520), 0.7, 1.0), snap + 0.02 + 0.03 * k + rng.uniform(0, 0.015), 0.3 * 0.75 ** k)
    # the sail rips and the lines part
    rp = 0.5
    rt = tvec(rp)
    rip = bp(colored(rp, rng, -1.0), 700, 6500) * bell(rp, 0.35, 1.3, 1.5) * (0.4 + 0.6 * smooth_random(rp, rng, 40))
    rip = rip + 0.8 * rn(grain_cloud(rp, rng, 1500 * bell(rp, 0.4, 1.3, 1.5) + 10, decay=0.0005, f_lo=1200, f_hi=7500)) \
        * bell(rp, 0.4, 1.3, 1.5)
    m.add(tame(rip, 10), snap + 0.04, 0.45)
    for at, f0, g in [(1.0, 240, 0.3), (1.08, 330, 0.25), (1.2, 190, 0.3), (1.31, 280, 0.2)]:
        m.add(modal(0.6, f0 * np.array([1, 2.01, 3.03, 4.06, 5.1]), [0.35, 0.22, 0.15, 0.1, 0.07],
                    [1, 0.6, 0.4, 0.25, 0.15], rng, glide=0.12), at, g)
    # over she goes: a long falling rush, and the sea
    fall = 0.45
    ft = tvec(fall)
    m.add(tvf(pink(fall, rng), "bp", 1500 - 900 * ft / fall, 0.8) * np.sin(np.pi / 2 * np.clip(ft / fall, 0, 1)) ** 2,
          1.12, 0.45)
    m.add(splash(2.4, rng, size=5.0, hard=1.0), 1.38, 1.3)
    m.add(splash(1.8, rng, size=2.6, hard=0.8), 1.66, 0.65)
    m.add(tvf(pink(1.6, rng), "bp", 300 * 2.4 ** (1 - np.exp(-tvec(1.6) / 0.4)), 0.7) * np.exp(-tvec(1.6) / 0.7)
          * (1 - np.exp(-tvec(1.6) / 0.05)), 1.4, 0.55)
    # afterwards
    for at, f0, g in [(1.95, 190, 0.4), (2.2, 240, 0.3), (2.52, 160, 0.3), (2.85, 210, 0.2)]:
        m.add(knock(rng, f0 / 215, 0.9, 0.7, 0.4, 0.5), at, g)
    m.add(creak_voice(1.0, rng, path([(0, 8), (0.3, 30), (0.6, 44), (1.0, 6)], 1.0), bell(1.0, 0.5, 1.3, 1.5),
                      hull_modes(90, 2400, peaks=(170, 400)), [(path([(0, 230), (1.0, 340)], 1.0), 4.0, 0.8)], jitter=0.14,
                      alt=0.2), 2.1, 0.5)
    bt = tvec(1.4)
    m.add(bubbles(1.4, rng, 70 * np.exp(-bt / 0.6) + 4, 2.0, 16.0, beta=1.7, level=1.0, weight=0.6, rise=0.4), 1.8, 0.35)
    for tt in np.sort(rng.uniform(1.9, 3.0, 14)):
        m.add(drip(rng), tt, rng.uniform(0.05, 0.14))
    return at_sea(fit(m.out(), ns(dur)), 0.08, seed=1840)


@recipe("hold_open", peak_db=-3.0, fade_out=0.2, max_len=0.75, tail_db=-60.0, loud=-16.0, limit_db=8.0, subtitle="Hatch opens")
def hold_open(v, rng):
    """the iron-banded hatch of the hold heaved open: the hasp lifted off its staple (iron on iron, a bright clink and a dull
    knock), the lid breaking free of its coaming with a crack, the hinges' dry squeal and a groan of oak as it swings up, and
    the lid carried over to rest against its stop with a knock, the iron bands answering with a small rattle. Heard from the
    deck, a little of the hold's hollow behind it"""
    dur = 0.9
    m = Bus(dur)
    m.add(iron_ring(0.3, rng, [1750, 2550], t60=0.09, gains=[1.0, 0.6]), 0.0, 0.28)
    m.add(hann_burst(0.002, rng, 2500, 9000), 0.0, 0.1)
    m.add(oak(0.15, rng, 330, t60=0.06, hard=1.0), 0.003, 0.5)
    m.add(pop(rng, 280, 0.5, 0.9), 0.1, 0.4)
    sd = 0.42
    m.add(squeal(sd, rng, path([(0, 560), (0.15, 700), (sd, 860)], sd), bell(sd, 0.45, 1.2, 1.5), harmonics=7, tilt=1.1,
                 chatter=0.45, wobble=0.02, peak_hz=1900), 0.11, 0.38)
    m.add(creak_voice(0.45, rng, path([(0, 18), (0.2, 50), (0.45, 28)], 0.45), bell(0.45, 0.45, 1.2, 1.5),
                      hull_modes(110, 2200, peaks=(220, 520)), [(path([(0, 280), (0.45, 440)], 0.45), 4.0, 0.8)], jitter=0.12),
          0.1, 0.65)
    m.add(knock(rng, 1.1, 0.8, 0.55, 0.4, 0.6), 0.56, 0.9)
    for at, f0, g in [(0.58, 1500, 0.2), (0.63, 2050, 0.14), (0.7, 1700, 0.08)]:
        m.add(iron_ring(0.15, rng, [f0 * rng.uniform(0.95, 1.05)], t60=0.04, gains=[1.0]), at, g)
    return below_deck(fit(m.out(), ns(dur)), 0.1, 0.35, seed=1850)


@recipe("hold_close", peak_db=-3.0, fade_out=0.25, max_len=0.8, tail_db=-60.0, loud=-15.0, limit_db=8.0,
        subtitle="Hatch slams shut")
def hold_close(v, rng):
    """the hatch let fall shut: the lid dropped from its stop (a short rush of air and a creak of the hinges), the slam of heavy
    oak on the coaming with a deep thud, the empty hold under it booming a moment (a big hollow of oak), the iron bands round
    the lid rattling, and the hasp dropped over its staple with a clink"""
    dur = 0.95
    m = Bus(dur)
    d = 0.12
    dt = tvec(d)
    m.add(swish(d, rng, np.sin(np.pi / 2 * np.clip(dt / d, 0, 1)) ** 2, 250, 1800, 0.4, 25), 0.0, 0.2)
    m.add(squeal(0.1, rng, path([(0, 780), (0.1, 640)], 0.1), bell(0.1, 0.5, 1.2, 1.5), harmonics=6, chatter=0.45, wobble=0.02,
                 peak_hz=1800), 0.0, 0.1)
    at = 0.13
    m.add(knock(rng, 0.85, 1.6, 1.0, 0.7, 1.15), at, 1.3)
    m.add(hann_burst(0.003, rng, 500, 3500), at, 0.1)
    hold = resonators(fit(hann_burst(0.008, rng, 100, 900), ns(0.6)), [82, 131, 197, 288, 391], [0.32, 0.26, 0.2, 0.15, 0.11],
                      [1.0, 0.7, 0.55, 0.4, 0.25])
    m.add(hold, at, 0.55)
    for k, f0 in enumerate([880, 1260, 1050, 1530, 960, 1380]):
        m.add(iron_ring(0.2, rng, [f0 * rng.uniform(0.96, 1.04)], t60=0.05, gains=[1.0]),
              at + 0.012 + 0.02 * k + rng.uniform(0, 0.012), 0.12 * 0.8 ** k)
    m.add(iron_ring(0.3, rng, [1850, 2650], t60=0.07, gains=[1.0, 0.6]), at + 0.2, 0.13)
    m.add(hann_burst(0.002, rng, 2500, 9000), at + 0.2, 0.06)
    return below_deck(fit(m.out(), ns(dur)), 0.18, 0.4, seed=1851)
# deck>>


# <<order
# =========================================================================== the order of the sounds
# the order of the listen-through and of sounds_entries.json: the sail and the timbers, the water, the anchor and the bell, the
# ship aground and alongside, put in and struck down, the deck and the hold
ORDER = ["creak", "sail_fill", "luff", "hoist", "wake", "lap", "rigging", "anchor_drop", "anchor_raise", "bell", "scrape",
         "bump", "launch", "pack", "wheel", "repair", "wreck", "hold_open", "hold_close"]
assert sorted(ORDER) == sorted(RECIPES), "every sound is in ORDER, once"
for _name in ORDER:
    RECIPES[_name] = RECIPES.pop(_name)
# order>>


# =========================================================================== main
def true_peak(x):
    return float(np.max(np.abs(signal.resample_poly(x, 4, 1)))) + 1e-12


def write_checked(path, x, ceiling_db=-1.0, quality=6):
    """encode, decode and measure: if the encoder's overshoot lifts the true peak over the ceiling, turn the sound down by
    that much and encode it again. Returns the samples as they were finally written"""
    for _ in range(3):
        write_ogg(path, x, quality=quality)
        over = 20 * np.log10(true_peak(read_audio(path)[0])) - ceiling_db
        if over <= 0:
            break
        x = x * db(-(over + 0.05))
    return x


def seam_report(path, n):
    """what a loop sounds like at its join, read back from the file that was written: the file's length against the
    loop's, the step across the join against the typical step between samples, and the high-frequency energy in the
    10 ms round the join against that of the loop elsewhere (a click or a dropout would show as a jump)"""
    y = read_audio(path)[0]
    step = abs(y[0] - y[-1])
    typ = float(np.median(np.abs(np.diff(y)))) + 1e-12
    h = hp(y, 1500.0, 2)
    w = ns(0.005)
    seam = np.sqrt(np.mean(np.concatenate([h[-w:], h[:w]]) ** 2))
    els = [np.sqrt(np.mean(h[i:i + 2 * w] ** 2)) for i in range(ns(0.1), len(y) - ns(0.1), 2 * w)]
    return len(y) - n, step / typ, seam / (np.median(els) + 1e-12)


def build(assets, names, wav_dir=None):
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            t0 = time.time()
            x = render_loop(name, v) if r["loop"] else render_effect(name, v)
            out = sound_path(name, v, r["variants"])
            path = os.path.join(assets, "sounds", out + ".ogg")
            x = write_checked(path, x)
            if wav_dir:
                write_wav(os.path.join(wav_dir, out + ".wav"), x)
            extra = ""
            if r["loop"]:
                d_len, step, seam = seam_report(path, len(x))
                extra = f"  loop {len(x)} samples (file {d_len:+d}), join {step:4.1f}x a step, {seam:4.2f}x the usual highs"
            print(f"{out:22s} {len(x) / SR:6.3f}s  peak {to_db(np.max(np.abs(x))):6.1f}  rms {to_db(rms(x)):6.1f}  "
                  f"{time.time() - t0:5.2f}s{extra}", flush=True)


GAP = 0.7  # seconds of quiet between one sound and the next in the listen-through
LOOP_SHOWN = 10.0  # how long each loop plays in the listen-through (so that its join is heard once)


def listen_through(assets, names, path):
    """one MP3 to listen to on a phone: every file that was just written, in order, a gap between, a chapter mark (the
    file's name) at each. A loop is played for ten seconds, round its join. The files are read back from disk, so it is what
    the game will play"""
    pieces, marks, at = [], [], 0.0
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            stem = sound_path(name, v, r["variants"])
            p = os.path.join(assets, "sounds", stem + ".ogg")
            if not os.path.exists(p):
                print(f"(skipped, not rendered: {stem})")
                continue
            x = read_audio(p)[0]
            if r["loop"]:
                x = np.tile(x, int(np.ceil(LOOP_SHOWN * SR / len(x))))[:ns(LOOP_SHOWN)]
                x = fade(x, 0.0, 0.3)
            marks.append((at, at + len(x) / SR + GAP, stem[len(SHIP_DIR) + 1:]))
            pieces += [x, np.zeros(ns(GAP))]
            at += len(x) / SR + GAP
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark ship sounds\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\n"
                        f"title={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-ac", "1", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        shutil.copyfile(mp3, path)  # (encoded aside, then copied: ffmpeg seeks back in a file to finish its header)
    print(f"{path}  {at:.1f}s, {len(marks)} files, {os.path.getsize(path) / 1e6:.2f} MB")


def fragments(directory):
    """sounds_entries.json (the sounds.json entries of every event, keys as the game names them) and lang_sounds.json (their
    subtitles), for the lead to merge; the real sounds.json and en_us.json are not touched"""
    import json
    sounds, lang = {}, {}
    for name, r in RECIPES.items():
        entries = []
        for v in range(r["variants"]):
            s = {"name": "guildmark:" + sound_path(name, v, r["variants"])}
            if r["loop"]:
                s["stream"] = False
            if r["reach"]:
                s["attenuation_distance"] = r["reach"]
            entries.append(s)
        key = f"ship.{name}"
        sounds[key] = {"sounds": entries}
        if r["subtitle"]:
            sounds[key]["subtitle"] = f"subtitles.guildmark.{key}"
            lang[f"subtitles.guildmark.{key}"] = r["subtitle"]
    os.makedirs(directory, exist_ok=True)
    for fname, data in (("sounds_entries.json", sounds), ("lang_sounds.json", lang)):
        p = os.path.join(directory, fname)
        with open(p, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(p, len(data), "entries")


def select(only):
    names = []
    for s in (s.strip() for s in only.split(",") if s.strip()):
        for pre in ("ship.", SHIP_DIR + "/"):
            if s.startswith(pre):
                s = s[len(pre):]
        if s not in RECIPES:
            raise SystemExit(f"unknown: {s} (sounds: {', '.join(RECIPES)})")
        if s not in names:
            names.append(s)
    return names


def main():
    ap = argparse.ArgumentParser(description="Guildmark's cog: the sounds of a sailing ship")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated sound names, e.g. creak,wake")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered")
    ap.add_argument("--fragments", default=None, help="also write sounds_entries.json and lang_sounds.json here")
    a = ap.parse_args()
    names = select(a.only) if a.only else list(RECIPES)
    build(a.assets, names, a.wav)
    if a.mp3:
        listen_through(a.assets, names, a.mp3)
    if a.fragments:
        fragments(a.fragments)


if __name__ == "__main__":
    main()
