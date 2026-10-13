"""Guildmark region music for the common lands, composed in code and rendered with FluidSynth.

usage (from the repo root):
    python3 -B art/audio/make_land_music.py src/main/resources/assets/guildmark --work DIR [--wav DIR]
        [--only heartland] [--sf2 FluidR3_GM.sf2]

Every part of the overworld belongs to a region, and each kind of region has an exploration theme that the game
plays (in the MUSIC category) in place of the overworld music. Minecraft plays a track once and then waits minutes
before the next, so each piece here stands alone: a soft opening, a development, one tasteful high point and a real
ending, never a loop. They play behind exploration, so they are calmer than the boss tracks.

The toolkit (Song and its parts, chords and voice leading, the per-stem MIDI export, the FluidSynth render, the stem
mix with its synthetic hall) is imported from make_music.py. This script adds the chords these pieces need, a
harmony map per piece, accompaniment helpers (arpeggios, strums, ornaments), tempo shaping for ritardandos, and a
mastering step that sets every piece to -17 LUFS integrated with true peaks below -1 dBTP.
"""
import argparse
import os
import sys
import warnings
from bisect import bisect_right

import numpy as np
from scipy import signal
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_music import (CHORDS, SF2_DEFAULT, P, Song, export_stem, hall_ir, in_range, mix, pcs,  # noqa: E402
                        render, roll, stereo_limit, voice_lead)
from sfxkit import SR, db, eq, hp, lp, write_ogg, write_wav  # noqa: E402

warnings.filterwarnings("ignore", category=wavfile.WavFileWarning)  # FluidSynth's float WAVs carry a LIST chunk

TARGET_LUFS = -17.0
TP_MAX = -1.5  # dBTP before encoding; Vorbis adds a little, and the files must stay below -1 dBTP

# chords these pieces use beyond make_music's set: root, third (or the suspended note), fifth, then extensions,
# in that order, because voice_lead() weighs the first tones most
CHORDS.update({
    "Bm": "B D F#", "Bm7": "B D F# A", "Em7": "E G B D", "F#m": "F# A C#", "Asus4": "A D E", "Dsus4": "D G A",
    "Gmaj7": "G B D F#", "Dmaj7": "D F# A C#", "C6": "C E G A", "Gm": "G Bb D", "Am7": "A C E G",
    "Dm7": "D F A C", "Cmaj7": "C E G B", "Fmaj7": "F A C E", "G7": "G B D F", "E7": "E G# B D",
    "Esus4": "E A B", "Bdim": "B D F", "Dm9": "D F A C E", "Am9": "A C E G B", "Bb6": "Bb D F G",
})


def _reg(ch):
    """slash chords ('G/B') share their tones with the plain chord; the bass note is read from the name"""
    if ch not in CHORDS:
        CHORDS[ch] = CHORDS[ch.split("/")[0]]
    return ch


def bass_pc(ch):
    """the chord's bass: the slash note if there is one, else the root"""
    if "/" in ch:
        return P(ch.split("/")[1] + "0") % 12
    return pcs(ch)[0]


def bass_in(ch, lo, hi):
    return in_range(bass_pc(ch), lo, hi)


class Harmony:
    """the chord at every beat, from one spec per bar: "D" (whole bar), "Em7 A7" (equal parts) or
    "Asus4:3 A:1" (lengths in beats)"""

    def __init__(self, song, bars):
        self.song = song
        self.changes = []
        for i, spec in enumerate(bars):
            toks = spec.split()
            t = i * song.bpb
            for tok in toks:
                if ":" in tok:
                    ch, d = tok.rsplit(":", 1)
                    d = float(d)
                else:
                    ch, d = tok, song.bpb / len(toks)
                self.changes.append((t, _reg(ch)))
                t += d
        self.starts = [c[0] for c in self.changes]
        assert len(bars) == song.bars, (len(bars), song.bars)

    def at(self, beat):
        return self.changes[max(0, bisect_right(self.starts, beat + 1e-6) - 1)][1]

    def spans(self, b0, b1):
        """(start, end, chord) for each chord sounding in [b0, b1)"""
        out = []
        ends = self.starts[1:] + [self.song.end_beat]
        for (t, ch), e in zip(self.changes, ends):
            if e > b0 + 1e-6 and t < b1 - 1e-6:
                out.append((max(t, b0), min(e, b1), ch))
        return out


