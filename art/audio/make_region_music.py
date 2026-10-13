"""Guildmark region music: exploration themes for the wild and far lands, composed in code and rendered
with FluidSynth through the toolkit in make_music.py.

usage (from the repo root):
    python3 -B art/audio/make_region_music.py src/main/resources/assets/guildmark --work DIR
        [--sf2 FluidR3_GM.sf2] [--wav DIR] [--only marsh,ashen]

Minecraft plays one of these once in place of the overworld music while the player is in a region of that
kind, then waits minutes before the next, so each is a standalone piece with a soft opening and a real
ending, not a loop. They are calmer than the boss tracks: each is mixed like them (dry FluidSynth stems,
per-stem gain and reverb send into a synthetic hall, gentle glue compression), then mastered to about
-17 LUFS integrated with the true peak kept under -1 dBTP.
"""
import argparse
import os
import sys
import warnings
from itertools import product

import numpy as np
from scipy import signal
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_music import (SF2_DEFAULT, CHORDS, P, Song, export_stem, in_range, mix, pcs, render, roll,  # noqa: E402
                        stereo_limit, swell, voice_lead)
from sfxkit import SR, db, eq, hp, lp, rng_for, room_ir, write_ogg, write_wav  # noqa: E402

warnings.filterwarnings("ignore", category=wavfile.WavFileWarning)  # FluidSynth's float WAVs carry a LIST chunk

LUFS_TARGET = -17.0
TRUE_PEAK_MAX = -1.8  # before encoding; Vorbis adds a few tenths, and the files must stay under -1 dBTP

# chord spellings beyond make_music's (root, third, fifth, then the added note voice_lead tries to keep)
REGION_CHORDS = {
    "Dm9": "D F A E", "Bbmaj7": "Bb D F A", "Ebmaj7": "Eb G Bb D", "Em7b5": "E G Bb D", "A7": "A C# E G",
    "D5": "D A", "Gm7": "G Bb D F", "Fmaj7": "F A C E", "C7": "C E G Bb", "Em9": "E G B F#",
    "Cmaj7": "C E G B", "Am7": "A C E G", "Bsus4": "B E F#", "B": "B D# F#", "Bm": "B D F#", "C#m": "C# E G#",
    "Am9": "A C E B", "Em7": "E G B D", "Bm7": "B D F# A", "Abmaj7": "Ab C Eb G", "Fm9": "F Ab C G",
    "Cm7": "C Eb G Bb", "Emaj7": "E G# B D#", "F#": "F# A# C#", "C#m9": "C# E G# D#", "Bb7": "Bb D F Ab",
    "A5": "A E",
}
for _k, _v in REGION_CHORDS.items():
    assert _k not in CHORDS or CHORDS[_k] == _v, _k
    CHORDS[_k] = _v


# ------------------------------------------------------------------ mastering
def lufs(x):
    """integrated loudness of a stereo signal (ITU-R BS.1770: K-weighting, 400 ms blocks, both gates)"""
    z = 0.0
    n, step = int(0.4 * SR), int(0.1 * SR)
    for c in x:
        k = eq(eq(c, "highshelf", 1681.974450955533, 0.7071752369554196, 3.999843853973347),
               "hp", 38.13547087602444, 0.5003270373238773)
        cs = np.concatenate([[0.0], np.cumsum(k ** 2)])
        st = np.arange(0, len(k) - n + 1, step)
        z = z + (cs[st + n] - cs[st]) / n
    lv = -0.691 + 10 * np.log10(z + 1e-20)
    gated = z[lv > -70.0]
    rel = -0.691 + 10 * np.log10(gated.mean()) - 10.0
    return -0.691 + 10 * np.log10(z[(lv > -70.0) & (lv > rel)].mean())


def true_peak(x):
    """dBTP, from 4x oversampling"""
    return 20 * np.log10(np.max(np.abs(signal.resample_poly(x, 4, 1, axis=1))) + 1e-12)


def master(x, target=LUFS_TARGET, ceiling=TRUE_PEAK_MAX):
    """bring the mix to the target loudness, then limit only if the true peak would pass the ceiling"""
    x = x * db(target - lufs(x))
    for _ in range(6):
        if true_peak(x) <= ceiling:
            break
        x = stereo_limit(x, ceiling - 0.6)
        x = x * db(target - lufs(x))
    return x


# ------------------------------------------------------------------ per-stem processing
def delay(y, seconds):
    k = int(round(seconds * SR))
    return np.concatenate([np.zeros(k), y[:len(y) - k]])


def stem_fx(y, ops):
    """channel processing before the mix: ('hp', f) ('lp', f) ('eq', kind, f, q, gain)
    ('echo', [(seconds, gain, side), ...], lowpass_hz) where side -1 is left, 1 right, 0 both"""
    y = y.copy()
    for op in ops:
        if op[0] == "hp":
            y = np.vstack([hp(c, op[1]) for c in y])
        elif op[0] == "lp":
            y = np.vstack([lp(c, op[1]) for c in y])
        elif op[0] == "eq":
            y = np.vstack([eq(c, *op[1:]) for c in y])
        elif op[0] == "echo":
            src = lp(y.mean(axis=0), op[2])
            out = y.copy()
            for t, g, side in op[1]:
                d = delay(src, t) * g
                out[0] += d * (1.0 if side <= 0 else 0.35)
                out[1] += d * (1.0 if side >= 0 else 0.35)
            y = out
    return y


