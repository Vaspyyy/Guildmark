"""ambkit: what Guildmark's city ambience sounds share (make_ambience.py and the modules in ambience/).

A recipe is one sound: a function (variant, rng) -> audio, registered with @recipe. One-shots come out
mono (positional in game) and peak-normalised; beds come out as stereo loops, levelled by RMS, cut to an
exact length with the extra tail crossfaded over the head so they repeat without a seam. Everything is
seeded, so a rerun gives the same files.
"""
import os

import numpy as np

from sfxkit import *  # noqa: F401,F403
from sfxkit import SR, ns, tvec, db, to_db, fit, fade, lp, hp, reverb, limiter, finalize, peak_norm

RECIPES = {}

# Set by make_ambience.py from its arguments: where the raw TTS takes live (make_city_voices.py tts)
RAW_DIR = None


class Skip(Exception):
    """a recipe that can't be made in this run (its raw takes aren't there)"""


def recipe(path, variants=1, stereo=False, loop=None, xfade=2.0, peak_db=-3.0, rms_db=None, ceiling_db=-1.0,
           fade_out=0.03, max_len=None, tail_db=-62.0):
    """Register a sound written to sounds/ambience/<path><n>.ogg (n counts variants from 1).

    loop: length in seconds of a seamless loop. The recipe returns at least loop + xfade seconds; the
    extra tail is crossfaded (equal power) over the head, so the file's last sample runs straight on
    into its first. Loops are levelled to rms_db and limited at ceiling_db.
    stereo: the recipe returns a (2, n) array (beds; Minecraft plays stereo files unpositioned).
    One-shots are trimmed, faded and peak-normalised to peak_db (or levelled to rms_db when given).
    """
    def deco(fn):
        RECIPES[path] = dict(fn=fn, variants=variants, stereo=stereo, loop=loop, xfade=xfade, peak_db=peak_db,
                             rms_db=rms_db, ceiling_db=ceiling_db, fade_out=fade_out, max_len=max_len,
                             tail_db=tail_db)
        return fn
    return deco


def nrm(x):
    return x / (np.max(np.abs(x)) + 1e-12)


def raw_take(name):
    """a raw TTS take (mono float at SR) by file stem, from the folder make_city_voices.py tts wrote"""
    if not RAW_DIR:
        raise Skip("needs --raw (the TTS takes from make_city_voices.py tts)")
    path = os.path.join(RAW_DIR, name + ".wav")
    if not os.path.exists(path):
        raise Skip(f"no raw take {path}")
    return read_audio(path)[0]


# ---------------------------------------------------------------- places
def outdoors(x, wet=0.12, seed=91):
    """open ground: a little slap back off the hills and nothing else"""
    return reverb(x, wet=wet, rt60=1.4, predelay=0.045, hf_damp=0.7, seed=seed, early_span=0.08)


def street(x, wet=0.2, seed=301, rt60=1.1):
    """a stone street: quick slaps off the house fronts either side and a short bright-ish tail"""
    return reverb(x, wet=wet, rt60=rt60, predelay=0.012, hf_damp=0.45, seed=seed, early_span=0.05)


def square(x, wet=0.28, seed=302, rt60=1.8):
    """a big paved square walled by tall buildings: later reflections, a longer tail"""
    return reverb(x, wet=wet, rt60=rt60, predelay=0.03, hf_damp=0.5, seed=seed, early_span=0.09)


def far(x, dist, seed=303):
    """as heard from dist blocks (metres) away in a town: the air takes the top end, the walls add more tail"""
    cutoff = max(1800.0, 16000.0 * np.exp(-dist / 70.0))
    y = lp(x, cutoff, order=2)
    return reverb(y, wet=min(0.6, 0.12 + dist / 120.0), rt60=1.3 + dist / 80.0, predelay=0.01 + dist / 4000.0,
                  hf_damp=0.6, seed=seed, early_span=0.07)


# ---------------------------------------------------------------- stereo
def pan(x, az, itd=0.0006):
    """mono -> (2, n) at azimuth az (-1 hard left .. 1 hard right): constant power, plus the far ear's
    small delay"""
    x = np.asarray(x, dtype=np.float64)
    a = (np.clip(az, -1, 1) + 1) * np.pi / 4
    gl, gr = np.cos(a), np.sin(a)
    d = ns(abs(az) * itd)
    late = np.concatenate([np.zeros(d), x])[: len(x)] if d else x
    left, right = (x, late) if az < 0 else (late, x)
    return np.stack([gl * left, gr * right])


def stereo_reverb(st, wet=0.2, rt60=1.2, predelay=0.02, hf_damp=0.5, seeds=(311, 312), early_span=0.06, dry=1.0):
    """reverb each channel through its own impulse response, so the tails come out decorrelated (wide)"""
    out = [reverb(st[c], wet=wet, rt60=rt60, predelay=predelay, hf_damp=hf_damp, seed=seeds[c],
                  early_span=early_span, dry=dry) for c in range(2)]
    n = max(len(o) for o in out)
    return np.stack([fit(o, n) for o in out])


class StereoMix:
    """accumulates (2, n) layers at time offsets"""

    def __init__(self, dur=0.0):
        self.buf = np.zeros((2, ns(dur)))

    def add(self, st, at=0.0, gain=1.0):
        st = np.asarray(st, dtype=np.float64)
        if st.ndim == 1:
            st = np.stack([st, st])
        i = ns(at)
        end = i + st.shape[1]
        if end > self.buf.shape[1]:
            self.buf = np.concatenate([self.buf, np.zeros((2, end - self.buf.shape[1]))], axis=1)
        self.buf[:, i:end] += gain * st
        return self

    def out(self, n=None):
        b = self.buf.copy()
        if n is not None:
            b = np.stack([fit(b[0], n), fit(b[1], n)])
        return b


