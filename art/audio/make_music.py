"""Guildmark boss music and orchestral UI stingers, composed in code and rendered with FluidSynth.

usage (from the repo root):
    python3 art/audio/make_music.py src/main/resources/assets/guildmark [--sf2 FluidR3_GM.sf2]
        [--work DIR] [--wav DIR] [--only fallen_warden,fallen_warden_2,boss_appear,foe_vanquished]

Everything is original. Halvard's leitmotif ("the oath") is a stern falling four-note figure,
A - F - E - C# in D minor: it falls a major third, a semitone, then a minor third and stops on the
leading tone, so it never resolves (the oath still binds him). Phase two plays it in diminution, and
the victory stinger finally resolves it in D major (A - F# - E - D).

Each piece is written as parts (one per player or section), exported per stem (low strings, high
strings, brass, choir, percussion, colour) to MIDI with humanised timing/velocity, rendered dry with
FluidSynth (General MIDI FluidR3 soundfont), then mixed here: per-stem gain and send to a synthetic
stereo concert-hall convolution reverb, gentle glue compression, a lookahead limiter to -1 dBFS and fades.
"""
import argparse
import os
import subprocess
import sys
from itertools import product

import mido
import numpy as np
from scipy import signal
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import SR, room_ir, hp, eq, db, to_db, write_ogg, write_wav, fade, rng_for  # noqa: E402
from scipy.ndimage import maximum_filter1d, minimum_filter1d, uniform_filter1d  # noqa: E402

SF2_DEFAULT = "/usr/share/sounds/sf2/FluidR3_GM.sf2"

# ------------------------------------------------------------------ pitches and chords
NOTE_BASE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def P(name):
    """'C#4' -> 61, 'Bb2' -> 46 (C4 = 60)"""
    if isinstance(name, (int, np.integer)):
        return int(name)
    letter, i, acc = name[0].upper(), 1, 0
    while i < len(name) and name[i] in "#b":
        acc += 1 if name[i] == "#" else -1
        i += 1
    return 12 * (int(name[i:]) + 1) + NOTE_BASE[letter] + acc


CHORDS = {
    "Dm": "D F A", "D": "D F# A", "A": "A C# E", "A7": "A C# E G", "Asus": "A D E", "Gm": "G Bb D",
    "Gm6": "G Bb D E", "Bb": "Bb D F", "F": "F A C", "Eb": "Eb G Bb", "C": "C E G", "Ebm": "Eb Gb Bb",
    "Cm": "C Eb G", "Fm": "F Ab C", "Ab": "Ab C Eb", "Db": "Db F Ab", "Bbm": "Bb Db F", "E": "E G# B",
    "Em": "E G B", "G": "G B D", "Am": "A C E",
}


def pcs(ch):
    return [P(n + "0") % 12 for n in CHORDS[ch].split()]


def in_range(pc, lo, hi):
    """lowest midi note with pitch class pc in [lo, hi]"""
    for m in range(lo, hi + 1):
        if m % 12 == pc:
            return m
    raise ValueError((pc, lo, hi))


def voice_lead(chords, ranges, start=None):
    """smooth voicings: for each chord choose one note per voice (ranges low->high) minimising
    movement while covering root and third, avoiding crossings and doubled thirds"""
    out = []
    prev = start or [(lo + hi) // 2 for lo, hi in ranges]
    for ch in chords:
        pc = pcs(ch)
        cand = [[m for m in range(lo, hi + 1) if m % 12 in pc] for lo, hi in ranges]
        best, bestc = None, 1e9
        for combo in product(*cand):
            if any(combo[i] >= combo[i + 1] for i in range(len(combo) - 1)):
                continue
            cost = sum(abs(a - b) for a, b in zip(combo, prev))
            have = [m % 12 for m in combo]
            if pc[0] not in have:
                cost += 12
            if pc[1] not in have:
                cost += 10
            if len(pc) > 2 and pc[2] not in have:
                cost += 3
            if len(pc) > 3 and pc[3] not in have:
                cost += 5
            if have.count(pc[1]) > 1:
                cost += 4
            if combo[-1] - combo[0] > 19:
                cost += 2
            if cost < bestc:
                best, bestc = combo, cost
        out.append(list(best))
        prev = list(best)
    return out


DYN = dict(ppp=24, pp=36, p=50, mp=64, mf=78, f=94, ff=108, fff=120)


# ------------------------------------------------------------------ parts and songs
class Part:
    def __init__(self, name, program, stem, bank=0, pan=64, volume=100, drum=False, hum=0.010, vjit=5,
                 lag=0.0, expr0=110):
        self.name, self.program, self.stem, self.bank, self.pan = name, program, stem, bank, pan
        self.volume, self.drum, self.hum, self.vjit, self.lag, self.expr0 = volume, drum, hum, vjit, lag, expr0
        self.notes, self.ccs = [], []

    def n(self, beat, dur, p, vel):
        self.notes.append([float(beat), float(dur), P(p), int(max(1, min(127, round(vel))))])

    def line(self, beat, spec, vel, tr=0, leg=1.0, accent=0):
        """'A4:2 F4:1.5 E4:0.5 C#4:4 R:1' (beats); chord tokens like 'D4+F4:2'"""
        first = True
        for tok in spec.split():
            nm, d = tok.rsplit(":", 1)
            d = float(d)
            if nm != "R":
                for q in nm.split("+"):
                    self.n(beat, d * leg, P(q) + tr, vel + (accent if first else 0))
            first = False
            beat += d
        return beat

    def chord(self, beat, dur, notes, vel):
        for q in notes:
            self.n(beat, dur, q, vel)

    def expr(self, pts, step=0.125, cc=11):
        """piecewise-linear controller curve [(beat, value), ...] (CC11 expression by default)"""
        pts = sorted(pts)
        for (b0, v0), (b1, v1) in zip(pts[:-1], pts[1:]):
            k = max(1, int(round((b1 - b0) / step)))
            for i in range(k):
                self.ccs.append((b0 + (b1 - b0) * i / k, cc, int(round(v0 + (v1 - v0) * i / k))))
        self.ccs.append((pts[-1][0], cc, int(round(pts[-1][1]))))


class Song:
    def __init__(self, name, bpm, bars, bpb=4, tail=1.2):
        self.name, self.bpm, self.bars, self.bpb, self.tail = name, bpm, bars, bpb, tail
        self.parts = []
        self.end_beat = bars * bpb

    def part(self, *a, **k):
        p = Part(*a, **k)
        self.parts.append(p)
        return p

    def b(self, bar, beat=1.0):
        """absolute beat of (1-based bar, 1-based beat)"""
        return (bar - 1) * self.bpb + (beat - 1)

    @property
    def spb(self):
        return 60.0 / self.bpm


def export_stem(song, stem, path, tail_beats=8):
    parts = [p for p in song.parts if p.stem == stem]
    tpb = 960
    mf = mido.MidiFile(type=1, ticks_per_beat=tpb)
    meta = mido.MidiTrack()
    mf.tracks.append(meta)
    meta.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(song.bpm), time=0))
    meta.append(mido.MetaMessage("end_of_track", time=int((song.end_beat + tail_beats) * tpb)))
    chans = [c for c in range(16) if c != 9]
    ci = 0
    for p in parts:
        rng = rng_for(song.name, p.name)
        if p.drum:
            ch = 9
        else:
            ch = chans[ci]
            ci += 1
        ev = []
        if not p.drum:
            ev.append((0, 0, mido.Message("control_change", channel=ch, control=0, value=p.bank)))
            ev.append((0, 0, mido.Message("control_change", channel=ch, control=32, value=0)))
        ev.append((0, 1, mido.Message("program_change", channel=ch, program=p.program)))
        for c, v in ((7, p.volume), (10, p.pan), (11, p.expr0)):
            ev.append((0, 2, mido.Message("control_change", channel=ch, control=c, value=int(v))))
        for b, c, v in p.ccs:
            ev.append((int(round(b * tpb)), 3, mido.Message("control_change", channel=ch, control=c,
                                                             value=int(max(0, min(127, v))))))
        # humanise, then stop same-pitch notes from cutting each other
        hn = []
        for b, d, q, vel in p.notes:
            off = (rng.normal(0, p.hum) + p.lag) / song.spb
            t0 = max(0, int(round((b + off) * tpb)))
            t1 = max(t0 + 20, int(round((b + d + off * 0.5) * tpb)))
            v = int(max(1, min(127, vel + rng.normal(0, p.vjit))))
            hn.append([t0, t1, q, v])
        hn.sort(key=lambda e: (e[2], e[0]))
        for i in range(len(hn) - 1):
            if hn[i][2] == hn[i + 1][2] and hn[i][1] > hn[i + 1][0] - 10:
                hn[i][1] = max(hn[i][0] + 10, hn[i + 1][0] - 10)
        for t0, t1, q, v in hn:
            ev.append((t0, 5, mido.Message("note_on", channel=ch, note=q, velocity=v)))
            ev.append((t1, 4, mido.Message("note_off", channel=ch, note=q, velocity=0)))
        ev.sort(key=lambda e: (e[0], e[1]))
        tr = mido.MidiTrack()
        last = 0
        for t, _, m in ev:
            tr.append(m.copy(time=t - last))
            last = t
        mf.tracks.append(tr)
    mf.save(path)
    return len(parts)


def render(mid, wav, sf2):
    subprocess.run(["fluidsynth", "-ni", "-q", "-F", wav, "-T", "wav", "-O", "float", "-r", str(SR),
                    "-R", "0", "-C", "0", "-g", "0.5", "-o", "synth.polyphony=1024", sf2, mid],
                   check=True, stdout=subprocess.DEVNULL)
    sr, y = wavfile.read(wav)
    y = y.astype(np.float64).T
    if y.ndim == 1:
        y = np.vstack([y, y])
    return y


# ------------------------------------------------------------------ mixing
def stereo_comp(x, thresh_db=-16.0, ratio=2.0, attack=0.01, release=0.25):
    """linked feed-forward compressor (smoothed envelope, vectorised)"""
    env = np.max(np.abs(x), axis=0)
    a = np.exp(-1.0 / (attack * SR))
    r = np.exp(-1.0 / (release * SR))
    # peak-ish envelope: fast rise via max filter, smooth decay
    pk = maximum_filter1d(env, size=int(attack * SR) * 2 + 1)
    sm = signal.lfilter([1 - r], [1, -r], pk)
    sm = np.maximum(sm, signal.lfilter([1 - a], [1, -a], pk))
    lev = to_db(sm)
    g = db(-np.maximum(0, lev - thresh_db) * (1 - 1 / ratio))
    return x * g


def stereo_limit(x, ceiling_db=-1.0, lookahead=0.004, release=0.08):
    c = db(ceiling_db)
    env = np.max(np.abs(x), axis=0)
    w = int(lookahead * SR)
    target = np.minimum(1.0, c / np.maximum(maximum_filter1d(env, size=2 * w + 1), 1e-12))
    g = minimum_filter1d(target, size=2 * w + 1)
    rr = np.exp(-1.0 / (release * SR))
    g2 = signal.lfilter([1 - rr], [1, -rr], g - 1.0) + 1.0
    g = uniform_filter1d(np.minimum(g, g2), size=w)
    return x * g