# ------------------------------------------------------------------ shared writing helpers
def drips(part, song, bars, chord_of, lo, hi, vel, seed, every=(1.5, 4.0), double=0.3, dur=1.5, damp=False):
    """sparse, irregular single notes from the harmony, some with a softer echo a step below; with damp, a note
    still ringing at the next bar line (where these pieces change chord) is stopped just after it"""
    rng = rng_for(song.name, part.name, seed)
    beat = song.b(bars[0]) + rng.uniform(0.0, every[0])
    end = song.b(bars[-1]) + song.bpb
    while beat < end:
        ch = chord_of(beat)
        cands = [m for m in range(lo, hi + 1) if m % 12 in pcs(ch)]
        q = int(rng.choice(cands))
        v = vel(beat) if callable(vel) else vel
        bar_end = (beat // song.bpb + 1) * song.bpb + 0.05

        def ring(t):
            return min(dur, bar_end - t) if damp else dur
        part.n(beat, ring(beat), q, v + rng.uniform(-6, 4))
        if rng.random() < double:
            below = [m for m in cands if m < q]
            if below:
                t = beat + rng.choice([0.25, 0.375, 0.5])
                part.n(t, ring(t), below[-1], v - 14)
        beat += rng.uniform(*every)
        beat = round(beat * 8) / 8


def voicings(chords, ranges, bass=None, start=None):
    """voice_lead with a cleaner ear for slow, exposed harmony: one note per voice (ranges low to high),
    moving as little as possible, covering root, third and fifth before any colour note, and never a minor
    second or minor ninth between two voices or against the bass note given for that chord"""
    out = []
    prev = start or [(lo + hi) // 2 for lo, hi in ranges]
    for i, ch in enumerate(chords):
        pc = pcs(ch)
        cand = [[m for m in range(lo, hi + 1) if m % 12 in pc] for lo, hi in ranges]
        b = P(bass[i]) if bass is not None else None
        best, bestc = None, 1e9
        for combo in product(*cand):
            if any(combo[k] >= combo[k + 1] for k in range(len(combo) - 1)):
                continue
            cost = sum(abs(x - y) for x, y in zip(combo, prev))
            have = [m % 12 for m in combo]
            for k, w in enumerate((12, 10, 6, 5)):
                if k < len(pc) and pc[k] not in have:
                    cost += w
            if len(pc) > 2 and have.count(pc[1]) > 1:
                cost += 4
            notes = list(combo) + ([b] if b is not None else [])
            for x in range(len(notes)):
                for y in range(x + 1, len(notes)):
                    iv = abs(notes[x] - notes[y])
                    if iv in (1, 13):
                        cost += 30
                    elif iv == 25:
                        cost += 6
                    elif iv == 2 and min(notes[x], notes[y]) < 55:
                        cost += 6
            if cost < bestc:
                best, bestc = combo, cost
        out.append(list(best))
        prev = list(best)
    return out


def place(part, song, bars, vcs, idxs, vel, legato=1.015, beats=None, clear=()):
    """put voices [idxs] of a per-bar voicing list on a part as held chords, leaving out any note a semitone (or
    a semitone and an octave or two) from a note of at least a beat that the parts in `clear` play in that bar"""
    for bar, vc in zip(bars, vcs):
        v = vel(bar) if callable(vel) else vel
        b0 = song.b(bar)
        b1 = b0 + (beats or song.bpb)
        near = [n[2] for p_ in clear for n in p_.notes if n[0] < b1 and n[0] + n[1] > b0 and n[1] >= 0.75]
        notes = [vc[i] for i in idxs if i < len(vc) and not any(abs(vc[i] - m) in (1, 13, 25) for m in near)]
        part.chord(b0, (beats or song.bpb) * legato, notes, v)


def sing_line(part, beat, spec, vel, tr=0, leg=1.0, contour=0.35):
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


def above(ch, q, gap=5):
    """the lowest tone of the chord at least `gap` semitones above q"""
    return next(m for m in range(q + gap, q + gap + 12) if m % 12 in pcs(ch))


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


# ================================================================== the marsh
def compose_marsh():
    """A rotting marsh under green murk. D, wavering between dorian (the B natural) and phrygian (the Eb).
    A lonely five-note motif rises a minor third, slips back a step and falls a fifth, and every time it
    returns it starts a step lower: the bass clarinet carries it down from A to A an octave below while
    a clarinet answers higher up and drifts the other way. Bowed glass and a contrabass drone hold the
    murk, harp, pizzicato and celesta drip in irregular time. The strings take the motif at the
    climax over a falling bass and peak on the Eb chord, the phrygian flat second, and the piece sinks
    back to the drone through that same Eb."""
    s = Song("region_marsh", bpm=56, bars=34, tail=6.5)
    prog = (["D5", "D5", "Dm9", "Dm9", "C", "Am", "F", "Gm", "Bb", "Ebmaj7", "Dm"]
            + ["F", "Am", "Bbmaj7", "Gm", "Em7b5", "Bb", "Eb", "A"]
            + ["Bb", "F", "Gm", "Dm", "Eb", "Ebmaj7", "A7", "A"]
            + ["Dm9", "Dm", "Dm9", "C", "Ebmaj7", "Dm", "Dm9"])
    bass = (["D2"] * 11 + ["C2", "C2", "Bb1", "Bb1", "E2", "Bb1", "Eb2", "A1"]
            + ["Bb1", "A1", "G1", "F1", "Eb1", "Eb1", "A1", "A1"] + ["D2"] * 7)
    assert len(prog) == 34 and len(bass) == 34
    s.chord_at = lambda beat: prog[min(len(prog) - 1, int(beat // 4))]
    INTRO, A, B, C, D = range(1, 4), range(4, 12), range(12, 20), range(20, 28), range(28, 35)

    bcl = s.part("bcl", 71, "winds", pan=56, volume=104, hum=0.012, vjit=3, lag=0.02)
    cl = s.part("cl", 71, "winds", pan=76, volume=96, hum=0.012, vjit=3, lag=0.02)
    bsn = s.part("bsn", 70, "winds", pan=60, volume=84, hum=0.01, vjit=3)
    cb = s.part("cb", 43, "low", pan=70, volume=100, hum=0.01, vjit=3)
    vcl = s.part("vcl", 42, "low", pan=80, volume=96, hum=0.012, vjit=3, lag=0.015)
    glass = s.part("glass", 92, "pads", pan=44, volume=96, hum=0.02, vjit=3)
    glass2 = s.part("glass2", 92, "pads", pan=84, volume=90, hum=0.02, vjit=3)
    strs = s.part("strs", 49, "strings", pan=58, volume=96, hum=0.02, vjit=3)
    vln = s.part("vln", 49, "strings", pan=36, volume=100, hum=0.014, vjit=3, lag=0.012)
    vla = s.part("vla", 49, "strings", pan=66, volume=96, hum=0.014, vjit=3, lag=0.014)
    trem = s.part("trem", 44, "strings", pan=72, volume=84, hum=0.01, vjit=3)
    ohh = s.part("ohh", 53, "choir", pan=50, volume=92, hum=0.02, vjit=3)
    harp = s.part("harp", 46, "drips", pan=30, volume=100, hum=0.006, vjit=4)
    pizz = s.part("pizz", 45, "drips", pan=86, volume=96, hum=0.006, vjit=4)
    cel = s.part("cel", 8, "drips", pan=64, volume=84, hum=0.004, vjit=4)
    timp = s.part("timp", 47, "perc", pan=58, volume=100, hum=0.004, vjit=3)

    inten = np.interp(np.arange(1, 35), [1, 3, 4, 11, 12, 19, 20, 24, 25, 27, 28, 34],
                      [.20, .30, .36, .42, .46, .62, .70, .92, .96, .80, .40, .22])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[int(bar) - 1]

    def Vb(lo, hi):  # by beat
        return lambda beat: lo + (hi - lo) * inten[min(33, int(beat // 4))]

    # ---- the drone: contrabass on the bass line, a low bassoon colouring the pedal now and then
    for bar in range(1, 35):
        cb.n(s.b(bar), 4.06, bass[bar - 1], V(46, 82)(bar))
    cb.expr([(0, 40), (s.b(3), 92), (s.b(12), 96), (s.b(20), 108), (s.b(25), 116), (s.b(28), 100),
             (s.b(33), 92), (s.b(35) - 0.1, 50)])
    bsn.line(s.b(2), "D2:8", 46, leg=1.0)
    bsn.expr([(s.b(2), 50), (s.b(3), 96), (s.b(4) - 0.1, 60)])

    # ---- one voicing for every held layer, so glass, strings and choir never rub against each other
    PV = voicings(prog, [(50, 57), (55, 62), (59, 66), (62, 70), (66, 74)], bass=bass)
    ALL = list(range(1, 35))

    # ---- bowed glass: the murk, breathing in two-bar swells
    place(glass, s, ALL[:2], PV[:2], [1, 2], V(44, 74), legato=1.0)
    place(glass, s, ALL[2:], PV[2:], [0, 1, 2], V(44, 74))
    place(glass2, s, ALL[2:], PV[2:], [3, 4], lambda b: V(44, 74)(b) - 6)
    for p_ in (glass, glass2):
        swell(p_, s, ALL[:32], lo=64, hi=104, every=2)
        p_.expr([(s.b(33), 90), (s.b(35) - 0.1, 30)])

    # ---- choir: a distant hum, opening and closing; full oohs at the climax
    ohh.chord(s.b(1), 12.1, ["D3", "A3"], 40)
    ohh.expr([(0, 30), (s.b(2), 90), (s.b(4) - 0.1, 70)])
    place(ohh, s, list(C), PV[19:27], [0, 1, 2, 3], V(40, 70))
    swell(ohh, s, list(C), lo=60, hi=110, every=2)
    place(ohh, s, [33, 34], PV[32:34], [0, 1, 2], 44)
    ohh.expr([(s.b(33), 80), (s.b(35) - 0.1, 30)])

    # ---- the motif: bass clarinet sinking, clarinet answering higher and rising
    bcl.line(s.b(4), "A3:2 C4:1.5 B3:0.5 E3:4", 70, leg=1.0)
    cl.line(s.b(6), "E4:2 G4:1.5 F4:0.5 C4:3", 60, leg=1.0)
    bcl.line(s.b(8), "G3:2 Bb3:1.5 A3:0.5 D3:4", 72, leg=1.0)
    cl.line(s.b(10), "D4:2 F4:1.5 Eb4:0.5 A3:3", 62, leg=1.0)
    bcl.line(s.b(12), "F3:2 A3:1.5 G3:0.5 C3:4", 74, leg=1.0)
    cl.line(s.b(14), "F4:2 A4:1.5 G4:0.5 D4:3", 66, leg=1.0)
    bcl.line(s.b(16), "E3:2 G3:1.5 F3:0.5 Bb2:4", 76, leg=1.0)
    cl.line(s.b(18), "G4:2 Bb4:1.5 A4:0.5 E4:4", 72, leg=1.0)
    # under the climax the clarinets hold the inner harmony
    bcl.line(s.b(20), "F3:4 F3:4 D3:4 A2:4 G3:4 G3:4 E3:4 C#3:4", 60, leg=1.0)
    cl.line(s.b(20), "Bb3:4 C4:4 Bb3:4 D4:4 Bb3:4 Bb3:4 C#4:4 C#4:4", 56, leg=1.0)
    for p_, phrase in ((bcl, [(4, 6), (8, 10), (12, 14), (16, 18)]), (cl, [(6, 8), (10, 12), (14, 16), (18, 20)])):
        for b0, b1 in phrase:
            p_.expr([(s.b(b0), 84), (s.b(b0) + 2.5, 112), (s.b(b0 + 1) + 1, 100), (s.b(b1) - 0.3, 64)])
    for p_ in (bcl, cl):
        p_.expr([(s.b(20), 70), (s.b(24), 92), (s.b(28) - 0.1, 70)])
    # the ending: the motif from the bottom, a memory of its first form, the phrygian slip, home
    bcl.line(s.b(28), "D3:2 F3:1.5 E3:0.5 A2:4", 68, leg=1.0)
    cl.line(s.b(30), "A4:2 C5:1.5 B4:0.5 E4:4", 54, leg=1.0)
    bcl.line(s.b(32), "D3:2 F3:1.5 Eb3:0.5 A2:3.5 R:0.5 D3:8", 62, leg=1.0)
    bsn.line(s.b(34), "D2:5", 52, leg=1.0)
    bcl.expr([(s.b(28), 84), (s.b(28) + 2.5, 110), (s.b(30) - 0.3, 70), (s.b(32), 84), (s.b(32) + 2.5, 104),
              (s.b(34), 84), (s.b(35) + 1, 40)])
    cl.expr([(s.b(30), 70), (s.b(30) + 2.5, 100), (s.b(32) - 0.2, 60)])
    bsn.expr([(s.b(34), 70), (s.b(35) + 1, 40)])

    # ---- tremolo strings: unease growing under the development
    place(trem, s, list(B), PV[11:19], [2, 3], V(36, 64))
    trem.expr([(s.b(12), 50), (s.b(16), 80), (s.b(19), 100), (s.b(20) - 0.1, 112)])

    # ---- the climax: the motif in the strings, octaves over a falling bass
    climax = ("D5:2 F5:1.5 E5:0.5 A4:3 C5:1 Bb4:2 D5:1.5 C5:0.5 F4:3 A4:1 "
              "G4:1 Bb4:1 Eb5:2 D5:2 C5:1.5 Bb4:0.5 A4:2 G4:2 E4:4")
    vln.line(s.b(20), climax, 76, leg=1.02)
    vla.line(s.b(20), climax, 70, tr=-12, leg=1.02)
    for bar in C:  # the cellos sing the falling bass an octave above the contrabasses
        vcl.n(s.b(bar), 4.04, P(bass[bar - 1]) + 12, 66)
    for p_ in (vln, vla, vcl):
        p_.expr([(s.b(20), 70), (s.b(22), 96), (s.b(24), 112), (s.b(25), 124), (s.b(26), 104),
                 (s.b(28) - 0.1, 70)])
    place(strs, s, list(range(12, 28)), PV[11:27], [1, 2, 3], V(30, 66))
    swell(strs, s, list(range(12, 28)), lo=60, hi=108, every=2)

    # ---- drips: harp low, pizzicato, a celesta far off
    def chord_beat(beat):
        return prog[min(33, int(beat // 4))]
    drips(harp, s, list(range(1, 35)), chord_beat, 38, 57, Vb(40, 64), seed=1, every=(2.0, 5.0), double=0.35)
    drips(pizz, s, list(range(3, 28)), chord_beat, 38, 55, Vb(42, 70), seed=2, every=(2.5, 6.0), double=0.25,
          dur=1.0)
    drips(cel, s, list(range(2, 34)), chord_beat, 74, 86, Vb(30, 48), seed=3, every=(4.0, 9.0), double=0.4)
    harp.n(s.b(34, 3), 4, "A4", 50)
    cel.n(s.b(34, 3.5), 3, "E6", 34)

    # ---- timpani: a distant roll into the climax and under its peak, a last soft stroke
    roll(timp, s, s.b(19), 4, P("A1"), 30, 70, rate=0.25)
    roll(timp, s, s.b(23, 3), 2, P("Eb2"), 40, 80, rate=0.25)
    timp.n(s.b(24), 2, "Eb2", 76)
    roll(timp, s, s.b(27), 4, P("A1"), 34, 66, rate=0.25)
    timp.n(s.b(28), 2, "D2", 56)
    timp.n(s.b(34), 3, "D2", 40)
    return s


MARSH_MIX = {"winds": (4.0, 0.30), "low": (2.0, 0.20), "pads": (0.0, 0.42), "strings": (1.0, 0.34),
             "choir": (0.0, 0.46), "drips": (3.0, 0.52), "perc": (-2.0, 0.30)}


# ================================================================== the ashen highlands
LAMENT = ("C4:1 F4:2 E4:1.5 F4:0.5 G4:1 Ab4:1 G4:2 F4:1 D4:1.5 Eb4:0.5 "
          "Db4:1.5 C4:0.5 Bb3:1 C4:1 Ab4:2 G4:1.5 F4:0.5 E4:1 E4:2 R:1")
LAMENT_HIGH = ("C4:1 F4:2 E4:1.5 F4:0.5 G4:1 Ab4:0.5 G4:0.5 C5:2 D5:1 Bb4:1.5 C5:0.5 "
               "Db5:1.5 C5:0.5 Bb4:1 Ab4:1 F5:2 E5:1.5 Db5:0.5 C5:1 Ab4:1 F4:2")


def compose_ashen():
    """The burnt highlands: badlands and scorched plateaus under falling ash. A slow sarabande in F minor
    over a ground bass that falls a semitone at a time from F to C, the old lament bass, harmonised
    i, V6, v6, IV6, iv6 and the cadence. The ground comes round five times. A distant horn calls before it
    starts; the english horn sings the lament, once ending open on the leading tone and once climbing to
    F and closing; the cellos take it below while the english horn holds a slow counter-line; at the high
    point the violins sing the second phrase in octaves over a brass chorale, peaking on F above the
    cadential six-four; then the english horn begins once more, the cellos finish for it, and the horn's
    call returns over the last F minor chord."""
    s = Song("region_ashen", bpm=58, bars=48, bpb=3, tail=6.5)
    ground = ["Fm", "C", "Cm", "Bb", "Bbm", "Fm", "C", "C7"]
    ground_bass = ["F2", "E2", "Eb2", "D2", "Db2", "C2", "C2", "C2"]
    close = ["Fm", "C", "Cm", "Bb", "Bbm", "Fm", "C7", "Fm"]
    close_bass = ["F2", "E2", "Eb2", "D2", "Db2", "C2", "C2", "F2"]
    prog = (["Fm", "Fm", "Db", "Fm"] + ground + close + ground + close + ground + ["Fm", "C", "Fm", "Fm"])
    bass = (["F2"] * 4 + ground_bass + close_bass + ground_bass + close_bass + ground_bass
            + ["F2", "E2", "F2", "F2"])
    assert len(prog) == 48 and len(bass) == 48
    s.chord_at = lambda beat: prog[min(47, int(beat // 3))]
    b = s.b
    G1, G2, G3, G4, G5 = range(5, 13), range(13, 21), range(21, 29), range(29, 37), range(37, 45)

    eh = s.part("ehorn", 69, "winds", pan=60, volume=104, hum=0.012, vjit=3, lag=0.015)
    vcs = s.part("vcsolo", 42, "lead", pan=72, volume=100, hum=0.012, vjit=3, lag=0.015)
    vln1 = s.part("vln1", 49, "lead", pan=40, volume=100, hum=0.012, vjit=3, lag=0.012)
    vln2 = s.part("vln2", 49, "lead", pan=50, volume=96, hum=0.012, vjit=3, lag=0.014)
    gvc = s.part("gvc", 42, "low", pan=74, volume=100, hum=0.01, vjit=3)
    gcb = s.part("gcb", 43, "low", pan=82, volume=98, hum=0.01, vjit=3)
    gpz = s.part("gpz", 45, "low", pan=80, volume=96, hum=0.006, vjit=3)
    vla = s.part("vla", 49, "strings", pan=64, volume=94, hum=0.02, vjit=3)
    vlnp = s.part("vlnp", 49, "strings", pan=46, volume=90, hum=0.02, vjit=3)
    hn1 = s.part("hn1", 60, "brass", pan=70, volume=100, hum=0.014, vjit=3, lag=0.015)
    hn2 = s.part("hn2", 60, "brass", pan=78, volume=96, hum=0.014, vjit=3, lag=0.015)
    tb1 = s.part("tb1", 57, "brass", pan=84, volume=92, hum=0.014, vjit=3, lag=0.015)
    tb2 = s.part("tb2", 57, "brass", pan=90, volume=90, hum=0.014, vjit=3, lag=0.015)
    call = s.part("call", 60, "far", pan=36, volume=96, hum=0.016, vjit=3, lag=0.02)
    ohh = s.part("ohh", 53, "choir", pan=54, volume=92, hum=0.02, vjit=3)
    aah = s.part("aah", 52, "choir", pan=60, volume=92, hum=0.02, vjit=3)
    harp = s.part("harp", 46, "harp", pan=28, volume=100, hum=0.006, vjit=4)
    timp = s.part("timp", 47, "perc", pan=58, volume=100, hum=0.004, vjit=3)
    kit = s.part("kit", 48, "perc", drum=True, pan=60, volume=84, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 49), [1, 4, 5, 12, 13, 20, 21, 28, 29, 33, 34, 35, 36, 37, 44, 45, 48],
                      [.18, .30, .32, .40, .42, .52, .55, .70, .78, .92, 1.0, .92, .80, .50, .40, .30, .18])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[int(bar) - 1]

    def Vb(lo, hi):
        return lambda beat: lo + (hi - lo) * inten[min(47, int(beat // 3))]

    PV = voicings(prog, [(53, 60), (56, 63), (60, 67), (63, 70), (67, 75)], bass=bass)

    # ---- intro: a drone on F, a hum swelling out of it, and a horn calling far off
    gcb.n(0, b(5) - 0.05, "F1", 52)
    gvc.n(0, b(5) - 0.05, "F2", 48)
    for p_ in (gcb, gvc):
        p_.expr([(0, 30), (b(2), 84), (b(4), 92), (b(5) - 0.1, 80)])
    ohh.chord(0, b(5), ["F3", "C4"], 50)
    ohh.chord(b(3), 3.05, ["Ab4"], 40)
    ohh.expr([(0, 24), (b(2), 82), (b(3, 2), 96), (b(5) - 0.1, 50)])
    call.line(b(2), "C4:1 F4:2 Ab4:1 F4:2 G4:1 F4:2", 56, leg=1.0)
    call.expr([(b(2), 70), (b(2, 2), 104), (b(3, 2), 108), (b(4, 2), 92), (b(5) - 0.1, 40)])
    roll(timp, s, 0, 3, P("F2"), 24, 46, rate=0.125)
    timp.n(3, 2, "F2", 44)
    harp.chord(b(1, 1), 4, ["F2", "C3"], 46)

    # ---- the ground: cellos alone at first, the basses an octave below once it grows, pizzicato as it fades
    for bar in list(G1) + list(G2) + list(G3) + list(G4):
        gvc.n(b(bar), 3.04, bass[bar - 1], V(52, 84)(bar))
    for bar in list(G2) + list(G3) + list(G4):
        gcb.n(b(bar), 3.04, P(bass[bar - 1]) - 12, V(40, 76)(bar))
    for bar in G5:
        gpz.n(b(bar), 1.4, P(bass[bar - 1]), V(44, 72)(bar))
        gpz.n(b(bar), 1.4, P(bass[bar - 1]) - 12, V(38, 64)(bar))
    gvc.line(b(45), "F2:3 E2:3 F2:6.5", 44, leg=1.0)
    gcb.line(b(45), "F1:3 E1:3 F1:6.5", 40, leg=1.0)
    gvc.expr([(b(5), 76), (b(12), 90), (b(13), 92), (b(21), 100), (b(29), 108), (b(34), 120), (b(36), 108),
              (b(37) - 0.1, 90), (b(45), 90), (b(49), 40)])
    gcb.expr([(b(13), 84), (b(29), 104), (b(34), 118), (b(37) - 0.1, 90), (b(45), 90), (b(49), 40)])

    # ---- the english horn: the lament, open the first time, closing the second
    sing_line(eh, b(5), LAMENT, 70)
    sing_line(eh, b(13), LAMENT_HIGH, 74)
    for b0 in (5, 13):
        eh.expr([(b(b0), 92), (b(b0 + 2), 112), (b(b0 + 3), 104), (b(b0 + 5), 110), (b(b0 + 6), 118),
                 (b(b0 + 7), 104), (b(b0 + 8) - 0.1, 90)])

    # ---- strings breathing under it: violas from the second bar of the ground, violins when it returns
    place(vla, s, list(G1) + list(G2), PV[4:20], [1, 2], V(40, 62), clear=[eh])
    place(vlnp, s, list(G2), PV[12:20], [3, 4], V(36, 56), clear=[eh])
    for p_ in (vla, vlnp):
        p_.expr([(b(5), 50), (b(7), 80)])
        swell(p_, s, list(G1) + list(G2), lo=70, hi=100, every=2)

    # ---- harp: an ember glow under each bar, and ash drifting down high above
    for bar in list(G1) + list(G2):
        q = P(bass[bar - 1])
        harp.n(b(bar), 2.5, q, V(40, 56)(bar))
        harp.n(b(bar) + 0.12, 2.4, above(prog[bar - 1], q), V(34, 50)(bar))
    drips(harp, s, list(range(13, 29)), s.chord_at, 72, 84, Vb(30, 44), seed=11, every=(3.0, 7.0), double=0.5,
          dur=2.0, damp=True)

    # ---- the ground's third time: the cellos sing the lament low, the english horn's slow counter-line above
    sing_line(vcs, b(21), LAMENT, 78, tr=-12)
    vcs.expr([(b(21), 90), (b(23), 110), (b(25), 104), (b(27), 116), (b(29) - 0.1, 104)])
    sing_line(eh, b(21), "Ab4:3 G4:3 Eb4:1 G4:2 F4:3 F4:1.5 Eb4:0.5 Db4:1 C4:1 F4:2 E4:1.5 F4:0.5 G4:1 G4:2 R:1",
              58)
    eh.expr([(b(21), 80), (b(24), 98), (b(27), 106), (b(29) - 0.1, 90)])
    place(vla, s, list(G3), PV[20:28], [0, 1], V(40, 60), clear=[vcs, eh])
    place(vlnp, s, list(G3), PV[20:28], [3, 4], V(34, 52), clear=[vcs, eh])
    for p_ in (vla, vlnp):
        p_.expr([(b(21), 70), (b(25), 92), (b(28), 104), (b(29) - 0.1, 110)])
    for bar, q in ((21, "F2"), (23, "C2"), (25, "F2"), (27, "C2")):
        timp.n(b(bar), 1.5, q, 36 + (bar - 21) * 2)
    roll(timp, s, b(28), 3, P("C2"), 32, 64, rate=0.125)
    roll(kit, s, b(28), 3, 57, 18, 50, rate=0.125)

    # ---- the high point: violins in octaves over a brass chorale, the choir behind
    sing_line(vln1, b(29), LAMENT_HIGH, 80, tr=12)
    sing_line(vln2, b(29), LAMENT_HIGH, 76)
    for p_ in (vln1, vln2):
        p_.expr([(b(29), 92), (b(31), 106), (b(33), 112), (b(34), 124), (b(35), 116), (b(36), 104),
                 (b(37) - 0.1, 80)])
    BV = voicings(prog[28:36], [(41, 50), (46, 55), (51, 60), (55, 64)], bass=bass[28:36])
    for bar, vc in zip(G4, BV):
        v = V(50, 76)(bar)
        tb1.n(b(bar), 3.04, vc[0], v)
        tb2.n(b(bar), 3.04, vc[1], v)
        hn2.n(b(bar), 3.04, vc[2], v)
        hn1.n(b(bar), 3.04, vc[3], v)
    for p_ in (hn1, hn2, tb1, tb2):
        p_.expr([(b(29), 60), (b(31), 90), (b(33), 104), (b(34), 118), (b(35), 104), (b(36), 90),
                 (b(37) - 0.1, 50)])
    place(aah, s, list(G4), PV[28:36], [1, 2, 3], V(40, 64), clear=[vln1, vln2])
    aah.expr([(b(29), 50), (b(31), 90), (b(34), 116), (b(36), 96), (b(37) - 0.1, 50)])
    place(vla, s, list(G4), PV[28:36], [0, 1, 2], V(44, 66), clear=[vln1, vln2])
    vla.expr([(b(29), 90), (b(34), 116), (b(37) - 0.1, 80)])
    timp.n(b(29), 2, "F2", 64)
    roll(timp, s, b(33, 2), 2, P("C2"), 40, 76, rate=0.125)
    timp.n(b(34), 2, "C2", 80)
    timp.n(b(35), 2, "C2", 62)
    timp.n(b(36), 2, "F2", 58)
    kit.n(b(34), 4, 57, 52)

    # ---- the ground's last time: the english horn begins the lament, the cello finishes it
    sing_line(eh, b(37), "C4:1 F4:2 E4:1.5 F4:0.5 G4:1 Ab4:1 G4:2 F4:1 D4:1.5 Eb4:0.5", 62)
    eh.expr([(b(37), 86), (b(39), 104), (b(41) - 0.1, 84)])
    sing_line(vcs, b(41), "Db3:1.5 C3:0.5 Bb2:1 C3:1 Ab3:2 G3:1.5 F3:0.5 E3:1 E3:2 R:1", 70)
    vcs.expr([(b(41), 90), (b(43), 104), (b(45) - 0.1, 80)])
    place(vla, s, list(G5), PV[36:44], [1, 2], V(36, 56), clear=[eh, vcs])
    vla.expr([(b(37), 80), (b(41), 90), (b(44), 70), (b(45) - 0.1, 60)])
    drips(harp, s, list(G5), s.chord_at, 70, 82, Vb(30, 42), seed=12, every=(3.0, 6.0), double=0.5, dur=2.0,
          damp=True)

    # ---- coda: the english horn's last words, the call far off once more, F minor
    sing_line(eh, b(45), "C4:1 F4:2 E4:1.5 F4:0.5 G4:1 Ab4:1 G4:1 F4:1 F4:4.5", 58)
    eh.expr([(b(45), 90), (b(46, 2), 104), (b(47), 96), (b(48), 84), (b(49) + 1, 30)])
    call.line(b(47), "C4:1 F4:5", 40, leg=1.0)
    call.expr([(b(47), 60), (b(47, 2), 92), (b(49) + 1, 30)])
    place(ohh, s, [45, 46, 47, 48], PV[44:48], [0, 1, 2], 42, clear=[eh])
    ohh.expr([(b(45), 50), (b(46), 84), (b(48), 80), (b(49) + 1, 24)])
    place(vlnp, s, [47, 48], PV[46:48], [3, 4], 34, clear=[eh])
    vlnp.expr([(b(47), 40), (b(48), 76), (b(49) + 1, 24)])
    harp.chord(b(48), 6, ["F1", "C2", "F2"], 46)
    harp.n(b(48, 2.5), 5, "Ab4", 34)
    timp.n(b(48), 3, "F2", 40)

    s.tempo = [(0, 1.0), (b(33), 1.0), (b(34), 1.06), (b(35), 1.0), (b(45), 1.0), (b(47), 1.12),
               (b(49) + 6, 1.4)]
    return s


ASHEN_MIX = {"winds": (3.0, 0.30), "lead": (1.0, 0.32), "low": (0.0, 0.22), "strings": (-1.0, 0.38),
             "brass": (-3.0, 0.42), "far": (-6.0, 0.80), "choir": (-2.0, 0.48), "harp": (1.0, 0.42),
             "perc": (-3.0, 0.34)}


# ================================================================== the frozen peaks
ICE = "E4:1 B4:2 A4:1 G4:1.5 F#4:0.5 E4:2 C5:1 B4:1 A4:1 E4:1 F#4:4"
ICE_ANSWER = "E4:1 B4:2 A4:1 G4:1.5 A4:0.5 B4:2 D5:1.5 C5:0.5 B4:1 A4:1 B4:4"


def compose_frozen():
    """Snowfields, ice and the frozen peaks. E aeolian at a still 66, where the C major chord carries F sharp
    as a lydian glint. Celesta and harp let snow fall in a slow three-three-two, high strings hold the cold
    in harmonics, and a low, breathy flute sings the theme: a fifth up, a step back, a slow fall to the
    ninth. The violins take it an octave up over distant horns; then the cellos start a line that climbs in
    two-bar steps, handed to the violins, up to the sharpened leading tone, and the theme breaks out in
    E major: the sun on the snow. Its last chord slips to C major, the cloud comes back, and the flute
    sings the theme once more, low, down to E."""
    s = Song("region_frozen", bpm=66, bars=42, tail=7.0)
    prog = (["Em9", "Em9", "Cmaj7", "Em9"]
            + ["Em9", "Cmaj7", "Am7", "Bsus4", "Em9", "Cmaj7", "D", "Em"]
            + ["Em9", "Cmaj7", "Am7", "Bsus4", "Em9", "Cmaj7", "Am7", "B"]
            + ["C", "D", "Bm", "Em", "C", "D", "G", "B"]
            + ["E", "C#m", "A", "B", "E", "C#m", "A", "C"]
            + ["Em9", "Cmaj7", "Am7", "Bsus4", "Em9", "Em9"])
    bass = (["E2", "E2", "E2", "E2"]
            + ["E2", "C2", "A1", "B1", "E2", "C2", "D2", "E2"]
            + ["E2", "C2", "A1", "B1", "E2", "C2", "A1", "B1"]
            + ["C2", "D2", "B1", "E2", "C2", "F#2", "G2", "B1"]
            + ["E2", "C#2", "A1", "B1", "E2", "C#2", "A1", "C2"]
            + ["E2", "C2", "A1", "B1", "E2", "E2"])
    assert len(prog) == 42 and len(bass) == 42
    s.chord_at = lambda beat: prog[min(41, int(beat // 4))]
    b = s.b
    INTRO, A, A2, B, C, CODA = (range(1, 5), range(5, 13), range(13, 21), range(21, 29), range(29, 37),
                                range(37, 43))

    fl = s.part("flute", 73, "winds", pan=56, volume=104, hum=0.012, vjit=3, lag=0.01)
    fl2 = s.part("flute2", 73, "winds", pan=70, volume=92, hum=0.012, vjit=3, lag=0.012)
    vln1 = s.part("vln1", 49, "lead", pan=40, volume=100, hum=0.012, vjit=3, lag=0.012)
    vln2 = s.part("vln2", 49, "lead", pan=48, volume=96, hum=0.012, vjit=3, lag=0.014)
    vcl = s.part("vcl", 42, "lead", pan=74, volume=100, hum=0.012, vjit=3, lag=0.014)
    pad1 = s.part("pad1", 49, "strings", pan=54, volume=92, hum=0.02, vjit=3)
    pad2 = s.part("pad2", 49, "strings", pan=72, volume=90, hum=0.02, vjit=3)
    harm = s.part("harm", 49, "strings", pan=34, volume=84, hum=0.01, vjit=2)
    trem = s.part("trem", 44, "strings", pan=64, volume=88, hum=0.01, vjit=3)
    cb = s.part("cb", 43, "low", pan=80, volume=100, hum=0.01, vjit=3)
    vc = s.part("vc", 42, "low", pan=74, volume=96, hum=0.01, vjit=3)
    hn1 = s.part("hn1", 60, "brass", pan=76, volume=98, hum=0.014, vjit=3, lag=0.015)
    hn2 = s.part("hn2", 60, "brass", pan=84, volume=94, hum=0.014, vjit=3, lag=0.015)
    cel = s.part("cel", 8, "snow", pan=66, volume=96, hum=0.004, vjit=4)
    harp = s.part("harp", 46, "snow", pan=26, volume=100, hum=0.006, vjit=4)
    glk = s.part("glock", 9, "snow", pan=58, volume=76, hum=0.004, vjit=2)
    aah = s.part("aah", 52, "choir", pan=58, volume=90, hum=0.02, vjit=3)
    timp = s.part("timp", 47, "perc", pan=60, volume=96, hum=0.004, vjit=2)
    kit = s.part("kit", 48, "perc", drum=True, pan=56, volume=84, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 43), [1, 4, 5, 12, 13, 20, 21, 26, 28, 29, 32, 33, 35, 36, 37, 42],
                      [.15, .26, .30, .36, .40, .48, .50, .70, .86, .92, .96, 1.0, .92, .70, .36, .16])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[int(bar) - 1]

    PV = voicings(prog, [(52, 59), (55, 64), (59, 67), (62, 71)], bass=bass)

    # ---- snow: celesta and harp falling in a slow three-three-two, from the chord and the ninth above E
    rng = rng_for("frozen", "snow")

    def flakes(bar, lo, hi, held):
        """open tones for the snow: root and fifth, with F sharp over E and B (stacked fifths, the cold sound)
        and the third elsewhere, leaving out anything a semitone from what is held in that bar"""
        ch = pcs(prog[bar - 1])
        tones = {ch[0], ch[2]} | ({6} if ch[0] in (4, 11) else {ch[1]})
        return [m for m in range(lo, hi + 1) if m % 12 in tones and not any(abs(m - q) in (1, 13, 25) for q in held)]

    def snow(bars, vel, harp_too=True, clear=()):
        for bar in bars:
            b0 = b(bar)
            held = [n[2] for p_ in clear for n in p_.notes if n[0] < b0 + 4 and n[0] + n[1] > b0 and n[1] >= 0.75]
            hi_ = flakes(bar, 76, 91, held)
            k = len(hi_) - 1 - int(rng.integers(0, 3))
            for i, t in enumerate((0.0, 1.5, 3.0)):
                v = vel(bar) - (6 if i == 2 else 0)
                cel.n(b0 + t, min(1.6, 3.95 - t), hi_[max(0, k)], v + rng.uniform(-4, 3))
                k -= int(rng.integers(1, 3))
                if k < 1:
                    k = len(hi_) - 1 - int(rng.integers(0, 2))
            if harp_too:
                lo_ = flakes(bar, 59, 74, held)
                j = int(rng.integers(len(lo_) // 2, len(lo_)))
                for t in (0.75, 2.25):
                    harp.n(b0 + t, min(2.0, 3.95 - t), lo_[j], vel(bar) - 8 + rng.uniform(-4, 3))
                    j = max(0, j - int(rng.integers(1, 3)))

    # ---- the cold itself: harmonics high above, a low E under the start and the end
    harm.chord(0, b(5) + 0.1, ["B5", "F#6"], 42)
    harm.expr([(0, 20), (b(2), 70), (b(4), 92), (b(5), 80)])
    harm.chord(b(13), 28.1, ["E6"], 30)
    harm.expr([(b(13), 40), (b(15), 76), (b(19), 70), (b(20) - 0.1, 30)])
    harm.chord(b(37), 26, ["B5", "F#6"], 32)
    harm.expr([(b(37), 30), (b(39), 76), (b(43) + 2, 20)])
    cb.n(0, b(5), "E1", 50)
    vc.n(0, b(5), "E2", 46)
    for p_ in (cb, vc):
        p_.expr([(0, 24), (b(3), 84), (b(5) - 0.1, 90)])
    pad1.chord(b(3), 8.1, ["G3", "B3"], 40)
    pad1.expr([(b(3), 30), (b(4), 70), (b(5) - 0.1, 76)])

    # ---- A: the flute, low and breathing, over open strings
    sing_line(fl, b(5), ICE + " " + ICE_ANSWER, 72, contour=0.25)
    fl.expr([(b(5), 92), (b(6), 108), (b(8), 100), (b(9) - 0.1, 84), (b(9), 94), (b(10), 110), (b(12), 104),
             (b(13) - 0.1, 80)])
    for bar in A:
        cb.n(b(bar), 4.05, bass[bar - 1], V(40, 60)(bar))
    cb.expr([(b(5), 80), (b(13), 90)])
    place(pad1, s, list(A), PV[4:12], [0, 1], V(38, 54), clear=[fl])
    place(pad2, s, list(A), PV[4:12], [2, 3], V(34, 50), clear=[fl])
    for p_ in (pad1, pad2):
        p_.expr([(b(5), 70)])
        swell(p_, s, list(A), lo=64, hi=96, every=2)

    # ---- A2: the violins an octave up, a horn pair far below, the flute in long notes between
    sing_line(vln1, b(13), ICE, 66, tr=12, contour=0.2)
    sing_line(vln1, b(17), "E4:1 B4:2 A4:1 G4:1.5 A4:0.5 B4:2 D5:1.5 C5:0.5 B4:1 A4:1 B4:4", 68, tr=12,
              contour=0.2)
    vln1.expr([(b(13), 80), (b(14), 96), (b(16), 90), (b(17), 88), (b(18), 102), (b(20), 96), (b(21) - 0.1, 70)])
    sing_line(fl2, b(13), "B4:4 C5:4 C5:2 B4:2 B4:4 B4:4 E5:4 E5:2 D5:2 D#5:4", 50, contour=0.1)
    fl2.expr([(b(13), 70), (b(15), 92), (b(17), 84), (b(19), 96), (b(21) - 0.1, 70)])
    for bar in A2:
        cb.n(b(bar), 4.05, bass[bar - 1], V(40, 62)(bar))
        vc.n(b(bar), 4.05, P(bass[bar - 1]) + 12, V(36, 54)(bar))
    vc.expr([(b(13), 60), (b(15), 86)])
    HV = voicings(prog[12:20], [(48, 57), (55, 64)], bass=bass[12:20])
    place(hn2, s, list(A2), HV, [0], V(36, 52), clear=[vln1, fl2])
    place(hn1, s, list(A2), HV, [1], V(36, 52), clear=[vln1, fl2])
    for p_ in (hn1, hn2):
        p_.expr([(b(13), 40), (b(15), 80), (b(19), 96), (b(21) - 0.1, 70)])
    place(pad1, s, list(A2), PV[12:20], [0, 1], V(36, 50), clear=[vln1, fl2])
    swell(pad1, s, list(A2), lo=60, hi=92, every=2)

    # ---- B: the wind rising; the cellos climb in two-bar steps and hand the line to the violins
    climb = ("G3:1 C4:1 E4:2 F#4:2 E4:1 D4:1 D4:1 F#4:1 B4:2 A4:1.5 G4:0.5 E4:2 "
             "E4:1 G4:1 C5:2 B4:1.5 A4:0.5 F#4:2 G4:1 B4:1 D5:2 E5:2 D#5:2")
    sing_line(vcl, b(21), " ".join(climb.split()[:12]), 74)
    sing_line(vln1, b(25), " ".join(climb.split()[12:]), 76)
    sing_line(vln2, b(25), " ".join(climb.split()[12:]), 70, tr=-12)
    vcl.expr([(b(21), 90), (b(22), 104), (b(24), 112), (b(25) - 0.1, 104)])
    for p_ in (vln1, vln2):
        p_.expr([(b(25), 92), (b(26), 104), (b(27), 112), (b(28), 120), (b(29) - 0.1, 124)])
    for bar in B:
        cb.n(b(bar), 4.05, bass[bar - 1], V(44, 70)(bar))
        vc.n(b(bar), 4.05, P(bass[bar - 1]) + 12, V(40, 62)(bar))
    place(trem, s, list(B), PV[20:28], [1, 2, 3], V(30, 58), clear=[vcl, vln1])
    trem.expr([(b(21), 40), (b(24), 70), (b(26), 96), (b(28), 116), (b(29) - 0.1, 124)])
    place(pad2, s, list(B), PV[20:28], [0, 1], V(36, 56), clear=[vcl, vln1])
    swell(pad2, s, list(B), lo=64, hi=104, every=2, shape="cresc")
    HB = voicings(prog[24:28], [(50, 59), (55, 66)], bass=bass[24:28])
    place(hn2, s, list(B)[4:], HB, [0], V(40, 60), clear=[vln1])
    place(hn1, s, list(B)[4:], HB, [1], V(40, 60), clear=[vln1])
    for p_ in (hn1, hn2):
        p_.expr([(b(25), 50), (b(27), 90), (b(29) - 0.1, 116)])
    roll(timp, s, b(27), 8, P("B1"), 26, 70, rate=0.125)
    roll(kit, s, b(27), 8, 57, 14, 58, rate=0.125)

    # ---- C: the theme in E major, violins and flute in octaves, horns and choir under it
    sun = "E5:1 B5:2.5 A5:0.5 G#5:1.5 F#5:0.5 E5:2 C#6:1 B5:1 A5:1 E5:1 F#5:4"
    sun2 = "E5:1 B5:2.5 A5:0.5 G#5:1.5 A5:0.5 B5:2 C#6:1.5 B5:0.5 A5:1 F#5:1 E5:4"
    sing_line(vln1, b(29), sun + " " + sun2, 82, contour=0.25)
    sing_line(vln2, b(29), sun + " " + sun2, 76, tr=-12, contour=0.25)
    sing_line(fl, b(29), sun + " " + sun2, 70, contour=0.25)
    for p_ in (vln1, vln2, fl):
        p_.expr([(b(29), 112), (b(30), 120), (b(32), 112), (b(33), 118), (b(34), 124), (b(35), 116),
                 (b(36), 96), (b(37) - 0.1, 70)])
    CV = voicings(prog[28:36], [(47, 56), (52, 60), (56, 64), (59, 68)], bass=bass[28:36])
    for bar, vc_ in zip(C, CV):
        v = V(48, 70)(bar)
        hn2.chord(b(bar), 4.05, [vc_[0], vc_[1]], v)
        hn1.chord(b(bar), 4.05, [vc_[2], vc_[3]], v)
    for p_ in (hn1, hn2):
        p_.expr([(b(29), 104), (b(33), 112), (b(35), 100), (b(36), 84), (b(37) - 0.1, 50)])
    place(aah, s, list(C), PV[28:36], [1, 2, 3], V(40, 60), clear=[vln1, vln2])
    aah.expr([(b(29), 70), (b(31), 96), (b(34), 110), (b(36), 80), (b(37) - 0.1, 40)])
    place(pad1, s, list(C), PV[28:36], [0, 1], V(44, 62), clear=[vln1, vln2])
    pad1.expr([(b(29), 96), (b(34), 110), (b(37) - 0.1, 70)])
    for bar in C:
        cb.n(b(bar), 4.05, bass[bar - 1], V(50, 76)(bar))
        vc.n(b(bar), 4.05, P(bass[bar - 1]) + 12, V(46, 68)(bar))
    for bar, q in ((29, "E6"), (31, "C#7"), (33, "E6"), (35, "C#7")):
        glk.n(b(bar), 2.5, q, 46)
    timp.n(b(29), 2.5, "E2", 66)
    timp.n(b(33), 2.5, "E2", 60)
    kit.n(b(29), 4, 81, 40)
    roll(timp, s, b(35, 3), 2, P("B1"), 30, 50, rate=0.125)
    glk.n(b(36), 3, "G5", 30)
    cel.n(b(36, 2), 2.5, "E6", 34)
    cel.n(b(36, 3.5), 2.0, "B5", 28)

    # ---- coda: the cloud back over the sun; the flute, low again, down to E
    sing_line(fl, b(37), ICE + " B4:2 A4:1 F#4:1 E4:6", 64, contour=0.25)
    fl.expr([(b(37), 86), (b(38), 100), (b(40), 90), (b(41), 84), (b(42), 76), (b(43) + 2, 30)])
    for bar in CODA:
        cb.n(b(bar), 4.05, bass[bar - 1], V(38, 54)(bar))
    cb.n(b(42), 7, "E1", 40)
    place(pad1, s, list(CODA), PV[36:42], [0, 1], V(34, 48), clear=[fl])
    place(pad2, s, list(CODA), PV[36:42], [2, 3], V(30, 44), clear=[fl])
    for p_ in (pad1, pad2, cb):
        p_.expr([(b(37), 80), (b(41), 76), (b(43) + 2, 24)])
    harp.chord(b(42), 6, ["E2", "B2"], 40)
    harp.n(b(42, 2), 5, "F#4", 30)
    cel.n(b(42, 3), 5, "B6", 26)

    # ---- the snow falls around everything held
    held = [fl, fl2, vln1, vcl, pad1, pad2, harm, hn1, hn2, trem]
    snow(list(INTRO) + list(A), V(44, 60), clear=held)
    snow(list(A2), V(34, 50), clear=held)
    snow(list(B)[:4], V(30, 44), harp_too=False, clear=held)
    snow(list(CODA)[:-1], V(34, 54), clear=held)

    s.tempo = [(0, 1.0), (b(28), 1.0), (b(28, 4), 1.08), (b(29), 1.0), (b(40), 1.0), (b(42), 1.15),
               (b(43) + 6, 1.35)]
    return s


FROZEN_MIX = {"winds": (3.0, 0.32), "lead": (1.0, 0.34), "strings": (-1.0, 0.42), "low": (0.0, 0.24),
              "brass": (-5.0, 0.44), "snow": (0.0, 0.50), "choir": (-3.0, 0.50), "perc": (-4.0, 0.36)}


# ================================================================== the crystal deeps
def compose_crystal():
    """Caves far below, grown through with crystal. Lydian, at a hushed 54: the raised fourth is the glint.
    The motif rises a third and then a fifth to that fourth (G, B, F sharp over C). It is heard in three
    keys a major third apart, the way light turns in a crystal: C lydian on the vibraphone, then A flat
    lydian in the violins, then E lydian at the high point, sung by the choir with the violins and peaking
    on the ninth of C sharp minor, and home to C, where it ends on the raised fourth, unresolved. Each key
    turns to the next on the notes they share. Celesta, glockenspiel and harp catch the light at random
    above it all, the harp ripples while the key is away from home, and a low C, A flat or E holds the dark
    below."""
    s = Song("region_crystal", bpm=54, bars=32, tail=8.0)
    prog = (["Cmaj7"] * 4
            + ["Cmaj7", "D", "Cmaj7", "D", "Am9", "Em7", "Bm7", "Cmaj7"]
            + ["Abmaj7", "Bb", "Abmaj7", "Bb", "Fm9", "Cm7", "Gm7", "Abmaj7"]
            + ["Emaj7", "F#", "Emaj7", "F#", "C#m9", "Emaj7"]
            + ["Cmaj7", "D", "Cmaj7", "D", "Cmaj7", "Cmaj7"])
    bass = (["C2"] * 4
            + ["C2", "C2", "C2", "C2", "A1", "E2", "B1", "C2"]
            + ["Ab1", "Ab1", "Ab1", "Ab1", "F1", "C2", "G1", "Ab1"]
            + ["E2", "E2", "E2", "E2", "C#2", "E2"]
            + ["C2"] * 6)
    ninth = {"Cmaj7": "D", "D": "E", "Am9": "B", "Em7": "F#", "Bm7": "C#", "Abmaj7": "Bb", "Bb": "C", "Fm9": "G",
             "Cm7": "D", "Gm7": "A", "Emaj7": "F#", "F#": "G#", "C#m9": "D#"}
    assert len(prog) == 32 and len(bass) == 32
    s.chord_at = lambda beat: prog[min(31, int(beat // 4))]
    b = s.b
    INTRO, A, B, C, R = range(1, 5), range(5, 13), range(13, 21), range(21, 27), range(27, 33)

    vib = s.part("vib", 11, "lead", pan=58, volume=104, hum=0.006, vjit=3)
    celm = s.part("celm", 8, "lead", pan=70, volume=90, hum=0.004, vjit=3)
    vln = s.part("vln", 49, "strings", pan=42, volume=100, hum=0.012, vjit=3, lag=0.012)
    vln2 = s.part("vln2", 49, "strings", pan=50, volume=94, hum=0.012, vjit=3, lag=0.014)
    pad1 = s.part("pad1", 49, "strings", pan=60, volume=90, hum=0.02, vjit=3)
    pad2 = s.part("pad2", 49, "strings", pan=76, volume=88, hum=0.02, vjit=3)
    harm = s.part("harm", 49, "strings", pan=30, volume=80, hum=0.01, vjit=2)
    cb = s.part("cb", 43, "low", pan=78, volume=100, hum=0.01, vjit=3)
    vc = s.part("vc", 42, "low", pan=70, volume=94, hum=0.01, vjit=3)
    ohh = s.part("ohh", 53, "choir", pan=52, volume=94, hum=0.02, vjit=3, lag=0.015)
    ohh2 = s.part("ohh2", 53, "choir", pan=66, volume=88, hum=0.02, vjit=3)
    harp = s.part("harp", 46, "harp", pan=24, volume=100, hum=0.006, vjit=4)
    cel = s.part("cel", 8, "glints", pan=82, volume=90, hum=0.004, vjit=4)
    glk = s.part("glk", 9, "glints", pan=40, volume=74, hum=0.004, vjit=3)
    hhp = s.part("hhp", 46, "glints", pan=64, volume=86, hum=0.004, vjit=3)
    bell = s.part("bell", 14, "perc", pan=60, volume=82, hum=0.004, vjit=2)
    timp = s.part("timp", 47, "perc", pan=58, volume=90, hum=0.004, vjit=2)
    kit = s.part("kit", 48, "perc", drum=True, pan=50, volume=80, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 33), [1, 4, 5, 12, 13, 18, 20, 21, 24, 25, 26, 27, 30, 32],
                      [.22, .30, .34, .44, .50, .62, .70, .82, .92, 1.0, .90, .50, .36, .22])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[int(bar) - 1]

    def colour(ch):
        """the chord's root, third and fifth, with its seventh and ninth where they don't sit a semitone off"""
        tri = pcs(ch)[:3]
        extra = [t for t in pcs(ch)[3:] + [P(ninth[ch] + "0") % 12]
                 if all(abs(t - u) % 12 not in (1, 11) for u in tri)]
        return set(tri) | set(extra)

    def free(beat, dur, cands, against):
        near = [n[2] for p_ in against for n in p_.notes
                if n[0] < beat + dur and n[0] + n[1] > beat and n[1] >= 0.75]
        return [m for m in cands if not any(abs(m - q) in (1, 13, 25) for q in near)]

    PV = voicings(prog, [(52, 60), (55, 64), (59, 67), (62, 71)], bass=bass)

    # ---- the dark below: a low pedal through each key
    for bar in range(1, 33):
        cb.n(b(bar), 4.05, P(bass[bar - 1]) - 12 if bar <= 4 or bar >= 31 else bass[bar - 1], V(40, 66)(bar))
    for bar in range(5, 31):
        vc.n(b(bar), 4.05, P(bass[bar - 1]) + 12, V(32, 54)(bar))
    cb.expr([(0, 30), (b(3), 90), (b(5), 96), (b(21), 110), (b(26), 112), (b(27), 96), (b(33) + 2, 30)])
    vc.expr([(b(5), 60), (b(7), 90), (b(25), 110), (b(27), 90), (b(31) - 0.1, 50)])
    harm.chord(0, b(5) + 0.1, ["G5", "D6"], 40)
    harm.expr([(0, 20), (b(2), 70), (b(4), 90), (b(5), 80)])

    # ---- A: C lydian, the motif on the vibraphone, the celesta an octave above it from the second phrase
    motif_c = ("G4:1 B4:1 F#5:2 E5:3 D5:1 G4:1 B4:1 G5:2 F#5:1.5 E5:0.5 D5:2 "
               "C5:1 E5:1 B5:2 G5:1.5 F#5:0.5 E5:2 F#5:1 D5:1 B4:2 G4:4")
    sing_line(vib, b(5), motif_c, 82, contour=0.25)
    sing_line(celm, b(9), " ".join(motif_c.split()[11:]), 50, tr=12, contour=0.2)
    place(pad1, s, list(A), PV[4:12], [0, 1], V(34, 52), clear=[vib, celm])
    place(pad2, s, list(A), PV[4:12], [2, 3], V(30, 46), clear=[vib, celm])
    for p_ in (pad1, pad2):
        p_.expr([(b(5), 50), (b(6), 84)])
        swell(p_, s, list(A), lo=70, hi=100, every=2)

    # ---- B: A flat lydian under the held G; the violins take the motif, the vibraphone a slow line below
    motif_ab = ("Eb4:1 G4:1 D5:2 C5:3 Bb4:1 Eb4:1 G4:1 Eb5:2 D5:1.5 C5:0.5 Bb4:2 "
                "Ab4:1 C5:1 G5:2 Eb5:1.5 D5:0.5 C5:2 D5:1 Bb4:1 G4:2 G4:2 Eb5:2")
    sing_line(vln, b(13), motif_ab, 70, contour=0.25)
    vln.expr([(b(13), 80), (b(14), 98), (b(17), 104), (b(19), 110), (b(21) - 0.1, 116)])
    sing_line(vib, b(17), "Eb5:4 G5:2 F5:2 F5:2 D5:2 Eb5:4", 48, contour=0.1)
    place(ohh, s, list(B), PV[12:20], [0, 1, 2], V(30, 50), clear=[vln, vib])
    ohh.expr([(b(13), 40), (b(15), 86), (b(19), 100), (b(21) - 0.1, 104)])
    place(pad1, s, list(B), PV[12:20], [0, 1], V(34, 50), clear=[vln, vib])
    swell(pad1, s, list(B), lo=72, hi=102, every=2)
    roll(kit, s, b(12, 2), 3, 57, 12, 42, rate=0.125)

    # ---- C: E lydian, the high point: the choir and violins sing it
    motif_e = "B4:1 D#5:1 A#5:2 G#5:3 F#5:1 B4:1 D#5:1 B5:2 A#5:1.5 G#5:0.5 F#5:2 E5:1 G#5:1 D#6:2 B5:2 G#5:2 B5:2"
    sing_line(vln, b(21), motif_e, 80, contour=0.25)
    sing_line(vln2, b(21), motif_e, 74, tr=-12, contour=0.25)
    sing_line(ohh, b(21), motif_e, 64, tr=-12, contour=0.2)
    for p_ in (vln, vln2, ohh):
        p_.expr([(b(21), 108), (b(23), 116), (b(25), 124), (b(26), 112), (b(27) - 0.1, 84)])
    place(ohh2, s, list(C), PV[20:26], [0, 1, 2], V(40, 58), clear=[vln, vln2, ohh])
    ohh2.expr([(b(21), 90), (b(25), 116), (b(27) - 0.1, 80)])
    place(pad1, s, list(C), PV[20:26], [0, 1], V(42, 60), clear=[vln, vln2, ohh])
    place(pad2, s, list(C), PV[20:26], [2, 3], V(38, 56), clear=[vln, vln2, ohh])
    for p_ in (pad1, pad2):
        p_.expr([(b(21), 96), (b(25), 112), (b(27) - 0.1, 76)])
    roll(kit, s, b(20, 2), 3, 57, 14, 54, rate=0.125)
    roll(timp, s, b(24, 3), 2, P("C#2"), 26, 52, rate=0.125)
    timp.n(b(25), 3, "C#2", 50)

    # ---- R: home to C lydian; the motif once more, its echo high, and low again to end on the raised fourth
    sing_line(vib, b(27), "G4:1 B4:1 F#5:2 E5:3 D5:1", 72, contour=0.25)
    sing_line(celm, b(29), "G5:1 B5:1 F#6:2 E6:4", 50, contour=0.2)
    sing_line(vib, b(31), "G4:1 B4:1 F#5:10", 64, contour=0.2)
    place(pad1, s, list(R), PV[26:32], [0, 1], V(30, 46), clear=[vib, celm])
    place(pad2, s, list(R), PV[26:32], [2, 3], V(26, 40), clear=[vib, celm])
    place(ohh2, s, list(R)[:4], PV[26:30], [1, 2], V(28, 40), clear=[vib, celm])
    for p_ in (pad1, pad2, ohh2):
        p_.expr([(b(27), 80), (b(29), 86), (b(31), 70), (b(33) + 2, 24)])
    harm.chord(b(29), 4.05, ["G5", "D6"], 30)
    harm.chord(b(31), 10, ["B5", "D6"], 30)
    harm.expr([(b(29), 30), (b(30), 70), (b(32), 60), (b(33) + 2, 20)])
    for i, q in enumerate(["C3", "G3", "D4", "E4", "B4", "F#5", "B5"]):
        harp.n(b(31) + i * 0.25, 8 - i * 0.25, q, 40 + i)
    vc.n(b(31), 9, "G2", 34)

    # ---- the harp ripples up through each chord while the key is away, the bells when it turns
    held = [vib, celm, vln, vln2, pad1, pad2, harm, ohh, ohh2]
    for bar, lo, hi, step in ([(bar, 56, 91, 2) for bar in B] + [(bar, 52, 87, 1) for bar in C]):
        ch = prog[bar - 1]
        tones = [m for m in range(lo, hi + 1) if m % 12 in colour(ch)]
        for i in range(8):
            t = b(bar) + i * 0.5
            ring = min(3.0, 4.05 - i * 0.5)
            lad = free(t, ring, tones, held)
            if lad:
                q = lad[min(len(lad) - 1, i * step + (bar % 2))]
                harp.n(t, ring, q, V(34, 50)(bar) + (6 if i == 0 else 0))
    for bar, cands, v in ((21, ["G#4", "B4", "E4"], 46), (25, ["C#4", "G#4"], 40), (27, ["E4", "G4", "C4"], 38)):
        ok = free(b(bar), 4, [P(c) for c in cands], held)
        if ok:
            bell.n(b(bar), 4, ok[0], v)

    # ---- glints: light catching the crystal, clear of everything held
    def glints(part, bars, lo, hi, vel, seed, every, dur):
        rng = rng_for("crystal", part.name, seed)
        beat = b(bars[0]) + rng.uniform(0, every[0])
        end = b(bars[-1]) + 4
        while beat < end:
            bar = int(beat // 4) + 1
            ring = min(dur, b(bar) + 4.05 - beat)
            cands = free(beat, ring, [m for m in range(lo, hi + 1) if m % 12 in colour(prog[bar - 1])], held + [harp])
            if cands:
                part.n(beat, ring, int(rng.choice(cands)), vel(bar) + rng.uniform(-6, 4))
            beat = round((beat + rng.uniform(*every)) * 8) / 8
    glints(cel, list(range(1, 33)), 79, 96, V(34, 46), 1, (2.0, 5.5), 2.5)
    glints(glk, list(range(5, 31)), 86, 100, V(26, 38), 2, (5.0, 11.0), 2.0)
    glints(hhp, list(range(1, 33)), 67, 84, V(32, 44), 3, (3.0, 7.0), 3.0)

    s.tempo = [(0, 1.0), (b(20, 3), 1.0), (b(21), 1.06), (b(21, 2), 1.0), (b(30), 1.0), (b(32), 1.15),
               (b(33) + 8, 1.3)]
    return s


CRYSTAL_MIX = {"lead": (6.0, 0.40), "strings": (-1.0, 0.44), "low": (-1.0, 0.26), "choir": (-3.0, 0.52),
               "harp": (-1.0, 0.46), "glints": (1.0, 0.60), "perc": (-6.0, 0.50)}


# ================================================================== the open sea
SEA_A1 = ("Eb4:2 Bb3:1 Eb4:2 F4:1 G4:3 Bb4:3 C5:2 Bb4:1 Ab4:2 G4:1 F4:5 R:1 "
          "Eb4:2 Bb3:1 Eb4:2 F4:1 G4:3 C5:3 Db5:2 C5:1 Bb4:2 Ab4:1 Bb4:5 R:1")
SEA_A2 = ("Eb4:2 Bb3:1 Eb4:2 F4:1 G4:3 Bb4:3 Eb5:3 C5:2 Ab4:1 Ab4:2 G4:1 F4:3 "
          "G4:2 F4:1 Eb4:2 G4:1 C5:3 Ab4:3 G4:2 F4:1 D4:2 F4:1 Eb4:5 R:1")


def compose_sea():
    """The open sea. A sea song in E flat, in a slow six-eight that rocks like a swell: two dotted beats to
    the bar at about 52, the cellos rolling up and down under it in eighths. The horn sings the tune, which
    lifts to the flattened seventh (D flat major, the old mixolydian of sailors' songs) before it comes
    round; the second time it climbs to E flat above and comes home, with a clarinet holding a slow line
    over it. The violins answer in C minor, the deep water, and then climb in two-bar waves through
    D flat to the dominant; the whole orchestra sings the tune at the high point, the violins an octave up
    over the horns, and the swell carries it out to a last E flat chord."""
    s = Song("region_sea", bpm=156, bars=62, bpb=6, tail=7.0)
    a1 = ["Eb", "Eb", "Ab", "Bb", "Eb", "Cm", "Db", "Eb"]
    a1b = ["Eb2", "Eb2", "Ab2", "Bb1", "Eb2", "C2", "Db2", "Eb2"]
    a2 = ["Eb", "Eb", "Ab", "Fm", "Eb", "Ab", "Bb", "Eb"]
    a2b = ["Eb2", "G2", "Ab2", "F2", "G2", "Ab2", "Bb1", "Eb2"]
    prog = (["Eb", "Eb", "Ab", "Eb", "Db", "Eb"] + a1 + a2
            + ["Cm", "Ab", "Fm", "G", "Cm", "Ab", "Fm", "G"]
            + ["Ab", "Bb", "Cm", "Ab", "Db", "Eb", "Bb7", "Bb7"]
            + a1 + a2 + ["Eb", "Eb", "Ab", "Eb", "Db", "Eb", "Eb", "Eb"])
    bass = (["Eb2"] * 6 + a1b + a2b
            + ["C2", "Ab1", "F1", "G1", "C2", "Ab1", "F1", "G1"]
            + ["Ab1", "Bb1", "C2", "Ab1", "Db2", "Bb1", "Bb1", "Bb1"]
            + a1b + a2b + ["Eb2"] * 8)
    assert len(prog) == 62 and len(bass) == 62
    s.chord_at = lambda beat: prog[min(61, int(beat // 6))]
    b = s.b
    INTRO, A1, A2, B1, B2, C1, C2, CODA = (range(1, 7), range(7, 15), range(15, 23), range(23, 31), range(31, 39),
                                         range(39, 47), range(47, 55), range(55, 63))

    hn = s.part("horn", 60, "lead", pan=62, volume=104, hum=0.012, vjit=3, lag=0.015)
    hn2 = s.part("horn2", 60, "brass", pan=74, volume=96, hum=0.014, vjit=3, lag=0.015)
    hn3 = s.part("horn3", 60, "brass", pan=82, volume=94, hum=0.014, vjit=3, lag=0.015)
    tbn = s.part("tbn", 57, "brass", pan=88, volume=90, hum=0.014, vjit=3, lag=0.015)
    vln1 = s.part("vln1", 48, "strings", pan=38, volume=102, hum=0.012, vjit=3, lag=0.01)
    vln2 = s.part("vln2", 48, "strings", pan=46, volume=96, hum=0.012, vjit=3, lag=0.012)
    pad = s.part("pad", 49, "strings", pan=56, volume=90, hum=0.02, vjit=3)
    vla = s.part("vla", 41, "rock", pan=66, volume=92, hum=0.008, vjit=4)
    vc = s.part("vc", 42, "rock", pan=72, volume=100, hum=0.008, vjit=4)
    cb = s.part("cb", 43, "low", pan=80, volume=98, hum=0.01, vjit=3)
    cl = s.part("clar", 71, "winds", pan=48, volume=96, hum=0.012, vjit=3, lag=0.01)
    fl = s.part("flute", 73, "winds", pan=56, volume=94, hum=0.012, vjit=3, lag=0.01)
    ob = s.part("oboe", 68, "winds", pan=70, volume=92, hum=0.012, vjit=3, lag=0.01)
    harp = s.part("harp", 46, "harp", pan=26, volume=100, hum=0.006, vjit=4)
    aah = s.part("aah", 52, "choir", pan=58, volume=88, hum=0.02, vjit=3)
    timp = s.part("timp", 47, "perc", pan=58, volume=96, hum=0.004, vjit=3)
    kit = s.part("kit", 48, "perc", drum=True, pan=54, volume=84, hum=0.003, vjit=2)
    far = s.part("far", 60, "far", pan=30, volume=96, hum=0.016, vjit=3, lag=0.02)

    inten = np.interp(np.arange(1, 63), [1, 6, 7, 14, 15, 22, 23, 30, 31, 38, 39, 46, 47, 49, 51, 54, 55, 62],
                      [.20, .32, .36, .42, .46, .52, .50, .58, .60, .86, .88, .90, .94, 1.0, .96, .84, .44, .18])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[int(bar) - 1]

    PV = voicings(prog, [(55, 63), (58, 67), (62, 70), (65, 74)], bass=bass)

    # ---- the swell: cellos rolling up and down an open chord in eighths, violas joining when it grows
    def ladder(bar, lo):
        ch = prog[bar - 1]
        root = P(bass[bar - 1]) % 12
        q = next(m for m in range(lo, lo + 12) if m % 12 == root)
        out = [q]
        for m in range(q + 1, q + 30):
            if m % 12 in pcs(ch) and (m - q >= 7 or m - out[-1] >= 5) and m - out[-1] >= 3:
                out.append(m)
        return out

    def rock(part, bars, lo, vel, pattern=(0, 1, 2, 3, 2, 1)):
        for bar in bars:
            lad = ladder(bar, lo)
            for i, k in enumerate(pattern):
                part.n(b(bar) + i, 1.12 if i < 5 else 0.98, lad[min(k, len(lad) - 1)],
                       vel(bar) + (8 if i == 0 else 3 if i == 3 else 0))
    rock(vc, list(range(1, 63)), 36, lambda bar: V(40, 66)(bar) + (12 if bar <= 6 else 8 if bar >= 58 else 0))
    rock(vla, list(B2) + list(C1) + list(C2), 48, V(34, 58), pattern=(1, 2, 3, 4, 3, 2))
    vc.expr([(0, 72), (b(2), 96), (b(7), 100), (b(31), 102), (b(39), 112), (b(55), 98), (b(61), 76),
             (b(63) + 2, 30)])
    vla.expr([(b(31), 60), (b(35), 90), (b(39), 104), (b(55) - 0.1, 90)])
    for bar in range(7, 63):
        cb.n(b(bar), 5.6, bass[bar - 1], V(44, 74)(bar))
    cb.expr([(b(7), 70), (b(9), 92), (b(39), 108), (b(55), 96), (b(63) + 2, 30)])

    # ---- the intro: the swell alone, a distant horn fifth, the harp catching light on the water
    far.chord(b(2), 9, ["Eb3", "Bb3"], 52)
    far.chord(b(5), 6.5, ["Db3", "Ab3"], 48)
    far.expr([(b(2), 40), (b(3), 96), (b(4), 70), (b(5), 92), (b(6, 6), 40)])
    roll(timp, s, 0, 12, P("Eb2"), 22, 40, rate=0.25)
    roll(kit, s, b(5), 6, 57, 10, 34, rate=0.25)

    # ---- A: the horn sings the tune; the clarinet holds a slow line over it the second time
    sing_line(hn, b(7), SEA_A1 + " " + SEA_A2, 74, contour=0.3)
    for b0 in (7, 15):
        hn.expr([(b(b0), 92), (b(b0 + 1), 106), (b(b0 + 3), 100), (b(b0 + 4) - 0.2, 84), (b(b0 + 4), 96),
                 (b(b0 + 6), 114), (b(b0 + 8) - 0.2, 88)])
    sing_line(cl, b(15), "G4:6 Bb4:6 C5:6 C5:6 Bb4:6 Eb5:6 D5:6 Eb5:5 R:1", 54, contour=0.1)
    cl.expr([(b(15), 70), (b(17), 96), (b(20), 104), (b(23) - 0.2, 80)])
    place(pad, s, list(A1) + list(A2), PV[6:22], [0, 1, 2], V(34, 50), clear=[hn, cl])
    swell(pad, s, list(A1) + list(A2), lo=64, hi=96, every=2)
    for bar in list(INTRO)[2:] + list(A1) + list(A2):
        ch = prog[bar - 1]
        tones = [m for m in range(67, 86) if m % 12 in pcs(ch)]
        for t, k in ((0, 0), (3, 1)):
            for j in range(3):
                q = tones[min(len(tones) - 1, k + j * 2)]
                harp.n(b(bar) + t + j * 0.18, 2.6 - j * 0.18, q, V(34, 48)(bar) - j * 3)

    # ---- B: the violins in C minor, the deep water, the oboe's echo; then waves climbing to the dominant
    sing_line(vln1, b(23), "Eb5:3 D5:2 C5:1 C5:3 Eb5:3 Ab5:3 G5:2 F5:1 D5:3 B4:3 "
                           "Eb5:3 D5:2 C5:1 C5:2 Eb5:1 Ab5:3 Ab5:2 G5:1 F5:3 G5:3 F5:2 D5:1", 72, contour=0.3)
    vln1.expr([(b(23), 90), (b(25), 106), (b(27), 98), (b(29), 110), (b(31) - 0.2, 100)])
    sing_line(ob, b(24, 4), "G4:3 Ab4:2 C5:1 B4:3 G4:3 R:12 Ab4:3 Bb4:2 C5:1 B4:6", 56, contour=0.1)
    ob.expr([(b(24), 80), (b(26), 96), (b(29), 100), (b(31) - 0.2, 70)])
    place(pad, s, list(B1), PV[22:30], [0, 1, 2], V(34, 50), clear=[vln1, ob])
    swell(pad, s, list(B1), lo=70, hi=100, every=2)
    climb = ("Eb4:2 Ab4:1 C5:3 D5:2 C5:1 Bb4:3 G4:2 C5:1 Eb5:3 F5:2 Eb5:1 C5:3 "
             "F5:2 Ab5:1 Db6:3 Bb5:3 G5:3 Ab5:3 F5:3 D5:2 Eb5:1 F5:3")
    sing_line(vln1, b(31), climb, 76, contour=0.3)
    sing_line(vln2, b(31), climb, 70, tr=-12, contour=0.3)
    for p_ in (vln1, vln2):
        p_.expr([(b(31), 88), (b(33), 100), (b(35), 112), (b(37), 118), (b(39) - 0.2, 124)])
    BV = voicings(prog[30:38], [(46, 55), (51, 60), (55, 64)], bass=bass[30:38])
    for bar, vc_ in zip(B2, BV):
        v = V(42, 62)(bar)
        hn3.n(b(bar), 6.05, vc_[0], v)
        hn2.n(b(bar), 6.05, vc_[1], v)
        hn.n(b(bar), 6.05, vc_[2], v)
    for p_ in (hn, hn2, hn3):
        p_.expr([(b(31), 60), (b(35), 96), (b(39) - 0.2, 116)])
    for k in range(4):
        bar = 31 + 2 * k
        roll(timp, s, b(bar, 4), 3 + 3 * (k == 3), P("Bb1") if k == 3 else P(bass[bar] if bar < 62 else "Eb2"),
             24 + 6 * k, 42 + 10 * k, rate=0.25)
    roll(kit, s, b(37), 12, 57, 12, 60, rate=0.25)

    # ---- C: the tune in full: violins an octave up, horns in the middle, flute and oboe doubling, choir under
    full = SEA_A1 + " " + SEA_A2
    sing_line(vln1, b(39), full, 82, tr=12, contour=0.3)
    sing_line(vln2, b(39), full, 76, contour=0.3)
    sing_line(hn, b(39), full, 80, contour=0.3)
    sing_line(fl, b(47), SEA_A2, 62, tr=12, contour=0.3)
    sing_line(ob, b(39), SEA_A1, 60, tr=12, contour=0.3)
    for p_ in (vln1, vln2, hn, fl, ob):
        p_.expr([(b(39), 108), (b(41), 116), (b(43), 110), (b(47), 116), (b(49), 124), (b(51), 116),
                 (b(53), 112), (b(55) - 0.2, 96)])
    CV = voicings(prog[38:54], [(43, 52), (48, 57), (52, 61)], bass=bass[38:54])
    for bar, vc_ in zip(list(C1) + list(C2), CV):
        v = V(46, 66)(bar)
        tbn.n(b(bar), 6.05, vc_[0], v)
        hn3.n(b(bar), 6.05, vc_[1], v)
        hn2.n(b(bar), 6.05, vc_[2], v)
    for p_ in (hn2, hn3, tbn):
        p_.expr([(b(39), 100), (b(49), 116), (b(55) - 0.2, 80)])
    place(aah, s, list(C1) + list(C2), PV[38:54], [0, 1, 2], V(36, 54), clear=[vln1, vln2, hn])
    aah.expr([(b(39), 70), (b(47), 96), (b(49), 110), (b(55) - 0.2, 70)])
    for bar in list(C1) + list(C2):
        ch = prog[bar - 1]
        tones = [m for m in range(60, 91) if m % 12 in pcs(ch)]
        for j in range(6):
            q = tones[min(len(tones) - 1, j * 2)]
            harp.n(b(bar) + j * 0.5, 2.0, q, V(36, 50)(bar) - j)
    for bar in (39, 43, 47, 51):
        roll(timp, s, b(bar) - 3, 3, P("Bb1"), 30, 60, rate=0.25)
        timp.n(b(bar), 3, "Eb2", 66)
    kit.n(b(49), 6, 57, 50)
    kit.n(b(39), 6, 81, 40)

    # ---- coda: the swell carries it out; the horn's last line, the harp, E flat
    sing_line(hn, b(55), "Eb4:2 Bb3:1 Eb4:2 F4:1 G4:3 Bb4:3 C5:2 Bb4:1 Ab4:2 G4:1 Eb4:3 R:3", 66, contour=0.3)
    hn.expr([(b(55), 88), (b(56), 100), (b(58), 84), (b(59) - 0.2, 70)])
    sing_line(far, b(60), "Bb3:3 Eb4:9", 44)
    far.expr([(b(60), 60), (b(60, 4), 96), (b(61), 80), (b(62), 50)])
    place(pad, s, list(CODA), PV[54:62], [0, 1, 2], V(36, 52), clear=[hn, far])
    pad.expr([(b(55), 90), (b(59), 80), (b(63) + 2, 24)])
    for i, q in enumerate(["Eb2", "Bb2", "Eb3", "G3", "Bb3", "Eb4", "G4", "Bb4", "Eb5"]):
        harp.n(b(62) + i * 0.4, 10 - i * 0.4, q, 46 + i)
    roll(timp, s, b(61), 6, P("Eb2"), 20, 34, rate=0.25)
    timp.n(b(62), 6, "Eb2", 36)

    s.tempo = [(0, 1.0), (b(38, 4), 1.0), (b(39) - 0.5, 1.08), (b(39), 1.0), (b(60), 1.0), (b(62), 1.18),
               (b(63) + 8, 1.4)]
    return s


SEA_MIX = {"lead": (2.0, 0.30), "brass": (-4.0, 0.42), "strings": (0.0, 0.36), "rock": (-1.0, 0.26),
           "low": (-1.0, 0.22), "winds": (0.0, 0.34), "harp": (-2.0, 0.40), "choir": (-4.0, 0.48),
           "perc": (-4.0, 0.36), "far": (-7.0, 0.80)}


# ================================================================== the windswept crags
CALL = "E4:2 A4:1 B4:2 C5:3 B4:1 G4:1 A4:2 F#4:1 D4:2 E4:5 E4:2 A4:1 B4:2 C5:2 D5:1 E5:2 D5:3 B4:1 G4:1 A4:5"


def compose_crags():
    """Windswept hills and stone peaks. A dorian in five-four, three and two, the stride of someone climbing.
    A horn calls across the peaks before anything else, and a farther horn gives back the end of it. Cellos
    and basses take up a striding figure (the root held for three beats, then the fifth and the octave as
    two steps up), while high strings rise and fall in gusts. The horn sings the call in full, its raised
    sixth (F sharp) the colour of the dorian; the cellos and the horn trade its head over a climbing bass
    as the wind and the drums grow; at the high point the horns, trombones and violins sing it together and
    it ends on A major, light breaking over the stone. Then the horn calls once more alone, the far horn
    answers, and only the wind and an open fifth remain."""
    s = Song("region_crags", bpm=72, bars=34, bpb=5, tail=7.0)
    a = ["Am", "C", "D", "Am", "Am", "C", "G", "Am"]
    ab = ["A2", "C3", "D3", "A2", "A2", "C3", "G2", "A2"]
    prog = (["A5"] * 4 + a + ["F", "G", "Am", "Em", "F", "G", "C", "D"]
            + ["Am", "C", "D", "Am", "Am", "C", "G", "A"] + ["A5"] * 6)
    bass = (["A2"] * 4 + ab + ["F2", "G2", "A2", "E2", "F2", "G2", "C3", "D3"]
            + ab[:7] + ["A2"] + ["A2"] * 6)
    assert len(prog) == 34 and len(bass) == 34
    s.chord_at = lambda beat: prog[min(33, int(beat // 5))]
    b = s.b
    INTRO, A, B, C, CODA = range(1, 5), range(5, 13), range(13, 21), range(21, 29), range(29, 35)

    hn = s.part("horn", 60, "lead", pan=60, volume=106, hum=0.012, vjit=3, lag=0.015)
    echo = s.part("echo", 60, "far", pan=96, volume=96, hum=0.016, vjit=3, lag=0.02)
    hn2 = s.part("horn2", 60, "brass", pan=72, volume=98, hum=0.014, vjit=3, lag=0.015)
    hn3 = s.part("horn3", 60, "brass", pan=80, volume=94, hum=0.014, vjit=3, lag=0.015)
    tb1 = s.part("tb1", 57, "brass", pan=86, volume=92, hum=0.014, vjit=3, lag=0.015)
    tb2 = s.part("tb2", 57, "brass", pan=92, volume=90, hum=0.014, vjit=3, lag=0.015)
    vcl = s.part("vcl", 42, "lead", pan=70, volume=100, hum=0.012, vjit=3, lag=0.012)
    vln1 = s.part("vln1", 48, "strings", pan=38, volume=100, hum=0.012, vjit=3, lag=0.01)
    vln2 = s.part("vln2", 48, "strings", pan=46, volume=96, hum=0.012, vjit=3, lag=0.012)
    pad = s.part("pad", 49, "strings", pan=56, volume=90, hum=0.02, vjit=3)
    wind = s.part("wind", 44, "wind", pan=30, volume=84, hum=0.01, vjit=3)
    wind2 = s.part("wind2", 49, "wind", pan=100, volume=80, hum=0.01, vjit=3)
    vc = s.part("vc", 42, "low", pan=74, volume=100, hum=0.008, vjit=4)
    cb = s.part("cb", 43, "low", pan=82, volume=98, hum=0.008, vjit=4)
    aah = s.part("aah", 52, "choir", pan=58, volume=88, hum=0.02, vjit=3)
    fl = s.part("flute", 73, "winds", pan=44, volume=90, hum=0.012, vjit=3, lag=0.01)
    taiko = s.part("taiko", 116, "drums", pan=56, volume=96, hum=0.004, vjit=3)
    timp = s.part("timp", 47, "drums", pan=62, volume=96, hum=0.004, vjit=3)
    kit = s.part("kit", 48, "drums", drum=True, pan=50, volume=82, hum=0.003, vjit=2)

    inten = np.interp(np.arange(1, 35), [1, 4, 5, 12, 13, 16, 17, 20, 21, 25, 26, 27, 28, 29, 34],
                      [.26, .32, .38, .46, .50, .58, .64, .82, .90, .96, 1.0, .96, .92, .46, .20])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[int(bar) - 1]

    PV = voicings(prog, [(52, 60), (55, 64), (59, 67), (62, 71)], bass=bass)

    # ---- the horn calls first, alone with the wind; the far horn gives back the end of the call
    sing_line(hn, 0, "E4:2 A4:1 B4:2 C5:3 B4:1 A4:1", 66, contour=0.3)
    hn.expr([(0, 80), (2, 104), (b(2), 110), (b(2, 4), 96), (b(3) - 0.1, 70)])
    sing_line(echo, b(3), "C5:3 B4:1 A4:6", 50, contour=0.2)
    echo.expr([(b(3), 70), (b(3, 2), 96), (b(4, 3), 70), (b(5), 40)])

    # ---- the wind: high strings in gusts, never quite still
    rng = rng_for("crags", "wind")
    for k in range(0, 34, 2):
        bar = k + 1
        both = [pcs(prog[bar - 1]), pcs(prog[min(33, bar)])]
        notes = [n for n in ("A5", "E5", "D5", "G5", "B5")
                 if all(abs(P(n) % 12 - t) % 12 not in (1, 11) for ch in both for t in ch)][:2]
        wind.chord(b(bar), 10.05, notes, V(34, 56)(bar))
        wind2.chord(b(bar) + 2.5, 7.5, [notes[1]], V(28, 46)(bar))
        peak = b(bar) + rng.uniform(3.0, 7.0)
        for p_ in (wind, wind2):
            p_.expr([(b(bar), 30), (peak, rng.uniform(80, 110)), (b(bar) + 9.9, 34)])

    # ---- the stride: the root held three beats, the fifth and octave as two steps up
    def stride(bars, vel, cb_too=True):
        for bar in bars:
            r = P(bass[bar - 1])
            fifth = next(m for m in range(r + 6, r + 9) if m % 12 in pcs(prog[bar - 1]))
            vc.n(b(bar), 2.9, r, vel(bar) + 8)
            vc.n(b(bar, 4), 0.95, fifth, vel(bar))
            vc.n(b(bar, 5), 0.95, r + 12, vel(bar) + 3)
            if cb_too:
                cb.n(b(bar), 2.9, r - 12, vel(bar))
    stride(list(INTRO)[2:], V(40, 66), cb_too=False)
    stride(list(A) + list(B) + list(C), V(44, 72))
    vc.expr([(b(3), 60), (b(4), 92), (b(5), 98), (b(21), 112), (b(29) - 0.1, 100)])
    cb.expr([(b(5), 80), (b(21), 108), (b(29) - 0.1, 96)])

    # ---- A: the call in full on the horn; strings hold the chords low
    sing_line(hn, b(5), CALL, 74, contour=0.3)
    for b0 in (5, 9):
        hn.expr([(b(b0), 92), (b(b0, 3), 108), (b(b0 + 1), 112), (b(b0 + 2), 100), (b(b0 + 3), 96),
                 (b(b0 + 4) - 0.1, 76)])
    sing_line(echo, b(8, 3), "D4:1 E4:5", 44, contour=0.2)
    sing_line(echo, b(12, 3), "G4:1 A4:5", 44, contour=0.2)
    echo.expr([(b(8, 3), 60), (b(9), 90), (b(10) - 0.1, 50), (b(12, 3), 60), (b(13), 90), (b(14) - 0.1, 50)])
    place(pad, s, list(A), PV[4:12], [0, 1, 2], V(34, 50), clear=[hn, echo])
    swell(pad, s, list(A), lo=62, hi=96, every=2)

    # ---- B: the cellos and the horn trade the head of the call; the wind and the drums grow
    sing_line(vcl, b(13), "F3:2 C4:1 D4:2 E4:3 D4:1 B3:1 C4:2 A3:1 E3:2 B3:5", 72, contour=0.3)
    vcl.expr([(b(13), 92), (b(14), 108), (b(15), 100), (b(16), 106), (b(17) - 0.1, 90)])
    sing_line(hn, b(17), "G4:2 C5:1 D5:2 E5:3 D5:1 B4:1", 76, contour=0.3)
    hn.expr([(b(17), 96), (b(18), 116), (b(19) - 0.1, 96)])
    sing_line(vln1, b(19), "E5:2 G5:1 A5:2 F#5:3 E5:1 D5:1", 76, contour=0.3)
    sing_line(vln2, b(19), "E5:2 G5:1 A5:2 F#5:3 E5:1 D5:1", 70, tr=-12, contour=0.3)
    for p_ in (vln1, vln2):
        p_.expr([(b(19), 92), (b(20), 112), (b(21) - 0.1, 120)])
    sing_line(fl, b(15), "A5:5 G5:5", 50, contour=0.1)
    fl.expr([(b(15), 50), (b(16), 90), (b(17) - 0.1, 40)])
    place(pad, s, list(B), PV[12:20], [0, 1, 2], V(36, 56), clear=[vcl, hn, vln1, vln2, fl])
    swell(pad, s, list(B), lo=66, hi=104, every=2, shape="cresc")
    for bar in B:
        taiko.n(b(bar), 2, "D2", V(36, 64)(bar))
        if bar >= 17:
            taiko.n(b(bar, 4), 1, "D2", V(30, 54)(bar))
    roll(timp, s, b(20), 5, P("D2"), 30, 72, rate=0.125)
    roll(kit, s, b(20), 5, 57, 14, 56, rate=0.125)

    # ---- C: the call sung by everyone, ending on A major
    sing_line(vln1, b(21), CALL, 84, tr=12, contour=0.3)
    sing_line(vln2, b(21), CALL, 78, contour=0.3)
    sing_line(hn, b(21), CALL, 84, contour=0.3)
    sing_line(hn2, b(21), CALL, 78, contour=0.3)
    for p_ in (vln1, vln2, hn, hn2):
        p_.expr([(b(21), 110), (b(22), 120), (b(24), 108), (b(25), 112), (b(26), 124), (b(27), 118),
                 (b(28), 120), (b(29) - 0.1, 96)])
    CV = voicings(prog[20:28], [(40, 50), (45, 55), (50, 59)], bass=bass[20:28])
    for bar, vc_ in zip(C, CV):
        v = V(50, 72)(bar)
        tb2.n(b(bar), 5.05, vc_[0], v)
        tb1.n(b(bar), 5.05, vc_[1], v)
        hn3.n(b(bar), 5.05, vc_[2], v)
    for p_ in (tb1, tb2, hn3):
        p_.expr([(b(21), 100), (b(26), 120), (b(29) - 0.1, 90)])
    place(aah, s, list(C), PV[20:28], [0, 1, 2], V(36, 56), clear=[vln1, vln2, hn])
    aah.expr([(b(21), 80), (b(26), 112), (b(29) - 0.1, 70)])
    for bar in C:
        taiko.n(b(bar), 2, "D2", V(50, 76)(bar))
        taiko.n(b(bar, 4), 1, "D2", V(40, 64)(bar))
    timp.n(b(21), 3, "A1", 72)
    roll(timp, s, b(27, 4), 2, P("E2"), 40, 76, rate=0.125)
    timp.n(b(28), 4, "A1", 74)
    kit.n(b(21), 5, 57, 52)
    kit.n(b(28), 6, 57, 46)

    # ---- coda: the horn calls once more alone, the far horn answers, the wind and an open fifth
    sing_line(hn, b(29, 3), "E4:2 A4:1 B4:2 C5:3 B4:1 A4:6", 62, contour=0.3)
    hn.expr([(b(29, 3), 80), (b(30), 104), (b(31), 96), (b(32) - 0.5, 60)])
    sing_line(echo, b(32), "C5:3 B4:1 A4:8", 46, contour=0.2)
    echo.expr([(b(32), 66), (b(32, 3), 92), (b(33, 3), 60), (b(35), 30)])
    cb.n(b(29), 30, "A1", 46)
    vc.n(b(29), 30, "E2", 40)
    for p_ in (cb, vc):
        p_.expr([(b(29), 96), (b(31), 84), (b(35) + 2, 24)])
    pad.chord(b(29), 30.5, ["A3", "E4"], 36)
    pad.expr([(b(29), 80), (b(31), 76), (b(35) + 2, 20)])

    s.tempo = [(0, 1.0), (b(20, 4), 1.0), (b(21), 1.06), (b(21, 2), 1.0), (b(32), 1.0), (b(34), 1.15),
               (b(35) + 7, 1.35)]
    return s


CRAGS_MIX = {"lead": (2.0, 0.32), "far": (-5.0, 0.85), "brass": (-3.0, 0.40), "strings": (-1.0, 0.36),
             "wind": (-3.0, 0.50), "low": (0.0, 0.22), "choir": (-4.0, 0.48), "winds": (-2.0, 0.40),
             "drums": (-3.0, 0.36)}


# ================================================================== driver
PIECES = {
    "marsh": dict(fn=compose_marsh, mix=MARSH_MIX, ir=dict(rt60=2.9, seed=201, predelay=0.03, hf_damp=0.7),
                  fade_out=3.0, comp=(-18.0, 1.6)),
    "ashen": dict(fn=compose_ashen, mix=ASHEN_MIX, ir=dict(rt60=2.7, seed=203, predelay=0.03, hf_damp=0.6),
                  fade_out=3.0, comp=(-20.0, 1.8)),
    "frozen": dict(fn=compose_frozen, mix=FROZEN_MIX, ir=dict(rt60=3.2, seed=207, predelay=0.035, hf_damp=0.45),
                   fade_out=3.0, comp=(-20.0, 1.8)),
    "crystal": dict(fn=compose_crystal, mix=CRYSTAL_MIX, ir=dict(rt60=3.8, seed=211, predelay=0.04, hf_damp=0.4),
                    fade_out=3.5, comp=(-22.0, 2.0)),
    "sea": dict(fn=compose_sea, mix=SEA_MIX, ir=dict(rt60=2.6, seed=213, predelay=0.03, hf_damp=0.55),
                fade_out=3.0, comp=(-22.0, 2.0)),
    "crags": dict(fn=compose_crags, mix=CRAGS_MIX, ir=dict(rt60=3.0, seed=217, predelay=0.04, hf_damp=0.55),
                  fade_out=3.0, comp=(-22.0, 2.0)),
}


def build(name, assets, sf2, work, wav_dir=None):
    cfg = PIECES[name]
    song = cfg["fn"]()
    if getattr(song, "tempo", None):
        tempo_map(song, song.tempo)
    os.makedirs(work, exist_ok=True)
    stems = {}
    for stem in sorted(set(p.stem for p in song.parts)):
        mid = os.path.join(work, f"region_{name}_{stem}.mid")
        wav = os.path.join(work, f"region_{name}_{stem}.wav")
        export_stem(song, stem, mid, tail_beats=10)
        stems[stem] = stem_fx(render(mid, wav, sf2), cfg.get("fx", {}).get(stem, ()))
    length = song.end_beat * song.spb + song.tail
    ir = room_ir(early=18, early_span=0.07, stereo=True, density_ms=35.0, **cfg["ir"])
    out = mix(stems, cfg["mix"], ir, length, fade_in=0.02, fade_out=cfg["fade_out"],
              master_eq=cfg.get("master_eq", True), comp=cfg.get("comp", (-18.0, 1.6)))
    out = master(out)
    path = os.path.join(assets, "sounds", "music", "region", name + ".ogg")
    write_ogg(path, out, quality=cfg.get("quality", 3))
    if wav_dir:
        write_wav(os.path.join(wav_dir, name + ".wav"), out)
    print(f"{path}  {out.shape[1] / SR:.2f}s  {lufs(out):.1f} LUFS  {true_peak(out):.1f} dBTP", flush=True)
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