# ---------------------------------------------------------------- finishing
def make_loop(x, n, nx):
    """cut x (len >= n + nx) to n samples with x[n:n+nx] crossfaded over the head (equal power), so the
    sample after the last one is, in effect, x[n]: the loop runs on without a seam"""
    if len(x) < n + nx:
        raise ValueError(f"loop needs {n + nx} samples, recipe gave {len(x)}")
    y = x[:n].copy()
    k = np.arange(nx) / max(1, nx - 1)
    fin, fout = np.sin(k * np.pi / 2), np.cos(k * np.pi / 2)
    y[:nx] = x[:nx] * fin + x[n:n + nx] * fout
    return y


def level_rms(x, rms_db):
    """scale so the RMS (over both channels when stereo) is rms_db dBFS"""
    r = float(np.sqrt(np.mean(np.square(x))))
    return x * (db(rms_db) / (r + 1e-12))


def finish(x, r, v):
    """turn a recipe's raw output into the file's samples (mono (n,) or stereo (2, n))"""
    x = np.asarray(x, dtype=np.float64)
    stereo = r["stereo"]
    if stereo and (x.ndim != 2 or x.shape[0] != 2):
        raise ValueError("stereo recipe must return a (2, n) array")
    if not stereo and x.ndim != 1:
        raise ValueError("mono recipe must return a 1-D array")
    chans = x if stereo else x[None, :]
    if r["loop"]:
        n, nx = ns(r["loop"]), ns(r["xfade"])
        chans = np.stack([hp(c, 25.0) for c in chans])
        chans = level_rms(chans, r["rms_db"] if r["rms_db"] is not None else -24.0)
        ceiling = db(r["ceiling_db"])
        # limit before looping, on the summed peak so both channels move together
        link = np.max(np.abs(chans), axis=0)
        lim = limiter(link, ceiling=ceiling, lookahead=0.003, release=0.12)
        g = np.where(link > 1e-9, np.abs(lim) / np.maximum(link, 1e-9), 1.0)
        chans = chans * g
        chans = np.stack([make_loop(c, n, nx) for c in chans])
        return chans if stereo else chans[0]
    ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]
    if not stereo:
        y = finalize(x, peak_db=r["peak_db"], fade_out=r["fade_out"], max_len=ml, tail_db=r["tail_db"])
        if r["rms_db"] is not None:
            y = level_rms(y, r["rms_db"])
            y = limiter(y, ceiling=db(r["ceiling_db"]))
        return y
    # stereo one-shot: trim on the louder channel, treat both alike
    m = np.max(np.abs(chans), axis=0)
    pk = m.max() + 1e-12
    on = np.nonzero(m > pk * db(-40))[0]
    start = max(0, on[0] - ns(0.0005)) if len(on) else 0
    alive = np.nonzero(m > pk * db(r["tail_db"]))[0]
    end = alive[-1] + 1 if len(alive) else chans.shape[1]
    chans = chans[:, start:end]
    if ml:
        chans = chans[:, : ns(ml)]
    chans = np.stack([fade(c, 0.0, min(r["fade_out"], c.shape[0] / SR * 0.5)) for c in chans])
    chans = chans * (db(r["peak_db"]) / (np.max(np.abs(chans)) + 1e-12))
    if r["rms_db"] is not None:
        chans = level_rms(chans, r["rms_db"])
        link = np.max(np.abs(chans), axis=0)
        lim = limiter(link, ceiling=db(r["ceiling_db"]))
        chans = chans * np.where(link > 1e-9, np.abs(lim) / np.maximum(link, 1e-9), 1.0)
    return chans


def describe(y, looped):
    """one line of numbers to sanity-check a file by: length, peak, RMS and, for a loop, the seam"""
    chans = y if y.ndim == 2 else y[None, :]
    peak = to_db(np.max(np.abs(chans)))
    r = to_db(np.sqrt(np.mean(np.square(chans))))
    line = f"{chans.shape[1] / SR:7.3f}s  peak {peak:6.1f} dB  rms {r:6.1f} dB"
    if looped:
        # the step across the seam, against the typical sample-to-sample step
        step = np.max(np.abs(chans[:, 0] - chans[:, -1]))
        typical = np.median(np.abs(np.diff(chans, axis=1))) + 1e-12
        line += f"  seam {step / typical:5.1f}x typical step"
    return line


def spectrogram_png(path, y, title):
    """a waveform and spectrogram picture of y, for looking a sound over without hearing it"""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    mono = y.mean(axis=0) if y.ndim == 2 else y
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(10, 5), sharex=True, gridspec_kw=dict(height_ratios=[1, 2]))
    t = np.arange(len(mono)) / SR
    a1.plot(t, mono, lw=0.4)
    a1.set_ylim(-1, 1)
    a1.set_title(title)
    a2.specgram(mono + 1e-9, NFFT=2048, Fs=SR, noverlap=1536, cmap="magma", vmin=-130, vmax=-20)
    a2.set_yscale("symlog", linthresh=500)
    a2.set_ylim(40, 20000)
    a2.set_xlabel("s")
    fig.tight_layout()
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    fig.savefig(path, dpi=80)
    plt.close(fig)