def mix(stems, settings, ir, length_s, fade_in=0.005, fade_out=1.0, master_eq=True, comp=(-16.0, 2.0)):
    n = max(s.shape[1] for s in stems.values())
    dry = np.zeros((2, n))
    send = np.zeros(n)
    for name, y in stems.items():
        g_db, snd = settings[name]
        y = np.pad(y, ((0, 0), (0, n - y.shape[1])))
        dry += db(g_db) * y
        send += snd * db(g_db) * y.mean(axis=0)
    send = hp(send, 180)
    wet = np.vstack([signal.fftconvolve(send, ir[0])[:n], signal.fftconvolve(send, ir[1])[:n]])
    out = dry + wet
    out = out[:, :int(length_s * SR)]
    for c in range(2):
        out[c] = hp(out[c], 30, order=2)
        if master_eq:
            out[c] = eq(out[c], "peak", 320, 0.8, -1.5)
            out[c] = eq(out[c], "highshelf", 7500, 0.7, 2.0)
    out /= np.max(np.abs(out)) + 1e-12
    out = stereo_comp(out, *comp)
    out /= np.max(np.abs(out)) + 1e-12
    out = stereo_limit(out * db(2.0), -1.0)
    out = out * (db(-1.0) / (np.max(np.abs(out)) + 1e-12))
    for c in range(2):
        out[c] = fade(out[c], fin=fade_in, fout=fade_out)
    return out


def hall_ir(rt60=2.3, seed=101, predelay=0.024):
    return room_ir(rt60=rt60, predelay=predelay, early=18, early_span=0.07, hf_damp=0.5, seed=seed,
                   stereo=True, density_ms=35.0)


# ------------------------------------------------------------------ shared writing helpers
def roots(prog, lo, hi):
    return [in_range(pcs(c)[0], lo, hi) for c in prog]


def ostinato(part, song, bars, prog, lo, hi, pattern, vel, acc=16, length=0.42, step=0.5, cresc=None):
    """repeated figure per bar; pattern = list of (semitones above root, accent)"""
    for bar in bars:
        ch = prog[bar - 1]
        r = in_range(pcs(ch)[0], lo, hi)
        for i, (iv, a) in enumerate(pattern):
            v = vel(bar) if callable(vel) else vel
            part.n(song.b(bar) + i * step, length, r + iv, v + (acc if a else 0))


def pads(part_list, song, bars, prog, ranges, vel, legato=1.02, dur_beats=4):
    """sustained voice-led chords; part_list gets one or more voices each (list of index lists)"""
    voicings = voice_lead([prog[b - 1] for b in bars], ranges)
    for bar, vc in zip(bars, voicings):
        for part, idxs in part_list:
            for i in idxs:
                v = vel(bar) if callable(vel) else vel
                part.n(song.b(bar), dur_beats * legato, vc[i], v)
    return voicings


def swell(part, song, bars, lo=70, hi=118, every=2, shape="cresc-dim"):
    for i in range(0, len(bars), every):
        b0 = song.b(bars[i])
        b1 = song.b(bars[min(len(bars) - 1, i + every - 1)]) + song.bpb
        mid = b0 + (b1 - b0) * 0.6
        if shape == "cresc":
            part.expr([(b0, lo), (b1 - 0.01, hi)])
        else:
            part.expr([(b0, lo), (mid, hi), (b1 - 0.01, lo + (hi - lo) * 0.45)])


def roll(part, song, beat0, beats, pitch, v0, v1, rate=0.125, kit=False):
    k = int(round(beats / rate))
    for i in range(k):
        part.n(beat0 + i * rate, rate * 0.9, pitch, v0 + (v1 - v0) * i / max(1, k - 1))


MOTIF = "A4:2 F4:1.5 E4:0.5 C#4:4"  # Halvard's oath (2 bars of 4/4)


