"""Guildmark region music for the common lands, composed in code and rendered with FluidSynth.

usage (from the repo root):
    python3 -B art/audio/make_land_music.py src/main/resources/assets/guildmark --work DIR [--wav DIR]
        [--only heartland,dunes] [--sf2 FluidR3_GM.sf2]

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
    "Fm7": "F Ab C Eb", "Cm6": "C Eb G A",
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


def pad(parts, H, b0, b1, ranges, vel, legato=1.03, start=None, clear=()):
    """sustained voice-led chords across [b0, b1); parts is [(part, [voice indices]), ...]. A note a semitone (or
    a semitone and an octave or two) from a note of at least a beat that the parts in `clear` play under the
    chord is left out."""
    sp = H.spans(b0, b1)
    vcs = voice_lead([ch for _, _, ch in sp], ranges, start=start)
    for (t, e, _), vc in zip(sp, vcs):
        near = [n[2] for p_ in clear for n in p_.notes if n[0] < e and n[0] + n[1] > t and n[1] >= 0.75]
        for part, idxs in parts:
            for i in idxs:
                if any(abs(vc[i] - m) in (1, 13, 25) for m in near):
                    continue
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


# ================================================================== wildwood
WOOD_THEME = ("G3:1 C4:1 Eb4:1.5 D4:0.5 C4:1 G3:1 A3:2 A3:1 C4:1 F4:1.5 Eb4:0.5 D4:2 Bb3:2 "
              "G3:1 C4:1 Eb4:1.5 D4:0.5 C4:1 Eb4:1 Ab4:2 G4:1.5 F4:0.5 D4:1 B3:1 C4:4")
WOOD_COUNTER = "C4:2 Eb4:2 Eb4:2 C4:2 F4:2 A4:2 F4:2 D4:2 Eb4:2 G4:2 Ab4:2 C5:2 B4:2 F4:2 Eb4:4"


def compose_wildwood():
    """Dark forests, pale gardens and the old pine woods: a tale told walking. C dorian at 76, the raised
    sixth (A natural) giving the minor its old-story colour. Pizzicato cellos and basses walk in quarters
    while the bassoon tells the tune, an eight-bar period that climbs a minor third, settles on the dorian
    sixth, turns through F and B flat major and the darker A flat before the leading tone brings it home.
    The oboe takes it higher with the clarinet beneath and the flute calling like a bird. Then the
    footsteps stop under the old trees: the horns sing a slow chorale in A flat with the strings holding
    pale harmonics above. The violins sing the tune at the high point, and the bassoon remembers its start
    as the steps fade, ending on C minor with the sixth, as the story began."""
    s = Song("wildwood", bpm=76, bars=42, tail=6.0)
    tune = ["Cm", "Cm", "F", "Bb", "Cm", "Ab", "G", "Cm"]
    tune2 = ["Cm", "Cm", "F", "Bb", "Cm", "Ab", "G7", "Cm"]
    H = Harmony(s, ["Cm", "Cm", "Ab", "G"] + tune + tune2
                + ["Ab", "Eb/G", "Fm", "Bb", "Ab", "Eb", "Fm7", "G"]
                + tune2 + ["Cm", "Cm", "F", "Cm", "Bb", "Cm6"])
    s.chord_at = H.at
    b = s.b

    bsn = s.part("bassoon", 70, "winds", pan=66, volume=104, hum=0.012, vjit=3, lag=0.012)
    cl = s.part("clar", 71, "winds", pan=50, volume=96, hum=0.012, vjit=3, lag=0.01)
    ob = s.part("oboe", 68, "winds", pan=60, volume=100, hum=0.012, vjit=3, lag=0.01)
    fl = s.part("flute", 73, "winds", pan=40, volume=88, hum=0.01, vjit=3, lag=0.008)
    pz = s.part("pizz", 45, "pizz", pan=72, volume=100, hum=0.006, vjit=4)
    pzb = s.part("pizzb", 45, "pizz", pan=82, volume=96, hum=0.006, vjit=4)
    vln = s.part("vln", 48, "str", pan=40, volume=102, hum=0.012, vjit=3, lag=0.01)
    vla = s.part("vla", 41, "str", pan=58, volume=96, hum=0.012, vjit=3, lag=0.012)
    pad1 = s.part("pad1", 49, "str", pan=52, volume=90, hum=0.02)
    pad2 = s.part("pad2", 49, "str", pan=68, volume=88, hum=0.02)
    pale = s.part("pale", 49, "str", pan=30, volume=80, hum=0.01, vjit=2)
    hn1 = s.part("hn1", 60, "brass", pan=70, volume=100, hum=0.012, lag=0.012)
    hn2 = s.part("hn2", 60, "brass", pan=78, volume=96, hum=0.014, lag=0.014)
    hn3 = s.part("hn3", 60, "brass", pan=84, volume=94, hum=0.014, lag=0.014)
    tbn = s.part("tbn", 57, "brass", pan=90, volume=90, hum=0.014, lag=0.014)
    vc = s.part("vc", 42, "low", pan=76, volume=100, hum=0.012, vjit=3)
    cb = s.part("cb", 43, "low", pan=86, volume=96, hum=0.012, vjit=3)
    harp = s.part("harp", 46, "harp", pan=26, volume=100, hum=0.006, vjit=4)
    timp = s.part("timp", 47, "perc", pan=60, volume=94, hum=0.004, vjit=2)
    kit = s.part("kit", 48, "perc", drum=True, pan=56, volume=84, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 43), [1, 4, 5, 12, 13, 20, 21, 26, 28, 29, 33, 34, 36, 37, 42],
                      [.30, .36, .40, .44, .48, .54, .50, .60, .70, .84, .94, 1.0, .88, .46, .24])

    def V(lo, hi):
        return lambda t: lo + (hi - lo) * inten[min(41, int(t // 4))]

    # ---- footsteps: pizzicato walking root, fifth, octave, fifth; the basses on the strong beats
    def walk(b0, b1, vel):
        t = b0
        while t < b1 - 1e-6:
            ch = H.at(t)
            r = bass_in(ch, 36, 47)
            k = int(round(t % 4))
            q = [r, r + 7, r + 12, r + 7][k]
            pz.n(t, 0.8, q, vel(t) + (6 if k == 0 else 0))
            if k in (0, 2):
                pzb.n(t, 0.8, r if k == 0 else r + 7, vel(t) - 4)
            t += 1
    walk(0, b(21), V(48, 70))
    walk(b(37), b(41), V(46, 66))
    pz.n(b(41), 2, "C3", 48)
    pzb.n(b(41), 2, "C2", 50)

    # ---- intro: footsteps, the low strings, and the clarinet down in its chalumeau with the tune's first turn
    pad([(pad1, [0, 1]), (pad2, [2])], H, 0, b(5), [(48, 55), (51, 60), (55, 64)], 44)
    for p_ in (pad1, pad2):
        p_.expr([(0, 40), (b(2), 84), (b(5) - 0.1, 80)])
    sing(cl, b(2, 3), "G3:1 C4:1 Eb4:1.5 D4:0.5 C4:2 R:2 B3:2 R:2", 52)
    cl.expr([(b(2, 3), 80), (b(3), 100), (b(5) - 0.1, 70)])

    # ---- A: the bassoon tells it; the clarinet echoes the ends of its phrases
    sing(bsn, b(5), WOOD_THEME, 74, contour=0.3)
    bsn.expr([(b(5), 96), (b(6), 108), (b(7), 104), (b(9) - 0.1, 90), (b(9), 98), (b(10), 112), (b(12), 104),
              (b(13) - 0.1, 86)])
    sing(cl, b(6, 3), "C5:1 A4:1", 48)
    sing(cl, b(8, 3), "F4:0.5 Eb4:0.5 D4:1", 48)
    sing(cl, b(12, 2), "Eb4:1 D4:1 C4:1", 50)
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(5), b(13), [(48, 55), (52, 60), (55, 65)], V(36, 52), clear=[bsn, cl])

    # ---- A2: the oboe takes it higher, the clarinet under it, the flute calling like a bird in the gaps
    sing(ob, b(13), WOOD_THEME, 76, tr=12, contour=0.3)
    ob.expr([(b(13), 92), (b(14), 106), (b(15), 100), (b(17) - 0.1, 90), (b(17), 96), (b(18), 112), (b(20), 104),
             (b(21) - 0.1, 86)])
    sing(cl, b(13), WOOD_COUNTER, 58, contour=0.15)
    cl.expr([(b(13), 80), (b(17), 92), (b(21) - 0.1, 76)])
    for bar, call in ((14, "G6:0.25 A6:0.25 G6:0.25 A6:0.25 G6:1"), (16, "Bb6:0.25 C7:0.25 Bb6:1.5"),
                      (18, "Eb6:0.25 F6:0.25 Eb6:0.25 F6:0.25 Eb6:1")):
        sing(fl, b(bar, 3), call, 42, contour=0.1)
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(13), b(21), [(50, 57), (53, 62), (57, 66)], V(36, 52),
        clear=[ob, cl, fl])
    arp(harp, H, b(13), b(21), [0, 1, 2, 3, 4, 3, 2, 1], 43, 74, V(34, 46), step=0.5, dur=1.4)

    # ---- B: the footsteps stop under the old trees; the horns' chorale, pale harmonics high above
    chor = "C5:4 Bb4:4 Ab4:4 Bb4:4 C5:4 Eb5:4 C5:2 Ab4:2 B4:4"
    sing(hn1, b(21), chor, 62, tr=-12, contour=0.1)
    pad([(hn2, [2]), (hn3, [1]), (tbn, [0])], H, b(21), b(29), [(41, 50), (46, 55), (50, 58)], V(40, 56),
        clear=[hn1])
    for p_ in (hn1, hn2, hn3, tbn):
        p_.expr([(b(21), 60), (b(23), 92), (b(25), 100), (b(27), 110), (b(29) - 0.1, 96)])
    bassline(vc, H, b(21), b(29), 36, 48, V(40, 58))
    bassline(cb, H, b(21), b(29), 28, 40, V(36, 52))
    for bar, q in ((21, "Eb6"), (22, "Eb6"), (23, "C6"), (24, "F6"), (25, "Eb6"), (26, "Bb5"), (27, "C6"),
                   (28, "D6")):
        pale.n(b(bar), 4.05, q, V(30, 42)(b(bar)))
    pale.expr([(b(21), 40), (b(22), 80), (b(28), 90), (b(29) - 0.1, 50)])
    sing(fl, b(23), "Ab4:4 G4:4", 46, contour=0.1)
    sing(fl, b(27), "Ab4:2 F4:2 F4:4", 48, contour=0.1)
    fl.expr([(b(23), 70), (b(24), 92), (b(25) - 0.1, 60), (b(27), 70), (b(28), 96), (b(29) - 0.1, 70)])
    roll(timp, s, b(28), 4, P("G1"), 26, 60, rate=0.125)
    harp.n(b(21), 4, "Ab2", 46)
    harp.n(b(25), 4, "Ab2", 44)

    # ---- C: the violins sing it, violas an octave below; oboe and clarinet with the horns' counter-line
    sing(vln, b(29), WOOD_THEME, 82, tr=12, contour=0.3)
    sing(vla, b(29), WOOD_THEME, 76, contour=0.3)
    for p_ in (vln, vla):
        p_.expr([(b(29), 104), (b(30), 114), (b(31), 108), (b(33), 112), (b(34), 124), (b(35), 116),
                 (b(36), 108), (b(37) - 0.1, 84)])
    sing(hn1, b(29), WOOD_COUNTER, 64, contour=0.15)
    sing(cl, b(29), WOOD_COUNTER, 60, tr=12, contour=0.15)
    for p_ in (hn1, cl):
        p_.expr([(b(29), 96), (b(33), 110), (b(34), 118), (b(37) - 0.1, 80)])
    pad([(hn2, [1]), (hn3, [0])], H, b(29), b(37), [(46, 55), (50, 59)], V(44, 60), clear=[vln, vla, hn1, cl])
    for p_ in (hn2, hn3):
        p_.expr([(b(29), 90), (b(34), 112), (b(37) - 0.1, 80)])
    bassline(vc, H, b(29), b(37), 36, 48, V(48, 66), rhythm=2)
    bassline(cb, H, b(29), b(37), 28, 40, V(44, 60), rhythm=2)
    for p_ in (vc, cb):
        p_.expr([(b(21), 80), (b(29), 104), (b(34), 116), (b(37) - 0.1, 90)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(29), b(37), [(50, 57), (53, 62), (57, 66)], V(40, 56),
        clear=[vln, vla, hn1, cl])
    arp(harp, H, b(29), b(37), [0, 2, 3, 4, 5, 4, 3, 2], 43, 79, V(38, 50), step=0.5, dur=1.4)
    timp.n(b(29), 2, "C2", 62)
    timp.n(b(33), 2, "C2", 64)
    roll(timp, s, b(35, 3), 2, P("G1"), 34, 64, rate=0.125)
    timp.n(b(36), 2, "C2", 60)
    kit.n(b(34), 3, 81, 38)

    # ---- coda: the steps again, fading; the bassoon remembers the start; C minor with its sixth
    sing(bsn, b(37), "G3:1 C4:1 Eb4:1.5 D4:0.5 C4:1 G3:1 A3:2 R:4 G3:1 C4:1 Eb4:2 D4:2 C4:2 A3:6", 66, contour=0.3)
    bsn.expr([(b(37), 92), (b(38), 104), (b(39), 80), (b(40), 96), (b(41), 90), (b(43), 40)])
    sing(cl, b(39, 1), "C5:1 A4:1 G4:2", 44)
    pad([(pad1, [0, 1]), (pad2, [2, 3])], H, b(37), b(43), [(48, 55), (51, 60), (55, 64), (57, 67)],
        V(32, 46), clear=[bsn, cl])
    for p_ in (pad1, pad2):
        p_.expr([(b(37), 86), (b(41), 80), (b(43), 30)])
    harp.chord(b(42), 6, ["C2", "G2", "Eb3", "A3"], 40)
    cb.n(b(41), 9, "C2", 44)
    cb.expr([(b(41), 90), (b(43), 30)])

    s.tempo = [(0, 1.0), (b(20, 3), 1.0), (b(21), 1.06), (b(21, 2), 1.0), (b(28, 3), 1.0), (b(29), 1.05),
               (b(29, 2), 1.0), (b(40), 1.0), (b(42), 1.2), (b(43) + 6, 1.4)]
    return s


WOOD_MIX = {"winds": (2.0, 0.30), "pizz": (0.0, 0.26), "str": (0.0, 0.36), "brass": (-4.0, 0.40),
            "low": (-1.0, 0.24), "harp": (-1.0, 0.34), "perc": (-4.0, 0.32)}


# ================================================================== dunes
DUNE_THEME = ("E4:1 F4:0.5 G#4:0.5 A4:1 B4:1 C5:1 B4:0.5 A4:0.5 B4:2 A4:1 D5:1 C5:0.5 B4:0.5 A4:1 G#4:1.5 F4:0.5 E4:2 "
              "E4:1 F4:0.5 G#4:0.5 A4:1 B4:1 C5:1 D5:0.5 E5:0.5 E5:2 F5:1 E5:0.5 D5:0.5 C5:1 A4:1 G#4:1.5 F4:0.5 E4:2")


def compose_dunes():
    """Deserts: a caravan crossing the dunes in the heat. E hijaz (E, F, G sharp, A, B, C, D): the augmented
    second between F and G sharp is its colour, and F major over the E drone its sting. A breathy flute calls
    over a drone of E and B; then a hand drum takes up the caravan's slow maqsum (dum, tek, rest, tek, dum,
    rest, tek), a hammered dulcimer ripples on the drone, and the oboe sings the tune, an ornamented rise
    through the hijaz tetrachord that falls back to E. The english horn and violas take it low; the violins
    sing over the old falling cadence (A minor, G, F, E) twice; at the high point the tune comes back in
    the violins and oboe with the horns holding the open fifth; then the drum walks away, and the flute
    calls once more before an E major chord."""
    s = Song("dunes", bpm=72, bars=44, tail=6.0)
    tune = ["E", "E", "Dm", "E", "E", "Am", "F", "E"]
    H = Harmony(s, ["E"] * 4 + tune + tune + ["Am", "G", "F", "E", "Am", "G", "F", "E"] + tune
                + ["E", "E", "Dm", "E", "E", "F", "E", "E"])
    s.chord_at = H.at
    b = s.b

    ob = s.part("oboe", 68, "lead", pan=58, volume=104, hum=0.01, vjit=3, lag=0.01)
    eh = s.part("ehorn", 69, "lead", pan=66, volume=100, hum=0.012, vjit=3, lag=0.012)
    ney = s.part("ney", 77, "ney", pan=44, volume=100, hum=0.014, vjit=3, lag=0.015)
    dul = s.part("dulcimer", 15, "dulcimer", pan=30, volume=96, hum=0.004, vjit=5)
    vln1 = s.part("vln1", 48, "str", pan=40, volume=100, hum=0.012, vjit=3, lag=0.01)
    vln2 = s.part("vln2", 48, "str", pan=48, volume=96, hum=0.012, vjit=3, lag=0.012)
    vla = s.part("vla", 41, "str", pan=62, volume=94, hum=0.012, vjit=3, lag=0.012)
    pad1 = s.part("pad1", 49, "str", pan=54, volume=88, hum=0.02)
    heat = s.part("heat", 49, "str", pan=24, volume=78, hum=0.01, vjit=2)
    vc = s.part("vc", 42, "low", pan=74, volume=100, hum=0.01, vjit=3)
    cb = s.part("cb", 43, "low", pan=84, volume=96, hum=0.01, vjit=3)
    hn1 = s.part("hn1", 60, "brass", pan=72, volume=96, hum=0.014, lag=0.014)
    hn2 = s.part("hn2", 60, "brass", pan=80, volume=92, hum=0.014, lag=0.014)
    harp = s.part("harp", 46, "dulcimer", pan=70, volume=92, hum=0.006, vjit=4)
    darb = s.part("darbuka", 0, "drums", drum=True, pan=56, volume=96, hum=0.006, vjit=5)
    riq = s.part("riq", 0, "drums", drum=True, pan=66, volume=86, hum=0.006, vjit=5)
    kit = s.part("kit", 48, "cym", drum=True, pan=50, volume=82, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 45), [1, 4, 5, 12, 13, 20, 21, 28, 29, 33, 34, 36, 37, 40, 41, 44],
                      [.30, .34, .40, .46, .48, .54, .58, .76, .86, .96, 1.0, .90, .60, .44, .34, .22])

    def V(lo, hi):
        return lambda t: lo + (hi - lo) * inten[min(43, int(t // 4))]

    # ---- the drone: E and B in the low strings wherever E is the chord, the chord's root elsewhere
    for bar in range(1, 45):
        ch = H.at(b(bar))
        r = bass_in(ch, 40, 51)
        vc.n(b(bar), 4.06, r, V(42, 66)(b(bar)))
        cb.n(b(bar), 4.06, r - 12, V(40, 62)(b(bar)))
        if ch == "E":
            vc.n(b(bar), 4.06, r + 7, V(36, 56)(b(bar)))
    for p_ in (vc, cb):
        p_.expr([(0, 40), (b(2), 90), (b(5), 96), (b(29), 110), (b(37), 96), (b(43), 80), (b(45) + 2, 30)])
    for bar, q in ((1, "B5"), (3, "E6"), (37, "B5"), (41, "E6")):
        heat.n(b(bar), 8.05, q, 34)
    heat.expr([(0, 30), (b(2), 80), (b(5) - 0.1, 40), (b(37), 30), (b(38), 70), (b(41), 74), (b(45), 20)])

    # ---- the flute's call over the drone
    call = "B4:3 C5:1 B4:1 A4:0.5 G#4:0.5 A4:2 G#4:1 F4:1 E4:4 R:2 B4:1 C5:0.5 B4:0.5 E5:3 D5:0.5 C5:0.5 B4:4"
    sing(ney, b(1, 2), call, 60, contour=0.3)
    ney.expr([(b(1, 2), 70), (b(1, 4), 104), (b(3), 90), (b(3, 3), 106), (b(5), 70)])
    grace(ney, b(1, 2), P("C5"), 46)
    grace(ney, b(4, 1), P("F5"), 44)

    # ---- the caravan: a slow maqsum on the hand drum, a riq shimmering on the strong beats
    def maqsum(bars, vel, fill=False):
        for bar in bars:
            t0 = b(bar)
            v = vel(t0)
            for t, q, a in ((0, 64, 10), (0.5, 62, 0), (1.5, 62, -4), (2, 64, 6), (3, 62, 0)):
                darb.n(t0 + t, 0.4, q, v + a)
            if fill and bar % 2 == 0:
                for t in (3.5, 3.75):
                    darb.n(t0 + t, 0.2, 60, v - 10)
            riq.n(t0, 0.5, 54, v - 14)
            riq.n(t0 + 2, 0.5, 54, v - 18)
    maqsum(range(5, 21), V(48, 72))
    maqsum(range(21, 37), V(54, 80), fill=True)
    maqsum(range(37, 41), lambda t: V(46, 66)(t) - 6 * (t - b(37)) / 16)

    # ---- the hammered dulcimer: root, fifth and octave rippling, with the flat second over E
    def ripple(b0, b1, vel):
        t = b0
        while t < b1 - 1e-6:
            ch = H.at(t)
            r = bass_in(ch, 52, 63)
            k = int(round((t % 4) / 0.5))
            colour = r + 1 if ch == "E" else next(m for m in range(r + 3, r + 5) if m % 12 in pcs(ch))
            q = [r, r + 7, r + 12, r + 7, colour + 12, r + 7, r + 12, r + 7][k]
            dul.n(t, 0.6, q, vel(t) + (8 if k == 0 else 0) - (6 if k == 4 else 0))
            t += 0.5
    ripple(b(5), b(37), V(38, 54))
    ripple(b(37), b(41), lambda t: V(36, 50)(t) - 8 * (t - b(37)) / 16)

    # ---- A: the oboe's tune, ornamented
    sing(ob, b(5), DUNE_THEME, 76, contour=0.3)
    for t, q in ((b(6, 3), "C5"), (b(8, 3), "F4"), (b(10, 3), "F5"), (b(12, 3), "F4")):
        grace(ob, t, P(q), 60)
    ob.expr([(b(5), 92), (b(6), 108), (b(7), 104), (b(9) - 0.1, 88), (b(9), 98), (b(10), 112), (b(11), 116),
             (b(13) - 0.1, 90)])
    pad([(pad1, [0, 1, 2])], H, b(5), b(13), [(52, 59), (56, 64), (59, 68)], V(30, 44), clear=[ob])
    pad1.expr([(b(5), 60), (b(7), 90)])

    # ---- A2: the english horn and violas sing it low; the oboe answers at the ends of phrases
    sing(eh, b(13), DUNE_THEME, 72, tr=-12, contour=0.3)
    sing(vla, b(13), DUNE_THEME, 62, tr=-12, contour=0.3)
    for p_ in (eh, vla):
        p_.expr([(b(13), 94), (b(14), 108), (b(17) - 0.1, 90), (b(17), 98), (b(19), 114), (b(21) - 0.1, 90)])
    for bar, spec in ((14, "B5:0.5 C6:0.5 B5:1"), (16, "G#5:0.5 A5:0.5 G#5:0.5 F5:0.5"),
                      (18, "E6:0.5 D6:0.5 E6:1"), (20, "G#5:0.5 F5:0.5 E5:1")):
        sing(ob, b(bar, 3), spec, 56, contour=0.1)
    pad([(pad1, [1, 2])], H, b(13), b(21), [(52, 59), (56, 64), (59, 68)], V(30, 44), clear=[eh, ob])

    # ---- B: the violins over the old falling cadence, twice; the dulcimer and drum stronger
    cadence = ("E5:2 D5:1 C5:1 D5:2 B4:1 G4:1 C5:2 A4:1 F4:1 B4:1 C5:0.5 B4:0.5 G#4:2 "
               "A5:2 G5:1 E5:1 G5:2 F5:1 D5:1 F5:1.5 E5:0.5 D5:1 C5:1 B4:4")
    sing(vln1, b(21), cadence, 78, contour=0.3)
    sing(vln2, b(21), cadence, 72, tr=-12, contour=0.3)
    for p_ in (vln1, vln2):
        p_.expr([(b(21), 92), (b(22), 104), (b(24), 100), (b(25), 108), (b(26), 116), (b(28), 120),
                 (b(29) - 0.1, 124)])
    pad([(pad1, [0, 1, 2])], H, b(21), b(29), [(50, 57), (54, 62), (57, 66)], V(34, 50), clear=[vln1, vln2])
    pad([(hn2, [0]), (hn1, [1])], H, b(25), b(29), [(48, 57), (53, 62)], V(36, 52), clear=[vln1, vln2])
    for p_ in (hn1, hn2):
        p_.expr([(b(25), 60), (b(29) - 0.1, 110)])
    for bar in range(21, 29):
        ch = H.at(b(bar))
        tones = [m for m in range(64, 89) if m % 12 in pcs(ch)]
        for j, q in enumerate(tones[:6]):
            harp.n(b(bar) + j * 0.12, 2.0, q, V(32, 44)(b(bar)) + j)
    roll(kit, s, b(28), 4, 57, 14, 54, rate=0.125)

    # ---- C: the tune at the high point, violins an octave over the oboe, the horns on the open fifth
    sing(vln1, b(29), DUNE_THEME, 84, tr=12, contour=0.3)
    sing(vln2, b(29), DUNE_THEME, 78, contour=0.3)
    sing(ob, b(29), DUNE_THEME, 78, contour=0.3)
    for t, q in ((b(30, 3), "C5"), (b(32, 3), "F4"), (b(34, 3), "F5"), (b(36, 3), "F4")):
        grace(ob, t, P(q), 62)
    for p_ in (vln1, vln2, ob):
        p_.expr([(b(29), 112), (b(30), 120), (b(32), 112), (b(33), 118), (b(34), 126), (b(35), 120),
                 (b(37) - 0.1, 96)])
    for bar in range(29, 37):
        ch = H.at(b(bar))
        r = bass_in(ch, 40, 51)
        fifth = next(m for m in range(r + 6, r + 9) if m % 12 in pcs(ch))
        hn2.n(b(bar), 4.06, r + 12 if r + 12 <= 57 else r, V(46, 64)(b(bar)))
        hn1.n(b(bar), 4.06, fifth + 12 if fifth + 12 <= 64 else fifth, V(46, 64)(b(bar)))
    for p_ in (hn1, hn2):
        p_.expr([(b(29), 96), (b(34), 116), (b(37) - 0.1, 80)])
    pad([(pad1, [0, 1, 2])], H, b(29), b(37), [(50, 57), (54, 62), (57, 66)], V(38, 54),
        clear=[vln1, vln2, ob, hn1, hn2])
    kit.n(b(29), 4, 57, 48)
    kit.n(b(33), 4, 57, 44)

    # ---- coda: the caravan walks away; the flute calls once more; E major
    sing(ney, b(40, 3), "B4:1 C5:0.5 B4:0.5 A4:2 G#4:1 F4:1 G#4:2 R:2 E4:8", 58, contour=0.3)
    ney.expr([(b(40, 3), 80), (b(41), 100), (b(42), 92), (b(43), 84), (b(45), 40)])
    grace(ney, b(40, 3), P("C5"), 44)
    pad([(pad1, [0, 1, 2])], H, b(37), b(45), [(52, 59), (56, 64), (59, 68)], V(30, 44), clear=[ney])
    pad1.expr([(b(37), 86), (b(41), 80), (b(45), 30)])
    for i, q in enumerate(["E2", "B2", "E3", "G#3", "B3", "E4", "G#4", "B4"]):
        harp.n(b(44) + i * 0.2, 6 - i * 0.2, q, 44 + i)

    s.tempo = [(0, 1.0), (b(28, 3), 1.0), (b(29), 1.05), (b(29, 2), 1.0), (b(42), 1.0), (b(44), 1.15),
               (b(45) + 6, 1.35)]
    return s


DUNE_MIX = {"lead": (2.0, 0.30), "ney": (0.0, 0.44), "dulcimer": (-1.0, 0.32), "str": (0.0, 0.36),
            "low": (-2.0, 0.22), "brass": (-5.0, 0.40), "drums": (-1.0, 0.20), "cym": (-6.0, 0.40)}


# ================================================================== steppe
STEPPE_THEME = ("D4:1.5 E4:0.5 G4:2 A4:1 G4:0.5 E4:0.5 D4:2 C4:1.5 D4:0.5 F4:1 E4:1 D4:4 "
                "D4:1.5 E4:0.5 G4:1 A4:1 C5:2 B4:1 A4:1 A4:1.5 G4:0.5 F4:1 D4:1 G4:4")


def compose_steppe():
    """The low savannas and grass seas, where the herds run. G mixolydian at 88, the flattened seventh (F) the
    sound of the open plains. A cello sings the tune like a horsehead fiddle, long-breathed and pentatonic,
    over a drone of G and D, while a two-stringed lute (a nylon guitar) strums its fifths in a running
    down-up-down and the low strings canter beneath (two quick notes and a strong one, every beat). The
    fiddle takes the tune up an octave with the flute; the horns open a broad strain in C; the violins and
    horns sing the tune at the high point and widen it into a long cadence through F to G; then the herd
    runs on into the distance and the cello sings the first half once more."""
    s = Song("steppe", bpm=88, bars=48, tail=6.0)
    tune = ["G", "G", "F", "G", "G", "C", "F", "G"]
    H = Harmony(s, ["G", "G", "G", "G"] + tune + tune + ["C", "C", "F", "G", "Am", "Em", "F", "F"]
                + tune + ["F", "C", "F", "G"] + ["G", "G", "F", "G", "G", "G", "G", "G"])
    s.chord_at = H.at
    b = s.b

    vcl = s.part("cello", 42, "lead", pan=62, volume=104, hum=0.012, vjit=3, lag=0.012)
    fid = s.part("fiddle", 110, "lead", pan=48, volume=98, hum=0.01, vjit=3, lag=0.01)
    fl = s.part("flute", 73, "winds", pan=40, volume=94, hum=0.012, vjit=3, lag=0.01)
    lute = s.part("lute", 24, "lute", pan=30, volume=100, hum=0.004, vjit=5)
    gal = s.part("gallop", 48, "gallop", pan=70, volume=96, hum=0.004, vjit=4)
    galb = s.part("gallopb", 45, "gallop", pan=82, volume=92, hum=0.004, vjit=4)
    vln1 = s.part("vln1", 48, "str", pan=38, volume=100, hum=0.012, vjit=3, lag=0.01)
    vln2 = s.part("vln2", 48, "str", pan=46, volume=96, hum=0.012, vjit=3, lag=0.012)
    pad1 = s.part("pad1", 49, "str", pan=56, volume=88, hum=0.02)
    pad2 = s.part("pad2", 49, "str", pan=72, volume=86, hum=0.02)
    drone = s.part("drone", 42, "low", pan=76, volume=98, hum=0.01, vjit=3)
    cb = s.part("cb", 43, "low", pan=86, volume=96, hum=0.01, vjit=3)
    hn1 = s.part("hn1", 60, "brass", pan=70, volume=100, hum=0.012, lag=0.012)
    hn2 = s.part("hn2", 60, "brass", pan=78, volume=96, hum=0.014, lag=0.014)
    hn3 = s.part("hn3", 60, "brass", pan=84, volume=94, hum=0.014, lag=0.014)
    drum = s.part("drum", 0, "drums", drum=True, pan=60, volume=92, hum=0.006, vjit=5)
    timp = s.part("timp", 47, "drums", pan=58, volume=94, hum=0.004, vjit=2)
    kit = s.part("kit", 48, "drums", drum=True, pan=50, volume=82, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 49), [1, 4, 5, 12, 13, 20, 21, 28, 29, 34, 36, 40, 41, 44, 48],
                      [.30, .36, .42, .48, .52, .58, .60, .76, .88, .96, 1.0, .92, .52, .40, .22])

    def V(lo, hi):
        return lambda t: lo + (hi - lo) * inten[min(47, int(t // 4))]

    def fifth_of(ch, lo):
        r = bass_in(ch, lo, lo + 11)
        return r, next(m for m in range(r + 6, r + 9) if m % 12 in pcs(ch))

    # ---- the drone: G and D under the tune, the chord's root and fifth when it moves
    for bar in range(1, 49):
        r, f = fifth_of(H.at(b(bar)), 43)
        drone.chord(b(bar), 4.06, [r, f], V(40, 62)(b(bar)))
        cb.n(b(bar), 4.06, r - 12, V(40, 60)(b(bar)))
    for p_ in (drone, cb):
        p_.expr([(0, 40), (b(2), 90), (b(5), 96), (b(29), 110), (b(41), 96), (b(47), 80), (b(49) + 2, 30)])

    # ---- the lute strums its fifths: down, up, down on every beat
    def strums(b0, b1, vel):
        t = b0
        while t < b1 - 1e-6:
            r, f = fifth_of(H.at(t), 55)
            notes = [r, f, r + 12]
            v = vel(t)
            strum(lute, t, notes, 0.5, v + 6, spread=0.025)
            strum(lute, t + 0.5, notes[1:], 0.25, v - 8, spread=0.02, up=True)
            strum(lute, t + 0.75, notes, 0.25, v - 4, spread=0.02)
            t += 1
    strums(b(3), b(45), V(36, 58))

    # ---- the canter: two quick notes and a strong one, every beat
    def canter(b0, b1, vel):
        t = b0
        while t < b1 - 1e-6:
            r, f = fifth_of(H.at(t), 43)
            v = vel(t)
            for dt, q, a in ((0, r, -6), (0.25, r, -8), (0.5, f if int(t) % 2 else r + 12, 4)):
                gal.n(t + dt, 0.18, q, v + a)
            if int(round(t)) % 2 == 0:
                galb.n(t + 0.5, 0.4, r - 12, v - 4)
                drum.n(t + 0.5, 0.3, 64, v - 8)
            t += 1
    canter(b(5), b(41), V(34, 62))
    canter(b(41), b(45), lambda t: V(34, 54)(t) - 10 * (t - b(41)) / 16)

    # ---- intro: the wind over the grass (the flute's breath), the drone, then the lute
    sing(fl, b(1, 2), "D5:2 E5:1 G5:3 A5:1 G5:0.5 E5:0.5 D5:4 R:4", 50, contour=0.2)
    fl.expr([(b(1, 2), 60), (b(2), 96), (b(3), 84), (b(4), 70), (b(5) - 0.1, 40)])

    # ---- A: the cello sings the tune
    sing(vcl, b(5), STEPPE_THEME, 78, contour=0.3)
    for t, q in ((b(5) - 0.12, "C4"), (b(8) - 0.12, "E4"), (b(12) - 0.12, "A4")):
        grace(vcl, t + 0.12, P(q), 60)
    vcl.expr([(b(5), 96), (b(6), 110), (b(7), 104), (b(8), 112), (b(9) - 0.1, 90), (b(9), 100), (b(10), 116),
              (b(12), 108), (b(13) - 0.1, 90)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(5), b(13), [(55, 62), (59, 67), (62, 71)], V(30, 44), clear=[vcl])
    for p_ in (pad1, pad2):
        p_.expr([(b(5), 60), (b(7), 90)])

    # ---- A2: the fiddle an octave up with the flute; the cello holds a low counter-line
    sing(fid, b(13), STEPPE_THEME, 76, tr=12, contour=0.3)
    sing(fl, b(13), STEPPE_THEME, 60, tr=12, contour=0.3)
    for p_ in (fid, fl):
        p_.expr([(b(13), 92), (b(14), 106), (b(16), 112), (b(17) - 0.1, 92), (b(17), 100), (b(18), 116),
                 (b(20), 108), (b(21) - 0.1, 90)])
    sing(vcl, b(13), "G3:4 B3:4 A3:4 B3:4 G3:4 G3:4 A3:4 B3:4", 60, contour=0.1)
    vcl.expr([(b(13), 80), (b(17), 92), (b(21) - 0.1, 80)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(13), b(21), [(55, 62), (59, 67), (62, 71)], V(32, 46),
        clear=[fid, fl, vcl])

    # ---- B: the horns open a broad strain in C; the violins hold under them and climb
    strain = "G4:2 C5:2 E5:2 D5:1 C5:1 C5:2 A4:2 B4:2 D5:2 E5:2 C5:2 B4:2 E5:2 F5:2 E5:1 D5:1 C5:4"
    sing(hn1, b(21), strain, 76, tr=-12, contour=0.3)
    sing(vln1, b(21), strain, 70, contour=0.3)
    hn1.expr([(b(21), 90), (b(23), 104), (b(25), 110), (b(27), 118), (b(29) - 0.1, 112)])
    vln1.expr([(b(21), 80), (b(25), 100), (b(29) - 0.1, 116)])
    pad([(hn2, [1]), (hn3, [0])], H, b(21), b(29), [(46, 55), (50, 59)], V(40, 56), clear=[hn1, vln1])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(21), b(29), [(52, 60), (56, 64), (60, 69)], V(34, 50),
        clear=[hn1, vln1])
    for p_ in (hn2, hn3, pad1, pad2):
        p_.expr([(b(21), 80), (b(25), 96), (b(29) - 0.1, 112)])
    roll(timp, s, b(28), 4, P("D2"), 30, 70, rate=0.125)
    roll(kit, s, b(28), 4, 57, 14, 50, rate=0.125)

    # ---- C: the tune at the high point, then widened into a long cadence through F to G
    sing(vln1, b(29), STEPPE_THEME + " F5:4 E5:4 C5:4 D5:4", 84, tr=12, contour=0.3)
    sing(vln2, b(29), STEPPE_THEME + " F5:4 E5:4 C5:4 D5:4", 78, contour=0.3)
    sing(hn1, b(29), STEPPE_THEME + " A4:4 G4:4 A4:4 B4:4", 80, contour=0.3)
    sing(fid, b(29), STEPPE_THEME + " F5:4 E5:4 C5:4 D5:4", 70, tr=12, contour=0.3)
    for p_ in (vln1, vln2, hn1, fid):
        p_.expr([(b(29), 112), (b(30), 120), (b(32), 116), (b(33), 112), (b(34), 124), (b(36), 118),
                 (b(37), 112), (b(39), 120), (b(41) - 0.1, 104)])
    pad([(hn2, [1]), (hn3, [0])], H, b(29), b(41), [(46, 55), (50, 59)], V(46, 62), clear=[vln1, vln2, hn1])
    for p_ in (hn2, hn3):
        p_.expr([(b(29), 104), (b(36), 116), (b(41) - 0.1, 90)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(29), b(41), [(52, 60), (56, 64), (60, 69)], V(36, 52),
        clear=[vln1, vln2, hn1, fid])
    for bar in (29, 33, 37):
        timp.n(b(bar), 2, "G1", 66)
    roll(timp, s, b(40), 4, P("D2"), 36, 66, rate=0.125)
    kit.n(b(29), 4, 57, 46)
    kit.n(b(37), 4, 57, 40)

    # ---- coda: the herd runs on into the distance; the cello sings the first half once more
    sing(vcl, b(41), "D4:1.5 E4:0.5 G4:2 A4:1 G4:0.5 E4:0.5 D4:2 C4:1.5 D4:0.5 F4:1 E4:1 D4:2 R:2 G3:8", 70,
         contour=0.3)
    vcl.expr([(b(41), 92), (b(42), 106), (b(43), 98), (b(44), 92), (b(45), 84), (b(47), 70), (b(49), 40)])
    sing(fl, b(46), "D6:2 G5:6", 44, contour=0.1)
    fl.expr([(b(46), 50), (b(47), 90), (b(49), 40)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(41), b(49), [(55, 62), (59, 67), (62, 71)], V(30, 44),
        clear=[vcl, fl])
    for p_ in (pad1, pad2):
        p_.expr([(b(41), 84), (b(45), 80), (b(49), 30)])
    for i, q in enumerate(["G2", "D3", "G3", "B3", "D4", "G4"]):
        lute.n(b(48) + i * 0.08, 6 - i * 0.08, q, 46 + i)

    s.tempo = [(0, 1.0), (b(28, 3), 1.0), (b(29), 1.05), (b(29, 2), 1.0), (b(46), 1.0), (b(48), 1.15),
               (b(49) + 6, 1.35)]
    return s


STEPPE_MIX = {"lead": (2.0, 0.30), "winds": (-1.0, 0.36), "lute": (-3.0, 0.26), "gallop": (-2.0, 0.22),
              "str": (0.0, 0.36), "low": (-3.0, 0.22), "brass": (-3.0, 0.40), "drums": (-2.0, 0.30)}


# ================================================================== jungle
JUNGLE_THEME = ("B4:0.5 D5:0.5 F#5:1.5 E5:0.5 D5:1 E5:1.5 G#5:0.5 B5:1 G#5:1 A5:1.5 F#5:0.5 D5:1 E5:1 C#5:3 R:1 "
                "A4:0.5 D5:0.5 F#5:1.5 E5:0.5 D5:1 E5:1.5 C#5:0.5 A4:1 C#5:1 B4:1 E5:1 G#5:1 F#5:0.5 E5:0.5 "
                "F#5:1 D5:1 B4:2")
JUNGLE_HIGH = ("B4:0.5 D5:0.5 F#5:1.5 E5:0.5 D5:1 E5:1.5 G#5:0.5 B5:1 G#5:1 A5:1.5 F#5:0.5 D5:1 E5:1 C#5:3 R:1 "
               "F#5:0.5 A5:0.5 D6:1.5 C#6:0.5 B5:1 C#6:1.5 B5:0.5 A5:1 E5:1 G#5:1 B5:1 E6:1 D6:0.5 C#6:0.5 "
               "C#6:3 R:1")
JUNGLE_B = ("F#4:2 A4:1 D5:1 C#5:2 B4:1 A4:1 B4:1.5 G#4:0.5 E4:2 F#4:3 R:1 "
            "A4:2 D5:1 F#5:1 E5:2 C#5:1 A4:1 G#4:1.5 A4:0.5 B4:1 C#5:1 B4:4")


def compose_jungle():
    """Jungles: green and loud with life, ruins under the creepers. B dorian at 96 (B minor with a raised sixth,
    so E is a major chord: the brightness under the canopy). A marimba keeps the forest's pulse in a 3+3+2
    ostinato, congas and a shaker round it, while a kalimba drips and the birds call (a piccolo, quick and
    high). The pan flute sings the tune, then sings it again climbing higher; a cello takes a warmer strain
    through D, A and E; the bass climbs a step at a time into the high point, where the violins and horns
    take the high tune with the drums at full cry, and the cello's strain comes back in the violins to
    close it. Then the forest quiets, and the flute's first phrase is left to the birds."""
    s = Song("jungle", bpm=96, bars=54, tail=6.0)
    tune = ["Bm", "E", "Bm", "E", "D", "A", "E", "Bm"]
    H = Harmony(s, ["Bm", "Bm", "E/B", "Bm", "Bm", "E/B"] + tune + tune[:7] + ["A"]
                + ["D", "A", "E", "Bm", "D", "A", "E", "E"] + ["Bm", "A/C#", "D", "E"]
                + tune[:7] + ["A"] + ["D", "A", "E", "E"] + ["Bm", "E", "Bm", "E", "Bm", "E/B", "Bm", "Bm"])
    s.chord_at = H.at
    b = s.b

    pf = s.part("panflute", 75, "lead", pan=56, volume=104, hum=0.012, vjit=3, lag=0.01)
    vcl = s.part("cello", 42, "lead", pan=66, volume=102, hum=0.012, vjit=3, lag=0.012)
    mar = s.part("marimba", 12, "mallets", pan=42, volume=100, hum=0.003, vjit=5)
    kal = s.part("kalimba", 108, "mallets", pan=26, volume=92, hum=0.003, vjit=4)
    pic = s.part("birds", 72, "birds", pan=18, volume=80, hum=0.004, vjit=4)
    vln1 = s.part("vln1", 48, "str", pan=38, volume=100, hum=0.012, vjit=3, lag=0.01)
    vln2 = s.part("vln2", 48, "str", pan=46, volume=96, hum=0.012, vjit=3, lag=0.012)
    trem = s.part("trem", 44, "str", pan=52, volume=88, hum=0.01, vjit=2)
    pad1 = s.part("pad1", 49, "str", pan=58, volume=88, hum=0.02)
    pad2 = s.part("pad2", 49, "str", pan=72, volume=86, hum=0.02)
    drone = s.part("drone", 42, "low", pan=76, volume=96, hum=0.01, vjit=3)
    cb = s.part("cb", 43, "low", pan=86, volume=96, hum=0.01, vjit=3)
    pz = s.part("pizz", 45, "low", pan=70, volume=100, hum=0.004, vjit=4)
    hn1 = s.part("hn1", 60, "brass", pan=70, volume=100, hum=0.012, lag=0.012)
    hn2 = s.part("hn2", 60, "brass", pan=78, volume=96, hum=0.014, lag=0.014)
    hn3 = s.part("hn3", 60, "brass", pan=84, volume=94, hum=0.014, lag=0.014)
    aah = s.part("aahs", 52, "choir", pan=50, volume=86, hum=0.02)
    cga = s.part("congas", 0, "drums", drum=True, pan=62, volume=94, hum=0.006, vjit=5)
    shk = s.part("shaker", 0, "drums", drum=True, pan=36, volume=80, hum=0.004, vjit=5)
    wb = s.part("woodblock", 0, "drums", drum=True, pan=30, volume=80, hum=0.004, vjit=4)
    tom = s.part("toms", 0, "drums", drum=True, pan=56, volume=92, hum=0.006, vjit=4)
    timp = s.part("timp", 47, "drums", pan=58, volume=94, hum=0.004, vjit=2)
    kit = s.part("kit", 48, "drums", drum=True, pan=50, volume=82, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 55), [1, 6, 7, 14, 15, 22, 23, 30, 31, 34, 35, 40, 42, 46, 47, 50, 54],
                      [.32, .40, .44, .50, .52, .58, .52, .60, .68, .86, .94, 1.0, .98, .90, .52, .40, .22])

    def V(lo, hi):
        return lambda t: lo + (hi - lo) * inten[min(53, int(t // 4))]

    def fifth_of(ch, lo):
        """the bass and the chord tone a fifth above it (a fourth or sixth over a slash bass)"""
        r = bass_in(ch, lo, lo + 11)
        return r, next(m for m in (r + 7, r + 6, r + 8, r + 5, r + 9) if m % 12 in pcs(ch))

    # ---- the marimba's pulse: eighths in 3+3+2 from the chord's ladder, the groups' first notes leaned on
    def pulse(b0, b1, vel, only=None):
        t = b0
        while t < b1 - 1e-6:
            k = int(round((t % 4) / 0.5))
            if only is None or k in only:
                lad = ladder(H.at(t), 47, 76)
                q = lad[min([0, 2, 3, 1, 2, 3, 1, 4][k], len(lad) - 1)]
                mar.n(t, 0.45, q, vel(t) + (8 if k in (0, 3, 6) else 0))
            t += 0.5
    pulse(b(5), b(47), V(40, 64))
    pulse(b(47), b(51), lambda t: V(40, 56)(t) - 8 * (t - b(47)) / 16, only=(0, 3, 6))

    # ---- the drums
    def congas(bars, vel, busy=False):
        for bar in bars:
            t0, v = b(bar), vel(b(bar))
            for dt, q, a in ((0, 62, -6), (1, 62, 4), (2, 62, -6), (2.5, 64, 0), (3, 63, 2), (3.5, 63, 0)):
                cga.n(t0 + dt, 0.3, q, v + a)
            if busy:
                for dt, q, a in ((0.5, 62, -12), (1.5, 63, -8), (3.75, 64, -6)):
                    cga.n(t0 + dt, 0.3, q, v + a)

    def shaker(bars, vel):
        for bar in bars:
            t0, v = b(bar), vel(b(bar))
            for k in range(8):
                shk.n(t0 + k * 0.5, 0.2, 70, v + (4 if k % 2 else -6))

    def clave(bars, vel):
        for bar in bars:
            for dt in ((0, 1.5, 3) if bar % 2 else (1, 2)):
                wb.n(b(bar) + dt, 0.2, 77, vel(b(bar)))

    congas(range(6, 23), V(44, 70))
    congas(range(23, 31), V(36, 58))
    congas(range(31, 47), V(46, 76), busy=True)
    congas(range(47, 50), lambda t: V(40, 60)(t) - 6 * (t - b(47)) / 12)
    shaker(range(3, 51), lambda t: V(30, 52)(t) - (6 if t < b(5) else 0))
    clave(range(15, 31), V(34, 50))
    clave(range(35, 47), V(40, 56))
    for bar in range(35, 47):
        for dt, q, a in ((0, 41, 6), (1.5, 43, 0), (3, 41, 2), (3.5, 45, -4)):
            tom.n(b(bar) + dt, 0.4, q, V(40, 62)(b(bar)) + a)

    # ---- the low strings: a drone of B under the opening and the close, a pizzicato bass on the 3+3+2
    for bar in list(range(1, 7)) + list(range(51, 55)):
        r, f = fifth_of(H.at(b(bar)), 43)
        drone.chord(b(bar), 4.06, [r, f], V(42, 62)(b(bar)))
        cb.n(b(bar), 4.06, r - 12, V(40, 58)(b(bar)))
    for bar in range(23, 35):
        cb.n(b(bar), 4.06, bass_in(H.at(b(bar)), 31, 42), V(40, 60)(b(bar)))
    for p_ in (drone, cb):
        p_.expr([(0, 40), (b(2), 90), (b(5), 96), (b(23), 84), (b(31), 104), (b(35), 96), (b(51), 84),
                 (b(55) + 2, 30)])

    def tresillo(bars, vel, lo=35):
        for bar in bars:
            r, f = fifth_of(H.at(b(bar)), lo)
            v = vel(b(bar))
            for dt, q, a in ((0, r, 6), (1.5, r, 0), (3, f, -2)):
                pz.n(b(bar) + dt, 0.9, q, v + a)
    tresillo(range(7, 23), V(46, 70))
    for bar in range(23, 31):
        pz.n(b(bar), 1.4, bass_in(H.at(b(bar)), 35, 46), V(40, 60)(b(bar)))
    tresillo(range(31, 47), V(52, 76))

    # ---- intro: the drone, the shaker, the flute's call; then the marimba alone
    sing(pf, b(2), "F#5:2 E5:1 D5:1 B4:4", 60, contour=0.2)
    pf.expr([(b(2), 70), (b(2, 3), 100), (b(3), 92), (b(4) - 0.1, 50)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(1), b(7), [(54, 62), (59, 66), (62, 71)], V(30, 42), clear=[pf])
    for p_ in (pad1, pad2):
        p_.expr([(0, 40), (b(2), 84), (b(5), 76), (b(7) - 0.1, 70)])

    # ---- A: the pan flute's tune over the pulse
    sing(pf, b(7), JUNGLE_THEME, 72, contour=0.3)
    pf.expr([(b(7), 96), (b(8), 110), (b(9), 104), (b(10), 112), (b(11) - 0.1, 90), (b(11), 98), (b(12), 110),
             (b(13), 114), (b(15) - 0.1, 90)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(7), b(15), [(54, 62), (59, 66), (62, 71)], V(30, 44), clear=[pf])

    # ---- A2: the tune again, climbing higher; the violins hold a soft line under it
    sing(pf, b(15), JUNGLE_HIGH, 74, contour=0.3)
    pf.expr([(b(15), 96), (b(16), 108), (b(17), 104), (b(18), 108), (b(19) - 0.1, 90), (b(19), 100),
             (b(20), 110), (b(21), 112), (b(23) - 0.1, 94)])
    sing(vln2, b(15), "D5:4 B4:4 D5:4 C#5:4 D5:4 E5:4 E5:4 E5:4", 52, contour=0.1)
    vln2.expr([(b(15), 60), (b(17), 84), (b(21), 96), (b(23) - 0.1, 70)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(15), b(23), [(54, 62), (59, 66), (62, 71)], V(32, 46),
        clear=[pf, vln2])

    # ---- B: the cello's warmer strain through D, A and E; the flute answers at the ends of phrases
    sing(vcl, b(23), JUNGLE_B, 76, tr=-12, contour=0.3)
    vcl.expr([(b(23), 94), (b(24), 108), (b(25), 104), (b(26), 98), (b(27) - 0.1, 86), (b(27), 100),
              (b(28), 114), (b(29), 110), (b(31) - 0.1, 90)])
    sing(pf, b(26, 3), "F#5:0.5 D5:0.5 B4:1", 58, contour=0.1)
    sing(pf, b(30, 3), "G#5:0.5 F#5:0.5 E5:1", 58, contour=0.1)
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(23), b(31), [(54, 62), (59, 66), (62, 71)], V(32, 46),
        clear=[vcl, pf])
    for p_ in (pad1, pad2):
        p_.expr([(b(23), 84), (b(27), 92), (b(31) - 0.1, 100)])

    # ---- the climb: the bass a step at a time (B, C sharp, D, E), the violins rising over trembling strings
    sing(vln1, b(31), "F#5:4 E5:4 F#5:4 G#5:4", 66, contour=0.2)
    sing(vln2, b(31), "B4:4 C#5:4 D5:4 E5:4", 60, contour=0.2)
    for p_ in (vln1, vln2):
        p_.expr([(b(31), 70), (b(33), 96), (b(35) - 0.1, 120)])
    pad([(trem, [0, 1, 2])], H, b(31), b(35), [(54, 61), (57, 64), (61, 68)], V(36, 56), clear=[vln1, vln2])
    trem.expr([(b(31), 50), (b(35) - 0.1, 110)])
    pad([(hn2, [1]), (hn3, [0])], H, b(31), b(35), [(46, 55), (50, 59)], V(40, 58), clear=[vln1, vln2])
    for p_ in (hn2, hn3):
        p_.expr([(b(31), 60), (b(35) - 0.1, 112)])
    roll(timp, s, b(34), 4, P("E2"), 30, 72, rate=0.125)
    roll(kit, s, b(34), 4, 57, 14, 52, rate=0.125)

    # ---- C: the high tune in the violins and horns, the drums at full cry; the cello's strain to close it
    high = JUNGLE_HIGH + " F#5:2 A5:1 D6:1 C#6:2 B5:1 A5:1 B5:1.5 G#5:0.5 E5:2 E5:4"
    sing(vln1, b(35), high, 84, contour=0.3)
    sing(vln2, b(35), high, 78, tr=-12, contour=0.3)
    sing(pf, b(35), JUNGLE_HIGH, 66, contour=0.3)
    sing(hn1, b(35), JUNGLE_HIGH + " A4:2 D5:2 E5:2 C#5:2 E5:2 B4:2 G#4:4", 78, tr=-12, contour=0.3)
    for p_ in (vln1, vln2, hn1, pf):
        p_.expr([(b(35), 112), (b(36), 120), (b(37), 114), (b(38), 118), (b(39), 116), (b(40), 124),
                 (b(41), 126), (b(43), 118), (b(44), 122), (b(45), 116), (b(47) - 0.1, 96)])
    pad([(hn2, [1]), (hn3, [0])], H, b(35), b(47), [(46, 55), (50, 59)], V(46, 62), clear=[vln1, vln2, hn1, pf])
    for p_ in (hn2, hn3):
        p_.expr([(b(35), 104), (b(41), 116), (b(47) - 0.1, 90)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(35), b(47), [(52, 60), (56, 64), (60, 69)], V(36, 52),
        clear=[vln1, vln2, hn1, pf])
    pad([(aah, [0, 1])], H, b(35), b(47), [(62, 69), (66, 74)], V(34, 50), clear=[vln1, vln2, hn1, pf])
    aah.expr([(b(35), 70), (b(39), 96), (b(43), 104), (b(47) - 0.1, 70)])
    for bar, q in ((35, "B2"), (39, "B2"), (43, "D3")):
        timp.n(b(bar), 2, q, 64)
    roll(timp, s, b(46), 4, P("E2"), 36, 64, rate=0.125)
    kit.n(b(35), 4, 57, 48)
    kit.n(b(43), 4, 57, 42)

    # ---- coda: the forest quiets; the flute's first phrase, then the last call over the drone
    sing(pf, b(47), "B4:0.5 D5:0.5 F#5:1.5 E5:0.5 D5:1 E5:1.5 G#5:0.5 B5:1 G#5:1 "
                    "A5:1.5 F#5:0.5 D5:1 E5:1 C#5:3 R:1", 66, contour=0.3)
    sing(pf, b(53), "F#5:1 D5:1 B4:6", 56, contour=0.2)
    pf.expr([(b(47), 96), (b(48), 104), (b(49), 98), (b(51) - 0.1, 80), (b(53), 84), (b(54), 70),
             (b(55) + 1, 30)])
    pad([(pad1, [0, 1]), (pad2, [2])], H, b(47), b(55), [(54, 62), (59, 66), (62, 71)], V(30, 44), clear=[pf])
    for p_ in (pad1, pad2):
        p_.expr([(b(47), 84), (b(51), 80), (b(55), 30)])
    for i, q in enumerate(["B3", "F#4", "B4", "D5", "F#5"]):
        kal.n(b(54) + i * 0.25, 6 - i * 0.25, q, 50 + 2 * i)

    # ---- the canopy: the kalimba's drops and the birds, kept clear of everything held
    held = [pf, vcl, vln1, vln2, hn1, hn2, hn3, pad1, pad2, trem, aah]
    rng = np.random.default_rng(97)

    def drops(b0, b1, gap, vel, lo=71, hi=90):
        t = b0 + rng.uniform(0, gap)
        while t < b1 - 0.5:
            t = round(t * 4) / 4
            ch = H.at(t)
            near = [n[2] for p_ in held for n in p_.notes if n[0] < t + 1.5 and n[0] + n[1] > t and n[1] >= 0.75]
            cands = [m for m in range(lo, hi + 1) if m % 12 in pcs(ch)
                     and not any(abs(m - x) % 12 in (1, 11) for x in near)]
            if cands:
                q = cands[int(rng.integers(len(cands)))]
                i = bisect_right(H.starts, t + 1e-6)
                nxt = H.starts[i] if i < len(H.starts) else s.end_beat + 64
                kal.n(t, min(2.0, nxt - t + 0.05), q, vel(t) + rng.uniform(-6, 4))
                if rng.random() < 0.3 and t + 0.25 < nxt:
                    below = [m for m in cands if m < q]
                    if below:
                        kal.n(t + 0.25, min(1.5, nxt - t - 0.2), below[-1], vel(t) - 10)
            t += gap * rng.uniform(0.6, 1.4)

    def bird(t, kind, v):
        if kind == 0:
            for i in range(6):
                pic.n(t + i * 0.125, 0.11, P("C#7") if i % 2 else P("B6"), v - i)
        elif kind == 1:
            pic.n(t, 0.2, "F#6", v)
            pic.n(t + 0.25, 0.5, "B6", v + 4)
        else:
            for i, q in enumerate(["E7", "C#7", "B6", "G#6"]):
                pic.n(t + i * 0.125, 0.12, q, v - 2 * i)

    drops(b(1), b(7), 2.5, V(40, 54))
    drops(b(7), b(23), 4.0, V(34, 48))
    drops(b(23), b(31), 2.5, V(38, 52))
    drops(b(47), b(54), 2.0, V(40, 54))
    for t in sorted(rng.uniform(b(1), b(6), 6)) + sorted(rng.uniform(b(23), b(31), 3)) \
            + sorted(rng.uniform(b(48), b(54), 4)):
        bird(round(t * 4) / 4, int(rng.integers(3)), int(rng.integers(30, 44)))

    s.tempo = [(0, 1.0), (b(34, 3), 1.0), (b(35), 1.04), (b(35, 2), 1.0), (b(52), 1.0), (b(54), 1.15),
               (b(55) + 6, 1.35)]
    return s


JUNGLE_MIX = {"lead": (2.0, 0.32), "mallets": (0.0, 0.30), "birds": (-3.0, 0.55), "str": (0.0, 0.36),
              "low": (-2.0, 0.22), "brass": (-3.0, 0.40), "choir": (-6.0, 0.48), "drums": (-1.0, 0.22)}


# ================================================================== mushroom isles
MUSH_THEME = ("C5:1 F5:1 A5:1 G5:1.5 F5:0.5 D5:1 B5:2 A5:1 G5:1 D5:1 B4:1 C5:1 E5:1 A5:1 F5:1.5 E5:0.5 D5:1 "
              "B4:1 D5:1 G5:1 E5:3 "
              "C5:1 F5:1 A5:1 G5:1.5 F5:0.5 D5:1 B5:2 D6:1 A5:1 G5:1 D5:1 F5:1 A5:1 D6:1 D6:1.5 C6:0.5 B5:1 "
              "G5:1 E5:1 C5:1 F5:3")
MUSH_DREAM = ("A4:2 D5:1 F5:3 E5:2 G5:1 B5:3 A5:2 G5:1 D5:3 E5:2 C5:1 A4:3 "
              "A4:2 D5:1 F5:3 G5:2 B5:1 E6:3 C6:2 A5:1 B5:3 C#6:2 E5:1 E5:3")


def compose_mushroom():
    """Mushroom isles: soft purple ground, giant caps and the mooshrooms' slow grazing, a little unreal. F
    lydian at 100 in three (F major with B natural, so the G chord sits over F like a held breath). A music
    box plays the tune over a waltz, a bassoon's staccato oom and the pizzicato pah-pah; a celesta joins it;
    then the clarinet takes the tune low while the music box turns figures over it and a glockenspiel
    twinkles. In the middle the waltz goes dreamy (D minor, voices and a harp) and the violins sing a long
    line that lands, strangely, on A major; from there the tune returns grand, in the violins with the horns
    and voices, the celesta high above. Then the music box plays its opening once more and runs down."""
    s = Song("mushroom", bpm=100, bars=76, bpb=3, tail=6.0)
    ph1 = ["F", "F", "G/F", "G/F", "Am", "Dm", "G", "C"]
    ph2 = ["F", "F", "G/F", "G/F", "Dm", "G", "C", "F"]
    dream = ["Dm", "Dm", "Em", "Em", "F", "G", "Am", "Am", "Dm", "Dm", "Em", "Em", "F", "G", "A", "A"]
    H = Harmony(s, ["F", "G/F", "F", "G/F", "F", "F"] + ph1 + ph2 + ph1 + ph2 + dream + ph1 + ph2
                + ["F", "F", "G/F", "G/F", "F", "F"])
    s.chord_at = H.at
    b = s.b

    box = s.part("musicbox", 10, "box", pan=44, volume=100, hum=0.002, vjit=4)
    cel = s.part("celesta", 8, "box", pan=30, volume=94, hum=0.002, vjit=4)
    glk = s.part("glock", 9, "box", pan=24, volume=86, hum=0.002, vjit=3)
    cl = s.part("clarinet", 71, "winds", pan=58, volume=102, hum=0.012, vjit=3, lag=0.01)
    bsn = s.part("bassoon", 70, "winds", pan=68, volume=100, hum=0.008, vjit=4)
    pz = s.part("pizz", 45, "pizz", pan=62, volume=96, hum=0.004, vjit=4)
    vln1 = s.part("vln1", 48, "str", pan=38, volume=100, hum=0.012, vjit=3, lag=0.01)
    vln2 = s.part("vln2", 48, "str", pan=46, volume=96, hum=0.012, vjit=3, lag=0.012)
    pad1 = s.part("pad1", 49, "str", pan=56, volume=86, hum=0.02)
    ohh = s.part("oohs", 53, "choir", pan=50, volume=88, hum=0.02)
    harp = s.part("harp", 46, "harp", pan=72, volume=94, hum=0.006, vjit=4)
    cb = s.part("cb", 43, "low", pan=80, volume=94, hum=0.01, vjit=3)
    hn1 = s.part("hn1", 60, "brass", pan=72, volume=96, hum=0.014, lag=0.014)
    hn2 = s.part("hn2", 60, "brass", pan=80, volume=92, hum=0.014, lag=0.014)
    timp = s.part("timp", 47, "perc", pan=58, volume=90, hum=0.004, vjit=2)
    kit = s.part("kit", 48, "perc", drum=True, pan=40, volume=80, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 77), [1, 6, 7, 22, 23, 38, 39, 46, 47, 54, 55, 62, 63, 70, 71, 76],
                      [.32, .40, .44, .52, .54, .62, .50, .58, .62, .74, .90, .96, 1.0, .92, .46, .24])

    def V(lo, hi):
        return lambda t: lo + (hi - lo) * inten[min(75, int(t // 3))]

    bars_ch = [H.at(b(bar)) for bar in range(1, 77)]
    upper = voice_lead(bars_ch, [(55, 62), (59, 67)])

    # ---- the waltz: the bassoon's staccato oom, the pizzicato pah-pah (the harp's, in the dream)
    def waltz(bar0, bar1, vel, oom=bsn, pah=pz, oom_len=0.5):
        for bar in range(bar0, bar1):
            ch, v = H.at(b(bar)), vel(b(bar))
            oom.n(b(bar), oom_len, bass_in(ch, 41, 52), v + 4)
            for beat, a in ((1, -2), (2, -6)):
                pah.chord(b(bar) + beat, 0.4, upper[bar - 1], v + a)
    waltz(5, 7, lambda t: V(38, 60)(t) - 6)
    waltz(7, 39, V(40, 64))
    waltz(39, 55, V(36, 56), oom=harp, pah=harp, oom_len=1.5)
    waltz(55, 71, V(44, 70))
    waltz(71, 75, lambda t: V(36, 56)(t) - 8 * (t - b(71)) / 12)
    for bar in range(39, 71):
        cb.n(b(bar), 3.05, bass_in(H.at(b(bar)), 29, 40), V(38, 58)(b(bar)))
    cb.expr([(b(39), 70), (b(47), 90), (b(55), 104), (b(71) - 0.1, 90)])

    # ---- intro: voices and a harp's slow rise; the waltz begins under them
    pad([(ohh, [0, 1, 2])], H, b(1), b(7), [(53, 60), (57, 64), (60, 69)], V(32, 44))
    ohh.expr([(0, 30), (b(2), 84), (b(5), 90), (b(7) - 0.1, 72)])
    for bar in range(1, 5):
        rise = ["F3", "C4", "A4", "C5", "E5", "A5"] if bar % 2 else ["F3", "D4", "G4", "B4", "D5", "G5"]
        for i, q in enumerate(rise):
            harp.n(b(bar) + i * 0.25, 3.05 - i * 0.25, q, 42 + 2 * i - (4 if bar > 2 else 0))

    # ---- A: the music box's tune, the celesta joining it an octave up for the second phrase
    sing(box, b(7), MUSH_THEME, 70, contour=0.3)
    sing(cel, b(15), MUSH_THEME.split(" E5:3 ", 1)[1], 50, tr=12, contour=0.3)
    pad([(pad1, [0, 1, 2])], H, b(7), b(23), [(53, 60), (57, 64), (60, 69)], V(28, 40), clear=[box, cel])
    pad1.expr([(b(7), 60), (b(9), 84)])

    # ---- A2: the clarinet takes the tune low; the music box turns figures over it, the glockenspiel twinkles
    sing(cl, b(23), MUSH_THEME, 66, tr=-12, contour=0.3)
    cl.expr([(b(23), 88), (b(25), 100), (b(27), 96), (b(29), 102), (b(31) - 0.1, 88), (b(31), 96),
             (b(33), 106), (b(35), 108), (b(39) - 0.1, 88)])
    arp(box, H, b(23), b(39), [0, 2, 1, 3, 2, 1], 67, 90, V(44, 58), step=0.5, dur=1.0, bar_len=3)
    for bar in range(23, 39, 2):
        tones = [m for m in range(84, 97) if m % 12 in pcs(H.at(b(bar)))]
        glk.n(b(bar), 2.0, tones[len(tones) // 2], V(38, 50)(b(bar)))
    for bar in (23, 31):
        kit.n(b(bar), 2, 81, 40)
    pad([(pad1, [0, 1, 2])], H, b(23), b(39), [(53, 60), (57, 64), (60, 69)], V(30, 42), clear=[cl])
    pad([(ohh, [1, 2])], H, b(31), b(39), [(53, 60), (60, 67), (64, 72)], V(28, 40), clear=[cl])
    ohh.expr([(b(31), 50), (b(35), 84), (b(39) - 0.1, 90)])

    # ---- the dream: D minor, voices, the harp's waltz; the violins' long line lands on A major
    sing(vln1, b(39), MUSH_DREAM, 72, contour=0.3)
    sing(cl, b(47), "F4:3 D4:3 E4:3 G4:3 F4:3 D4:3 C#4:3 E4:3", 58, contour=0.1)
    vln1.expr([(b(39), 84), (b(41), 100), (b(43), 96), (b(45), 104), (b(47) - 0.1, 90), (b(47), 98),
               (b(49), 110), (b(51), 116), (b(53), 112), (b(55) - 0.1, 104)])
    cl.expr([(b(47), 80), (b(51), 96), (b(55) - 0.1, 90)])
    pad([(ohh, [0, 1, 2])], H, b(39), b(55), [(53, 60), (57, 64), (60, 69)], V(34, 48), clear=[vln1, cl])
    ohh.expr([(b(39), 92), (b(47), 100), (b(55) - 0.1, 106)])
    pad([(pad1, [0, 1])], H, b(39), b(55), [(48, 55), (52, 59)], V(30, 42), clear=[vln1, cl])
    roll(timp, s, b(54), 3, P("C3"), 28, 60, rate=0.125)
    roll(kit, s, b(54), 3, 57, 14, 48, rate=0.125)

    # ---- the tune returns grand: violins in octaves, the celesta high, horns and voices under them
    sing(vln1, b(55), MUSH_THEME, 82, contour=0.3)
    sing(vln2, b(55), MUSH_THEME, 76, tr=-12, contour=0.3)
    sing(cel, b(55), MUSH_THEME, 54, tr=12, contour=0.3)
    sing(cl, b(55), MUSH_THEME, 56, tr=-12, contour=0.2)
    for p_ in (vln1, vln2):
        p_.expr([(b(55), 110), (b(57), 118), (b(59), 112), (b(61), 116), (b(63) - 0.1, 104), (b(63), 112),
                 (b(65), 122), (b(67), 124), (b(69), 116), (b(71) - 0.1, 96)])
    arp(box, H, b(55), b(71), [0, 2, 1, 3, 2, 1], 67, 90, V(42, 58), step=0.5, dur=1.0, bar_len=3)
    pad([(hn2, [0]), (hn1, [1])], H, b(55), b(71), [(48, 55), (53, 60)], V(42, 58), clear=[vln1, vln2, cl])
    for p_ in (hn1, hn2):
        p_.expr([(b(55), 90), (b(63), 110), (b(71) - 0.1, 84)])
    pad([(ohh, [0, 1, 2])], H, b(55), b(71), [(57, 64), (60, 67), (64, 72)], V(34, 50), clear=[vln1, vln2, cl])
    ohh.expr([(b(55), 96), (b(63), 110), (b(71) - 0.1, 90)])
    for bar in range(55, 71, 2):
        tones = [m for m in range(84, 97) if m % 12 in pcs(H.at(b(bar)))]
        glk.n(b(bar), 2.0, tones[len(tones) // 2], V(40, 54)(b(bar)))
    for bar in (55, 63):
        timp.n(b(bar), 2, "F2", 60)
        kit.n(b(bar), 3, 57, 40)
    for bar in (59, 67):
        kit.n(b(bar), 2, 81, 42)

    # ---- coda: the music box plays its opening once more and runs down
    sing(box, b(71), "C5:1 F5:1 A5:1 G5:1.5 F5:0.5 D5:1 B5:2 A5:1 G5:1 D5:1 B4:1", 62, contour=0.3)
    for i, q in enumerate(["F4", "A4", "C5", "E5", "G5", "B5"]):
        box.n(b(75) + i * 0.5, 4.5 - i * 0.5, q, 58 - i)
    pad([(ohh, [0, 1, 2])], H, b(71), b(77), [(53, 60), (57, 64), (60, 69)], V(30, 42), clear=[box])
    ohh.expr([(b(71), 84), (b(74), 76), (b(77), 30)])
    pad([(pad1, [0, 1, 2])], H, b(71), b(77), [(53, 60), (57, 64), (60, 69)], V(26, 36), clear=[box])
    pad1.expr([(b(71), 70), (b(77), 20)])

    # ---- the celesta's glints over the opening and the dream, clear of everything held
    held = [vln1, vln2, cl, ohh, pad1, hn1, hn2, harp]
    rng = np.random.default_rng(113)

    def glints(b0, b1, gap, vel):
        t = b0 + rng.uniform(0, gap)
        while t < b1 - 0.5:
            t = round(t * 2) / 2
            ch = H.at(t)
            near = [n[2] for p_ in held for n in p_.notes if n[0] < t + 1.5 and n[0] + n[1] > t and n[1] >= 0.75]
            cands = [m for m in range(79, 96) if m % 12 in pcs(ch)
                     and not any(abs(m - x) % 12 in (1, 11) for x in near)]
            if cands:
                i = bisect_right(H.starts, t + 1e-6)
                nxt = H.starts[i] if i < len(H.starts) else s.end_beat + 64
                cel.n(t, min(1.5, nxt - t + 0.05), cands[int(rng.integers(len(cands)))], vel(t) + rng.uniform(-6, 4))
            t += gap * rng.uniform(0.6, 1.4)
    glints(b(1), b(7), 1.5, V(40, 52))
    glints(b(39), b(55), 2.5, V(34, 46))

    s.tempo = [(0, 1.0), (b(54, 2), 1.0), (b(55), 1.06), (b(55, 2), 1.0), (b(71), 1.0), (b(73), 1.08),
               (b(75), 1.25), (b(76), 1.45), (b(77) + 6, 1.6)]
    return s


MUSH_MIX = {"box": (0.0, 0.40), "winds": (0.0, 0.30), "pizz": (-2.0, 0.26), "str": (0.0, 0.38),
            "choir": (-4.0, 0.50), "harp": (-2.0, 0.40), "brass": (-5.0, 0.42), "low": (-2.0, 0.24),
            "perc": (-4.0, 0.36)}


# ================================================================== driver
PIECES = {
    "heartland": dict(fn=compose_heartland, mix=HEART_MIX, ir=(2.4, 201), fade_out=3.0,
                      fx={"wind": dict(hp=180), "str": dict(hp=110), "harp": dict(hp=70), "brass": dict(hp=90)}),
    "wildwood": dict(fn=compose_wildwood, mix=WOOD_MIX, ir=(2.2, 203), fade_out=3.0, comp=(-20.0, 1.8),
                     fx={"winds": dict(hp=80, shelf=(6000, 2.5)), "str": dict(hp=100, shelf=(6000, 2.5)),
                         "harp": dict(hp=60), "pizz": dict(hp=45)}),
    "dunes": dict(fn=compose_dunes, mix=DUNE_MIX, ir=(2.0, 207), fade_out=3.0, comp=(-20.0, 1.8),
                  fx={"lead": dict(hp=150), "ney": dict(hp=200), "dulcimer": dict(hp=120), "str": dict(hp=100)}),
    "steppe": dict(fn=compose_steppe, mix=STEPPE_MIX, ir=(2.0, 209), fade_out=3.0, comp=(-20.0, 1.8),
                   fx={"lead": dict(hp=70), "winds": dict(hp=200), "lute": dict(hp=90), "gallop": dict(hp=50),
                       "str": dict(hp=100)}),
    "jungle": dict(fn=compose_jungle, mix=JUNGLE_MIX, ir=(2.0, 211), fade_out=3.0, comp=(-20.0, 1.8),
                   fx={"lead": dict(hp=120), "mallets": dict(hp=70), "birds": dict(hp=700), "str": dict(hp=100),
                       "choir": dict(hp=150)}),
    "mushroom": dict(fn=compose_mushroom, mix=MUSH_MIX, ir=(2.4, 213), fade_out=3.0, comp=(-20.0, 1.8),
                     fx={"box": dict(hp=200), "winds": dict(hp=70), "pizz": dict(hp=60), "str": dict(hp=100),
                         "choir": dict(hp=150)}),
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