def ladder(ch, lo, hi):
    """the chord's tones from its bass upwards, the first octave opened (no third, nothing closer than a fourth
    above the bass) so low arpeggios stay clear"""
    tones = set(pcs(ch))
    third = pcs(ch)[1]
    b = bass_in(ch, lo, lo + 11)
    out = [b]
    for m in range(b + 1, hi + 1):
        if m % 12 not in tones:
            continue
        if m < b + 12 and (m - b < 5 or m % 12 == third):
            continue
        out.append(m)
    return out


def arp(part, H, b0, b1, pattern, lo, hi, vel, step=0.5, dur=1.5, accent=6, bar_len=None, damp=0.12):
    """broken chords: pattern indexes the chord's ladder, restarting each bar; vel may be a function of beat.
    Notes ring for `dur` but are damped `damp` beats after the chord changes, as a harpist or guitarist would."""
    bar_len = bar_len or H.song.bpb
    t = b0
    while t < b1 - 1e-6:
        lad = ladder(H.at(t), lo, hi)
        k = int(round(((t % bar_len) / step))) % len(pattern)
        q = lad[min(pattern[k], len(lad) - 1)]
        v = vel(t) if callable(vel) else vel
        i = bisect_right(H.starts, t + 1e-6)
        nxt = H.starts[i] if i < len(H.starts) else H.song.end_beat + 64
        part.n(t, min(dur, nxt - t + damp), q, v + (accent if k == 0 else 0))
        t += step


def pad(parts, H, b0, b1, ranges, vel, legato=1.03, start=None):
    """sustained voice-led chords across [b0, b1); parts is [(part, [voice indices]), ...]"""
    sp = H.spans(b0, b1)
    vcs = voice_lead([ch for _, _, ch in sp], ranges, start=start)
    for (t, e, _), vc in zip(sp, vcs):
        for part, idxs in parts:
            for i in idxs:
                v = vel(t) if callable(vel) else vel
                part.n(t, (e - t) * legato, vc[i], v)
    return vcs


def bassline(part, H, b0, b1, lo, hi, vel, rhythm=None, legato=0.98):
    """the bass of each chord; rhythm (beats) re-strikes it within long chords"""
    for t, e, ch in H.spans(b0, b1):
        q = bass_in(ch, lo, hi)
        if not rhythm:
            part.n(t, (e - t) * legato, q, vel(t) if callable(vel) else vel)
            continue
        u = t
        while u < e - 1e-6:
            d = min(rhythm, e - u)
            part.n(u, d * legato, q, vel(u) if callable(vel) else vel)
            u += d


def sing(part, beat, spec, vel, tr=0, leg=1.0, contour=0.35):
    """Part.line with a natural contour in the velocities: higher and longer notes a little stronger, quick
    notes a little lighter, so a tune breathes without hand-written dynamics"""
    toks = [t.rsplit(":", 1) for t in spec.split()]
    ps = [P(n.split("+")[0]) for n, _ in toks if n != "R"]
    mid = (max(ps) + min(ps)) / 2 if ps else 60
    for nm, d in toks:
        d = float(d)
        if nm != "R":
            for q in nm.split("+"):
                v = vel + contour * (P(q) - mid) + (3 if d >= 2 else 0) - (4 if d <= 0.5 else 0)
                part.n(beat, d * leg, P(q) + tr, v)
        beat += d
    return beat


def grace(part, beat, pitch, vel, before=0.12):
    """a quick grace note just ahead of the beat"""
    part.n(beat - before, before * 0.9, pitch, vel)


def strum(part, beat, notes, dur, vel, spread=0.04, up=False):
    """a strummed chord: the strings follow each other by `spread` beats (low to high, or high to low)"""
    order = sorted(P(q) for q in notes)
    if up:
        order = order[::-1]
    for i, q in enumerate(order):
        part.n(beat + i * spread, dur - i * spread, q, vel - (2 * i if up else i))