# ================================================================== phase one
def compose_phase1():
    s = Song("fallen_warden", bpm=96, bars=46, tail=1.3)
    prog = (["Dm", "Dm", "Bb", "A"]
            + ["Dm", "A", "Gm", "A", "Dm", "A", "Gm", "A7"]
            + ["Dm", "A", "Bb", "F", "Gm", "Eb", "A", "A7"]
            + ["Bb", "F", "Gm", "Dm", "Eb", "Bb", "Gm6", "A"]
            + ["Dm", "A", "Dm", "A", "Gm", "D", "Gm", "A", "Bb", "Gm", "Eb", "A"]
            + ["Dm", "Bb", "Gm", "Eb", "A", "A"])
    assert len(prog) == 46
    s.chord_at = lambda beat: prog[min(len(prog) - 1, int(beat // 4))]

    # players
    ost = s.part("ost", 48, "low", pan=76, volume=112, hum=0.007, vjit=4)
    cb = s.part("cb", 43, "low", pan=88, volume=108, hum=0.007, vjit=4)
    pizz = s.part("pizz", 45, "low", pan=70, volume=100, hum=0.008)
    vln = s.part("vln", 48, "high", pan=34, volume=104, lag=0.012, hum=0.012)
    pad1 = s.part("pad1", 49, "high", pan=48, volume=96, hum=0.02)
    pad2 = s.part("pad2", 49, "high", pan=80, volume=96, hum=0.02)
    trem = s.part("trem", 44, "high", pan=42, volume=86, hum=0.01)
    cello = s.part("cello", 42, "high", pan=84, volume=108, lag=0.015, hum=0.012)
    hn1 = s.part("hn1", 60, "brass", pan=40, volume=110, lag=0.012)
    hn2 = s.part("hn2", 60, "brass", pan=52, volume=104, lag=0.014)
    tbn = s.part("tbn", 57, "brass", pan=82, volume=108, lag=0.01)
    tbn2 = s.part("tbn2", 57, "brass", pan=72, volume=100, lag=0.012)
    tuba = s.part("tuba", 58, "brass", pan=88, volume=104, lag=0.01)
    tpt = s.part("tpt", 56, "brass", pan=60, volume=88, lag=0.008)
    ahh = s.part("ahh", 52, "choir", pan=56, volume=100, hum=0.02)
    ohh = s.part("ohh", 53, "choir", pan=74, volume=100, hum=0.02)
    timp = s.part("timp", 47, "perc", pan=58, volume=112, hum=0.005, vjit=3)
    taiko = s.part("taiko", 116, "perc", pan=66, volume=100, hum=0.005, vjit=4)
    # Drum kit 48 is FluidR3's Orchestra Kit: 35/36 bass drum, 38 snare, 55 splash, 57/59 crash; 41 to 53 are
    # tuned timpani (45 is A, 49 is C#), not toms or cymbals
    kit = s.part("kit", 48, "perc", drum=True, pan=64, volume=104, hum=0.004, vjit=4)
    harp = s.part("harp", 46, "color", pan=28, volume=96, hum=0.008)
    bells = s.part("bells", 14, "color", pan=62, volume=80)

    # intensity curve (per bar, 0..1) drives velocities
    inten = np.interp(np.arange(1, 47),
                      [1, 4, 5, 12, 13, 20, 21, 24, 25, 28, 29, 36, 40, 41, 44, 45, 46],
                      [.50, .56, .62, .70, .76, .86, .42, .46, .52, .64, .90, .95, 1.0, .62, .60, .70, .88])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[bar - 1]

    # ---- low string ostinato: 3+3+2 accents, root / octave / fifth
    pat = [(0, 1), (0, 0), (0, 0), (12, 1), (0, 0), (0, 0), (7, 1), (0, 0)]
    run = list(range(1, 21)) + list(range(29, 47))
    ostinato(ost, s, run, prog, 43, 54, pat, V(48, 96), acc=16, length=0.44)
    ostinato(cb, s, run, prog, 31, 42, [(0, 1), (0, 0), (0, 0), (0, 1), (0, 0), (0, 0), (0, 1), (0, 0)],
             V(46, 92), acc=14, length=0.46)
    ostinato(pizz, s, range(25, 29), prog, 43, 54, pat, V(52, 90), acc=14, length=0.3)
    for bar in range(21, 25):  # long bass roots under the lament
        cb.n(s.b(bar), 4.0, in_range(pcs(prog[bar - 1])[0], 31, 42), 58)
    ost.expr([(0, 104), (s.b(41), 104), (s.b(41) + 0.1, 92), (s.b(45), 92), (s.b(47) - 0.1, 124)])

    # ---- string pads (four voices across two players)
    rng4 = [(50, 62), (55, 67), (60, 72), (64, 76)]
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, range(1, 47), prog, rng4, V(40, 82), dur_beats=4)
    for p_ in (pad1, pad2):
        swell(p_, s, list(range(1, 47)), lo=72, hi=112)

    # ---- choir: oohs early, aahs for the big sections
    choir_rng = [(48, 60), (53, 65), (57, 69), (62, 74)]
    pads([(ohh, [0, 1, 2, 3])], s, list(range(1, 13)), prog, choir_rng, V(36, 70))
    pads([(ahh, [0, 1, 2, 3])], s, list(range(13, 21)), prog, choir_rng, V(54, 86))
    pads([(ohh, [0, 1, 2, 3])], s, list(range(21, 29)), prog, choir_rng, V(40, 74))
    pads([(ahh, [0, 1, 2, 3])], s, list(range(29, 41)), prog, choir_rng, V(60, 96))
    pads([(ohh, [0, 1, 2, 3])], s, list(range(41, 47)), prog, choir_rng, V(40, 80))
    pads([(ahh, [1, 2, 3])], s, [45, 46], prog, choir_rng[0:4], 70)
    swell(ohh, s, list(range(1, 47)), lo=66, hi=110)
    swell(ahh, s, list(range(1, 47)), lo=70, hi=118)

    # ---- intro: a quiet hint of the oath in low horn
    hn2.line(s.b(3), "A3:2 F3:1.5 E3:0.5 C#3:4", 58, leg=0.98)
    hn2.expr([(s.b(3), 70), (s.b(4), 100), (s.b(5) - 0.1, 84)])

    # ---- A: the oath on horns (unison + octave)
    melA = ("A4:2 F4:1.5 E4:0.5 C#4:4 Bb4:2 G4:1.5 F4:0.5 E4:4 "
            "A4:2 F4:1.5 E4:0.5 C#4:2 E4:1 G4:1 Bb4:1 A4:1 G4:1 F4:1 E4:3 R:1")
    hn1.line(s.b(5), melA, 84, leg=0.99, accent=6)
    hn2.line(s.b(5), melA, 78, tr=-12, leg=0.99)
    hn1.expr([(s.b(5), 96), (s.b(7), 112), (s.b(9), 100), (s.b(11), 116), (s.b(13) - 0.1, 104)])

    # ---- B: trombones/tuba take the oath, horns hold harmony, violins sing above
    melB = ("A3:2 F3:1.5 E3:0.5 C#3:4 D3:2 Bb2:1.5 A2:0.5 F2:2 A2:1 C3:1 "
            "D3:2 Bb2:1.5 A2:0.5 G2:4 E3:2 C#3:1.5 B2:0.5 A2:4")
    tbn.line(s.b(13), melB, 90, leg=0.98, accent=8)
    tbn2.line(s.b(13), melB, 82, tr=12, leg=0.98)
    tuba.line(s.b(13), melB, 84, tr=-12, leg=0.98)
    pads([(hn1, [1]), (hn2, [0])], s, list(range(13, 21)), prog, [(53, 64), (58, 69)], V(56, 84))
    swell(hn1, s, list(range(13, 21)), lo=80, hi=120)
    swell(hn2, s, list(range(13, 21)), lo=80, hi=120)
    vlnB = "D5:1 E5:1 F5:2 E5:3 C#5:1 D5:2 F5:2 C5:2 A4:2 Bb4:1 C5:1 D5:2 Eb5:2 D5:1 Bb4:1 C#5:2 E5:2 A5:4"
    vln.line(s.b(13), vlnB, 84, leg=1.0)
    vln.expr([(s.b(13), 92), (s.b(16), 112), (s.b(17), 96), (s.b(20), 124), (s.b(21) - 0.1, 100)])
    trem.chord(s.b(20), 4, ["A4", "C#5", "E5"], 70)
    trem.expr([(s.b(20), 60), (s.b(21) - 0.1, 124)])

    # ---- subito piano: one heavy downbeat "button" ends section B and opens the lament
    tbn.chord(s.b(21), 1.0, ["Bb2", "F3"], 104)
    tuba.n(s.b(21), 1.0, "Bb1", 104)
    hn2.chord(s.b(21), 1.0, ["D4"], 92)
    ost.n(s.b(21), 0.5, "Bb2", 104)
    cb.n(s.b(21), 0.5, "Bb1", 104)

    # ---- C: the lament (cello), harp, soft pizzicato rebuilding
    melC = "D4:3 C4:1 A3:4 Bb3:2 A3:1.5 G3:0.5 F3:4 G3:2 Bb3:2 F3:2 D3:2 E3:2 G3:1.5 F3:0.5 E3:4"
    cello.line(s.b(21), melC, 84, leg=1.02)
    cello.expr([(s.b(21), 84), (s.b(22) + 2, 112), (s.b(24), 90), (s.b(26), 118), (s.b(28), 96),
                (s.b(29) - 0.1, 110)])
    for bar in range(21, 29):
        pc = pcs(prog[bar - 1])
        r = in_range(pc[0], 43, 54)
        third = r + ((pc[1] - pc[0]) % 12)
        fifth = r + ((pc[2] - pc[0]) % 12)
        arp = [r, fifth, r + 12, third + 12, fifth + 12, r + 24, fifth + 12, third + 12]
        for i, q in enumerate(arp):
            harp.n(s.b(bar) + i * 0.5, 1.6, q, 52 + (8 if i == 0 else 0))
    pads([(hn1, [1]), (hn2, [0])], s, list(range(25, 29)), prog, [(53, 64), (58, 69)], V(46, 74))
    bells.n(s.b(21), 4, "D5", 52)
    bells.n(s.b(1), 4, "D5", 46)

    # ---- D: climax. low brass oath, horns double above, trumpets punctuate, violins soar
    melD = ("A3:2 F3:1.5 E3:0.5 C#3:4 A3:2 F3:1.5 E3:0.5 E3:2 C#3:2 Bb3:2 G3:1.5 F#3:0.5 D3:4 "
            "Bb3:2 G3:1.5 F3:0.5 E3:4 D4:2 Bb3:1.5 A3:0.5 G3:4 G3:2 Eb3:1.5 D3:0.5 C#3:4")
    tbn.line(s.b(29), melD, 104, leg=0.98, accent=10)
    tbn2.line(s.b(29), melD, 98, leg=0.98)
    tuba.line(s.b(29), melD, 98, tr=-12, leg=0.98)
    hn1.line(s.b(29), melD, 98, tr=12, leg=0.98, accent=8)
    hn2.line(s.b(29), melD, 94, tr=12, leg=0.98)
    for p_ in (tbn, tbn2, hn1, hn2, tuba):
        p_.expr([(s.b(29), 108), (s.b(33), 116), (s.b(37), 124), (s.b(41) - 0.1, 118)])
    vlnD = ("F5:2 A5:2 A5:2 G5:1 E5:1 F5:1 E5:1 D5:2 C#5:2 E5:2 D5:2 G5:2 F#5:2 A5:2 "
            "Bb5:2 A5:1 G5:1 A5:4 F5:2 D5:2 G5:2 Bb5:2 G5:2 Eb5:1 F5:1 E5:2 C#5:2")
    vln.line(s.b(29), vlnD, 98, leg=1.0)
    vln.expr([(s.b(29), 108), (s.b(36), 124), (s.b(37), 112), (s.b(40), 127), (s.b(41) - 0.1, 104)])
    tv = voice_lead([prog[b - 1] for b in range(29, 41)], [(69, 79), (74, 84)])
    for bar, vc in zip(range(29, 41), tv):
        trem.chord(s.b(bar), 4.05, vc, 62)
    trem.expr([(s.b(29), 80), (s.b(40), 112), (s.b(41) - 0.1, 80)])
    for bar in (29, 31, 33, 35, 37, 39):
        top = voice_lead([prog[bar - 1]], [(69, 77)])[0][0]
        tpt.n(s.b(bar), 0.9, top, 96)
        tpt.n(s.b(bar + 1, 4.5), 0.22, top, 84)
        tpt.n(s.b(bar + 1, 4.75), 0.22, top, 88)

    # ---- outro: the oath fades, then a half cadence on A leads back to the top
    melO = "A4:2 F4:1.5 E4:0.5 D4:4 Bb4:2 G4:1.5 F4:0.5 Eb4:4"
    hn1.line(s.b(41), melO, 70, leg=0.99)
    hn2.line(s.b(41), melO, 64, tr=-12, leg=0.99)
    hn1.expr([(s.b(41), 100), (s.b(45) - 0.1, 96)])
    tbn.line(s.b(45), "E3:2 C#3:2 A2:3.5", 84, leg=1.0)
    tuba.line(s.b(45), "A2:2 A2:2 A1:3.5", 80, leg=1.0)
    hn1.chord(s.b(45), 7.5, ["C#4", "E4"], 74)
    hn2.chord(s.b(45), 7.5, ["A3"], 74)
    tpt.chord(s.b(46), 3.5, ["E5"], 66)
    for p_ in (tbn, tuba, hn1, hn2, tpt):
        p_.expr([(s.b(45), 80), (s.b(46, 4.4), 124), (s.b(46, 4.5), 124)])

    # ---- percussion
    T = lambda bar: in_range(pcs(prog[bar - 1])[0], 33, 45)  # noqa: E731
    timp.n(s.b(1), 1.5, T(1), 104)
    for bar in (2, 3):
        timp.n(s.b(bar), 1.5, T(bar), 70)
    roll(timp, s, s.b(4), 4, T(4), 44, 98)
    for bar in range(5, 13):
        timp.n(s.b(bar), 1.5, T(bar), V(70, 96)(bar))
        timp.n(s.b(bar, 3), 1.0, T(bar) + 7 if T(bar) + 7 <= 45 else T(bar) - 5, V(58, 84)(bar))
    for bar in range(13, 20):
        timp.n(s.b(bar), 1.0, T(bar), V(80, 104)(bar))
        timp.n(s.b(bar, 3), 1.0, T(bar), V(66, 92)(bar))
        timp.n(s.b(bar, 4.5), 0.5, T(bar), V(60, 86)(bar))
    roll(timp, s, s.b(20), 4, T(20), 56, 112)
    for bar in (21, 23, 25, 27):
        timp.n(s.b(bar), 2, T(bar), 112 if bar == 21 else 56)
    roll(timp, s, s.b(28), 4, T(28), 40, 104)
    for bar in range(29, 41):
        timp.n(s.b(bar), 1.0, T(bar), 112)
        timp.n(s.b(bar, 2.5), 0.5, T(bar), 86)
        timp.n(s.b(bar, 3), 1.0, T(bar), 100)
        if bar in (32, 36):
            roll(timp, s, s.b(bar, 4), 1, T(bar), 70, 110)
        else:
            timp.n(s.b(bar, 4.5), 0.5, T(bar), 84)
    roll(timp, s, s.b(40, 3), 2, T(40), 70, 118)
    for bar in (41, 43):
        timp.n(s.b(bar), 2, T(bar), 70)
    roll(timp, s, s.b(45), 7.5, T(45), 40, 116)
    timp.n(s.b(46, 4.5), 1.0, T(46), 118)

    for bar in range(5, 13):
        taiko.n(s.b(bar), 1, "D2", V(64, 90)(bar))
        if bar % 2 == 0:
            taiko.n(s.b(bar, 4.5), 0.5, "D2", V(50, 74)(bar))
    for bar in range(13, 21):
        for bt, vv in ((1, 96), (2.5, 70), (3, 86), (4.5, 74)):
            taiko.n(s.b(bar, bt), 0.5, "D2", vv * (0.8 + 0.25 * inten[bar - 1]))
    for bar in range(29, 41):
        for bt, vv in ((1, 112), (1.75, 70), (2.5, 84), (3, 104), (4, 80), (4.5, 92)):
            taiko.n(s.b(bar, bt), 0.5, "D2", vv)
    for bar in range(45, 47):
        for i in range(8):
            taiko.n(s.b(bar) + i * 0.5, 0.5, "D2", 56 + 6 * i + (16 if bar == 46 else 0))

    for bar in (1, 13, 29, 33, 37):
        kit.n(s.b(bar), 2, 57, 104 if bar != 1 else 84)
        kit.n(s.b(bar), 1, 36, 112)
    for bar in list(range(5, 13)) + list(range(41, 45)):
        kit.n(s.b(bar), 1, 36, 70)
    for bar in range(13, 21):
        kit.n(s.b(bar), 1, 36, 88)
    for bar in (21, 23, 25, 27):
        kit.n(s.b(bar), 1, 36, 108 if bar == 21 else 64)
    for bar in range(29, 41):
        kit.n(s.b(bar), 1, 36, 100)
        kit.n(s.b(bar, 3), 1, 36, 84)
    roll(kit, s, s.b(20, 3), 2, 38, 40, 96, rate=0.125)
    roll(kit, s, s.b(28, 3), 2, 38, 36, 92, rate=0.125)
    roll(kit, s, s.b(40), 4, 38, 46, 112, rate=0.125)
    roll(kit, s, s.b(46), 4, 38, 40, 104, rate=0.125)
    kit.n(s.b(46, 4.5), 1, 36, 110)
    return s


# per-stem (gain dB, reverb send). Balanced from measured active RMS: GM brass is hot, so it sits lower.
PHASE1_MIX = {"low": (3.0, 0.18), "high": (2.0, 0.32), "brass": (-2.0, 0.26), "choir": (3.0, 0.4),
              "perc": (1.0, 0.16), "color": (8.0, 0.36)}


# ================================================================== phase two
SCALE_D_MINOR = ["D", "E", "F", "G", "A", "Bb", "C"]


def scale_for(ch):
    """D natural minor, inflected to fit the chord (harmonic/major colours)"""
    sc = list(SCALE_D_MINOR)
    alter = {"A": {"C": "C#"}, "A7": {"C": "C#"}, "D": {"F": "F#", "C": "C#"}, "Eb": {"E": "Eb"},
             "Ebm": {"E": "Eb", "G": "Gb", "D": "Db"}, "Bb": {"E": "Eb"}, "Gm6": {}, "Bbm": {"E": "Eb", "D": "Db"}}
    for k, v in alter.get(ch, {}).items():
        sc[sc.index(k)] = v
    return sorted(set(P(n + "0") % 12 for n in sc))


def compose_phase2():
    s = Song("fallen_warden_2", bpm=120, bars=48, tail=1.2)
    H = {}  # half-bar chords: H[bar] = (first half, second half)
    for b in range(1, 4):
        H[b] = ("Dm", "Dm")
    H[4] = ("A", "A")
    for b, pair in zip(range(5, 13), [("Dm", "A"), ("Gm", "A"), ("Dm", "A"), ("Bb", "A"), ("Dm", "A"),
                                      ("Gm", "A"), ("Eb", "Bb"), ("Gm", "A")]):
        H[b] = pair
    for b, c in zip(range(13, 21), ["Dm", "A", "Bb", "F", "Gm", "D", "Eb", "A"]):
        H[b] = (c, c)
    for b, c in zip(range(21, 29), ["Dm", "Eb", "Dm", "Eb", "Dm", "Eb", "A", "A"]):
        H[b] = (c, c)
    for b in range(29, 33):
        H[b] = ("Dm", "A")
    for b in range(33, 36):
        H[b] = ("Ebm", "Bb")
    H[36] = ("Bb", "A")
    for b, c in zip(range(37, 45), ["Dm", "Bb", "Gm6", "A", "Bb", "Gm", "Eb", "A"]):
        H[b] = (c, c)
    for b, c in zip(range(45, 49), ["Dm", "Bb", "Gm6", "A"]):
        H[b] = (c, c)
    halves = [H[b][h] for b in range(1, 49) for h in (0, 1)]

    def ch_at(beat):
        return halves[min(len(halves) - 1, int(beat // 2))]
    s.chord_at = ch_at

    spic = s.part("spic", 48, "low", pan=74, volume=112, hum=0.004, vjit=4)
    cb = s.part("cb", 43, "low", pan=88, volume=110, hum=0.005, vjit=4)
    vln = s.part("vln", 48, "high", pan=34, volume=100, hum=0.004, vjit=5)
    trem = s.part("trem", 44, "high", pan=44, volume=96, hum=0.008)
    pad = s.part("pad", 49, "high", pan=62, volume=92, hum=0.015)
    tpt = s.part("tpt", 56, "brass", pan=58, volume=96, lag=0.006)
    hn1 = s.part("hn1", 60, "brass", pan=40, volume=112, lag=0.01)
    hn2 = s.part("hn2", 60, "brass", pan=50, volume=108, lag=0.01)
    tbn = s.part("tbn", 57, "brass", pan=80, volume=110, lag=0.008)
    tbn2 = s.part("tbn2", 57, "brass", pan=70, volume=104, lag=0.008)
    tuba = s.part("tuba", 58, "brass", pan=88, volume=106, lag=0.008)
    bsec = s.part("bsec", 61, "brass", pan=64, volume=92, lag=0.006)
    ahh = s.part("ahh", 52, "choir", pan=56, volume=104, hum=0.012)
    ohh = s.part("ohh", 53, "choir", pan=74, volume=100, hum=0.015)
    timp = s.part("timp", 47, "perc", pan=58, volume=114, hum=0.004, vjit=3)
    taiko = s.part("taiko", 116, "perc", pan=66, volume=106, hum=0.004, vjit=4)
    kit = s.part("kit", 48, "perc", drum=True, pan=64, volume=108, hum=0.003, vjit=4)

    inten = np.interp(np.arange(1, 49), [1, 4, 5, 12, 13, 20, 21, 24, 25, 28, 29, 36, 37, 44, 45, 48],
                      [.70, .82, .86, .92, .9, .97, .6, .66, .72, .9, .92, .97, .97, 1.0, .82, .95])

    # ---- spiccato 16ths (celli) and driving 8ths (basses), silent in the first half of the breakdown
    pat16 = [0, 0, 12, 0, 0, 12, 0, 0, 12, 0, 0, 12, 0, 7, 0, 12]
    acc16 = {0, 3, 6, 9, 12, 14}
    for bar in list(range(1, 21)) + list(range(25, 49)):
        for i, iv in enumerate(pat16):
            beat = s.b(bar) + i * 0.25
            r = in_range(pcs(ch_at(beat))[0], 43, 54)
            v = 52 + 34 * inten[bar - 1] + (18 if i in acc16 else 0)
            spic.n(beat, 0.2, r + iv, v)
        for i in range(8):
            beat = s.b(bar) + i * 0.5
            r = in_range(pcs(ch_at(beat))[0], 31, 42)
            cb.n(beat, 0.4, r, 60 + 34 * inten[bar - 1] + (16 if i in (0, 4) else 0))
    for bar in range(21, 25):
        cb.n(s.b(bar), 3.8, in_range(pcs(H[bar][0])[0], 31, 42), 70)
    spic.expr([(s.b(25), 70), (s.b(29) - 0.1, 124)])
    spic.expr([(s.b(29), 112), (s.b(45), 112), (s.b(48, 4.5), 124)])

    # ---- tremolo strings, voice-led on the half-bar harmony
    def trem_block(bars, rngs, vel):
        hs = [(bar, h) for bar in bars for h in (0, 1)]
        vcs = voice_lead([H[b][h] for b, h in hs], rngs)
        for (bar, h), vc in zip(hs, vcs):
            for q in vc:
                trem.n(s.b(bar) + 2 * h, 2.02, q, vel(bar) if callable(vel) else vel)
    trem_block(range(1, 13), [(62, 72), (67, 77), (72, 82)], lambda b: 50 + 40 * inten[b - 1])
    trem.expr([(0, 70), (s.b(4, 4.9), 124), (s.b(5), 96), (s.b(13) - 0.1, 104)])
    trem_block(range(21, 29), [(62, 72), (69, 79)], 70)
    trem.expr([(s.b(21), 60), (s.b(29) - 0.1, 124)])
    trem_block(range(29, 49), [(65, 74), (69, 79), (74, 84)], 72)
    trem.expr([(s.b(29), 92), (s.b(45), 100), (s.b(49) - 0.1, 120)])

    # ---- string pad underneath everything (half-bar voice leading)
    hs_all = [(bar, h) for bar in range(1, 49) for h in (0, 1)]
    vcs = voice_lead([H[b][h] for b, h in hs_all], [(50, 60), (55, 65), (60, 70)])
    for (bar, h), vc in zip(hs_all, vcs):
        for q in vc:
            pad.n(s.b(bar) + 2 * h, 2.04, q, 56 + 26 * inten[bar - 1])

    # ---- A (5-12): the oath in diminution, trumpets + horns
    lo = ("A4:1 F4:0.75 E4:0.25 C#4:2 Bb4:1 G4:0.75 F4:0.25 E4:2 "
          "A4:1 F4:0.75 E4:0.25 C#4:1 E4:1 D5:1 Bb4:0.75 G4:0.25 A4:2")
    hi = ("A5:1 F5:0.75 E5:0.25 C#5:2 Bb5:1 G5:0.75 F5:0.25 E5:2 "
          "Eb5:1 G5:0.75 Eb5:0.25 D5:1 F5:1 G5:0.5 F5:0.5 E5:0.5 D5:0.5 C#5:2")
    tpt.line(s.b(5), lo, 92, leg=0.95, accent=8)
    tpt.line(s.b(9), hi, 100, leg=0.95, accent=8)
    hn1.line(s.b(5), lo, 96, leg=0.97, accent=8)
    hn1.line(s.b(9), hi, 100, tr=-12, leg=0.97, accent=8)
    hn2.line(s.b(5), lo, 92, tr=-12, leg=0.97)
    hn2.line(s.b(9), hi, 96, tr=-12, leg=0.97)
    hsA = [(bar, h) for bar in range(5, 13) for h in (0, 1)]
    vA = voice_lead([H[b][h] for b, h in hsA], [(45, 55), (50, 60)])
    for (bar, h), vc in zip(hsA, vA):
        for q, p_ in zip(vc, (tbn, tbn2)):
            p_.n(s.b(bar) + 2 * h, 1.9, q, 88)
        tuba.n(s.b(bar) + 2 * h, 1.9, in_range(pcs(H[bar][h])[0], 33, 44), 92)

    # ---- choir: stabs in the intro, chords in A, the oath sung in B
    for bar in (2, 4):
        ch = H[bar][0]
        vc = voice_lead([ch], [(50, 60), (57, 67), (62, 72), (67, 77)])[0]
        ahh.chord(s.b(bar), 1.0, vc, 112)
    hsC = [(bar, h) for bar in range(5, 13) for h in (0, 1)]
    vC = voice_lead([H[b][h] for b, h in hsC], [(50, 60), (55, 65), (60, 70), (64, 74)])
    for (bar, h), vc in zip(hsC, vC):
        ahh.chord(s.b(bar) + 2 * h, 2.02, vc, 84)
    melB = "A4:2 F4:2 E4:2 C#4:2 D5:2 Bb4:2 A4:2 F4:2 G4:2 Bb4:2 A4:2 F#4:2 G4:2 Eb4:2 E4:4"
    ahh.line(s.b(13), melB, 108, leg=1.02)
    tbn.line(s.b(13), melB, 104, tr=-12, leg=0.98, accent=6)
    tbn2.line(s.b(13), melB, 100, tr=-12, leg=0.98)
    tuba.line(s.b(13), melB, 100, tr=-24, leg=0.98)
    hn1.line(s.b(13), melB, 98, leg=0.98)
    vB = voice_lead([H[b][0] for b in range(13, 21)], [(48, 57), (52, 61), (55, 64)])
    for bar, vc in zip(range(13, 21), vB):
        ohh.chord(s.b(bar), 4.04, vc, 92)
        hn2.chord(s.b(bar), 3.9, vc[1:2], 90)
    for p_ in (ahh, ohh):
        p_.expr([(s.b(13), 100), (s.b(16), 124), (s.b(17), 108), (s.b(20), 127), (s.b(21) - 0.1, 110)])

    # ---- violin 16th runs (B, D2, D3)
    def runs(bars, lo_=62, hi_=86, vel=78):
        cur = 74
        for bar in bars:
            for beat_i in range(4):
                beat = s.b(bar) + beat_i
                sc = scale_for(ch_at(beat))
                chord_pc = pcs(ch_at(beat))
                up = (beat_i % 2 == 0)
                # start on a chord tone near the current position
                cands = [m for m in range(lo_, hi_ + 1) if m % 12 in chord_pc]
                start = min(cands, key=lambda m: abs(m - (cur - (3 if up else -3))))
                seq = [start]
                m = start
                for _ in range(3):
                    m = m + 1 if up else m - 1
                    while m % 12 not in sc:
                        m = m + 1 if up else m - 1
                    seq.append(m)
                for k, q in enumerate(seq):
                    vln.n(beat + k * 0.25, 0.22, q, vel + (12 if k == 0 else 0))
                cur = seq[-1]
    runs(range(13, 21))
    runs(range(37, 45), vel=84)
    vln.expr([(s.b(13), 100), (s.b(20), 124), (s.b(21) - 0.1, 100)])
    vln.expr([(s.b(37), 108), (s.b(44, 4.9), 127)])

    # ---- C (21-28): breakdown. low brass staccato ostinato carrying the oath's notes
    for bar in range(21, 29):
        ch = H[bar][0]
        pc = pcs(ch)
        r = in_range(pc[0], 45, 56)
        third = (pc[1] - pc[0]) % 12
        figure = [0, 0, third, 0, 2, 0, -1, 0]
        for i, iv in enumerate(figure):
            v = 70 + 30 * (bar - 21) / 7 + (12 if i in (0, 2, 6) else 0)
            tbn.n(s.b(bar) + i * 0.5, 0.3, r + iv, v)
            tbn2.n(s.b(bar) + i * 0.5, 0.3, r + iv - 12, v - 6)
        tuba.n(s.b(bar), 0.5, r - 12, 96)
        tuba.n(s.b(bar, 3), 0.5, r - 12, 86)
    for bar in range(21, 29):
        mid = "F4" if H[bar][0] == "Dm" else ("G4" if H[bar][0] == "Eb" else "E4")
        low_ = {"Dm": "D4", "Eb": "Eb4", "A": "C#4"}[H[bar][0]]
        top_ = "A4" if H[bar][0] != "Eb" else "Bb4"
        ohh.chord(s.b(bar), 4.04, [low_, mid, top_], 74 + 3 * (bar - 21))
    ohh.expr([(s.b(21), 80), (s.b(29) - 0.1, 124)])

    # ---- D1 (29-36): canon. trumpets/horns lead, trombones answer a bar later
    canon = "A4:1 F4:0.75 E4:0.25 C#4:2 " * 4
    tpt.line(s.b(29), canon, 104, leg=0.95, accent=8)
    hn1.line(s.b(29), canon, 104, leg=0.97, accent=8)
    tbn.n(s.b(29), 3.9, "D3", 100)
    tbn2.n(s.b(29), 3.9, "D3", 94)
    tbn.line(s.b(30), "A3:1 F3:0.75 E3:0.25 C#3:2 " * 3, 106, leg=0.95, accent=8)
    tbn2.line(s.b(30), "A3:1 F3:0.75 E3:0.25 C#3:2 " * 3, 100, leg=0.95)
    eb = "Bb4:1 Gb4:0.75 F4:0.25 D4:2 " * 3 + "Bb4:1 A4:1 C#5:2"
    tpt.line(s.b(33), eb, 108, leg=0.95, accent=8)
    hn1.line(s.b(33), eb, 106, leg=0.97, accent=8)
    hn2.line(s.b(33), eb, 100, tr=-12, leg=0.97)
    tbn.line(s.b(33), "Eb3:2 D3:1.9", 104)
    tbn2.line(s.b(33), "Eb3:2 D3:1.9", 98)
    tbn.line(s.b(34), "Bb3:1 Gb3:0.75 F3:0.25 D3:2 " * 2 + "Bb3:2 A3:2", 108, leg=0.95, accent=8)
    tbn2.line(s.b(34), "Bb3:1 Gb3:0.75 F3:0.25 D3:2 " * 2 + "Bb3:2 A3:2", 102, leg=0.95)
    hn2.line(s.b(29), canon, 98, tr=-12, leg=0.97)
    for bar in range(29, 37):
        for h in (0, 1):
            tuba.n(s.b(bar) + 2 * h, 1.8, in_range(pcs(H[bar][h])[0], 33, 44), 100)
    hsD = [(bar, h) for bar in range(29, 37) for h in (0, 1)]
    vD = voice_lead([H[b][h] for b, h in hsD], [(50, 60), (55, 65), (60, 70), (64, 74)])
    for (bar, h), vc in zip(hsD, vD):
        ahh.chord(s.b(bar) + 2 * h, 2.02, vc, 100)

    # ---- D2 (37-40): the oath augmented in the low brass, brass-section fanfare above
    tbn.line(s.b(37), "A3:4 F3:4 E3:4 C#3:4", 112, leg=0.99)
    tbn2.line(s.b(37), "A3:4 F3:4 E3:4 C#3:4", 106, tr=-12, leg=0.99)
    tuba.line(s.b(37), "A2:4 F2:4 E2:4 C#2:4", 106, leg=0.99)
    vF = voice_lead([H[b][0] for b in range(37, 41)], [(62, 70), (65, 74), (69, 79)])
    for bar, vc in zip(range(37, 41), vF):
        for bt, vv, d in ((1, 112, 0.9), (2.5, 96, 0.4), (3, 106, 0.9), (4, 92, 0.4), (4.5, 100, 0.45)):
            bsec.chord(s.b(bar, bt), d, vc, vv)
            tpt.n(s.b(bar, bt), d, vc[-1], vv - 6)
        ahh.chord(s.b(bar), 4.04, [q - 12 for q in vc], 104)
        hn1.chord(s.b(bar), 3.9, vc[:2], 98)
        hn2.chord(s.b(bar), 3.9, [vc[0] - 12], 96)

    # ---- D3 (41-44): homophonic peak, the oath's fall in the top line
    top = "D5:2 Bb4:1.5 A4:0.5 G4:4 G4:2 Eb4:1.5 D4:0.5 C#4:4"
    tpt.line(s.b(41), top, 112, leg=0.97, accent=6)
    hn1.line(s.b(41), top, 110, leg=0.98)
    hn2.line(s.b(41), top, 104, tr=-12, leg=0.98)
    ahh.line(s.b(41), top, 112, leg=1.02)
    vG = voice_lead([H[b][0] for b in range(41, 45)], [(48, 57), (52, 61), (55, 64)])
    for bar, vc in zip(range(41, 45), vG):
        ohh.chord(s.b(bar), 4.04, vc, 100)
        tbn.chord(s.b(bar), 3.9, vc[:1], 108)
        tbn2.chord(s.b(bar), 3.9, [vc[1] - 12], 104)
        tuba.n(s.b(bar), 3.9, in_range(pcs(H[bar][0])[0], 33, 44), 106)
    for p_ in (ahh, ohh, tpt, hn1, hn2, tbn, tbn2):
        p_.expr([(s.b(41), 112), (s.b(44, 4.9), 127), (s.b(45), 104)])

    # ---- outro (45-48): turnaround to the loop
    tpt.line(s.b(45), "A4:1 F4:0.75 E4:0.25 D4:2 D5:1 Bb4:0.75 A4:0.25 F4:2 G4:1 E4:0.75 D4:0.25 Bb3:2", 96,
             leg=0.95)
    hn1.line(s.b(45), "A4:1 F4:0.75 E4:0.25 D4:2 D5:1 Bb4:0.75 A4:0.25 F4:2 G4:1 E4:0.75 D4:0.25 Bb3:2", 98,
             leg=0.97)
    vO = voice_lead([H[b][0] for b in range(45, 48)], [(45, 55), (50, 60)])
    for bar, vc in zip(range(45, 48), vO):
        tbn.n(s.b(bar), 3.9, vc[0], 96)
        tbn2.n(s.b(bar), 3.9, vc[1], 92)
        tuba.n(s.b(bar), 3.9, in_range(pcs(H[bar][0])[0], 33, 44), 96)
        ahh.chord(s.b(bar), 4.04, voice_lead([H[bar][0]], [(55, 64), (60, 69), (64, 74)])[0], 92)
    stab = ["A3", "C#4", "E4", "A4"]
    for bt, vv in ((1, 112), (2.5, 104), (3, 108), (4, 116)):
        d = 0.45 if bt != 4 else 0.5
        bsec.chord(s.b(48, bt), d, stab, vv)
        tbn.chord(s.b(48, bt), d, ["A2", "E3"], vv)
        tuba.n(s.b(48, bt), d, "A1", vv)
        ahh.chord(s.b(48, bt), d, ["E4", "A4", "C#5"], vv)
        tpt.n(s.b(48, bt), d, "E5", vv - 8)

    # ---- percussion
    T = lambda beat: in_range(pcs(ch_at(beat))[0], 33, 45)  # noqa: E731
    for bar in range(1, 49):
        b0 = s.b(bar)
        sect = ("intro" if bar <= 4 else "A" if bar <= 12 else "B" if bar <= 20 else "C" if bar <= 28
                else "D" if bar <= 44 else "O")
        k = inten[bar - 1]
        # taiko: driving 8ths with accents
        if sect != "C" or bar >= 25:
            for i in range(8):
                acc = i in (0, 3, 6) if sect in ("A", "D", "O") else i in (0, 4)
                taiko.n(b0 + i * 0.5, 0.5, "D2", 60 + 30 * k + (22 if acc else 0))
        else:
            for bt, vv in ((1, 112), (1.75, 80), (2.5, 96), (3.5, 88), (4, 104), (4.75, 84)):
                taiko.n(s.b(bar, bt), 0.5, "D2", vv)
                if bt in (1, 4):
                    kit.n(s.b(bar, bt), 1, 36, vv)
        # timpani on the harmony
        if sect in ("A", "D", "B", "intro", "O"):
            for bt in (1, 3):
                timp.n(s.b(bar, bt), 0.9, T(s.b(bar, bt)), 96 + 20 * k)
            timp.n(s.b(bar, 2.5), 0.4, T(s.b(bar, 2.5)), 80)
            timp.n(s.b(bar, 4.5), 0.4, T(s.b(bar, 4.5)), 84)
        else:
            timp.n(b0, 1.5, T(b0), 96)
        # bass drum on 1 and 3
        if sect != "C" or bar >= 25:
            kit.n(b0, 1, 36, 96 + 20 * k)
            kit.n(s.b(bar, 3), 1, 36, 84 + 20 * k)
    for bar in (1, 5, 9, 13, 17, 25, 29, 33, 37, 41, 45):
        kit.n(s.b(bar), 2, 57, 112)
    roll(timp, s, s.b(4, 3), 2, T(s.b(4, 3)), 70, 120)
    roll(timp, s, s.b(12, 3), 2, T(s.b(12, 3)), 70, 120)
    roll(timp, s, s.b(20, 3), 2, T(s.b(20, 3)), 70, 120)
    roll(timp, s, s.b(27), 8, T(s.b(27)), 50, 124)
    roll(timp, s, s.b(36, 3), 2, T(s.b(36, 3)), 74, 124)
    roll(timp, s, s.b(44, 3), 2, T(s.b(44, 3)), 74, 124)
    roll(kit, s, s.b(27), 8, 38, 40, 118)
    roll(kit, s, s.b(44, 3), 2, 38, 70, 118)
    roll(kit, s, s.b(48), 3.9, 38, 50, 120)
    roll(timp, s, s.b(48), 3.9, P("A1"), 60, 124)
    timp.n(s.b(48, 4), 0.6, "A1", 124)
    kit.n(s.b(48, 4), 1, 36, 124)
    return s


PHASE2_MIX = {"low": (4.5, 0.14), "high": (3.5, 0.28), "brass": (-2.0, 0.22), "choir": (1.0, 0.34),
              "perc": (-1.5, 0.12)}


# ================================================================== stingers
def compose_boss_appear():
    s = Song("boss_appear", bpm=100, bars=1, bpb=5, tail=0.0)
    timp = s.part("timp", 47, "perc", pan=58, volume=120, hum=0.0)
    taiko = s.part("taiko", 116, "perc", pan=68, volume=110, hum=0.0)
    kit = s.part("kit", 48, "perc", drum=True, pan=64, volume=112, hum=0.0)
    tbn = s.part("tbn", 57, "brass", pan=80, volume=112, hum=0.004)
    tbn2 = s.part("tbn2", 57, "brass", pan=70, volume=108, hum=0.004)
    tuba = s.part("tuba", 58, "brass", pan=86, volume=110, hum=0.004)
    hn = s.part("hn", 60, "brass", pan=44, volume=112, hum=0.006)
    hn2 = s.part("hn2", 60, "brass", pan=52, volume=108, hum=0.006)
    ahh = s.part("ahh", 52, "choir", pan=60, volume=110, hum=0.01)
    ohh = s.part("ohh", 53, "choir", pan=70, volume=100, hum=0.01)
    trem = s.part("trem", 44, "low", pan=76, volume=104, hum=0.004)
    cb = s.part("cb", 43, "low", pan=88, volume=110, hum=0.0)
    # the hit
    timp.n(0, 1.5, "D2", 124)
    taiko.n(0, 1.5, "D2", 124)
    taiko.n(0.02, 1.5, "A1", 110)
    kit.n(0, 2, 36, 124)
    kit.n(0, 3, 57, 100)
    for p_, notes, v in ((tbn, ["D3", "A3"], 120), (tbn2, ["F3"], 116), (tuba, ["D2"], 120),
                         (hn, ["D4", "F4"], 112), (hn2, ["A3"], 108)):
        p_.chord(0, 1.6, notes, v)
        p_.expr([(0, 127), (1.6, 90)])
    cb.n(0, 2.8, "D2", 112)
    cb.n(2.8, 1.8, "A1", 116)
    trem.chord(0, 2.8, ["D2", "D3"], 100)
    trem.chord(2.8, 1.8, ["A1", "A2"], 104)
    trem.expr([(0, 96), (3.4, 127), (4.6, 110)])
    # choir swell: D minor, then onto the dominant with the brass
    ahh.chord(0.0, 2.8, ["D4", "F4", "A4", "D5"], 100)
    ahh.chord(2.8, 1.8, ["C#4", "E4", "A4", "E5"], 108)
    ohh.chord(0.0, 2.8, ["A3", "D4"], 96)
    ohh.chord(2.8, 1.8, ["A3", "C#4"], 100)
    for p_ in (ahh, ohh):
        p_.expr([(0, 40), (3.4, 127), (4.6, 118)])
    # the oath, fast, in the horns, landing on the dominant
    hn.line(1.6, "A4:0.6 F4:0.45 E4:0.15 C#4:2.4", 116, leg=1.0)
    hn2.line(1.6, "A3:0.6 F3:0.45 E3:0.15 C#3:2.4", 110, leg=1.0)
    tbn.chord(2.8, 1.8, ["A2", "E3"], 112)
    tbn2.chord(2.8, 1.8, ["C#3"], 108)
    tuba.n(2.8, 1.8, "A1", 116)
    for p_ in (hn, hn2, tbn, tbn2, tuba):
        p_.expr([(1.6, 104), (2.8, 112), (4.5, 127)])
    timp.n(2.8, 1.0, "A1", 116)
    taiko.n(2.8, 1.0, "D2", 118)
    roll(timp, s, 3.8, 0.8, P("A1"), 70, 110)
    return s


STINGER_MIX = {"perc": (0.0, 0.18), "brass": (0.0, 0.26), "choir": (-2.0, 0.4), "low": (-2.0, 0.2),
               "color": (-3.0, 0.38), "high": (-1.0, 0.32)}


def compose_foe_vanquished():
    s = Song("foe_vanquished", bpm=90, bars=1, bpb=7, tail=0.0)
    pad = s.part("pad", 49, "high", pan=50, volume=100, hum=0.012)
    pad2 = s.part("pad2", 48, "high", pan=78, volume=96, hum=0.012)
    vln = s.part("vln", 48, "high", pan=34, volume=98, hum=0.01)
    cb = s.part("cb", 43, "low", pan=86, volume=100)
    ahh = s.part("ahh", 52, "choir", pan=58, volume=104, hum=0.015)
    ohh = s.part("ohh", 53, "choir", pan=72, volume=96, hum=0.015)
    hn = s.part("hn", 60, "brass", pan=44, volume=108, hum=0.008)
    hn2 = s.part("hn2", 60, "brass", pan=52, volume=104, hum=0.008)
    tpt = s.part("tpt", 56, "brass", pan=60, volume=84, hum=0.006)
    tbn = s.part("tbn", 57, "brass", pan=78, volume=100, hum=0.006)
    harp = s.part("harp", 46, "color", pan=30, volume=100, hum=0.0)
    bells = s.part("bells", 14, "color", pan=60, volume=112, hum=0.0)
    cbell = s.part("cbell", 14, "color", bank=8, pan=66, volume=100, hum=0.0)
    timp = s.part("timp", 47, "perc", pan=58, volume=112, hum=0.0)
    kit = s.part("kit", 48, "perc", drum=True, pan=64, volume=100, hum=0.0)
    # bVI - bVII - I: Bb -> C -> D major (the oath released)
    for beat, dur, ch in ((0, 1.55, ["Bb2", "F3", "D4", "F4", "Bb4"]), (1.5, 1.55, ["C3", "G3", "E4", "G4", "C5"]),
                          (3.0, 4.0, ["D3", "A3", "F#4", "A4", "D5"])):
        pad.chord(beat, dur, ch[1:4], 78 if beat < 3 else 88)
        pad2.chord(beat, dur, ch[2:], 72 if beat < 3 else 84)
        ahh.chord(beat, dur, ch[2:], 80 if beat < 3 else 96)
        ohh.chord(beat, dur, ch[1:3], 76 if beat < 3 else 90)
        cb.n(beat, dur, ch[0], 84 if beat < 3 else 96)
        tbn.chord(beat, dur, ch[0:2], 70 if beat < 3 else 92)
    for p_ in (pad, pad2, ahh, ohh, cb, tbn):
        p_.expr([(0, 56), (2.9, 116), (3.0, 124), (4.6, 118), (7.0, 70)])
    melody = "F4:1.5 G4:1.5 A4:1 F#4:0.5 E4:0.5 D4:3"
    hn.line(0, melody, 92, leg=1.01)
    hn2.line(0, melody, 86, tr=-12, leg=1.01)
    tpt.line(3.0, "A4:1 F#4:0.5 E4:0.5 D4:3", 76, leg=1.01)
    vln.line(3.0, "D6:1 C#6:0.5 A5:0.5 D6:3", 70, leg=1.01)
    for p_ in (hn, hn2, tpt, vln):
        p_.expr([(0, 70), (2.9, 116), (3.0, 122), (4.6, 116), (7.0, 72)])
    # harp sweep into the arrival
    gl = ["D4", "E4", "F#4", "G4", "A4", "B4", "C#5", "D5", "E5", "F#5", "G5", "A5", "B5", "C#6", "D6"]
    for i, q in enumerate(gl):
        harp.n(2.25 + i * 0.05, 2.0, q, 60 + i * 3)
    for q in ("D3", "A3", "D4", "F#4", "A4"):
        harp.n(3.0, 3.0, q, 92)
    bells.n(3.0, 4, "D5", 104)
    bells.n(3.0, 4, "A4", 70)
    cbell.n(3.0, 4, "D4", 84)
    timp.n(3.0, 2.0, "D2", 112)
    roll(timp, s, 1.5, 1.5, P("A1"), 40, 96)
    kit.n(3.0, 3.0, 57, 70)
    kit.n(3.0, 1.0, 36, 100)
    return s


# ================================================================== field bosses
WHITE = [0, 2, 4, 5, 7, 9, 11]


def diatonic(root, steps):
    """the note [steps] scale steps from [root] along the white notes (E Phrygian here); root is a white note"""
    octave, pc = divmod(root, 12)
    o, k = divmod(WHITE.index(pc) + steps, 7)
    return (octave + o) * 12 + WHITE[k]


def compose_field_boss():
    """The wild's own bosses (the hill trolls first): no leitmotif, only weight. E Phrygian, whose flat
    second (F over E) gives the stomp its menace; taiko on every first and third beat are the footfalls,
    a low brass riff lumbers over a string ostinato, the horns sing a short dark tune, the choir shouts
    the break, and the outro rests on F so the loop falls back into E (the Phrygian cadence)."""
    s = Song("field_boss", bpm=104, bars=36, tail=1.4)
    prog = (["Em", "Em", "Em", "F"]
            + ["Em", "F", "Em", "Dm", "Em", "F", "G", "F"]
            + ["Am", "Em", "F", "Dm", "Am", "G", "Am", "F"]
            + ["F", "F", "Em", "Em"]
            + ["Em", "F", "Em", "Dm", "Em", "F", "G", "F"]
            + ["Em", "Dm", "F", "F"])
    assert len(prog) == 36
    s.chord_at = lambda beat: prog[min(len(prog) - 1, int(beat // 4))]
    A = list(range(5, 13))
    B = list(range(13, 21))
    BREAK = list(range(21, 25))
    A2 = list(range(25, 33))
    OUT = list(range(33, 37))

    ost = s.part("ost", 48, "low", pan=74, volume=112, hum=0.006, vjit=4)
    cb = s.part("cb", 43, "low", pan=86, volume=110, hum=0.006, vjit=4)
    bsn = s.part("bsn", 70, "low", pan=58, volume=92, hum=0.01)
    pad1 = s.part("pad1", 49, "high", pan=46, volume=94, hum=0.02)
    pad2 = s.part("pad2", 49, "high", pan=82, volume=94, hum=0.02)
    trem = s.part("trem", 44, "high", pan=40, volume=88, hum=0.01)
    hn1 = s.part("hn1", 60, "brass", pan=40, volume=110, lag=0.012)
    hn2 = s.part("hn2", 60, "brass", pan=54, volume=104, lag=0.014)
    tbn = s.part("tbn", 57, "brass", pan=80, volume=110, lag=0.01)
    tbn2 = s.part("tbn2", 57, "brass", pan=70, volume=102, lag=0.012)
    tuba = s.part("tuba", 58, "brass", pan=88, volume=108, lag=0.01)
    tpt = s.part("tpt", 56, "brass", pan=62, volume=88, lag=0.008)
    ahh = s.part("ahh", 52, "choir", pan=56, volume=104, hum=0.02)
    ohh = s.part("ohh", 53, "choir", pan=72, volume=96, hum=0.02)
    timp = s.part("timp", 47, "perc", pan=58, volume=114, hum=0.005, vjit=3)
    taiko = s.part("taiko", 116, "perc", pan=64, volume=112, hum=0.005, vjit=4)
    taiko2 = s.part("taiko2", 116, "perc", pan=38, volume=96, hum=0.006, vjit=5)
    kit = s.part("kit", 48, "perc", drum=True, pan=64, volume=106, hum=0.004, vjit=4)

    inten = np.interp(np.arange(1, 37), [1, 4, 5, 12, 13, 20, 21, 24, 25, 32, 33, 36],
                      [.55, .66, .72, .82, .72, .86, .74, .94, .95, 1.0, .70, .82])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[bar - 1]

    def root(bar, lo, hi):
        return in_range(pcs(prog[bar - 1])[0], lo, hi)

    # ---- low strings: an eighth-note ostinato in scale steps from each chord's root, leaning on 1 and 3
    steps = [0, 0, 1, 0, 0, 0, 2, 1]
    for bar in list(range(3, 21)) + A2 + OUT[:2]:
        r = root(bar, 38, 49)
        for i, st in enumerate(steps):
            ost.n(s.b(bar) + i * 0.5, 0.42, diatonic(r, st), V(52, 92)(bar) + (16 if i in (0, 4) else 0))
    for bar in BREAK:  # the break saws on the root alone, rising
        r = root(bar, 38, 49)
        for i in range(8):
            ost.n(s.b(bar) + i * 0.5, 0.4, r, 70 + 4 * i + (bar - 21) * 6)
    ost.expr([(0, 100), (s.b(21), 96), (s.b(25) - 0.1, 126), (s.b(25), 112), (s.b(37) - 0.1, 112)])

    # ---- contrabasses: the footfalls' roots, with a step up into the next
    for bar in range(1, 37):
        r = root(bar, 28, 39)
        cb.n(s.b(bar), 1.6, r, V(66, 104)(bar))
        cb.n(s.b(bar, 3), 1.2, r, V(58, 96)(bar))
        if bar >= 5:
            cb.n(s.b(bar, 4.5), 0.45, diatonic(r, 1), V(50, 84)(bar))

    # ---- the riff: low brass lumbering, a bassoon growling it an octave up
    riff_a = [(0, 0.0, 0.7, 1), (0, 0.75, 0.2, 0), (1, 1.0, 0.45, 0), (0, 1.5, 0.45, 0), (2, 2.0, 0.9, 1), (1, 3.0, 0.9, 0)]
    riff_b = [(0, 0.0, 0.7, 1), (0, 0.75, 0.2, 0), (1, 1.0, 0.45, 0), (0, 1.5, 0.45, 0), (-1, 2.0, 0.9, 1), (-2, 3.0, 0.9, 0)]

    def riff(part, bars, lo, hi, vel, tr=0):
        for k, bar in enumerate(bars):
            r = root(bar, lo, hi)
            for st, at, d, a in (riff_a if k % 2 == 0 else riff_b):
                part.n(s.b(bar) + at, d, diatonic(r, st) + tr, vel(bar) + (12 if a else 0))

    riff(tbn, A + A2, 38, 49, V(72, 104))
    riff(tbn2, A2, 38, 49, V(66, 98), tr=12)
    riff(tuba, A + A2, 26, 37, V(68, 100))
    riff(bsn, A + A2, 50, 61, V(56, 82))
    for p_ in (tbn, tbn2, tuba):
        p_.expr([(s.b(5), 104), (s.b(13) - 0.1, 116), (s.b(25), 112), (s.b(33) - 0.1, 124)])

    # ---- intro: the first footfalls, and a low call
    tbn.line(s.b(3), "E2:3 F2:1 F2:3.5 R:0.5", 70, leg=0.98)
    tuba.line(s.b(3), "E1:3 F1:1 F1:3.5 R:0.5", 66, leg=0.98)
    tbn.expr([(s.b(3), 70), (s.b(5) - 0.1, 118)])
    tuba.expr([(s.b(3), 70), (s.b(5) - 0.1, 118)])

    # ---- strings and choir underneath
    rng4 = [(52, 62), (55, 67), (59, 71), (64, 76)]
    pads([(pad1, [0, 1]), (pad2, [2, 3])], s, list(range(5, 37)), prog, rng4, V(40, 80), dur_beats=4)
    for p_ in (pad1, pad2):
        swell(p_, s, list(range(5, 37)), lo=72, hi=112)
    choir_rng = [(48, 60), (52, 64), (57, 69), (60, 72)]
    pads([(ohh, [0, 1, 2, 3])], s, B, prog, choir_rng, V(40, 76))
    pads([(ahh, [0, 1, 2, 3])], s, BREAK + A2, prog, choir_rng, V(62, 100))
    swell(ohh, s, B, lo=66, hi=112)
    swell(ahh, s, BREAK + A2, lo=74, hi=122)

    # ---- B: the horns' tune over held trombones
    melB = ("A4:2 B4:1 C5:1 B4:2 G4:1 E4:1 F4:3 E4:1 D4:2 E4:2 "
            "A4:2 B4:1 C5:1 D5:2 C5:1 B4:1 A4:3 G4:1 F4:4")
    hn1.line(s.b(13), melB, 92, leg=0.99, accent=6)
    hn2.line(s.b(13), melB, 86, tr=-12, leg=0.99)
    hn1.expr([(s.b(13), 98), (s.b(15), 114), (s.b(17), 102), (s.b(19), 120), (s.b(21) - 0.1, 108)])
    hn2.expr([(s.b(13), 98), (s.b(15), 114), (s.b(17), 102), (s.b(19), 120), (s.b(21) - 0.1, 108)])
    pads([(tbn, [0]), (tbn2, [1])], s, B, prog, [(45, 55), (50, 60)], V(56, 80))

    # ---- the break: shouted choir, tremolo climbing, brass hammering the flat second into the tonic
    tv = voice_lead([prog[b - 1] for b in BREAK], [(64, 74), (69, 79)])
    for bar, vc in zip(BREAK, tv):
        trem.chord(s.b(bar), 4.05, vc, 64 + (bar - 21) * 8)
    trem.expr([(s.b(21), 70), (s.b(25) - 0.1, 124)])
    for bar in BREAK:
        r = root(bar, 38, 49)
        for bt in (1, 2.5, 3, 4.5):
            tbn.n(s.b(bar, bt), 0.4, r, 92 + (bar - 21) * 6)
            tuba.n(s.b(bar, bt), 0.4, r - 12, 88 + (bar - 21) * 6)
    hn1.line(s.b(21), "C5+A4:4 C5+A4:3 D5+B4:1 B4+G4:8", 94, leg=0.98)
    hn2.line(s.b(21), "F4:8 E4:8", 90, leg=0.98)
    tpt.line(s.b(23), "B4:2 B4:1 C5:1 B4:4", 86, leg=0.98)

    # ---- A again: everything, the horns and trumpets crying over the riff
    melA2 = ("B4:1.5 B4:0.5 C5:1 B4:1 A4:2 C5:2 B4:1.5 G4:0.5 E4:2 F4:1 E4:1 D4:2 "
             "E4:1 G4:1 B4:2 C5:2 A4:1 C5:1 D5:3 B4:1 C5:2 A4:2")
    hn1.line(s.b(25), melA2, 102, leg=0.99, accent=8)
    hn2.line(s.b(25), melA2, 96, tr=-12, leg=0.99)
    tpt.line(s.b(25), melA2, 84, leg=0.98)
    for p_ in (hn1, hn2, tpt):
        p_.expr([(s.b(25), 104), (s.b(29), 116), (s.b(31), 124), (s.b(33) - 0.1, 110)])

    # ---- outro: the riff gone, a last call that comes to rest on F
    hn1.line(s.b(33), "E4:3 F4:1 D4:4 C4:2 A3:2 F3:4", 84, leg=0.99)
    hn2.line(s.b(33), "B3:3 C4:1 A3:4 A3:2 F3:2 C3:4", 78, leg=0.99)
    tbn.chord(s.b(35), 7.6, ["F2", "C3"], 82)
    tuba.n(s.b(35), 7.6, "F1", 80)
    for p_ in (hn1, hn2, tbn, tuba):
        p_.expr([(s.b(33), 96), (s.b(35), 90), (s.b(36, 4.4), 124), (s.b(37) - 0.1, 124)])

    # ---- percussion: the footfalls never stop
    for bar in range(1, 37):
        v = V(84, 118)(bar)
        taiko.n(s.b(bar), 0.5, "D2", v)
        taiko.n(s.b(bar, 3), 0.5, "D2", v - 10)
        if bar >= 3:
            taiko.n(s.b(bar, 4.5), 0.5, "D2", v - 26)
    for bar in A + BREAK + A2:
        for i in (1, 3, 5, 7):
            taiko2.n(s.b(bar) + i * 0.5, 0.5, "D2", V(46, 76)(bar) + (10 if i == 7 else 0))
    for bar in B:
        taiko2.n(s.b(bar, 2.5), 0.5, "D2", V(50, 72)(bar))
        taiko2.n(s.b(bar, 4), 0.5, "D2", V(46, 66)(bar))

    T = lambda bar: root(bar, 33, 45)  # noqa: E731
    for bar in range(1, 37):
        if bar in (4, 12, 20, 24, 32, 36):
            continue
        timp.n(s.b(bar), 1.4, T(bar), V(76, 110)(bar))
        if bar in A + A2 or bar in BREAK:
            timp.n(s.b(bar, 3), 1.0, T(bar), V(66, 100)(bar))
    roll(timp, s, s.b(4), 4, T(4), 46, 112)
    roll(timp, s, s.b(12, 3), 2, T(12), 60, 108)
    roll(timp, s, s.b(20, 3), 2, T(20), 60, 112)
    roll(timp, s, s.b(24), 4, T(24), 54, 122)
    roll(timp, s, s.b(32, 3), 2, T(32), 64, 112)
    roll(timp, s, s.b(36), 3.5, T(36), 44, 116)

    for bar in (5, 13, 21, 25, 33):
        kit.n(s.b(bar), 2, 55 if bar == 21 else 57, 108)
    for bar in range(1, 37):
        kit.n(s.b(bar), 1, 36, V(70, 104)(bar))
        if bar >= 5:
            kit.n(s.b(bar, 3), 1, 36, V(60, 94)(bar))
    roll(kit, s, s.b(4, 3), 2, 38, 40, 100, rate=0.125)
    roll(kit, s, s.b(12, 4), 1, 38, 50, 96, rate=0.125)
    roll(kit, s, s.b(24), 4, 38, 40, 116, rate=0.125)
    roll(kit, s, s.b(36, 3), 1.5, 38, 40, 96, rate=0.125)
    return s


FIELD_MIX = {"low": (3.0, 0.15), "high": (1.0, 0.3), "brass": (-2.0, 0.24), "choir": (2.0, 0.38),
             "perc": (2.5, 0.16)}


# ================================================================== the dusk rider
RIDER_CALL = "A3:4 D3:2 D3:6 A3:4 D3:1 F3:1 E3:4 C#3:2"  # the rider's horn: a fifth down, then the leading tone


def compose_dusk_rider():
    """The Dusk Rider's ride: D minor in 6/8 at a gallop (a beat here is an eighth note). Its horn call (the
    fifth A to D, as the horn in the game sounds it) opens and closes the piece; the strings never stop
    galloping (long-short, long-short), with a lament bass falling D C Bb A under the horns' theme. The
    middle opens out over Bb and F, then everything stops dead on the beat while a cor anglais mourns
    over the choir, before the gallop comes back with the theme in octaves. It ends on A, so the loop
    falls back home to D."""
    s = Song("dusk_rider", bpm=216, bars=36, bpb=6, tail=1.4)
    prog = (["Dm", "Dm", "Dm", "A"]
            + ["Dm", "C", "Bb", "A", "Dm", "C", "Gm", "A"]
            + ["Bb", "F", "Gm", "Dm", "Bb", "F", "Gm", "A"]
            + ["Bb", "Gm", "A", "A"]
            + ["Dm", "C", "Bb", "A", "Dm", "C", "Gm", "A"]
            + ["Dm", "Dm", "Dm", "A"])
    assert len(prog) == 36
    INTRO = [1, 2, 3, 4]
    A = list(range(5, 13))
    B = list(range(13, 21))
    BRIDGE = list(range(21, 25))
    A2 = list(range(25, 33))
    OUT = list(range(33, 37))
    GALLOP = A + B + A2 + OUT

    cb = s.part("cb", 43, "low", pan=86, volume=112, hum=0.005, vjit=4)
    vc = s.part("vc", 42, "low", pan=74, volume=104, hum=0.006, vjit=4)
    lowtrem = s.part("lowtrem", 44, "low", pan=70, volume=96, hum=0.01)
    vla = s.part("vla", 48, "high", pan=58, volume=96, hum=0.006, vjit=4)
    vn = s.part("vn", 48, "high", pan=40, volume=100, hum=0.008, vjit=4)
    pad = s.part("pad", 49, "high", pan=50, volume=90, hum=0.02)
    trem = s.part("trem", 44, "high", pan=34, volume=86, hum=0.01)
    hn1 = s.part("hn1", 60, "brass", pan=42, volume=112, lag=0.012)
    hn2 = s.part("hn2", 60, "brass", pan=54, volume=104, lag=0.014)
    tpt = s.part("tpt", 56, "brass", pan=62, volume=92, lag=0.008)
    tbn = s.part("tbn", 57, "brass", pan=78, volume=106, lag=0.01)
    tuba = s.part("tuba", 58, "brass", pan=88, volume=100, lag=0.01)
    ca = s.part("ca", 69, "high", pan=46, volume=100, hum=0.012)
    ohh = s.part("ohh", 53, "choir", pan=70, volume=98, hum=0.02)
    ahh = s.part("ahh", 52, "choir", pan=56, volume=104, hum=0.02)
    timp = s.part("timp", 47, "perc", pan=58, volume=112, hum=0.004, vjit=3)
    taiko = s.part("taiko", 116, "perc", pan=64, volume=110, hum=0.004, vjit=4)
    kit = s.part("kit", 48, "perc", drum=True, pan=64, volume=104, hum=0.004, vjit=4)

    inten = np.interp(np.arange(1, 37), [1, 4, 5, 12, 13, 20, 21, 24, 25, 32, 33, 36],
                      [.45, .62, .7, .8, .74, .86, .6, .9, .92, 1.0, .72, .8])

    def V(lo, hi):
        return lambda bar: lo + (hi - lo) * inten[bar - 1]

    def root(bar, lo, hi):
        return in_range(pcs(prog[bar - 1])[0], lo, hi)

    def fifth(bar, lo, hi):
        return in_range(pcs(prog[bar - 1])[2], lo, hi)

    def brass_bed(bars, vel):
        """the tuba on the root, the trombones holding the harmony above it"""
        for bar in bars:
            tuba.n(s.b(bar), 6.1, root(bar, 31, 42), vel(bar))
        pads([(tbn, [0, 1])], s, bars, prog, [(45, 56), (50, 62)], vel, dur_beats=6)

    # ---- the gallop: contrabasses long-short on the root, cellos turning on root and fifth in eighths
    for bar in GALLOP:
        r = root(bar, 29, 40)
        v = V(64, 104)(bar)
        for at, d, a in ((0, 1.8, 14), (2, 0.9, 0), (3, 1.8, 10), (5, 0.9, 0)):
            cb.n(s.b(bar) + at, d, r, v + a)
        r2 = root(bar, 41, 52)
        f2 = fifth(bar, r2, r2 + 11)
        for i, q in enumerate((r2, r2, f2, r2 + 12, f2, r2)):
            vc.n(s.b(bar) + i, 0.85, q, V(56, 92)(bar) + (14 if i in (0, 3) else 0))
    # the violas join the gallop in the second half of each section, a sixth above the cellos
    for bar in A[4:] + B + A2 + OUT[:2]:
        r = root(bar, 50, 61)
        third = pcs(prog[bar - 1])[1]
        t3 = in_range(third, r, r + 11)
        for i, q in enumerate((r, t3, r, r, t3, r + 12 if bar in A2 else r)):
            vla.n(s.b(bar) + i, 0.8, q, V(50, 84)(bar) + (12 if i in (0, 3) else 0))

    # ---- intro and outro: a low tremolo on D under the rider's horn, and the first hoofbeats
    for bar in INTRO:
        lowtrem.n(s.b(bar), 6.1, root(bar, 38, 49), 60 + 8 * (bar - 1))
    lowtrem.expr([(0, 40), (s.b(4, 6), 118)])
    hn1.line(s.b(1), RIDER_CALL, 84, tr=12, leg=0.98)
    hn2.line(s.b(1), RIDER_CALL, 76, leg=0.98)
    hn1.expr([(0, 70), (s.b(2), 100), (s.b(3), 76), (s.b(4, 6), 116)])
    hn2.expr([(0, 70), (s.b(2), 100), (s.b(3), 76), (s.b(4, 6), 116)])
    hn1.line(s.b(33), RIDER_CALL, 96, tr=12, leg=0.98)
    hn2.line(s.b(33), RIDER_CALL, 90, leg=0.98)
    tbn.line(s.b(33), "D3:12 D3:6 C#3:6", 82, leg=0.98)
    tuba.line(s.b(33), "D2:18 A1:6", 78, leg=0.98)

    # ---- A: the horns' theme over the falling bass, trombones holding the harmony
    theme = ("D4:2 A3:1 D4:2 F4:1 E4:2 D4:1 E4:2 G4:1 F4:2 E4:1 D4:2 Bb3:1 A3:3 C#4:3 "
             "D4:2 A3:1 D4:2 F4:1 G4:2 F4:1 E4:2 C4:1 D4:2 Bb3:1 G3:2 Bb3:1 A3:6")
    hn1.line(s.b(5), theme, 96, leg=0.97, accent=8)
    hn2.line(s.b(5), theme, 88, tr=-12, leg=0.97)
    for p_ in (hn1, hn2):
        p_.expr([(s.b(5), 100), (s.b(9), 112), (s.b(12, 6), 104), (s.b(13), 96)])
    brass_bed(A, V(52, 76))

    # ---- B: the trumpet and violins take a wider tune over Bb and F; the choir comes in underneath
    tuneB = ("F4:3 D5:3 C5:2 A4:1 F4:3 G4:2 A4:1 Bb4:2 D5:1 A4:6 "
             "F4:3 D5:3 C5:2 A4:1 C5:2 F5:1 G5:2 F5:1 D5:2 Bb4:1 A4:3 C#5:3")
    tpt.line(s.b(13), tuneB, 90, leg=0.97, accent=6)
    vn.line(s.b(13), tuneB, 84, leg=1.0)
    tpt.expr([(s.b(13), 96), (s.b(16), 112), (s.b(17), 100), (s.b(20, 6), 120)])
    hn1.line(s.b(13), "D4:6 C4:6 Bb3:6 A3:6 D4:6 C4:6 Bb3:6 C#4:6", 80, leg=0.99)
    hn2.line(s.b(13), "Bb3:6 A3:6 G3:6 F3:6 Bb3:6 A3:6 G3:6 E3:6", 76, leg=0.99)
    choir_rng = [(48, 60), (53, 64), (57, 69), (62, 74)]
    pads([(ohh, [0, 1, 2, 3])], s, B, prog, choir_rng, V(42, 78), dur_beats=6)
    swell(ohh, s, B, lo=64, hi=112)
    pads([(pad, [1, 2, 3])], s, B, prog, [(50, 62), (55, 67), (60, 72), (64, 76)], V(40, 70), dur_beats=6)

    # ---- the bridge: everything stops dead on the beat; a cor anglais mourns over the choir
    for bar in BRIDGE:
        r = root(bar, 29, 40)
        for p_, q, v in ((cb, r, 104), (tuba, r + 12, 96), (tbn, r + 24, 92), (vc, r + 12, 96)):
            p_.n(s.b(bar), 1.2, q, v + (bar - 21) * 4)
    tv = voice_lead([prog[b - 1] for b in BRIDGE], [(62, 72), (67, 77)])
    for bar, voicing in zip(BRIDGE, tv):
        trem.chord(s.b(bar), 6.05, voicing, 60 + (bar - 21) * 10)
    trem.expr([(s.b(21), 60), (s.b(24, 6), 124)])
    ca.line(s.b(21), "F5:3 E5:2 D5:1 D5:3 C5:2 Bb4:1 Bb4:2 A4:1 G4:2 F4:1 E4:4 C#4:2", 92, leg=1.0)
    ca.expr([(s.b(21), 96), (s.b(22), 110), (s.b(23), 100), (s.b(24, 4), 116), (s.b(25) - 0.1, 90)])
    pads([(ahh, [0, 1, 2, 3])], s, BRIDGE, prog, choir_rng, V(54, 96), dur_beats=6)
    swell(ahh, s, BRIDGE, lo=60, hi=120, every=4, shape="cresc")

    # ---- A again: the theme in octaves on horns, trumpet and violins, the choir crying over it
    hn1.line(s.b(25), theme, 104, leg=0.97, accent=10)
    hn2.line(s.b(25), theme, 96, tr=-12, leg=0.97)
    tpt.line(s.b(25), theme, 92, tr=12, leg=0.96, accent=6)
    vn.line(s.b(25), theme, 88, tr=12, leg=1.0)
    for p_ in (hn1, hn2, tpt):
        p_.expr([(s.b(25), 108), (s.b(29), 118), (s.b(31), 124), (s.b(32, 6), 112)])
    brass_bed(A2, V(60, 88))
    pads([(ahh, [0, 1, 2, 3])], s, A2, prog, choir_rng, V(60, 100), dur_beats=6)
    swell(ahh, s, A2, lo=76, hi=122)

    # ---- percussion: the hoofbeats never stop until the bridge
    for bar in range(2, 37):
        if bar in BRIDGE:
            taiko.n(s.b(bar), 1, "D2", 110)
            continue
        v = V(70, 116)(bar)
        taiko.n(s.b(bar), 1, "D2", v)
        taiko.n(s.b(bar, 4), 1, "D2", v - 8)
        if bar >= 5:
            kit.n(s.b(bar, 3), 0.5, 38, v - 46)
            kit.n(s.b(bar, 6), 0.5, 38, v - 40)
    T = lambda bar: root(bar, 38, 49)  # noqa: E731
    for bar in GALLOP:
        if bar in (12, 20, 32, 36):
            continue
        timp.n(s.b(bar), 2.6, T(bar), V(72, 108)(bar))
        timp.n(s.b(bar, 4), 2.0, fifth(bar, 40, 51), V(62, 96)(bar))
    roll(timp, s, s.b(4), 6, T(4), 40, 112, rate=0.25)
    roll(timp, s, s.b(12, 4), 3, T(12), 56, 108, rate=0.25)
    roll(timp, s, s.b(20, 1), 6, T(20), 50, 116, rate=0.25)
    roll(timp, s, s.b(24), 6, T(24), 50, 124, rate=0.25)
    roll(timp, s, s.b(32, 4), 3, T(32), 60, 112, rate=0.25)
    roll(timp, s, s.b(36), 5.5, T(36), 44, 114, rate=0.25)
    # (in the orchestra kit 41-53 are tuned timpani, so the cymbals are 55 splash and 57 crash)
    for bar in (5, 13, 25):
        kit.n(s.b(bar), 4, 57, 106)
    for bar in BRIDGE:
        kit.n(s.b(bar), 4, 57 if bar % 2 else 55, 90 + (bar - 21) * 8)
    roll(kit, s, s.b(12, 4), 3, 38, 40, 100, rate=0.25)
    roll(kit, s, s.b(24, 1), 6, 38, 36, 118, rate=0.25)
    return s


RIDER_MIX = {"low": (3.0, 0.16), "high": (0.5, 0.32), "brass": (-1.5, 0.26), "choir": (2.0, 0.4),
             "perc": (2.0, 0.16)}


# ================================================================== driver
PIECES = {
    "fallen_warden": dict(fn=compose_phase1, mix=PHASE1_MIX, out="music/fallen_warden", ir=(2.4, 101),
                          fade_out=1.1, quality=5),
    "fallen_warden_2": dict(fn=compose_phase2, mix=PHASE2_MIX, out="music/fallen_warden_2", ir=(2.1, 102),
                            fade_out=1.0, quality=5),
    "field_boss": dict(fn=compose_field_boss, mix=FIELD_MIX, out="music/field_boss", ir=(2.0, 105),
                       fade_out=1.0, quality=5),
    "dusk_rider": dict(fn=compose_dusk_rider, mix=RIDER_MIX, out="music/dusk_rider", ir=(2.2, 106),
                       fade_out=1.0, quality=5),
    "boss_appear": dict(fn=compose_boss_appear, mix=STINGER_MIX, out="ui/boss_appear", ir=(2.6, 103),
                        fade_out=0.45, quality=6, length=3.0),
    "foe_vanquished": dict(fn=compose_foe_vanquished, mix=STINGER_MIX, out="ui/foe_vanquished", ir=(2.8, 104),
                           fade_out=1.4, quality=6, length=4.8),
}


def build(name, assets, sf2, work, wav_dir=None):
    cfg = PIECES[name]
    song = cfg["fn"]()
    os.makedirs(work, exist_ok=True)
    stems = {}
    for stem in sorted(set(p.stem for p in song.parts)):
        mid = os.path.join(work, f"{name}_{stem}.mid")
        wav = os.path.join(work, f"{name}_{stem}.wav")
        export_stem(song, stem, mid)
        stems[stem] = render(mid, wav, sf2)
    length = cfg.get("length") or song.end_beat * song.spb + song.tail
    ir = hall_ir(rt60=cfg["ir"][0], seed=cfg["ir"][1])
    out = mix(stems, cfg["mix"], ir, length, fade_out=cfg["fade_out"])
    path = os.path.join(assets, "sounds", cfg["out"] + ".ogg")
    write_ogg(path, out, quality=cfg["quality"])
    if wav_dir:
        write_wav(os.path.join(wav_dir, cfg["out"] + ".wav"), out)
    print(f"{path}  {out.shape[1] / SR:.3f}s", flush=True)
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