def tempo_map(song, pts):
    """local tempo changes on a song exported at one tempo: pts = [(beat, stretch), ...] is a piecewise-linear
    seconds-per-beat multiplier (1.2 = 20% slower); every note and controller moves to its stretched position"""
    grid = np.arange(0.0, song.end_beat + 64.0, 1.0 / 64)
    xs, ys = zip(*sorted(pts))
    st = np.interp(grid, xs, ys)
    cum = np.concatenate([[0.0], np.cumsum((st[1:] + st[:-1]) / 2 * np.diff(grid))])

    def f(b):
        return float(np.interp(b, grid, cum))

    for p in song.parts:
        for nt in p.notes:
            b0, d = nt[0], nt[1]
            nt[0], nt[1] = f(b0), f(b0 + d) - f(b0)
        p.ccs = [(f(b), c, v) for b, c, v in p.ccs]
    song.end_beat = f(song.end_beat)


# ------------------------------------------------------------------ loudness (ITU-R BS.1770-4) and mastering
def _k_filters():
    f0, g, q = 1681.974450955533, 3.999843853973347, 0.7071752369554196
    k = np.tan(np.pi * f0 / SR)
    vh, vb = 10 ** (g / 20), (10 ** (g / 20)) ** 0.4996667741545416
    a0 = 1 + k / q + k * k
    shelf = ([(vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0],
             [1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0])
    f0, q = 38.13547087602444, 0.5003270373238773
    k = np.tan(np.pi * f0 / SR)
    a0 = 1 + k / q + k * k
    hpf = ([1.0, -2.0, 1.0], [1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0])
    return shelf, hpf


def lufs(x):
    """integrated loudness of a stereo signal (gated, 400 ms blocks with 75% overlap)"""
    (b1, a1), (b2, a2) = _k_filters()
    y = signal.lfilter(b2, a2, signal.lfilter(b1, a1, x, axis=1), axis=1)
    blk, hop = int(0.4 * SR), int(0.1 * SR)
    cs = np.concatenate([np.zeros((2, 1)), np.cumsum(y ** 2, axis=1)], axis=1)
    st = np.arange(0, y.shape[1] - blk + 1, hop)
    ms = ((cs[:, st + blk] - cs[:, st]) / blk).sum(axis=0)
    lk = -0.691 + 10 * np.log10(np.maximum(ms, 1e-20))
    rel = -0.691 + 10 * np.log10(ms[lk > -70].mean()) - 10
    return -0.691 + 10 * np.log10(ms[(lk > -70) & (lk > rel)].mean())


def true_peak_db(x):
    """peak of the 4x oversampled signal, in chunks to keep memory small"""
    n, step, pad, pk = x.shape[1], SR * 10, 256, 0.0
    for s0 in range(0, n, step):
        a, b = max(0, s0 - pad), min(n, s0 + step + pad)
        up = signal.resample_poly(x[:, a:b], 4, 1, axis=1)
        pk = max(pk, np.max(np.abs(up[:, 4 * (s0 - a):4 * (min(n, s0 + step) - a)])))
    return 20 * np.log10(pk + 1e-12)


def master(x, target=TARGET_LUFS, tp_max=TP_MAX):
    """bring the mix to the target integrated loudness, limiting only the peaks that would pass tp_max"""
    ceiling = tp_max - 0.3
    for _ in range(8):
        y = x
        for _ in range(6):
            y = y * db(target - lufs(y))
            if true_peak_db(y) > ceiling:
                y = stereo_limit(y, ceiling)
            if abs(lufs(y) - target) < 0.05:
                break
        tp = true_peak_db(y)
        if tp <= tp_max:
            return y
        ceiling -= tp - tp_max + 0.1
    return y


# ================================================================== heartland
HEART_THEME = ("A4:1 D5:1 F#5:1.5 E5:0.5 D5:1 B4:1 G5:2 F#5:1.5 E5:0.5 D5:1 B4:1 A4:3 R:1 "
               "A4:1 D5:1 F#5:1.5 E5:0.5 D5:1 B4:1 B5:2 A5:1.5 G5:0.5 F#5:1 E5:1 D5:3 R:1")


def compose_heartland():
    """The home theme: meadows, fields and the kind woods where most villages stand. D major at a walking 80,
    a harp rippling in eighths under a singing tune. The tune is an eight-bar period whose hook climbs a
    fourth and a third and then leaps a sixth (an octave the second time) before settling home. The flute sings
    it first, the oboe takes it with the flute in descant, the violins answer with a rising second tune that
    turns through the mixolydian C major, and the full strings, flute and horns sing it at the high point. The
    coda lets the flute remember the hook once more over a minor iv (G minor), and the last chord is D major."""
    s = Song("heartland", bpm=80, bars=42, tail=6.0)
    H = Harmony(s, [
        "D", "G/D", "D", "Asus4:2 A:2",                                  # 1-4 intro
        "D", "G/B", "D/A", "A", "D", "G", "Em7 A", "D",                 # 5-12 A1: flute
        "D", "G/B", "D/A", "A", "Bm", "G", "Em7 A", "D",                # 13-20 A2: oboe, flute descant
        "Bm", "Em", "A", "Bm", "G", "C", "D/F#", "Asus4:3 A:1",         # 21-28 B: violins
        "D", "G/B", "D/A", "A", "D", "G", "Em7 A", "D",                 # 29-36 A3: everyone
        "D", "G", "Gm", "D", "D", "D"])                                 # 37-42 coda
    s.chord_at = H.at
    b = s.b

    fl = s.part("flute", 73, "wind", pan=58, volume=100, hum=0.012, vjit=3, lag=0.01)
    ob = s.part("oboe", 68, "wind", pan=72, volume=98, hum=0.012, vjit=3, lag=0.01)
    cl = s.part("clar", 71, "wind", pan=48, volume=90, hum=0.014, vjit=3)
    hn1 = s.part("hn1", 60, "brass", pan=74, volume=100, hum=0.012, lag=0.012)
    hn2 = s.part("hn2", 60, "brass", pan=82, volume=94, hum=0.014, lag=0.014)
    vln = s.part("vln", 48, "str", pan=40, volume=104, hum=0.012, lag=0.01)
    pad1 = s.part("pad1", 49, "str", pan=50, volume=92, hum=0.02)
    pad2 = s.part("pad2", 49, "str", pan=70, volume=92, hum=0.02)
    vc = s.part("vc", 42, "low", pan=76, volume=100, hum=0.012, vjit=3)
    cb = s.part("cb", 43, "low", pan=86, volume=96, hum=0.012, vjit=3)
    harp = s.part("harp", 46, "harp", pan=30, volume=104, hum=0.006, vjit=4)
    glk = s.part("glock", 9, "harp", pan=62, volume=76, hum=0.004, vjit=2)
    timp = s.part("timp", 47, "perc", pan=60, volume=96, hum=0.004, vjit=2)
    kit = s.part("kit", 48, "perc", drum=True, pan=56, volume=88, hum=0.003, vjit=2)

    # ---- intro: harp alone, the strings breathing in under it, a horn fifth, and a clarinet hint of the hook
    arp(harp, H, 0, b(5), [0, 1, 2, 3, 4, 3, 2, 1], 50, 79, lambda t: 40 + 2.5 * t / 4, dur=1.6)
    pad([(pad1, [0, 1]), (pad2, [2, 3])], H, 0, b(5), [(50, 59), (54, 64), (57, 67), (61, 69)], 46)
    for p_ in (pad1, pad2):
        p_.expr([(0, 30), (b(2), 84), (b(4), 92), (b(5) - 0.1, 80)])
    hn2.chord(0, b(4) - 0.2, ["D3", "A3"], 40)
    hn2.expr([(0, 40), (b(2), 80), (b(4) - 0.5, 50)])
    vc.n(0, b(4) - 0.1, "D2", 50)
    vc.n(b(4), 3.9, "A2", 50)
    vc.expr([(0, 40), (b(2), 90), (b(5) - 0.1, 90)])
    sing(cl, b(3), "A4:1 D5:1 F#5:2 E5:3 R:1", 50)
    cl.expr([(b(3), 70), (b(4), 100), (b(5) - 0.1, 60)])

    # ---- A1: the flute sings the theme; the clarinet joins in thirds below for the answer
    sing(fl, b(5), HEART_THEME, 70)
    fl.expr([(b(5), 90), (b(6, 3), 112), (b(8), 100), (b(9), 96), (b(10, 3), 118), (b(12), 104),
             (b(13) - 0.1, 96)])
    sing(cl, b(9), "F#4:1 A4:1 D5:1.5 C#5:0.5 B4:1 G4:1 G5:2 F#5:1.5 E5:0.5 C#5:1 B4:1 A4:3", 52)
    cl.expr([(b(9), 92), (b(10, 3), 110), (b(13) - 0.1, 90)])
    arp(harp, H, b(5), b(13), [0, 1, 2, 3, 4, 3, 2, 1], 50, 79, 48, dur=1.6)
    pad([(pad1, [0, 1]), (pad2, [2, 3])], H, b(5), b(13), [(50, 59), (54, 64), (57, 67), (60, 69)], 48)
    bassA1 = {5: "D2:2 A2:2", 6: "B2:2 G2:2", 7: "A2:2 F#2:2", 8: "A2:2 E2:2",
              9: "D2:2 A2:2", 10: "G2:2 D2:2", 11: "E2:2 A2:2", 12: "D2:4"}
    for bar, spec in bassA1.items():
        vc.line(b(bar), spec, 56, leg=0.96)

    # ---- A2: the oboe takes the theme, the flute sings a descant above, the horns warm the second half
    sing(ob, b(13), HEART_THEME, 74)
    ob.expr([(b(13), 96), (b(14, 3), 114), (b(16), 100), (b(17), 98), (b(18, 3), 120), (b(20), 106),
             (b(21) - 0.1, 96)])
    sing(fl, b(13), "D6:2 A5:2 B5:2 D6:2 A5:2 F#5:2 E5:1 A5:1 C#6:2 "
                    "D6:2 B5:2 B5:2 D6:2 E6:2 C#6:2 D6:2 A5:2", 50, contour=0.2)
    fl.expr([(b(13), 80), (b(16), 96), (b(17), 90), (b(19), 104), (b(21) - 0.1, 84)])
    pad([(cl, [0])], H, b(13), b(21), [(55, 66)], 46)
    arp(harp, H, b(13), b(21), [0, 2, 3, 4, 5, 4, 3, 2], 50, 86, 50, dur=1.6)
    pad([(pad1, [0, 1]), (pad2, [2, 3])], H, b(13), b(21), [(50, 59), (54, 64), (57, 67), (60, 69)], 54)
    pad([(hn1, [1]), (hn2, [0])], H, b(17), b(21), [(50, 60), (55, 64)], 46)
    for p_ in (hn1, hn2):
        p_.expr([(b(17), 60), (b(19), 96), (b(21) - 0.1, 70)])
    walk = {13: "D2:2 F#2:2", 14: "B2:2 G2:2", 15: "A2:2 F#2:2", 16: "A2:2 C#3:2",
            17: "B2:2 A2:2", 18: "G2:2 B2:2", 19: "E2:2 A2:2", 20: "D2:2 A2:2"}
    for bar, spec in walk.items():
        vc.line(b(bar), spec, 58, leg=0.96)
        cb.n(b(bar), 3.9, spec.split()[0].split(":")[0], 50)

    # ---- B: the violins' rising answer, the flute joining at the octave... in unison, the horns underneath
    tuneB1 = "B4:2 D5:1 F#5:1 E5:3 D5:1 C#5:2 E5:1 A5:1 F#5:3 E5:1"
    tuneB2 = "D5:2 G5:1 B5:1 A5:2 G5:1 E5:1 F#5:1.5 E5:0.5 F#5:1 D5:1 E5:2 D5:1 C#5:0.5 B4:0.5"
    sing(vln, b(21), tuneB1 + " " + tuneB2, 76)
    vln.expr([(b(21), 88), (b(22), 100), (b(23), 96), (b(24), 106), (b(25), 102), (b(26), 114),
              (b(27), 108), (b(28), 116), (b(29) - 0.1, 120)])
    sing(fl, b(25), tuneB2, 62)
    fl.expr([(b(25), 84), (b(26), 100), (b(28), 108), (b(29) - 0.1, 110)])
    pad([(cl, [0]), (ob, [1])], H, b(21), b(29), [(55, 64), (64, 72)], 50)
    for p_ in (cl, ob):
        p_.expr([(b(21), 80), (b(25), 96), (b(29) - 0.1, 110)])
    arp(harp, H, b(21), b(29), [0, 1, 2, 3, 4, 5, 4, 3], 47, 86, lambda t: 48 + 6 * (t - b(21)) / 32, dur=1.6)
    pad([(pad1, [0, 1]), (pad2, [2, 3])], H, b(21), b(29), [(50, 59), (54, 64), (57, 67), (60, 69)],
        lambda t: 54 + 10 * (t - b(21)) / 32)
    pad([(hn1, [1]), (hn2, [0])], H, b(25), b(29), [(50, 60), (55, 65)], 54)
    for p_ in (hn1, hn2):
        p_.expr([(b(25), 70), (b(29) - 0.1, 112)])
    bassB = {21: "B2:2 A2:2", 22: "G2:2 E2:2", 23: "A2:2 C#3:2", 24: "B2:2 A2:2",
             25: "G2:2 B2:2", 26: "C3:2 G2:2", 27: "F#2:2 D2:2", 28: "A2:4"}
    for bar, spec in bassB.items():
        vc.line(b(bar), spec, 62 + (bar - 21), leg=0.97)
        cb.n(b(bar), 3.9, spec.split()[0].split(":")[0], 54 + (bar - 21))
    roll(timp, s, b(28), 4, P("A1"), 30, 70, rate=0.125)

    # ---- A3: the theme in full. violins and flute in unison, clarinet an octave below, the horns' countermelody
    sing(vln, b(29), HEART_THEME, 90)
    sing(fl, b(29), HEART_THEME, 80)
    sing(cl, b(29), HEART_THEME, 60, tr=-12)
    for p_ in (vln, fl):
        p_.expr([(b(29), 112), (b(30, 3), 124), (b(32), 112), (b(33), 112), (b(34, 3), 127), (b(36), 112),
                 (b(37) - 0.1, 96)])
    counter = ("D4:2 F#4:2 G4:2 B4:2 A4:2 F#4:2 C#4:1 E4:1 A4:1 G4:1 "
               "F#4:2 A4:2 G4:2 D5:2 B4:2 A4:1 G4:1 F#4:3 R:1")
    sing(hn1, b(29), counter, 74)
    pad([(hn2, [0])], H, b(29), b(37), [(45, 57)], 60)
    for p_ in (hn1, hn2):
        p_.expr([(b(29), 104), (b(31), 116), (b(33), 110), (b(35), 120), (b(37) - 0.1, 90)])
    arp(harp, H, b(29), b(37), [0, 2, 3, 4, 5, 6, 5, 4], 47, 88, 56, dur=1.6)
    pad([(pad1, [0, 1]), (pad2, [2, 3])], H, b(29), b(37), [(50, 59), (54, 64), (57, 67), (60, 69)], 64)
    for p_ in (pad1, pad2):
        p_.expr([(b(29), 100), (b(35), 112), (b(37) - 0.1, 86)])
    bassA3 = {29: "D2:2 A2:2", 30: "B2:2 G2:2", 31: "A2:2 F#2:2", 32: "A2:2 C#3:2",
              33: "D2:2 F#2:2", 34: "G2:2 B2:2", 35: "E2:2 A2:2", 36: "D2:4"}
    for bar, spec in bassA3.items():
        vc.line(b(bar), spec, 70, leg=0.97)
        cb.n(b(bar), 3.9, spec.split()[0].split(":")[0], 62)
    for bar, q in ((29, "F#6"), (30, "G6"), (33, "F#6"), (34, "B6")):
        glk.n(b(bar, 3), 2.0, q, 44)
    kit.n(b(29), 3, 81, 46)
    kit.n(b(33), 3, 81, 38)
    timp.n(b(29), 2, "D2", 64)
    timp.n(b(36), 2, "D2", 54)

    # ---- coda: the flute remembers the hook, the minor iv darkens it for a moment, and home
    sing(fl, b(37), "A4:1 D5:1 F#5:1.5 E5:0.5 D5:1 B4:1 B5:2 Bb5:2 A5:1 G5:1 F#5:11.5", 60, contour=0.25)
    fl.expr([(b(37), 90), (b(38, 3), 108), (b(39), 104), (b(40), 96), (b(42), 80), (b(43), 40)])
    sing(ob, b(39), "Bb4:4 A4:8", 50)
    sing(cl, b(39), "G4:4 F#4:8", 48)
    for p_ in (ob, cl):
        p_.expr([(b(39), 90), (b(40), 96), (b(43), 40)])
    arp(harp, H, b(37), b(41), [0, 2, 3, 4], 50, 81, 44, step=1.0, dur=2.5)
    for i, q in enumerate(["D2", "A2", "D3", "F#3", "A3", "D4", "F#4", "A4", "D5", "F#5", "A5", "D6"]):
        harp.n(b(41) + i * 0.17, 6.0, q, 50 + i)
    pad([(pad1, [0, 1]), (pad2, [2, 3])], H, b(37), b(43), [(50, 59), (54, 64), (57, 67), (60, 69)], 44)
    for p_ in (pad1, pad2):
        p_.expr([(b(37), 86), (b(39), 96), (b(41), 86), (b(43), 30)])
    hn1.chord(b(40), 11.5, ["F#4"], 40)
    hn2.chord(b(40), 11.5, ["A3", "D4"], 40)
    for p_ in (hn1, hn2):
        p_.expr([(b(40), 50), (b(41), 86), (b(43), 30)])
    vc.line(b(37), "D2:4 G2:4 G2:4 D2:12", 54, leg=0.98)
    cb.line(b(37), "D2:4 G1:4 G1:4 D2:12", 46, leg=0.98)
    for p_ in (vc, cb):
        p_.expr([(b(37), 100), (b(41), 92), (b(43), 40)])
    roll(timp, s, b(41), 6, P("D2"), 26, 40, rate=0.125)

    s.tempo = [(0, 1.0), (b(28), 1.0), (b(29) - 0.5, 1.07), (b(29), 1.0), (b(38), 1.0), (b(41), 1.25),
               (b(43) + 8, 1.3)]
    return s


HEART_MIX = {"wind": (0.0, 0.30), "str": (0.0, 0.34), "brass": (-4.0, 0.36), "low": (-1.0, 0.24),
             "harp": (1.0, 0.30), "perc": (-4.0, 0.30)}


# ================================================================== driver
PIECES = {
    "heartland": dict(fn=compose_heartland, mix=HEART_MIX, ir=(2.4, 201), fade_out=3.0,
                      fx={"wind": dict(hp=180), "str": dict(hp=110), "harp": dict(hp=70), "brass": dict(hp=90)}),
}


def stem_fx(y, hp_hz=None, lp_hz=None, shelf=None):
    out = y.copy()
    for c in range(out.shape[0]):
        if hp_hz:
            out[c] = hp(out[c], hp_hz, order=2)
        if lp_hz:
            out[c] = lp(out[c], lp_hz, order=2)
        if shelf:
            out[c] = eq(out[c], "highshelf", shelf[0], 0.7, shelf[1])
    return out


def build(name, assets, sf2, work, wav_dir=None):
    cfg = PIECES[name]
    song = cfg["fn"]()
    if getattr(song, "tempo", None):
        tempo_map(song, song.tempo)
    os.makedirs(work, exist_ok=True)
    stems = {}
    for stem in sorted(set(p.stem for p in song.parts)):
        mid = os.path.join(work, f"{name}_{stem}.mid")
        wav = os.path.join(work, f"{name}_{stem}.wav")
        export_stem(song, stem, mid)
        y = render(mid, wav, sf2)
        fx = cfg.get("fx", {}).get(stem)
        if fx:
            y = stem_fx(y, fx.get("hp"), fx.get("lp"), fx.get("shelf"))
        stems[stem] = y
    length = song.end_beat * song.spb + song.tail
    ir = hall_ir(rt60=cfg["ir"][0], seed=cfg["ir"][1])
    out = mix(stems, cfg["mix"], ir, length, fade_in=0.02, fade_out=cfg["fade_out"],
              master_eq=cfg.get("master_eq", True), comp=cfg.get("comp", (-18.0, 1.6)))
    out = master(out)
    path = os.path.join(assets, "sounds", "music", "region", name + ".ogg")
    write_ogg(path, out, quality=cfg.get("quality", 3))
    if wav_dir:
        write_wav(os.path.join(wav_dir, name + ".wav"), out)
    print(f"{path}  {out.shape[1] / SR:.2f}s  {lufs(out):.1f} LUFS  {true_peak_db(out):.2f} dBTP", flush=True)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--sf2", default=SF2_DEFAULT)
    ap.add_argument("--work", default="build/music_work")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    a = ap.parse_args()
    names = a.only.split(",") if a.only else list(PIECES)
    for n in names:
        build(n, a.assets, a.sf2, a.work, a.wav)


if __name__ == "__main__":
    main()
